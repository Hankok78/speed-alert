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
import android.os.Bundle
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
import android.app.AlarmManager
import android.graphics.drawable.GradientDrawable
import kotlin.math.*

class SpeedAlertService : Service(), TextToSpeech.OnInitListener, LocationListener {

    companion object {
        val speedLimitLiveData = MutableLiveData<Int>()
        val currentSpeedLiveData = MutableLiveData<Float>()
        val statusLiveData = MutableLiveData<String>()
        val locationLiveData = MutableLiveData<Pair<Double, Double>>()
        
        private const val CHANNEL_ID = "SpeedAlertChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "SpeedAlert"
        
        const val NO_LIMIT = -1
        var stoppedByUser = false
        
        var currentLanguage = "ro"
        var ttsVolume = 1.0f
    }

    private var tts: TextToSpeech? = null
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAnnouncedLimit = 0
    private var ttsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private var speedingWarned = false
    private var lastLimitChangeTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    
    private var cachedSpeedLimit = 0
    private var lastBearing = 0f
    
    private data class RoadSegment(
        val maxspeed: String,
        val conditional: String,
        val highway: String,
        val name: String,
        val points: List<Pair<Double, Double>>
    )
    private var roadCache = mutableListOf<RoadSegment>()
    private var cacheCenterLat = 0.0
    private var cacheCenterLon = 0.0
    private var cacheLoading = false
    
    private var windowManager: WindowManager? = null
    private var floatingBubble: TextView? = null
    private var isBubbleShowing = false
    
    private val translations = mapOf(
        "ro" to mapOf(
            "limit" to "Atenție! Limita %d",
            "no_limit" to "Fără limită! Autostradă liberă!",
            "speeding" to "Ai depășit limita!",
            "service_start" to "Serviciu pornit.",
            "downloading" to "Se descarcă harta...",
            "gps_active" to "GPS Activ",
            "no_gps" to "Lipsă permisiune GPS",
            "stopped" to "Oprit",
            "started" to "Pornit"
        ),
        "de" to mapOf(
            "limit" to "Achtung! Tempolimit %d",
            "no_limit" to "Kein Tempolimit! Freie Autobahn!",
            "speeding" to "Geschwindigkeitslimit überschritten!",
            "service_start" to "Dienst gestartet.",
            "downloading" to "Karte wird geladen...",
            "gps_active" to "GPS Aktiv",
            "no_gps" to "GPS Berechtigung fehlt",
            "stopped" to "Gestoppt",
            "started" to "Gestartet"
        ),
        "en" to mapOf(
            "limit" to "Warning! Speed limit %d",
            "no_limit" to "No speed limit! Open highway!",
            "speeding" to "Speed limit exceeded!",
            "service_start" to "Service started.",
            "downloading" to "Downloading map...",
            "gps_active" to "GPS Active",
            "no_gps" to "Missing GPS permission",
            "stopped" to "Stopped",
            "started" to "Started"
        )
    )
    
    private fun t(key: String): String {
        return translations[currentLanguage]?.get(key) ?: translations["en"]?.get(key) ?: key
    }
    
    private fun ttsLocale(): Locale {
        return when (currentLanguage) {
            "de" -> Locale.GERMAN
            "en" -> Locale.US
            else -> Locale("ro", "RO")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("...")
        startForeground(NOTIFICATION_ID, notification)
        
        tts = TextToSpeech(this, this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedAlert::WakeLock")
        wakeLock?.acquire()
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        statusLiveData.postValue(t("started"))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpeedAlert::WakeLock")
            wakeLock?.acquire()
        }
        startLocationUpdates()
        createFloatingBubble()
        announceMessage(t("service_start"))
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = ttsLocale()
            ttsReady = true
            Log.d(TAG, "TTS Ready")
        }
    }

