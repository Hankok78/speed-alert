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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    }

    private lateinit var tts: TextToSpeech
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSpeedLimit = -1
    private var ttsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    // Pentru avertizări repetate
    private var warningCount = 0
    private var lastWarningTime = 0L
    private var isCurrentlySpeeding = false
    private var alreadyWarnedForThisZone = false
    private val handler = Handler(Looper.getMainLooper())
    
    // Cache pentru limite de viteză (reduce întârzierea)
    private var cachedSpeedLimit = -1
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    
    // Floating bubble
    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var isBubbleShowing = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Wake lock pentru funcționare cu ecranul închis
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpeedAlert::LocationWakeLock"
        )
        wakeLock?.acquire()
        
        // Configurare audio focus pentru Android Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
        }
        
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
            val result = tts.setLanguage(Locale("ro", "RO"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.GERMAN)
            }
            ttsReady = true
            tts.setSpeechRate(1.1f)
            
            // Setează stream-ul audio pentru navigație (prioritate înaltă în Android Auto)
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
            
            // Listener pentru a elibera audio focus după ce termină de vorbit
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Audio focus cerut în speak()
                }
                override fun onDone(utteranceId: String?) {
                    releaseAudioFocus()
                }
                override fun onError(utteranceId: String?) {
                    releaseAudioFocus()
                }
            })
            
            speak("Avertizare viteză pornită")
        }
    }
    
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.requestAudioFocus(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        
        // Mărește volumul pentru notificări
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxVolume, 0)
        } catch (e: Exception) { }
    }
    
    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            requestAudioFocus()
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, "speedalert_${System.currentTimeMillis()}")
        }
    }

    private fun speakUrgent(text: String) {
        if (ttsReady) {
            requestAudioFocus()
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "speedalert_urgent")
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            // GPS cu frecvență MARE pentru reacție rapidă
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,  // 0.5 secunde (era 1 secundă)
                    2f,    // 2 metri (era 5 metri)
                    this,
                    Looper.getMainLooper()
                )
            }
            
            // Network ca backup
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
            statusLiveData.postValue("Lipsă permisiune GPS")
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = location.speed * 3.6f
        currentSpeedLiveData.postValue(speedKmh)
        
        // Trimite poziția pentru hartă
        locationLiveData.postValue(Pair(location.latitude, location.longitude))
        
        updateNotification("Viteză: ${speedKmh.toInt()} km/h | Limită: ${if (cachedSpeedLimit > 0) cachedSpeedLimit else "?"}")
        
        // Actualizează bubble-ul flotant
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        // Verifică dacă suntem departe de ultima căutare (>15m) sau nu avem limită
        val distance = floatArrayOf(0f)
        if (lastQueryLat != 0.0) {
            Location.distanceBetween(lastQueryLat, lastQueryLon, location.latitude, location.longitude, distance)
        }
        
        // Caută limită nouă dacă ne-am deplasat >15m sau nu avem limită
        if (distance[0] > 15 || cachedSpeedLimit < 0 || lastQueryLat == 0.0) {
            lastQueryLat = location.latitude
            lastQueryLon = location.longitude
            
            serviceScope.launch {
                val limit = getSpeedLimit(location.latitude, location.longitude)
                if (limit != null && limit > 0) {
                    val oldLimit = cachedSpeedLimit
                    cachedSpeedLimit = limit
                    speedLimitLiveData.postValue(limit)
                    
                    // Anunță ORICE schimbare de limită (30, 50, 60, etc.)
                    if (lastSpeedLimit != limit) {
                        lastSpeedLimit = limit
                        // Resetează avertizările la schimbarea zonei
                        warningCount = 0
                        isCurrentlySpeeding = false
                        alreadyWarnedForThisZone = false
                        
                        // Anunță vocal noua limită
                        withContext(Dispatchers.Main) {
                            speak("Limită $limit")
                        }
                    }
                }
            }
        }
        
        // Verifică depășire cu limita din cache (răspuns IMEDIAT)
        checkSpeedingAndWarn(speedKmh, cachedSpeedLimit)
    }
    
    private fun checkSpeedingAndWarn(speedKmh: Float, limit: Int) {
        if (limit <= 0) return
        
        val over = (speedKmh - limit).toInt()
        
        // Depășire cu peste 7 km/h - avertisment "boule"
        if (over > 7) {
            if (!alreadyWarnedForThisZone) {
                alreadyWarnedForThisZone = true
                isCurrentlySpeeding = true
                speakUrgent("Boule! Încetinește că iei amendă!")
                updateNotification("⚠️ DEPĂȘIRE! ${speedKmh.toInt()} / $limit km/h")
            }
        }
        // Depășire cu 3-7 km/h - avertisment normal
        else if (over > 3) {
            if (!isCurrentlySpeeding) {
                isCurrentlySpeeding = true
                speak("Ai depășit limita cu $over kilometri")
                updateNotification("⚠️ ${speedKmh.toInt()} / $limit km/h")
            }
        }
        // Sub limită sau în toleranță - RESETEAZĂ pentru a permite noi avertizări
        else {
            if (isCurrentlySpeeding || alreadyWarnedForThisZone) {
                isCurrentlySpeeding = false
                alreadyWarnedForThisZone = false  // RESETEAZĂ când încetinește!
                handler.removeCallbacksAndMessages(null)
            }
        }
    }

    private fun getSpeedLimit(lat: Double, lon: Double): Int? {
        return try {
            // Folosește TomTom API pentru limite de viteză precise
            val tomtomKey = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"
            val url = URL("https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$tomtomKey&returnSpeedLimit=true")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val json = JSONObject(response)
            val addresses = json.optJSONArray("addresses")
            
            if (addresses != null && addresses.length() > 0) {
                val address = addresses.getJSONObject(0)
                val addressInfo = address.optJSONObject("address")
                
                // Încearcă să obții limita de viteză
                if (addressInfo != null) {
                    val speedLimit = addressInfo.optInt("speedLimit", -1)
                    if (speedLimit > 0) {
                        return speedLimit
                    }
                    
                    // Dacă nu e disponibilă, estimează pe baza tipului de drum
                    val roadType = addressInfo.optString("streetNameAndNumber", "")
                    val roadClass = addressInfo.optString("roadUse", "")
                    
                    // Estimare pe baza codului de țară și tip drum
                    val countryCode = addressInfo.optString("countryCode", "")
                    
                    return when {
                        roadClass.contains("motorway", true) -> 130
                        roadClass.contains("trunk", true) -> 100
                        roadClass.contains("arterial", true) -> 70
                        roadClass.contains("local", true) -> 50
                        else -> null
                    }
                }
            }
            
            // Fallback la Overpass API dacă TomTom nu returnează nimic
            getSpeedLimitFromOSM(lat, lon)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback la OpenStreetMap
            getSpeedLimitFromOSM(lat, lon)
        }
    }
    
    private fun getSpeedLimitFromOSM(lat: Double, lon: Double): Int? {
        return try {
            val radius = 35
            val query = """
                [out:json][timeout:5];
                (
                  way(around:$radius,$lat,$lon)["maxspeed"];
                  way(around:$radius,$lat,$lon)["highway"];
                );
                out tags;
            """.trimIndent()
            
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
                val roads = mutableListOf<Pair<Int, Int>>()
                
                for (i in 0 until elements.length()) {
                    val element = elements.getJSONObject(i)
                    val tags = element.optJSONObject("tags")
                    if (tags != null) {
                        val maxspeed = tags.optString("maxspeed", "")
                        val highway = tags.optString("highway", "")
                        
                        var limit: Int? = null
                        var priority = when (highway) {
                            "motorway", "motorway_link" -> 100
                            "trunk", "trunk_link" -> 90
                            "primary", "primary_link" -> 80
                            "secondary", "secondary_link" -> 70
                            "tertiary", "tertiary_link" -> 60
                            "unclassified" -> 50
                            "residential" -> 20
                            "living_street" -> 10
                            "service" -> 5
                            else -> 1
                        }
                        
                        if (maxspeed.isNotEmpty()) {
                            val match = Regex("(\\d+)").find(maxspeed)
                            if (match != null) {
                                limit = match.groupValues[1].toInt()
                                priority += 50
                            }
                        } else {
                            limit = when (highway) {
                                "motorway" -> 130
                                "motorway_link" -> 80
                                "trunk" -> 100
                                "primary" -> 100
                                "secondary" -> 70
                                "tertiary" -> 50
                                "residential" -> 50
                                "living_street" -> 30
                                "service" -> 30
                                else -> null
                            }
                        }
                        
                        if (limit != null && priority > 0) {
                            roads.add(Pair(limit, priority))
                        }
                    }
                }
                
                if (roads.isNotEmpty()) {
                    return roads.maxByOrNull { it.second }?.first
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
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

    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return // Nu avem permisiune
        }
        
        try {
            floatingBubble = TextView(this).apply {
                text = "0"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(20, 20, 20, 20)
                
                // Fundal rotund
                val shape = GradientDrawable()
                shape.shape = GradientDrawable.OVAL
                shape.setColor(Color.parseColor("#4CAF50")) // Verde
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
            e.printStackTrace()
        }
    }
    
    private fun updateFloatingBubble(speed: Int, limit: Int) {
        handler.post {
            floatingBubble?.let { bubble ->
                bubble.text = "$speed"
                
                val shape = bubble.background as? GradientDrawable
                shape?.let {
                    when {
                        limit <= 0 -> it.setColor(Color.parseColor("#2196F3")) // Albastru - nu știm limita
                        speed > limit + 5 -> it.setColor(Color.parseColor("#F44336")) // Roșu - depășire mare
                        speed > limit -> it.setColor(Color.parseColor("#FF9800")) // Portocaliu - ușor peste
                        else -> it.setColor(Color.parseColor("#4CAF50")) // Verde - OK
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
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingBubble()
        handler.removeCallbacksAndMessages(null)
        locationManager.removeUpdates(this)
        tts.stop()
        tts.shutdown()
        serviceScope.cancel()
        
        // Eliberează wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        statusLiveData.postValue("Oprit")
    }
}
