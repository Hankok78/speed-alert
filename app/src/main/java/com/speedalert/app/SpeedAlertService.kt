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
        
        // -1 inseamna "fara limita" (Autobahn)
        const val NO_LIMIT = -1
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
    
    // Cache local OSM
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
    
    private var waitingForNewLimit = false
    
    // Mesaje comice
    private val funnyWarnings = listOf(
        "Boule! Încetinește că iei amendă!",
        "Ai prea mulți bani în buzunar? Încetinește!",
        "Nu ai ce face cu banii? Vrei să-i dai la poliție?",
        "Vrei să plătești amendă? Că e scumpă!",
        "Hei șoferule! Frânează odată!",
        "Ce grăbit ești! Încetinește!",
        "Radar în față! Glumesc, dar încetinește!",
        "Portofelul tău plânge! Încetinește!",
        "Banii tăi, amenda lor! Frânează!"
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
        announceMessage("Serviciu pornit. Aștept locația GPS.")
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
            statusLiveData.postValue("Lipsă permisiune GPS")
        }
    }

    override fun onLocationChanged(location: Location) {
        val rawSpeedKmh = location.speed * 3.6f
        // Sub 5 km/h = GPS drift, afisam 0
        val speedKmh = if (rawSpeedKmh < 5f) 0f else rawSpeedKmh
        val currentBearing = location.bearing
        
        currentSpeedLiveData.postValue(speedKmh)
        locationLiveData.postValue(Pair(location.latitude, location.longitude))
        
        // Detecteaza viraj
        val bearingChange = abs(currentBearing - lastBearing)
        val normalizedChange = if (bearingChange > 180) 360 - bearingChange else bearingChange
        val isTurning = normalizedChange > 30 && lastBearing != 0f
        
        if (isTurning) {
            Log.d(TAG, "VIRAJ DETECTAT! Bearing change: $normalizedChange")
            waitingForNewLimit = true
            lastAnnouncedLimit = 0  // RESETARE - forteaza anuntarea pe drumul nou
            cachedSpeedLimit = 0
            isCurrentlySpeeding = false
            alreadyWarnedForThisZone = false
        }
        lastBearing = currentBearing
        
        val limitText = when {
            cachedSpeedLimit == NO_LIMIT -> "fără limită"
            cachedSpeedLimit > 0 -> "$cachedSpeedLimit"
            else -> "?"
        }
        updateNotification("Viteza: ${speedKmh.toInt()} km/h | Limita: $limitText")
        updateFloatingBubble(speedKmh.toInt(), cachedSpeedLimit)
        
        // Descarca cache daca nu exista sau suntem departe de centrul cache-ului
        val distFromCache = floatArrayOf(0f)
        if (cacheCenterLat != 0.0) {
            Location.distanceBetween(cacheCenterLat, cacheCenterLon, location.latitude, location.longitude, distFromCache)
        }
        if ((roadCache.isEmpty() || distFromCache[0] > 3000) && !cacheLoading) {
            loadRoadCache(location.latitude, location.longitude)
        }
        
        // Cauta limita LOCAL din cache (INSTANT, fara internet)
        if (roadCache.isNotEmpty()) {
            val limit = findNearestSpeedLimit(location.latitude, location.longitude, currentBearing)
            
            if (limit != null && limit != lastAnnouncedLimit) {
                Log.d(TAG, "LIMITĂ NOUĂ: $limit (era: $lastAnnouncedLimit)")
                cachedSpeedLimit = limit
                speedLimitLiveData.postValue(limit)
                lastAnnouncedLimit = limit
                isCurrentlySpeeding = false
                alreadyWarnedForThisZone = false
                warnedOnce = false
                waitingForNewLimit = false
                
                handler.post {
                    if (limit == NO_LIMIT) {
                        announceMessage("Fără limită! Autostradă liberă!")
                    } else {
                        announceMessage("Atenție! Limita $limit")
                    }
                }
            } else if (limit != null) {
                cachedSpeedLimit = limit
                waitingForNewLimit = false
            }
        }
        
        // Verifica depasire (doar daca avem limita pozitiva)
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
                statusLiveData.postValue("Se descarcă harta...")
                
                // Descarcam si drumuri FARA maxspeed explicit pt a aplica limite implicite
                val query = """
                    [out:json][timeout:30];
                    (
                      way(around:5000,$lat,$lon)["highway"~"motorway|trunk|primary|secondary|tertiary|residential|living_street|unclassified"]["maxspeed"];
                      way(around:5000,$lat,$lon)["highway"~"motorway|trunk|primary|secondary|tertiary|residential|living_street|unclassified"][!"maxspeed"];
                    );
                    out body geom;
                """.trimIndent()
                
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
                    statusLiveData.postValue("Eroare descărcare: $responseCode")
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
                        
                        if (points.size >= 2) {
                            newCache.add(RoadSegment(
                                maxspeed = tags.optString("maxspeed", ""),
                                conditional = tags.optString("maxspeed:conditional", ""),
                                highway = tags.optString("highway", ""),
                                name = tags.optString("name", ""),
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
                statusLiveData.postValue("Eroare: ${e.message?.take(30)}")
            }
            cacheLoading = false
        }
    }
    
    // Calculeaza distanta de la un punct la un segment de drum (punct-la-linie)
    private fun distanceToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): Double {
        val cosLat = cos(Math.toRadians(px))
        // Convertim in metri aproximativ
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
            // Punct degenerat
            val ddx = pxM - axM
            val ddy = pyM - ayM
            return sqrt(ddx * ddx + ddy * ddy)
        }
        
        // Proiectia punctului pe segment, clamped la [0,1]
        var t = ((pxM - axM) * dx + (pyM - ayM) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        
        val projX = axM + t * dx
        val projY = ayM + t * dy
        val ddx = pxM - projX
        val ddy = pyM - projY
        return sqrt(ddx * ddx + ddy * ddy)
    }
    
    // Calculeaza bearing-ul unui segment de drum
    private fun segmentBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlon = Math.toRadians(lon2 - lon1)
        val y = sin(dlon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dlon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    
    // Diferenta intre doua bearing-uri (0-180)
    private fun bearingDiff(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360
        return if (diff > 180) 360 - diff else diff
    }
    
    // Cauta drumul cel mai aproape si returneaza limita (LOCAL, fara internet)
    private fun findNearestSpeedLimit(lat: Double, lon: Double, gpsBearing: Float): Int? {
        var bestDist = Double.MAX_VALUE
        var bestLimit = 0
        var bestScore = -1.0
        val hasBearing = gpsBearing != 0f
        
        for (road in roadCache) {
            // Calculeaza distanta minima la orice segment al drumului
            var minDist = Double.MAX_VALUE
            var closestSegBearing = 0.0
            
            for (i in 0 until road.points.size - 1) {
                val p1 = road.points[i]
                val p2 = road.points[i + 1]
                val dist = distanceToSegment(lat, lon, p1.first, p1.second, p2.first, p2.second)
                if (dist < minDist) {
                    minDist = dist
                    closestSegBearing = segmentBearing(p1.first, p1.second, p2.first, p2.second)
                }
            }
            
            // Doar drumuri in raza de 40m
            if (minDist > 40) continue
            
            // Scor bazat pe: distanta + aliniere cu directia de mers + prioritate tip drum
            val priority = when(road.highway) {
                "motorway" -> 6.0
                "trunk" -> 5.0
                "primary" -> 4.0
                "secondary" -> 3.0
                "tertiary" -> 2.0
                "residential", "living_street", "unclassified" -> 1.0
                else -> 0.0
            }
            
            // Verifica alinierea cu directia de mers
            var bearingMatch = 1.0
            if (hasBearing) {
                // Drumul poate fi parcurs in ambele directii, deci verificam ambele
                val diff1 = bearingDiff(gpsBearing.toDouble(), closestSegBearing)
                val diff2 = bearingDiff(gpsBearing.toDouble(), (closestSegBearing + 180) % 360)
                val bestBearingDiff = min(diff1, diff2)
                
                // Daca unghiul e > 60 grade, probabil e o strada laterala
                if (bestBearingDiff > 60) {
                    bearingMatch = 0.1
                } else {
                    bearingMatch = 1.0 - (bestBearingDiff / 90.0) * 0.5
                }
            }
            
            // Scor compus: prioritate drum * aliniere / distanta
            val score = (priority + 1) * bearingMatch * (1.0 / (minDist + 1.0)) * 1000
            
            if (score > bestScore) {
                // Determina limita de viteza pentru acest drum
                val limit = resolveSpeedLimit(road)
                if (limit != null) {
                    bestLimit = limit
                    bestScore = score
                    bestDist = minDist
                }
            }
        }
        
        return if (bestLimit != 0) bestLimit else null
    }
    
    // Determina limita de viteza efectiva pentru un drum
    private fun resolveSpeedLimit(road: RoadSegment): Int? {
        // 1. Verifica mai intai limita conditionala (cu orar)
        if (road.conditional.isNotEmpty()) {
            val condLimit = parseConditionalSpeed(road.conditional)
            if (condLimit != null) {
                return condLimit
            }
            // Daca conditia NU e activa, continua cu maxspeed de baza
        }
        
        // 2. Verifica maxspeed explicit
        if (road.maxspeed.isNotEmpty()) {
            if (road.maxspeed == "none") {
                return NO_LIMIT  // Autobahn fara limita
            }
            if (road.maxspeed == "walk") {
                return 5
            }
            val match = Regex("(\\d+)").find(road.maxspeed)
            if (match != null) {
                return match.groupValues[1].toInt()
            }
        }
        
        // 3. Limite implicite bazate pe tipul drumului (Germania)
        return when(road.highway) {
            "motorway" -> NO_LIMIT  // Autobahn implicit fara limita
            "living_street" -> 7    // Zona rezidentiala
            else -> null            // Nu stim limita
        }
    }
    
    // Parseaza limite cu orar: "30 @ (Mo-Fr 07:00-18:00)"
    private fun parseConditionalSpeed(conditional: String): Int? {
        try {
            // Poate avea mai multe conditii separate cu ";"
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
                
                // Verifica zilele
                var dayMatch = true
                val dayRange = Regex("(Mo|Tu|We|Th|Fr|Sa|Su)-(Mo|Tu|We|Th|Fr|Sa|Su)").find(condition)
                if (dayRange != null) {
                    val startDay = dayToNum[dayRange.groupValues[1]] ?: continue
                    val endDay = dayToNum[dayRange.groupValues[2]] ?: continue
                    
                    dayMatch = if (startDay <= endDay) {
                        today in startDay..endDay
                    } else {
                        today >= startDay || today <= endDay
                    }
                }
                
                if (!dayMatch) continue
                
                // Verifica ora
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
                    handler.post { announceUrgent("Ai depășit limita!") }
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
                        limit == NO_LIMIT -> Color.parseColor("#9C27B0") // Violet pt Autobahn
                        limit <= 0 -> Color.parseColor("#2196F3")       // Albastru - se incarca
                        speed > limit + 5 -> Color.parseColor("#F44336") // Rosu - depasire mare
                        speed > limit -> Color.parseColor("#FF9800")     // Portocaliu - depasire mica
                        else -> Color.parseColor("#4CAF50")              // Verde - OK
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
