package com.speedalert.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*
import android.graphics.drawable.GradientDrawable

class SpeedAlertService : Service(), TextToSpeech.OnInitListener, LocationListener {

    companion object {
        val speedLimitLiveData = MutableLiveData<Int>()
        val currentSpeedLiveData = MutableLiveData<Float>()
        val statusLiveData = MutableLiveData<String>()
        val locationLiveData = MutableLiveData<Pair<Double, Double>>()
        
        private const val CHANNEL_ID = "SpeedAlertChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SpeedAlert"
    }

    private var tts: TextToSpeech? = null
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAnnouncedLimit = 0  // Ultima limită anunțată vocal
    private var ttsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private var isCurrentlySpeeding = false
    private var alreadyWarnedForThisZone = false
    private val handler = Handler(Looper.getMainLooper())
    
    private var cachedSpeedLimit = 0
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    
    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var isBubbleShowing = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpeedAlert::LocationWakeLock"
        )
        wakeLock?.acquire()
        
        createNotificationChannel()
        createFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        startForeground(NOTIFICATION_ID, createNotification("Pornește..."))
        startLocationUpdates()
        statusLiveData.postValue("Activ")
        return START_STICKY
    }

    override fun onInit(status: Int) {
        Log.d(TAG, "TTS onInit status: $status")
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ro", "RO"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.GERMAN)
            }
            ttsReady = true
            tts?.setSpeechRate(1.0f)
            Log.d(TAG, "TTS READY!")
            
            // Anunț de pornire
            handler.postDelayed({
                announceMessage("Avertizare viteză pornită")
            }, 1000)
        } else {
            Log.e(TAG, "TTS initialization failed!")
            ttsReady = false
        }
    }

    private fun announceMessage(text: String) {
        Log.d(TAG, "announceMessage: $text, ttsReady: $ttsReady")
        if (ttsReady && tts != null) {
            val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "msg_${System.currentTimeMillis()}")
            Log.d(TAG, "TTS speak result: $result")
        } else {
            Log.e(TAG, "TTS not ready or null!")
        }
    }
    
    private fun announceUrgent(text: String) {
        Log.d(TAG, "announceUrgent: $text")
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "urgent_${System.currentTimeMillis()}")
        }
    }

    private fun startLocationUpdates() {
        Log.d(TAG, "Starting location updates")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    2f,
                    this,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "GPS updates started")
            }
            
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    5f,
                    this,
                    Looper.getMainLooper()
                )
            }
            
            statusLiveData.postValue("GPS Activ")
        } else {
            Log.e(TAG, "No location permission!")
            statusLiveData.postValue("Lipsă permisiune GPS")
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = location.speed * 3.6f
        currentSpeedLiveData.postValue(speedKmh)
        locationLiveData.postValue(Pair(location.latitude, location.longitude))
        
        updateNotification("Viteză: ${speedKmh.toInt()} km/h | Limită: ${if (cachedSpeedLimit > 0) cachedSpeedLimit else "?"}")
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        val distance = floatArrayOf(0f)
        if (lastQueryLat != 0.0) {
            Location.distanceBetween(lastQueryLat, lastQueryLon, location.latitude, location.longitude, distance)
        }
        
        // Verifică la fiecare 20 metri sau dacă nu avem limită
        val shouldQuery = distance[0] > 20 || cachedSpeedLimit <= 0 || lastQueryLat == 0.0
        
        if (shouldQuery) {
            lastQueryLat = location.latitude
            lastQueryLon = location.longitude
            
            serviceScope.launch {
                try {
                    val limit = getSpeedLimit(location.latitude, location.longitude)
                    Log.d(TAG, "=== RESULT === limit: $limit, cached: $cachedSpeedLimit, lastAnnounced: $lastAnnouncedLimit")
                    
                    if (limit != null && limit > 0) {
                        // Actualizează cache-ul
                        val previousLimit = cachedSpeedLimit
                        cachedSpeedLimit = limit
                        speedLimitLiveData.postValue(limit)
                        
                        // ANUNȚĂ DACĂ E DIFERIT DE ULTIMA ANUNȚATĂ
                        if (limit != lastAnnouncedLimit) {
                            Log.d(TAG, ">>> ANNOUNCING NEW LIMIT: $limit (was: $lastAnnouncedLimit)")
                            lastAnnouncedLimit = limit
                            isCurrentlySpeeding = false
                            alreadyWarnedForThisZone = false
                            
                            // ANUNȚ VOCAL
                            withContext(Dispatchers.Main) {
                                handler.post {
                                    announceMessage("Atenție! Limită de $limit")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in location changed: ${e.message}")
                }
            }
        }
        
        // Verifică depășirea
        checkSpeedingAndWarn(speedKmh, cachedSpeedLimit)
    }
    
    private fun checkSpeedingAndWarn(speedKmh: Float, limit: Int) {
        if (limit <= 0) return
        
        val over = (speedKmh - limit).toInt()
        
        if (over > 7) {
            if (!alreadyWarnedForThisZone) {
                alreadyWarnedForThisZone = true
                isCurrentlySpeeding = true
                announceUrgent("Boule! Încetinește că iei amendă!")
                updateNotification("⚠️ DEPĂȘIRE! ${speedKmh.toInt()} / $limit km/h")
            }
        } else if (over > 3) {
            if (!isCurrentlySpeeding) {
                isCurrentlySpeeding = true
                announceMessage("Ai depășit limita cu $over kilometri")
                updateNotification("⚠️ ${speedKmh.toInt()} / $limit km/h")
            }
        } else {
            if (isCurrentlySpeeding || alreadyWarnedForThisZone) {
                isCurrentlySpeeding = false
                alreadyWarnedForThisZone = false
            }
        }
    }

    private fun getSpeedLimit(lat: Double, lon: Double): Int? {
        // Încearcă TomTom pentru limită exactă
        var limit = getSpeedLimitFromTomTom(lat, lon)
        
        // Dacă TomTom nu are, încearcă OSM
        if (limit == null || limit <= 0) {
            limit = getSpeedLimitFromOSM(lat, lon)
        }
        
        // Dacă nici unul nu are limită exactă, detectează tipul drumului și aplică regulile germane
        if (limit == null || limit <= 0) {
            limit = getSpeedLimitByRoadType(lat, lon)
        }
        
        return limit
    }
    
    // Detectează tipul drumului și aplică regulile standard din Germania
    private fun getSpeedLimitByRoadType(lat: Double, lon: Double): Int? {
        return try {
            val radius = 30
            val query = "[out:json][timeout:5];way(around:$radius,$lat,$lon)[highway];out tags;"
            
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val postData = "data=${URLEncoder.encode(query, "UTF-8")}"
            connection.outputStream.write(postData.toByteArray())
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val elements = json.optJSONArray("elements")
            
            if (elements != null && elements.length() > 0) {
                // Găsește drumul cu cea mai mare prioritate
                var bestLimit = 50  // Default urban
                var bestPriority = 0
                
                for (i in 0 until elements.length()) {
                    val element = elements.getJSONObject(i)
                    val tags = element.optJSONObject("tags")
                    if (tags != null) {
                        val highway = tags.optString("highway", "")
                        val zone = tags.optString("zone:traffic", "")
                        val maxspeed = tags.optString("maxspeed", "")
                        
                        // Dacă are maxspeed explicit, folosește-l
                        if (maxspeed.isNotEmpty() && maxspeed != "none") {
                            val match = Regex("(\\d+)").find(maxspeed)
                            if (match != null) {
                                return match.groupValues[1].toInt()
                            }
                        }
                        
                        // Aplică regulile germane pe baza tipului de drum
                        val (limit, priority) = when (highway) {
                            "motorway" -> Pair(130, 100)
                            "motorway_link" -> Pair(80, 95)
                            "trunk" -> Pair(100, 90)
                            "trunk_link" -> Pair(60, 85)
                            "primary" -> Pair(100, 80)
                            "primary_link" -> Pair(50, 75)
                            "secondary" -> Pair(70, 70)
                            "secondary_link" -> Pair(50, 65)
                            "tertiary" -> Pair(50, 60)
                            "tertiary_link" -> Pair(50, 55)
                            "unclassified" -> Pair(50, 50)
                            "residential" -> Pair(30, 45)  // Zone rezidențiale = 30
                            "living_street" -> Pair(20, 40)  // Spielstraße = 20
                            "service" -> Pair(20, 30)
                            "pedestrian" -> Pair(10, 20)
                            else -> Pair(50, 10)
                        }
                        
                        // Verifică zone:traffic pentru zone speciale
                        if (zone == "DE:urban" || zone == "urban") {
                            if (priority > bestPriority) {
                                bestLimit = 50
                                bestPriority = priority + 10
                            }
                        } else if (zone == "DE:rural" || zone == "rural") {
                            if (priority > bestPriority) {
                                bestLimit = 100
                                bestPriority = priority + 10
                            }
                        }
                        
                        if (priority > bestPriority) {
                            bestLimit = limit
                            bestPriority = priority
                        }
                    }
                }
                
                Log.d(TAG, "Road type detection: limit=$bestLimit")
                return bestLimit
            }
            
            // Dacă nu găsim nimic, default 50 (urban)
            50
        } catch (e: Exception) {
            Log.e(TAG, "Road type detection error: ${e.message}")
            50
        }
    }
    
    private fun getSpeedLimitFromTomTom(lat: Double, lon: Double): Int? {
        return try {
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            val url = URL("https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$tomtomKey&returnSpeedLimit=true")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val addresses = json.optJSONArray("addresses")
            
            if (addresses != null && addresses.length() > 0) {
                val address = addresses.getJSONObject(0)
                val addressInfo = address.optJSONObject("address")
                
                if (addressInfo != null) {
                    val speedLimitStr = addressInfo.optString("speedLimit", "")
                    if (speedLimitStr.isNotEmpty()) {
                        val match = Regex("(\\d+)").find(speedLimitStr)
                        if (match != null) {
                            return match.groupValues[1].toInt()
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getSpeedLimitFromOSM(lat: Double, lon: Double): Int? {
        return try {
            val radius = 35
            val query = "[out:json][timeout:5];way(around:$radius,$lat,$lon)[maxspeed];out tags;"
            
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val postData = "data=${URLEncoder.encode(query, "UTF-8")}"
            connection.outputStream.write(postData.toByteArray())
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val elements = json.optJSONArray("elements")
            
            if (elements != null && elements.length() > 0) {
                for (i in 0 until elements.length()) {
                    val element = elements.getJSONObject(i)
                    val tags = element.optJSONObject("tags")
                    if (tags != null) {
                        val maxspeed = tags.optString("maxspeed", "")
                        if (maxspeed.isNotEmpty()) {
                            val match = Regex("(\\d+)").find(maxspeed)
                            if (match != null) {
                                return match.groupValues[1].toInt()
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "OSM error: ${e.message}")
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speed Alert",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Avertizări limită de viteză"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Alert")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }
    
    private fun createFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        
        try {
            floatingBubble = TextView(this).apply {
                text = "0"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(20, 20, 20, 20)
                
                val shape = GradientDrawable()
                shape.shape = GradientDrawable.OVAL
                shape.setColor(Color.parseColor("#4CAF50"))
                background = shape
            }
            
            val params = WindowManager.LayoutParams(
                130,
                130,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else 
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 20
            params.y = 100
            
            windowManager?.addView(floatingBubble, params)
            isBubbleShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Bubble error: ${e.message}")
        }
    }
    
    private fun updateFloatingBubble(speed: Int, limit: Int) {
        handler.post {
            floatingBubble?.let { bubble ->
                bubble.text = "$speed"
                
                val shape = bubble.background as? GradientDrawable
                shape?.let {
                    when {
                        limit <= 0 -> it.setColor(Color.parseColor("#2196F3"))
                        speed > limit + 5 -> it.setColor(Color.parseColor("#F44336"))
                        speed > limit -> it.setColor(Color.parseColor("#FF9800"))
                        else -> it.setColor(Color.parseColor("#4CAF50"))
                    }
                }
            }
        }
    }
    
    private fun removeFloatingBubble() {
        try {
            if (isBubbleShowing && floatingBubble != null) {
                windowManager?.removeView(floatingBubble)
                floatingBubble = null
                isBubbleShowing = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remove bubble error: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        removeFloatingBubble()
        handler.removeCallbacksAndMessages(null)
        locationManager.removeUpdates(this)
        tts?.stop()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
        
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        statusLiveData.postValue("Oprit")
    }
}
