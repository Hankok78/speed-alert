package com.speedalert.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvSpeedLimit: TextView
    private lateinit var tvCurrentSpeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var mapWebView: WebView

    private val LOCATION_PERMISSION_CODE = 1001
    private val BACKGROUND_LOCATION_CODE = 1002
    private val TOMTOM_KEY = "4F7NveARkj9ilHALcjNgT0Sa4VUG01bA"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSpeedLimit = findViewById(R.id.tvSpeedLimit)
        tvCurrentSpeed = findViewById(R.id.tvCurrentSpeed)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        mapWebView = findViewById(R.id.mapWebView)

        setupMap()

        btnStart.setOnClickListener { startService() }
        btnStop.setOnClickListener { stopService() }

        SpeedAlertService.speedLimitLiveData.observe(this) { limit ->
            tvSpeedLimit.text = if (limit > 0) "$limit" else "--"
        }

        SpeedAlertService.currentSpeedLiveData.observe(this) { speed ->
            tvCurrentSpeed.text = "${speed.toInt()}"
        }

        SpeedAlertService.statusLiveData.observe(this) { status ->
            tvStatus.text = status
        }
        
        SpeedAlertService.locationLiveData.observe(this) { location ->
            updateMapLocation(location.first, location.second)
        }

        checkPermissions()
        requestBatteryOptimizationExemption()
    }
    
    private fun setupMap() {
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.domStorageEnabled = true
        mapWebView.settings.loadWithOverviewMode = true
        mapWebView.settings.useWideViewPort = true
        mapWebView.settings.allowContentAccess = true
        mapWebView.settings.allowFileAccess = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mapWebView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        mapWebView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        mapWebView.webViewClient = WebViewClient()
        
        // Încarcă harta TomTom direct
        val mapUrl = "https://api.tomtom.com/map/1/staticimage?key=$TOMTOM_KEY&zoom=15&center=11.5820,48.1351&width=512&height=512&layer=basic&style=main&format=png"
        
        // Folosim HTML simplu cu harta
        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<link rel="stylesheet" type="text/css" href="https://api.tomtom.com/maps-sdk-for-web/cdn/6.x/6.25.0/maps/maps.css"/>
<script src="https://api.tomtom.com/maps-sdk-for-web/cdn/6.x/6.25.0/maps/maps-web.min.js"></script>
<style>
html,body{margin:0;padding:0;width:100%;height:100%;}
#map{width:100%;height:100%;}
</style>
</head>
<body>
<div id="map"></div>
<script>
tt.setProductInfo('SpeedAlert','1.0');
var map=tt.map({key:'$TOMTOM_KEY',container:'map',center:[11.5820,48.1351],zoom:15});
var marker=null;
function updateLocation(lat,lon){
if(marker)marker.remove();
marker=new tt.Marker().setLngLat([lon,lat]).addTo(map);
map.setCenter([lon,lat]);
}
</script>
</body>
</html>
        """.trimIndent()
        
        mapWebView.loadData(android.util.Base64.encodeToString(html.toByteArray(), android.util.Base64.NO_PADDING), "text/html", "base64")
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