    private fun announceMessage(text: String) {
        if (ttsReady && tts != null) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "speed_${System.currentTimeMillis()}")
        }
    }
    
    private fun announceUrgent(text: String) {
        if (ttsReady && tts != null) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "urgent_${System.currentTimeMillis()}")
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
            
            statusLiveData.postValue(t("gps_active"))
        } else {
            statusLiveData.postValue(t("no_gps"))
        }
    }

    override fun onLocationChanged(location: Location) {
        val rawSpeedKmh = location.speed * 3.6f
        val speedKmh = if (rawSpeedKmh < 5f) 0f else rawSpeedKmh
        val currentBearing = location.bearing
        
        currentSpeedLiveData.postValue(speedKmh)
        locationLiveData.postValue(Pair(location.latitude, location.longitude))
        lastBearing = currentBearing
        
        val limitText = when {
            cachedSpeedLimit == NO_LIMIT -> "∞"
            cachedSpeedLimit > 0 -> "$cachedSpeedLimit"
            else -> "?"
        }
        updateNotification("${speedKmh.toInt()} km/h | $limitText")
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        // Descarca cache daca nu exista sau suntem departe
        val distFromCache = floatArrayOf(0f)
        if (cacheCenterLat != 0.0) {
            Location.distanceBetween(cacheCenterLat, cacheCenterLon, location.latitude, location.longitude, distFromCache)
        }
        if ((roadCache.isEmpty() || distFromCache[0] > 3000) && !cacheLoading) {
            loadRoadCache(location.latitude, location.longitude)
        }
        
        // Cauta limita LOCAL din cache - SIMPLU
        if (roadCache.isNotEmpty()) {
            val limit = findNearestSpeedLimit(location.latitude, location.longitude, currentBearing)
            
            if (limit != null && limit != lastAnnouncedLimit) {
                // Limita s-a schimbat! Anunta imediat.
                Log.d(TAG, "LIMITA NOUA: $limit (era: $lastAnnouncedLimit)")
                cachedSpeedLimit = limit
                speedLimitLiveData.postValue(limit)
                lastAnnouncedLimit = limit
                speedingWarned = false
                lastLimitChangeTime = System.currentTimeMillis()
                
                handler.post {
                    if (limit == NO_LIMIT) {
                        announceMessage(t("no_limit"))
                    } else {
                        announceMessage(String.format(t("limit"), limit))
                    }
                }
            } else if (limit != null) {
                cachedSpeedLimit = limit
            }
        }
        
        // Avertizare depasire - doar dupa 5 sec de la schimbarea limitei
        val timeSinceLimitChange = System.currentTimeMillis() - lastLimitChangeTime
        if (cachedSpeedLimit > 0 && timeSinceLimitChange > 5000) {
            if (speedKmh > cachedSpeedLimit + 5 && !speedingWarned) {
                speedingWarned = true
                handler.post { announceUrgent(t("speeding")) }
            } else if (speedKmh <= cachedSpeedLimit) {
                speedingWarned = false
            }
        }
    }
    
    // Query-ul SIMPLU care functiona - DOAR drumuri, fara scoli
    private fun loadRoadCache(lat: Double, lon: Double) {
        cacheLoading = true
        serviceScope.launch {
            try {
                Log.d(TAG, "DESCARC CACHE OSM pentru $lat,$lon ...")
                statusLiveData.postValue(t("downloading"))
                
                val query = "[out:json][timeout:25];(" +
                    "way(around:5000,$lat,$lon)[\"highway\"~\"motorway|trunk|primary|secondary|tertiary|residential|living_street|unclassified\"][\"maxspeed\"];" +
                    "way(around:5000,$lat,$lon)[\"highway\"~\"motorway|trunk|primary|secondary|tertiary|residential|living_street|unclassified\"][!\"maxspeed\"];" +
                    ");out body geom;"
                
                val url = URL("https://overpass-api.de/api/interpreter")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "SpeedAlertApp/1.0")
                
                connection.outputStream.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
                
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.e(TAG, "OSM HTTP error: $responseCode")
                    statusLiveData.postValue("Error: $responseCode")
                    cacheLoading = false
                    return@launch
                }
                
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                
                val elements = JSONObject(response).optJSONArray("elements")
                val newRoads = mutableListOf<RoadSegment>()
                
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
                        
                        if (points.size >= 2) {
                            newRoads.add(RoadSegment(
                                maxspeed = tags.optString("maxspeed", ""),
                                conditional = tags.optString("maxspeed:conditional", ""),
                                highway = tags.optString("highway", ""),
                                name = tags.optString("name", ""),
                                points = points
                            ))
                        }
                    }
                }
                
                roadCache = newRoads
                cacheCenterLat = lat
                cacheCenterLon = lon
                Log.d(TAG, "CACHE: ${newRoads.size} drumuri")
                statusLiveData.postValue("${t("gps_active")} (${newRoads.size})")
                
            } catch (e: Exception) {
                Log.e(TAG, "Cache error: ${e.message}")
                statusLiveData.postValue("Error: ${e.message?.take(30)}")
            }
            cacheLoading = false
        }
    }
    
    private fun distanceToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val cosLat = cos(Math.toRadians(px))
        val pxM = px * 111000.0
        val pyM = py * 111000.0 * cosLat
        val axM = ax * 111000.0
        val ayM = ay * 111000.0 * cosLat
        val bxM = bx * 111000.0
        val byM = by * 111000.0 * cosLat
        
        val dx = bxM - axM
        val dy = byM - ayM
        val lenSq = dx * dx + dy * dy
        
        if (lenSq == 0.0) {
            val ddx = pxM - axM
            val ddy = pyM - ayM
            return sqrt(ddx * ddx + ddy * ddy)
        }
        
        var t = ((pxM - axM) * dx + (pyM - ayM) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        
        val projX = axM + t * dx
        val projY = ayM + t * dy
        val ddx = pxM - projX
        val ddy = pyM - projY
        return sqrt(ddx * ddx + ddy * ddy)
    }
    
    private fun segmentBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlon = Math.toRadians(lon2 - lon1)
        val y = sin(dlon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dlon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    
    private fun bearingDiff(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360
        return if (diff > 180) 360 - diff else diff
    }
    
    // SIMPLU: gaseste cel mai apropiat drum cu limita de viteza
    private fun findNearestSpeedLimit(lat: Double, lon: Double, gpsBearing: Float): Int? {
        var bestDist = Double.MAX_VALUE
        var bestLimit: Int? = null
        
        for (road in roadCache) {
            // Calculeaza distanta minima la drum
            var minDist = Double.MAX_VALUE
            for (i in 0 until road.points.size - 1) {
                val p1 = road.points[i]
                val p2 = road.points[i + 1]
                val dist = distanceToSegment(lat, lon, p1.first, p1.second, p2.first, p2.second)
                if (dist < minDist) minDist = dist
            }
            
            // Doar drumuri in raza de 50m
            if (minDist > 50) continue
            
            // Rezolva limita
            val limit = resolveSpeedLimit(road)
            if (limit == null) continue
            
            // Prioritate: drum mai important castiga la distante similare
            val priorityBonus = when(road.highway) {
                "motorway" -> -15.0
                "trunk" -> -12.0
                "primary" -> -10.0
                "secondary" -> -7.0
                "tertiary" -> -5.0
                else -> 0.0
            }
            val adjustedDist = minDist + priorityBonus
            
            if (adjustedDist < bestDist) {
                bestDist = adjustedDist
                bestLimit = limit
            }
        }
        
        return bestLimit
    }
    
    private fun resolveSpeedLimit(road: RoadSegment): Int? {
        if (road.conditional.isNotEmpty()) {
            val condLimit = parseConditionalSpeed(road.conditional)
            if (condLimit != null) return condLimit
        }
        
        if (road.maxspeed.isNotEmpty()) {
            if (road.maxspeed == "none") return NO_LIMIT
            if (road.maxspeed == "walk") return 5
            val match = Regex("(\\d+)").find(road.maxspeed)
            if (match != null) return match.groupValues[1].toInt()
        }
        
        return when(road.highway) {
            "motorway" -> NO_LIMIT
            "living_street" -> 7
            else -> null
        }
    }
    
    private fun parseConditionalSpeed(conditional: String): Int? {
        try {
            val conditions = conditional.split(";")
            for (cond in conditions) {
                val parts = cond.split("@")
                if (parts.size < 2) continue
                
                val speedMatch = Regex("(\\d+)").find(parts[0].trim()) ?: continue
                val conditionalSpeed = speedMatch.groupValues[1].toInt()
                val condition = parts[1].trim()
                
                val cal = Calendar.getInstance()
                val today = cal.get(Calendar.DAY_OF_WEEK)
                
                val dayToNum = mapOf(
                    "Mo" to Calendar.MONDAY, "Tu" to Calendar.TUESDAY,
                    "We" to Calendar.WEDNESDAY, "Th" to Calendar.THURSDAY,
                    "Fr" to Calendar.FRIDAY, "Sa" to Calendar.SATURDAY,
                    "Su" to Calendar.SUNDAY
                )
                
                var dayMatch = true
                val dayRange = Regex("(Mo|Tu|We|Th|Fr|Sa|Su)-(Mo|Tu|We|Th|Fr|Sa|Su)").find(condition)
                if (dayRange != null) {
                    val startDay = dayToNum[dayRange.groupValues[1]] ?: continue
                    val endDay = dayToNum[dayRange.groupValues[2]] ?: continue
                    dayMatch = if (startDay <= endDay) today in startDay..endDay
                              else today >= startDay || today <= endDay
                }
                if (!dayMatch) continue
                
                var timeMatch = true
                val timeRegex = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").find(condition)
                if (timeRegex != null) {
                    val startTotal = timeRegex.groupValues[1].toInt() * 60 + timeRegex.groupValues[2].toInt()
                    val endTotal = timeRegex.groupValues[3].toInt() * 60 + timeRegex.groupValues[4].toInt()
                    val nowTotal = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    timeMatch = nowTotal in startTotal until endTotal
                }
                
                if (dayMatch && timeMatch) return conditionalSpeed
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Conditional parse error: ${e.message}")
            return null
        }
    }
    
    private fun updateFloatingBubble(speed: Int, limit: Int) {
        handler.post {
            floatingBubble?.let { bubble ->
                bubble.text = "$speed"
                (bubble.background as? GradientDrawable)?.setColor(
                    when {
                        limit == NO_LIMIT -> Color.parseColor("#9C27B0")
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
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
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
        statusLiveData.postValue(t("stopped"))
        
        if (!stoppedByUser) {
            val restartIntent = Intent(applicationContext, SpeedAlertService::class.java)
            val pendingIntent = PendingIntent.getService(
                applicationContext, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
        }
        stoppedByUser = false
    }
}
