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
    private var lastAnnouncedLimit = 0
    private var ttsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private var isCurrentlySpeeding = false
    private var alreadyWarnedForThisZone = false
    private val handler = Handler(Looper.getMainLooper())
    
    private var cachedSpeedLimit = 0
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    private var lastBearing = 0f
    private var limitUpdateTime = 0L  // Când a fost actualizată ultima limită
    
    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var isBubbleShowing = false
    
    // Flag pentru a aștepta limita nouă după viraj
    private var waitingForNewLimit = false

    override fun onCreate() {
        super.onCreate()
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
        startForeground(NOTIFICATION_ID, createNotification("Pornește..."))
        startLocationUpdates()
        statusLiveData.postValue("Activ")
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ro", "RO"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.GERMAN)
            }
            ttsReady = true
            tts?.setSpeechRate(1.0f)
            
            handler.postDelayed({
                announceMessage("Avertizare viteză pornită")
            }, 1000)
        }
    }

    private fun announceMessage(text: String) {
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "msg_${System.currentTimeMillis()}")
        }
    }
    
    private fun announceUrgent(text: String) {
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "urgent_${System.currentTimeMillis()}")
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    300L,  // Mai rapid - 300ms
                    1f,    // 1 metru
                    this,
                    Looper.getMainLooper()
                )
            }
            
            statusLiveData.postValue("GPS Activ")
        } else {
            statusLiveData.postValue("Lipsă permisiune GPS")
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = location.speed * 3.6f
        val currentBearing = location.bearing
        
        currentSpeedLiveData.postValue(speedKmh)
        locationLiveData.postValue(Pair(location.latitude, location.longitude))
        
        // Detectează viraj (schimbare de direcție > 30 grade)
        val bearingChange = Math.abs(currentBearing - lastBearing)
        val isTurning = bearingChange > 30 && bearingChange < 330 && lastBearing != 0f
        
        if (isTurning) {
            Log.d(TAG, "VIRAJ DETECTAT! Bearing change: $bearingChange")
            // Resetează - așteaptă limita nouă înainte de a avertiza
            waitingForNewLimit = true
            isCurrentlySpeeding = false
            alreadyWarnedForThisZone = false
        }
        lastBearing = currentBearing
        
        updateNotification("Viteză: ${speedKmh.toInt()} km/h | Limită: ${if (cachedSpeedLimit > 0) cachedSpeedLimit else "?"}")
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        val distance = floatArrayOf(0f)
        if (lastQueryLat != 0.0) {
            Location.distanceBetween(lastQueryLat, lastQueryLon, location.latitude, location.longitude, distance)
        }
        
        // Verifică mai des: la 10 metri SAU dacă am făcut viraj SAU nu avem limită
        val shouldQuery = distance[0] > 10 || isTurning || cachedSpeedLimit <= 0 || lastQueryLat == 0.0
        
        if (shouldQuery) {
            lastQueryLat = location.latitude
            lastQueryLon = location.longitude
            
            serviceScope.launch {
                try {
                    val limit = getSpeedLimit(location.latitude, location.longitude)
                    
                    if (limit != null && limit > 0) {
                        val oldLimit = cachedSpeedLimit
                        cachedSpeedLimit = limit
                        limitUpdateTime = System.currentTimeMillis()
                        speedLimitLiveData.postValue(limit)
                        
                        // Limita s-a schimbat - anunță!
                        if (limit != lastAnnouncedLimit) {
                            Log.d(TAG, "LIMITĂ NOUĂ: $limit (era: $lastAnnouncedLimit)")
                            lastAnnouncedLimit = limit
                            isCurrentlySpeeding = false
                            alreadyWarnedForThisZone = false
                            waitingForNewLimit = false  // Am primit limita nouă
                            
                            withContext(Dispatchers.Main) {
                                announceMessage("Atenție! Limită de $limit")
                            }
                        } else {
                            // Limita e aceeași - nu mai așteptăm
                            waitingForNewLimit = false
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}")
                }
            }
        }
        
        // IMPORTANT: Nu avertiza pentru depășire dacă așteptăm limita nouă după viraj!
        if (!waitingForNewLimit) {
            checkSpeedingAndWarn(speedKmh, cachedSpeedLimit)
        }
    }
    
    private fun checkSpeedingAndWarn(speedKmh: Float, limit: Int) {
        if (limit <= 0) return
        
        // Verifică dacă limita e proaspătă (sub 3 secunde)
        val limitAge = System.currentTimeMillis() - limitUpdateTime
        if (limitAge > 5000 && limitUpdateTime > 0) {
            // Limita e veche - nu avertiza, așteaptă update
            return
        }
        
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
        // TomTom Routing API - limite REALE
        var limit = getSpeedLimitFromTomTomRouting(lat, lon)
        if (limit != null && limit > 0) return limit
        
        // Fallback: TomTom Geocode
        limit = getSpeedLimitFromTomTomGeocode(lat, lon)
        if (limit != null && limit > 0) return limit
        
        // Fallback: OSM
        return getSpeedLimitFromOSM(lat, lon)
    }
    
    private fun getSpeedLimitFromTomTomRouting(lat: Double, lon: Double): Int? {
        return try {
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            val lat2 = lat + 0.0003
            val lon2 = lon + 0.0003
            val url = URL("https://api.tomtom.com/routing/1/calculateRoute/$lat,$lon:$lat2,$lon2/json?key=$tomtomKey&sectionType=speedLimit")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val routes = json.optJSONArray("routes")
            
            if (routes != null && routes.length() > 0) {
                val sections = routes.getJSONObject(0).optJSONArray("sections")
                if (sections != null) {
                    for (i in 0 until sections.length()) {
                        val section = sections.getJSONObject(i)
                        if (section.optString("sectionType") == "SPEED_LIMIT") {
                            val speedLimit = section.optInt("maxSpeedLimitInKmh", 0)
                            if (speedLimit > 0) return speedLimit
                        }
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }
    
    private fun getSpeedLimitFromTomTomGeocode(lat: Double, lon: Double): Int? {
        return try {
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            val url = URL("https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$tomtomKey&returnSpeedLimit=true")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val addresses = json.optJSONArray("addresses")
            
            if (addresses != null && addresses.length() > 0) {
                val addressInfo = addresses.getJSONObject(0).optJSONObject("address")
                if (addressInfo != null) {
                    val speedLimitStr = addressInfo.optString("speedLimit", "")
                    if (speedLimitStr.isNotEmpty()) {
                        val match = Regex("(\\d+)").find(speedLimitStr)
                        if (match != null) return match.groupValues[1].toInt()
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }
    
    private fun getSpeedLimitFromOSM(lat: Double, lon: Double): Int? {
        return try {
            val query = "[out:json][timeout:3];way(around:25,$lat,$lon)[maxspeed];out tags;"
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            connection.outputStream.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val elements = JSONObject(response).optJSONArray("elements")
            if (elements != null && elements.length() > 0) {
                val tags = elements.getJSONObject(0).optJSONObject("tags")
                val maxspeed = tags?.optString("maxspeed", "") ?: ""
                val match = Regex("(\\d+)").find(maxspeed)
                if (match != null) return match.groupValues[1].toInt()
            }
            null
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Speed Alert", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Alert")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(content))
    }
    
    private fun createFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        
        try {
            floatingBubble = TextView(this).apply {
                text = "0"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(20, 20, 20, 20)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#4CAF50"))
                }
            }
            
            val params = WindowManager.LayoutParams(
                130, 130,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 20
            params.y = 100
            
            windowManager?.addView(floatingBubble, params)
            isBubbleShowing = true
        } catch (e: Exception) { }
    }
    
    private fun updateFloatingBubble(speed: Int, limit: Int) {
        handler.post {
            floatingBubble?.let { bubble ->
                bubble.text = "$speed"
                (bubble.background as? GradientDrawable)?.setColor(
                    when {
                        limit <= 0 -> Color.parseColor("#2196F3")
                        speed > limit + 5 -> Color.parseColor("#F44336")
                        speed > limit -> Color.parseColor("#FF9800")
                        else -> Color.parseColor("#4CAF50")
                    }
                )
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
        } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingBubble()
        handler.removeCallbacksAndMessages(null)
        locationManager.removeUpdates(this)
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        statusLiveData.postValue("Oprit")
    }
}
