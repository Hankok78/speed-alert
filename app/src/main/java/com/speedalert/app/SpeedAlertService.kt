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
    private var lastQueryTime = 0L
    
    // Stabilizare limită - 2 citiri la fel
    private var pendingLimit = 0
    private var pendingLimitCount = 0
    private val CONFIRMATIONS_NEEDED = 2
    
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
        
        // Minim 3 secunde intre query-uri (Overpass API rate limit)
        val timeSinceLastQuery = System.currentTimeMillis() - lastQueryTime
        val shouldQuery = (distance[0] > 30 || isTurning || cachedSpeedLimit <= 0 || lastQueryLat == 0.0) && timeSinceLastQuery > 3000
        
        if (shouldQuery) {
            lastQueryLat = location.latitude
            lastQueryLon = location.longitude
            lastQueryTime = System.currentTimeMillis()
            
            serviceScope.launch {
                try {
                    val limit = getSpeedLimit(location.latitude, location.longitude)
                    
                    if (limit != null && limit > 0) {
                        limitUpdateTime = System.currentTimeMillis()
                        
                        // STABILIZARE: 2 citiri la fel pentru a confirma
                        if (limit == pendingLimit) {
                            pendingLimitCount++
                        } else {
                            // Limită nouă - începe numărătoarea
                            pendingLimit = limit
                            pendingLimitCount = 1
                        }
                        
                        // Anunță DOAR dacă avem 3 citiri consecutive la fel
                        // SAU dacă e o schimbare MARE (diferență > 20 km/h) - probabil viraj
                        val bigChange = Math.abs(limit - lastAnnouncedLimit) >= 20
                        val confirmed = pendingLimitCount >= CONFIRMATIONS_NEEDED
                        
                        if ((confirmed || bigChange) && limit != lastAnnouncedLimit) {
                            Log.d(TAG, "LIMITĂ CONFIRMATĂ: $limit (era: $lastAnnouncedLimit, citiri: $pendingLimitCount)")
                            
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
                            // Limita e aceeași ca cea anunțată - OK
                            cachedSpeedLimit = limit
                            waitingForNewLimit = false
                        }
                        // Altfel, nu face nimic - așteaptă mai multe confirmări
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
        "Încetinește că nu ești pe autobahn!",
        "Ce grăbit ești! Încetinește!",
        "Radar în față! Glumesc, dar încetinește!",
        "Portofelul tău plânge! Încetinește!",
        "Banii tăi, amenda lor! Frânează!"
    )
    
    private var warnedOnce = false  // O singură avertizare per zonă
    
    private fun checkSpeedingAndWarn(speedKmh: Float, limit: Int) {
        if (limit <= 0) return
        
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

    private fun getSpeedLimit(lat: Double, lon: Double): Int? {
        return getSpeedLimitFromOSM(lat, lon)
    }
    
    private fun getSpeedLimitFromOSM(lat: Double, lon: Double): Int? {
        return try {
            // Query simplu - cauta ORICE drum cu maxspeed in raza de 30m
            val query = "[out:json][timeout:5];way(around:30,$lat,$lon)[maxspeed];out tags;"
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            connection.outputStream.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.e(TAG, "OSM HTTP error: $responseCode")
                connection.disconnect()
                return null
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            
            val elements = JSONObject(response).optJSONArray("elements")
            if (elements != null && elements.length() > 0) {
                // Prioritate: drumuri principale > rezidentiale
                var bestLimit = 0
                var bestPriority = -1
                
                for (i in 0 until elements.length()) {
                    val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                    val highway = tags.optString("highway", "")
                    val maxspeed = tags.optString("maxspeed", "")
                    val conditional = tags.optString("maxspeed:conditional", "")
                    
                    val priority = when(highway) {
                        "motorway" -> 6
                        "trunk" -> 5
                        "primary" -> 4
                        "secondary" -> 3
                        "tertiary" -> 2
                        "residential", "living_street", "unclassified" -> 1
                        else -> 0
                    }
                    
                    // Verifica limita conditionala (cu orar)
                    if (conditional.isNotEmpty()) {
                        val condLimit = parseConditionalSpeed(conditional)
                        if (condLimit != null && priority >= bestPriority) {
                            bestLimit = condLimit
                            bestPriority = priority
                            continue
                        }
                    }
                    
                    if (maxspeed == "none") {
                        if (priority > bestPriority) {
                            bestLimit = -1
                            bestPriority = priority
                        }
                    } else {
                        val match = Regex("(\\d+)").find(maxspeed)
                        if (match != null) {
                            val limit = match.groupValues[1].toInt()
                            if (priority >= bestPriority) {
                                bestLimit = limit
                                bestPriority = priority
                            }
                        }
                    }
                }
                
                if (bestLimit != 0) {
                    Log.d(TAG, "OSM: limita=$bestLimit, prioritate=$bestPriority")
                    return bestLimit
                }
            }
            Log.d(TAG, "OSM: nimic gasit la $lat,$lon")
            null
        } catch (e: Exception) {
            Log.e(TAG, "OSM error: ${e.message}")
            null
        }
    }
    
    // Parseaza limite cu orar: "30 @ (Mo-Fr 07:00-18:30)"
    private fun parseConditionalSpeed(conditional: String): Int? {
        try {
            val parts = conditional.split("@")
            if (parts.size < 2) return null
            
            val speedMatch = Regex("(\\d+)").find(parts[0].trim()) ?: return null
            val conditionalSpeed = speedMatch.groupValues[1].toInt()
            val condition = parts[1].trim()
            
            val cal = java.util.Calendar.getInstance()
            val today = cal.get(java.util.Calendar.DAY_OF_WEEK)
            // Calendar: SUNDAY=1, MONDAY=2, ..., SATURDAY=7
            
            val dayToNum = mapOf("Mo" to 2, "Tu" to 3, "We" to 4, "Th" to 5, "Fr" to 6, "Sa" to 7, "Su" to 1)
            
            // Verifica zilele: "Mo-Fr", "Mo-Sa", etc.
            val dayRange = Regex("(Mo|Tu|We|Th|Fr|Sa|Su)-(Mo|Tu|We|Th|Fr|Sa|Su)").find(condition)
            if (dayRange != null) {
                val startDay = dayToNum[dayRange.groupValues[1]] ?: return null
                val endDay = dayToNum[dayRange.groupValues[2]] ?: return null
                
                val todayInRange = if (startDay <= endDay) {
                    today in startDay..endDay
                } else {
                    today >= startDay || today <= endDay
                }
                
                if (!todayInRange) {
                    Log.d(TAG, "Conditional NU se aplica azi (zi $today, range $startDay-$endDay)")
                    return null
                }
            }
            
            // Verifica ora
            val timeMatch = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").find(condition)
            if (timeMatch != null) {
                val startTotal = timeMatch.groupValues[1].toInt() * 60 + timeMatch.groupValues[2].toInt()
                val endTotal = timeMatch.groupValues[3].toInt() * 60 + timeMatch.groupValues[4].toInt()
                val nowTotal = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                
                if (nowTotal in startTotal..endTotal) {
                    Log.d(TAG, "Conditional ACTIV: $conditionalSpeed km/h")
                    return conditionalSpeed
                }
            }
            return null
        } catch (e: Exception) { return null }
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
