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
import org.json.JSONArray
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
    private var lastBearing = 0f
    
    // Cache local OSM - descarcat o singura data, cautat local
    private data class RoadSegment(
        val maxspeed: String,
        val conditional: String,
        val highway: String,
        val points: List<Pair<Double, Double>>
    )
    private var roadCache = mutableListOf<RoadSegment>()
    private var cacheCenterLat = 0.0
    private var cacheCenterLon = 0.0
    private var cacheLoading = false
    
    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var isBubbleShowing = false
    
    private var waitingForNewLimit = false
    
    // Mesaje comice
    private val funnyWarnings = listOf(
        "Boule! Incetineste ca iei amenda!",
        "Ai prea multi bani in buzunar? Incetineste!",
        "Nu ai ce face cu banii? Vrei sa-i dai la politie?",
        "Vrei sa platesti amenda? Ca e scumpa!",
        "Hei soferule! Franeaza odata!",
        "Ce grabit esti! Incetineste!",
        "Radar in fata! Glumesc, dar incetineste!",
        "Portofelul tau plange! Incetineste!",
        "Banii tai, amenda lor! Franeaza!"
    )
    
    private var warnedOnce = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Se pornește...")
        startForeground(NOTIFICATION_ID, notification)
        
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedAlert::WakeLock")
        wakeLock?.acquire()
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        statusLiveData.postValue("Pornit")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        createFloatingBubble()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ro", "RO")
            ttsReady = true
            Log.d(TAG, "TTS Ready")
        }
    }

    private fun announceMessage(text: String) {
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speed_${System.currentTimeMillis()}")
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
                    300L,
                    1f,
                    this,
                    Looper.getMainLooper()
                )
            }
            
            statusLiveData.postValue("GPS Activ")
        } else {
            statusLiveData.postValue("Lipsa permisiune GPS")
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = location.speed * 3.6f
        val currentBearing = location.bearing
        
        currentSpeedLiveData.postValue(speedKmh)
        locationLiveData.postValue(Pair(location.latitude, location.longitude))
        
        // Detecteaza viraj
        val bearingChange = Math.abs(currentBearing - lastBearing)
        val isTurning = bearingChange > 30 && bearingChange < 330 && lastBearing != 0f
        
        if (isTurning) {
            Log.d(TAG, "VIRAJ DETECTAT! Bearing change: $bearingChange")
            waitingForNewLimit = true
            isCurrentlySpeeding = false
            alreadyWarnedForThisZone = false
        }
        lastBearing = currentBearing
        
        updateNotification("Viteza: ${speedKmh.toInt()} km/h | Limita: ${if (cachedSpeedLimit > 0) cachedSpeedLimit else "?"}")
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        // Descarca cache daca nu exista sau suntem departe
        val distFromCache = floatArrayOf(0f)
        if (cacheCenterLat != 0.0) {
            Location.distanceBetween(cacheCenterLat, cacheCenterLon, location.latitude, location.longitude, distFromCache)
        }
        if ((roadCache.isEmpty() || distFromCache[0] > 3000) && !cacheLoading) {
            loadRoadCache(location.latitude, location.longitude)
        }
        
        // Cauta limita LOCAL din cache (INSTANT, fara internet)
        if (roadCache.isNotEmpty()) {
            val limit = findNearestSpeedLimit(location.latitude, location.longitude)
            
            if (limit != null && limit > 0 && limit != lastAnnouncedLimit) {
                Log.d(TAG, "LIMITA NOUA: $limit (era: $lastAnnouncedLimit)")
                cachedSpeedLimit = limit
                speedLimitLiveData.postValue(limit)
                lastAnnouncedLimit = limit
                isCurrentlySpeeding = false
                alreadyWarnedForThisZone = false
                warnedOnce = false
                waitingForNewLimit = false
                
                handler.post {
                    announceMessage("Atentie! Limita de $limit")
                }
            } else if (limit != null && limit > 0) {
                cachedSpeedLimit = limit
                waitingForNewLimit = false
            }
        }
        
        // Verifica depasire
        if (cachedSpeedLimit > 0 && !waitingForNewLimit) {
            checkSpeedingAndWarn(speedKmh, cachedSpeedLimit)
        }
    }
    
    // Descarca TOATE drumurile cu maxspeed in raza de 5km - O SINGURA CERERE
    private fun loadRoadCache(lat: Double, lon: Double) {
        cacheLoading = true
        serviceScope.launch {
            try {
                Log.d(TAG, "DESCARC CACHE OSM pentru $lat,$lon ...")
                statusLiveData.postValue("Se descarca harta...")
                
                val query = "[out:json][timeout:25];way(around:5000,$lat,$lon)[maxspeed];out body geom;"
                val url = URL("https://overpass-api.de/api/interpreter")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                
                connection.outputStream.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.e(TAG, "OSM HTTP error: $responseCode")
                    cacheLoading = false
                    return@launch
                }
                
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                
                val elements = JSONObject(response).optJSONArray("elements")
                val newCache = mutableListOf<RoadSegment>()
                
                if (elements != null) {
                    for (i in 0 until elements.length()) {
                        val elem = elements.getJSONObject(i)
                        val tags = elem.optJSONObject("tags") ?: continue
                        val geom = elem.optJSONArray("geometry") ?: continue
                        
                        val points = mutableListOf<Pair<Double, Double>>()
                        for (j in 0 until geom.length()) {
                            val p = geom.getJSONObject(j)
                            points.add(Pair(p.getDouble("lat"), p.getDouble("lon")))
                        }
                        
                        if (points.isNotEmpty()) {
                            newCache.add(RoadSegment(
                                maxspeed = tags.optString("maxspeed", ""),
                                conditional = tags.optString("maxspeed:conditional", ""),
                                highway = tags.optString("highway", ""),
                                points = points
                            ))
                        }
                    }
                }
                
                roadCache = newCache
                cacheCenterLat = lat
                cacheCenterLon = lon
                Log.d(TAG, "CACHE GATA: ${newCache.size} drumuri")
                statusLiveData.postValue("GPS Activ (${newCache.size} drumuri)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Cache error: ${e.message}")
            }
            cacheLoading = false
        }
    }
    
    // Cauta drumul cel mai aproape si returneaza limita (LOCAL, fara internet)
    private fun findNearestSpeedLimit(lat: Double, lon: Double): Int? {
        var bestDist = Double.MAX_VALUE
        var bestLimit = 0
        var bestPriority = -1
        
        for (road in roadCache) {
            // Calculeaza distanta minima de la GPS la segmentele drumului
            var minDist = Double.MAX_VALUE
            for (point in road.points) {
                val dlat = lat - point.first
                val dlon = (lon - point.second) * Math.cos(Math.toRadians(lat))
                val dist = Math.sqrt(dlat * dlat + dlon * dlon) * 111000 // in metri
                if (dist < minDist) minDist = dist
            }
            
            // Doar drumuri in raza de 30m
            if (minDist > 30) continue
            
            val priority = when(road.highway) {
                "motorway" -> 6
                "trunk" -> 5
                "primary" -> 4
                "secondary" -> 3
                "tertiary" -> 2
                "residential", "living_street", "unclassified" -> 1
                else -> 0
            }
            
            // Preferam drumul cu prioritate mai mare, sau cel mai aproape la aceeasi prioritate
            if (priority > bestPriority || (priority == bestPriority && minDist < bestDist)) {
                // Verifica limita conditionala (cu orar)
                if (road.conditional.isNotEmpty()) {
                    val condLimit = parseConditionalSpeed(road.conditional)
                    if (condLimit != null) {
                        bestLimit = condLimit
                        bestPriority = priority
                        bestDist = minDist
                        continue
                    }
                }
                
                if (road.maxspeed == "none") {
                    bestLimit = -1
                    bestPriority = priority
                    bestDist = minDist
                } else {
                    val match = Regex("(\\d+)").find(road.maxspeed)
                    if (match != null) {
                        bestLimit = match.groupValues[1].toInt()
                        bestPriority = priority
                        bestDist = minDist
                    }
                }
            }
        }
        
        return if (bestLimit != 0) bestLimit else null
    }
    
    // Parseaza limite cu orar: "30 @ (Mo-Fr 07:00-18:00)"
    private fun parseConditionalSpeed(conditional: String): Int? {
        try {
            val parts = conditional.split("@")
            if (parts.size < 2) return null
            
            val speedMatch = Regex("(\\d+)").find(parts[0].trim()) ?: return null
            val conditionalSpeed = speedMatch.groupValues[1].toInt()
            val condition = parts[1].trim()
            
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_WEEK)
            
            val dayToNum = mapOf("Mo" to 2, "Tu" to 3, "We" to 4, "Th" to 5, "Fr" to 6, "Sa" to 7, "Su" to 1)
            
            val dayRange = Regex("(Mo|Tu|We|Th|Fr|Sa|Su)-(Mo|Tu|We|Th|Fr|Sa|Su)").find(condition)
            if (dayRange != null) {
                val startDay = dayToNum[dayRange.groupValues[1]] ?: return null
                val endDay = dayToNum[dayRange.groupValues[2]] ?: return null
                
                val todayInRange = if (startDay <= endDay) {
                    today in startDay..endDay
                } else {
                    today >= startDay || today <= endDay
                }
                
                if (!todayInRange) return null
            }
            
            val timeMatch = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").find(condition)
            if (timeMatch != null) {
                val startTotal = timeMatch.groupValues[1].toInt() * 60 + timeMatch.groupValues[2].toInt()
                val endTotal = timeMatch.groupValues[3].toInt() * 60 + timeMatch.groupValues[4].toInt()
                val nowTotal = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                
                if (nowTotal in startTotal..endTotal) return conditionalSpeed
            }
            return null
        } catch (e: Exception) { return null }
    }
    
    private fun checkSpeedingAndWarn(speedKmh: Float, limit: Int) {
        if (limit <= 0) return
        
        val over = speedKmh - limit
        
        if (over > 3) {
            if (!isCurrentlySpeeding) {
                isCurrentlySpeeding = true
                alreadyWarnedForThisZone = false
                warnedOnce = false
            }
            
            if (!alreadyWarnedForThisZone) {
                alreadyWarnedForThisZone = true
                
                if (over >= 6 && !warnedOnce) {
                    warnedOnce = true
                    val warning = funnyWarnings.random()
                    handler.post { announceUrgent(warning) }
                } else if (!warnedOnce) {
                    warnedOnce = true
                    handler.post { announceUrgent("Ai depasit limita!") }
                }
            }
        } else {
            if (isCurrentlySpeeding) {
                isCurrentlySpeeding = false
                alreadyWarnedForThisZone = false
                warnedOnce = false
            }
        }
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
    
    private fun createFloatingBubble() {
        if (isBubbleShowing) return
        if (!Settings.canDrawOverlays(this)) return
        
        try {
            val params = WindowManager.LayoutParams(
                150, 150,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 20
            params.y = 100
            
            val bubble = TextView(this)
            bubble.text = "0"
            bubble.textSize = 28f
            bubble.setTextColor(Color.WHITE)
            bubble.gravity = Gravity.CENTER
            
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.parseColor("#2196F3"))
            bubble.background = bg
            
            windowManager?.addView(bubble, params)
            floatingBubble = bubble
            isBubbleShowing = true
        } catch (e: Exception) {
            Log.e(TAG, "Bubble error: ${e.message}")
        }
    }
    
    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Alert")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speed Alert",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            if (isBubbleShowing && floatingBubble != null) {
                windowManager?.removeView(floatingBubble)
                isBubbleShowing = false
            }
        } catch (e: Exception) {}
        
        locationManager.removeUpdates(this)
        tts?.stop()
        tts?.shutdown()
        wakeLock?.release()
        serviceScope.cancel()
        statusLiveData.postValue("Oprit")
    }
}
