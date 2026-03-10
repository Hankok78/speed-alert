package com.speedalert.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.*

class SpeedAlertService : Service(), TextToSpeech.OnInitListener, LocationListener {

    companion object {
        val speedLimitLiveData = MutableLiveData<Int>()
        val currentSpeedLiveData = MutableLiveData<Float>()
        val statusLiveData = MutableLiveData<String>()
        
        private const val CHANNEL_ID = "SpeedAlertChannel"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var tts: TextToSpeech
    private lateinit var locationManager: LocationManager
    private var lastSpeedLimit = -1
    private var ttsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
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
            speak("Avertizare viteză pornită")
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speedalert")
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            // Try GPS first
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // 1 second
                    5f,    // 5 meters
                    this,
                    Looper.getMainLooper()
                )
            }
            
            // Also use network provider
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    10f,
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
        
        // Update notification with current speed
        updateNotification("Viteză: ${speedKmh.toInt()} km/h")
        
        // Get speed limit from OpenStreetMap
        serviceScope.launch {
            val limit = getSpeedLimit(location.latitude, location.longitude)
            if (limit != null && limit > 0) {
                speedLimitLiveData.postValue(limit)
                
                // Announce if limit changed
                if (lastSpeedLimit != limit) {
                    withContext(Dispatchers.Main) {
                        speak("Atenție, limită $limit")
                    }
                    lastSpeedLimit = limit
                }
                
                // Warn if over limit
                if (speedKmh > limit + 5) {
                    withContext(Dispatchers.Main) {
                        updateNotification("⚠️ ${speedKmh.toInt()} km/h - Limită $limit!")
                    }
                }
            }
        }
    }

    private fun getSpeedLimit(lat: Double, lon: Double): Int? {
        return try {
            val radius = 30
            val query = """
                [out:json][timeout:10];
                way(around:$radius,$lat,$lon)["maxspeed"];
                out body;
            """.trimIndent()
            
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
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

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        tts.stop()
        tts.shutdown()
        serviceScope.cancel()
        speak("Avertizare viteză oprită")
        statusLiveData.postValue("Oprit")
    }
}
