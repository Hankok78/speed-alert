package com.speedalert.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var tvSpeedLimit: TextView
    private lateinit var tvCurrentSpeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnNightMode: Button
    private lateinit var mapWebView: WebView
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerLayout: LinearLayout
    private lateinit var footerLayout: LinearLayout
    private lateinit var tvKmhLabel: TextView
    private lateinit var tvLimitLabel: TextView
    private lateinit var prefs: SharedPreferences

    private val LOCATION_PERMISSION_CODE = 1001
    private val BACKGROUND_LOCATION_CODE = 1002
    
    private var isNightMode = false
    private var isManualOverride = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("speed_alert_prefs", Context.MODE_PRIVATE)
        isManualOverride = prefs.getBoolean("manual_override", false)
        isNightMode = if (isManualOverride) {
            prefs.getBoolean("night_mode", false)
        } else {
            isNightTimeAuto()
        }

        tvSpeedLimit = findViewById(R.id.tvSpeedLimit)
        tvCurrentSpeed = findViewById(R.id.tvCurrentSpeed)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnNightMode = findViewById(R.id.btnNightMode)
        mapWebView = findViewById(R.id.mapWebView)
        rootLayout = findViewById(R.id.rootLayout)
        headerLayout = findViewById(R.id.headerLayout)
        footerLayout = findViewById(R.id.footerLayout)
        tvKmhLabel = findViewById(R.id.tvKmhLabel)
        tvLimitLabel = findViewById(R.id.tvLimitLabel)

        setupMap()
        applyTheme()

        btnStart.setOnClickListener { startService() }
        btnStop.setOnClickListener { stopService() }
        btnNightMode.setOnClickListener { toggleNightMode() }

        SpeedAlertService.speedLimitLiveData.observe(this) { limit ->
            tvSpeedLimit.text = when {
                limit == SpeedAlertService.NO_LIMIT -> "∞"
                limit > 0 -> "$limit"
                else -> "--"
            }
        }

        SpeedAlertService.currentSpeedLiveData.observe(this) { speed ->
            tvCurrentSpeed.text = "${speed.toInt()}"
        }

        SpeedAlertService.statusLiveData.observe(this) { status ->
            tvStatus.text = status
        }
        
        SpeedAlertService.locationLiveData.observe(this) { location ->
            updateMapLocation(location.first, location.second)
            // Auto-comutare la fiecare update de locatie (daca nu e manual)
            if (!isManualOverride) {
                val shouldBeNight = isNightTimeAuto()
                if (shouldBeNight != isNightMode) {
                    isNightMode = shouldBeNight
                    applyTheme()
                }
            }
        }

        checkPermissions()
        requestBatteryOptimizationExemption()
    }
    
    private fun isNightTimeAuto(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 20 || hour < 7
    }
    
    private fun toggleNightMode() {
        isNightMode = !isNightMode
        isManualOverride = true
        prefs.edit()
            .putBoolean("night_mode", isNightMode)
            .putBoolean("manual_override", true)
            .apply()
        applyTheme()
        
        // Dupa 30 min, revine la auto
        mapWebView.postDelayed({
            isManualOverride = false
            prefs.edit().putBoolean("manual_override", false).apply()
            val shouldBeNight = isNightTimeAuto()
            if (shouldBeNight != isNightMode) {
                isNightMode = shouldBeNight
                applyTheme()
            }
        }, 30 * 60 * 1000L)
    }
    
    private fun applyTheme() {
        if (isNightMode) {
            // MOD NOAPTE - foarte intunecat, rosu pentru ochi
            rootLayout.setBackgroundColor(Color.BLACK)
            headerLayout.setBackgroundColor(Color.BLACK)
            footerLayout.setBackgroundColor(Color.BLACK)
            tvCurrentSpeed.setTextColor(Color.parseColor("#CC0000"))
            tvKmhLabel.setTextColor(Color.parseColor("#660000"))
            tvLimitLabel.setTextColor(Color.parseColor("#660000"))
            tvStatus.setTextColor(Color.parseColor("#660000"))
            tvSpeedLimit.setTextColor(Color.parseColor("#CC0000"))
            btnNightMode.text = "ZI"
            btnNightMode.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#330000"))
            // Harta dark
            mapWebView.evaluateJavascript("if(typeof switchToDark==='function')switchToDark();", null)
        } else {
            // MOD ZI - normal
            rootLayout.setBackgroundColor(Color.parseColor("#1a1a2e"))
            headerLayout.setBackgroundColor(Color.parseColor("#0f0f1a"))
            footerLayout.setBackgroundColor(Color.parseColor("#0f0f1a"))
            tvCurrentSpeed.setTextColor(Color.parseColor("#00d9ff"))
            tvKmhLabel.setTextColor(Color.parseColor("#888888"))
            tvLimitLabel.setTextColor(Color.parseColor("#aaaaaa"))
            tvStatus.setTextColor(Color.parseColor("#888888"))
            tvSpeedLimit.setTextColor(Color.WHITE)
            btnNightMode.text = "NOAPTE"
            btnNightMode.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#555555"))
            // Harta light
            mapWebView.evaluateJavascript("if(typeof switchToLight==='function')switchToLight();", null)
        }
    }
    
    private fun setupMap() {
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.domStorageEnabled = true
        mapWebView.settings.loadWithOverviewMode = true
        mapWebView.settings.useWideViewPort = true
        mapWebView.webViewClient = WebViewClient()
        
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
html,body,#map{margin:0;padding:0;width:100%;height:100%;}
</style>
</head>
<body>
<div id="map"></div>
<script>
var map=L.map('map').setView([48.1351,11.5820],15);
var lightLayer=L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19});
var darkLayer=L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{maxZoom:19});
var currentLayer=lightLayer;
currentLayer.addTo(map);
var marker=null;
function updateLocation(lat,lon){
if(marker)map.removeLayer(marker);
marker=L.marker([lat,lon]).addTo(map);
map.setView([lat,lon],16);
}
function switchToDark(){
map.removeLayer(currentLayer);
currentLayer=darkLayer;
currentLayer.addTo(map);
}
function switchToLight(){
map.removeLayer(currentLayer);
currentLayer=lightLayer;
currentLayer.addTo(map);
}
</script>
</body>
</html>
        """.trimIndent()
        
        mapWebView.loadData(android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_PADDING), "text/html", "base64")
        
        // Aplica tema dupa ce harta se incarca
        mapWebView.postDelayed({
            if (isNightMode) {
                mapWebView.evaluateJavascript("if(typeof switchToDark==='function')switchToDark();", null)
            }
        }, 2000)
    }
    
    private fun updateMapLocation(lat: Double, lon: Double) {
        mapWebView.evaluateJavascript("updateLocation($lat, $lon);", null)
    }
    
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) { }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), LOCATION_PERMISSION_CODE)
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permite locația în fundal pentru funcționare cu ecranul închis!", Toast.LENGTH_LONG).show()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_LOCATION_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    checkBackgroundLocationPermission()
                }
            }
        }
    }

    private fun startService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Permite afișarea peste alte aplicații pentru bubble!", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 1234)
            return
        }
        
        val intent = Intent(this, SpeedAlertService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopService() {
        val intent = Intent(this, SpeedAlertService::class.java)
        stopService(intent)
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }
}
