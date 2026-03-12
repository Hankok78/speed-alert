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
    private var limitUpdateTime = 0L
    
    // Stabilizare limită - anunță doar când e confirmată
    private var pendingLimit = 0
    private var pendingLimitCount = 0
    private val CONFIRMATIONS_NEEDED = 3  // Trebuie 3 citiri la fel pentru a confirma
    
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
        
        val limitText = when {
            cachedSpeedLimit == -1 -> "∞"
            cachedSpeedLimit > 0 -> "$cachedSpeedLimit"
            else -> "?"
        }
        updateNotification("Viteză: ${speedKmh.toInt()} km/h | Limită: $limitText")
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        val distance = floatArrayOf(0f)
        if (lastQueryLat != 0.0) {
            Location.distanceBetween(lastQueryLat, lastQueryLon, location.latitude, location.longitude, distance)
        }
        
        // Verifică mai des: la 10 metri SAU dacă am făcut viraj SAU nu avem date (0 = necunoscut)
        val shouldQuery = distance[0] > 10 || isTurning || cachedSpeedLimit == 0 || lastQueryLat == 0.0
        
        if (shouldQuery) {
            lastQueryLat = location.latitude
            lastQueryLon = location.longitude
            
            serviceScope.launch {
                try {
                    val limit = getSpeedLimit(location.latitude, location.longitude, currentBearing)
                    
                    if (limit != null && (limit > 0 || limit == -1)) {
                        limitUpdateTime = System.currentTimeMillis()
                        
                        // Tratament special pentru FARA LIMITA (autostrada nelimitata)
                        if (limit == -1) {
                            if (lastAnnouncedLimit != -1) {
                                Log.d(TAG, "AUTOSTRADA FARA LIMITA!")
                                cachedSpeedLimit = -1
                                speedLimitLiveData.postValue(-1)
                                lastAnnouncedLimit = -1
                                isCurrentlySpeeding = false
                                alreadyWarnedForThisZone = false
                                warnedOnce = false
                                waitingForNewLimit = false
                                pendingLimit = -1
                                pendingLimitCount = 0
                                
                                withContext(Dispatchers.Main) {
                                    announceMessage("Fără limită! Autostradă liberă!")
                                }
                            } else {
                                cachedSpeedLimit = -1
                                waitingForNewLimit = false
                            }
                        } else {
                            // Limita normala (> 0)
                            // STABILIZARE: Asteapta 3 citiri la fel inainte de a anunta
                            if (limit == pendingLimit) {
                                pendingLimitCount++
                            } else {
                                pendingLimit = limit
                                pendingLimitCount = 1
                            }
                            
                            val bigChange = Math.abs(limit - lastAnnouncedLimit) >= 20
                            val confirmed = pendingLimitCount >= CONFIRMATIONS_NEEDED
                            
                            if ((confirmed || bigChange) && limit != lastAnnouncedLimit) {
                                Log.d(TAG, "LIMITA CONFIRMATA: $limit (era: $lastAnnouncedLimit, citiri: $pendingLimitCount)")
                                
                                cachedSpeedLimit = limit
                                speedLimitLiveData.postValue(limit)
                                lastAnnouncedLimit = limit
                                isCurrentlySpeeding = false
                                alreadyWarnedForThisZone = false
                                warnedOnce = false
                                waitingForNewLimit = false
                                pendingLimitCount = 0
                                
                                withContext(Dispatchers.Main) {
                                    announceMessage("Atenție! Limită de $limit")
                                }
                            } else if (limit == lastAnnouncedLimit) {
                                cachedSpeedLimit = limit
                                waitingForNewLimit = false
                            }
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
    
    // Mesaje comice pentru depășire (6+ km/h)
    private val funnyWarnings = listOf(
        "Boule! Încetinește că iei amendă!",
        "Ai prea mulți bani în buzunar? Încetinește!",
        "Nu ai ce face cu banii? Vrei să-i dai la poliție?",
        "Vrei să plătești amendă? Că e scumpă!",
        "Hei șoferule! Frânează odată!",
        "Asta nu-i autostradă fără limită! Încetinește!",
        "Ce grăbit ești! Încetinește!",
        "Radar în față! Glumesc, dar încetinește!",
        "Portofelul tău plânge! Încetinește!",
        "Banii tăi, amenda lor! Frânează!"
    )
    
    private var warnedOnce = false  // O singură avertizare per zonă
    
    private fun checkSpeedingAndWarn(speedKmh: Float, limit: Int) {
        // Nu avertiza daca nu avem limita sau suntem pe autostrada fara limita
        if (limit <= 0 || limit == -1) return
        
        // Verifică dacă limita e proaspătă
        val limitAge = System.currentTimeMillis() - limitUpdateTime
        if (limitAge > 5000 && limitUpdateTime > 0) {
            return
        }
        
        val over = (speedKmh - limit).toInt()
        
        // Depășire 6+ km/h - mesaj COMIC (o singură dată!)
        if (over >= 6) {
            if (!warnedOnce) {
                warnedOnce = true
                isCurrentlySpeeding = true
                alreadyWarnedForThisZone = true
                announceUrgent(funnyWarnings.random())
                updateNotification("⚠️ DEPĂȘIRE! ${speedKmh.toInt()} / $limit km/h")
            }
        } 
        // Depășire 3-5 km/h - avertizare simplă (o singură dată!)
        else if (over > 3) {
            if (!warnedOnce) {
                warnedOnce = true
                isCurrentlySpeeding = true
                announceMessage("Atenție, ai depășit limita.")
                updateNotification("⚠️ ${speedKmh.toInt()} / $limit km/h")
            }
        } 
        // Sub limită - resetează
        else {
            if (isCurrentlySpeeding) {
                isCurrentlySpeeding = false
                // NU resetăm warnedOnce - rămâne true până schimbă strada/zona
            }
        }
    }

    // -1 = fara limita (autostrada nelimitata)
    // >0 = limita normala
    // null = nu s-a putut determina
    
    private fun getSpeedLimit(lat: Double, lon: Double, bearing: Float): Int? {
        // Pasul 1: Detecteaza tipul drumului via TomTom Reverse Geocode
        val roadInfo = getRoadInfo(lat, lon)
        
        if (roadInfo.isHighway) {
            Log.d(TAG, "PE AUTOSTRADA: ${roadInfo.roadName}")
            // Pasul 2: Pe autostrada - foloseste routing cu bearing
            val routingLimit = getHighwaySpeedLimit(lat, lon, bearing)
            if (routingLimit != null && routingLimit > 0) return routingLimit
            // Daca routing-ul nu gaseste limita pe autostrada -> FARA LIMITA
            return -1
        }
        
        // Pe drum normal - foloseste speedLimit din reverse geocode
        if (roadInfo.speedLimit > 0) return roadInfo.speedLimit
        
        // Fallback: TomTom Routing
        val routingLimit = getSpeedLimitFromTomTomRouting(lat, lon, bearing)
        if (routingLimit != null && routingLimit > 0) return routingLimit
        
        // Fallback: OSM
        return getSpeedLimitFromOSM(lat, lon)
    }
    
    data class RoadInfo(val isHighway: Boolean, val speedLimit: Int, val roadName: String)
    
    private fun getRoadInfo(lat: Double, lon: Double): RoadInfo {
        return try {
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            val url = URL("https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$tomtomKey&returnSpeedLimit=true&returnRoadUse=true")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val addresses = json.optJSONArray("addresses")
            
            if (addresses != null && addresses.length() > 0) {
                val addrObj = addresses.getJSONObject(0)
                val addressInfo = addrObj.optJSONObject("address")
                
                // Detecteaza tip drum din roadUse
                var isHighway = false
                val roadUseArray = addrObj.optJSONArray("roadUse")
                if (roadUseArray != null) {
                    for (i in 0 until roadUseArray.length()) {
                        val use = roadUseArray.getString(i)
                        if (use == "LimitedAccess") {
                            isHighway = true
                            break
                        }
                    }
                }
                
                // Extrage speed limit
                var speedLimit = 0
                if (addressInfo != null) {
                    val speedLimitStr = addressInfo.optString("speedLimit", "")
                    if (speedLimitStr.isNotEmpty()) {
                        val match = Regex("(\\d+)").find(speedLimitStr)
                        if (match != null) speedLimit = match.groupValues[1].toInt()
                    }
                }
                
                // Extrage numele drumului
                val roadName = if (addressInfo != null) {
                    val routeNumbers = addressInfo.optJSONArray("routeNumbers")
                    if (routeNumbers != null && routeNumbers.length() > 0) {
                        routeNumbers.getString(0)
                    } else {
                        addressInfo.optString("street", "necunoscut")
                    }
                } else "necunoscut"
                
                Log.d(TAG, "RoadInfo: highway=$isHighway, speedLimit=$speedLimit, road=$roadName")
                return RoadInfo(isHighway, speedLimit, roadName)
            }
            RoadInfo(false, 0, "necunoscut")
        } catch (e: Exception) {
            Log.e(TAG, "getRoadInfo error: ${e.message}")
            RoadInfo(false, 0, "necunoscut")
        }
    }
    
    // Obtine limita pe autostrada folosind routing cu bearing
    private fun getHighwaySpeedLimit(lat: Double, lon: Double, bearing: Float): Int? {
        return try {
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            
            // Proiecteaza al doilea punct ~300m inainte in directia de mers
            val distance = 0.003 // ~300m
            val bearingRad = Math.toRadians(bearing.toDouble())
            val lat2 = lat + distance * Math.cos(bearingRad)
            val lon2 = lon + distance * Math.sin(bearingRad) / Math.cos(Math.toRadians(lat))
            
            val url = URL("https://api.tomtom.com/routing/1/calculateRoute/$lat,$lon:$lat2,$lon2/json?key=$tomtomKey&sectionType=speedLimit&routeType=fastest&travelMode=car")
            
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
                
                if (sections == null || sections.length() == 0) {
                    // Nicio sectiune de limita -> FARA LIMITA
                    Log.d(TAG, "Highway routing: no speed sections = UNLIMITED")
                    return null
                }
                
                // Cauta prima sectiune SPEED_LIMIT
                for (i in 0 until sections.length()) {
                    val section = sections.getJSONObject(i)
                    if (section.optString("sectionType") == "SPEED_LIMIT") {
                        val startIdx = section.optInt("startPointIndex", 0)
                        if (startIdx == 0) {
                            // Limita incepe de la punctul nostru
                            val speedLimit = section.optInt("maxSpeedLimitInKmh", 0)
                            Log.d(TAG, "Highway routing: limit at start = $speedLimit")
                            if (speedLimit > 0) return speedLimit
                        } else {
                            // Punctul nostru NU are limita (sectiunea incepe mai tarziu)
                            Log.d(TAG, "Highway routing: no limit at start (first section at $startIdx) = UNLIMITED")
                            return null
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "getHighwaySpeedLimit error: ${e.message}")
            null
        }
    }
    
    private fun getSpeedLimitFromTomTomRouting(lat: Double, lon: Double, bearing: Float): Int? {
        return try {
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            
            // Proiecteaza al doilea punct in directia de mers
            val distance = 0.002 // ~200m
            val bearingRad = Math.toRadians(bearing.toDouble())
            val lat2 = lat + distance * Math.cos(bearingRad)
            val lon2 = lon + distance * Math.sin(bearingRad) / Math.cos(Math.toRadians(lat))
            
            val url = URL("https://api.tomtom.com/routing/1/calculateRoute/$lat,$lon:$lat2,$lon2/json?key=$tomtomKey&sectionType=speedLimit&routeType=fastest&travelMode=car")
            
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
                        limit == -1 -> Color.parseColor("#9C27B0") // Violet pentru fara limita
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
