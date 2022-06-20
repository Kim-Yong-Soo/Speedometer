package com.kys.speedometer

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.location.*
import com.google.firebase.analytics.FirebaseAnalytics

class MainActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallBack: LocationCallback
    private var maxSpeed = 0.0
    private var mFirebaseAnalytics: FirebaseAnalytics? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallBack = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                displaySpeed(p0.locations[0])
            }
        }

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)
        MobileAds.initialize(this){}

        val mAdView = findViewById<AdView>(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        mAdView.loadAd(adRequest)
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallBack)
    }


    override fun onResume() {
        super.onResume()

        if (!checkGPS()) {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("위치 서비스 비활성화")
            builder.setMessage("앱 사용을 위해 위치 서비스 활성화가 필요합니다.")
            builder.setCancelable(true)
            builder.setPositiveButton("설정") { dialogInterface: DialogInterface, i: Int ->
                val callGpsSetting =
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(callGpsSetting)
            }
            builder.setNegativeButton("취소") { dialogInterface: DialogInterface, i: Int ->
                finish()
            }
            builder.create().show()
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 200
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallBack, Looper.getMainLooper()
        )
    }

    fun displaySpeed(location: Location) {
        var speed = (location.speed * 3.6F).toDouble()
        val speedString: String
        if (speed < 10) {
            if (speed < 1) {
                speed = 0.0
            }
            speedString = String.format("%.1f km/h", speed)
        } else {
            speedString = String.format("%d km/h", speed.toInt())
        }

        if (speed > maxSpeed) {
            maxSpeed = speed
            val maxSpeedView =
                findViewById<TextView>(R.id.msText)
            if (maxSpeed < 10) {
                maxSpeedView.text = String.format("%.1f km/h", maxSpeed)
            } else {
                maxSpeedView.text = String.format("%d km/h", maxSpeed.toInt())
            }
        }

        val speedView = findViewById<TextView>(R.id.csText)
        speedView.text = speedString
    }

    private fun checkGPS(): Boolean {
        var gps = false
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            gps = true
        return gps
    }
}