package com.kys.speedometer

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallBack: LocationCallback
    private lateinit var mMap: GoogleMap
    private var mFirebaseAnalytics: FirebaseAnalytics? = null

    private var maxSpeed = 0.0
    private lateinit var latLngList: MutableList<LatLng>
    private lateinit var speedList: MutableList<Double>
    private var mapClicked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        latLngList = mutableListOf()
        speedList = mutableListOf()

        val mapFragmentManager = supportFragmentManager
        val mapFragment =
            mapFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallBack = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                processSpeed(p0.locations[0])
            }
        }

        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)
        MobileAds.initialize(this) {}

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
            builder.setTitle("?????? ????????? ????????????")
            builder.setMessage("??? ????????? ?????? ?????? ????????? ???????????? ???????????????.")
            builder.setCancelable(true)
            builder.setPositiveButton("??????") { dialogInterface: DialogInterface, i: Int ->
                val callGpsSetting =
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(callGpsSetting)
            }
            builder.setNegativeButton("??????") { dialogInterface: DialogInterface, i: Int ->
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
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallBack, Looper.getMainLooper()
        )
    }

    override fun onMapReady(p0: GoogleMap) {
        mMap = p0

        mMap.setOnCameraMoveListener { // ???????????? ????????? ???????????? ???, 5??? ??? ?????? ?????? ????????? ??????????????? ???
            mapClicked = true

            var time = 1
            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (time++ > 5) {
                        mapClicked = false
                        timer.cancel()
                    }
                }
            }, 1000, 1000)
        }
    }


    fun processSpeed(location: Location) {
        val maxSpeedView = findViewById<TextView>(R.id.maxSpeedTv)
        val speedView = findViewById<TextView>(R.id.currentSpeedTv)
        val accuracyView = findViewById<TextView>(R.id.accuracyTv)
        val avgSpeedView = findViewById<TextView>(R.id.avgSpeedTv)

        var speed = (location.speed * 3.6F).toDouble()
        val accuracy = location.accuracy
        val speedString: String

        accuracyView.text = String.format("%d m", location.accuracy.toInt())

        if (latLngList.isEmpty()) { // ?????? ?????? ????????? ?????? ??????
            latLngList.add(LatLng(location.latitude, location.longitude))
            mMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(
                        location.latitude,
                        location.longitude
                    ), 14F
                )
            )
            return
        }

        val lastLocation = Location("lastLocation")
        lastLocation.latitude = latLngList.last().latitude
        lastLocation.longitude = latLngList.last().longitude

        // ?????? ?????? 10m ????????? ??? ??????????????? ?????????
        // ?????? ????????? ????????? ???????????? ???, 2 m/s ???????????? ???????????? ??????
        if (accuracy < 10 && location.distanceTo(lastLocation) > 2) {
            speedView.setTextColor(Color.parseColor("#000000"))
            if (speed < 10) { // ?????? 10 ????????? ??????
                if (speed < 1) // ?????? 1 ????????? ?????? 0.0?????? ?????? (????????? ????????? ?????? ?????? ??????) -> ?????? ???????????? ?????? ??????
                    speed = 0.0
                speedString = String.format("%.1f km/h", speed)
            } else
                speedString = String.format("%d km/h", speed.toInt())

            // ?????? ??????
            if (speed > maxSpeed) {
                maxSpeed = speed
                if (maxSpeed < 10)
                    maxSpeedView.text = String.format("%.1f km/h", maxSpeed)
                else
                    maxSpeedView.text = String.format("%d km/h", maxSpeed.toInt())
            }

            // ????????? ?????? ??? ?????? ??????
            latLngList.add(LatLng(location.latitude, location.longitude))
            speedList.add(speed)
            avgSpeedView.text = String.format("%.1f km/h", speedList.sum() / speedList.size)
            speedView.text = speedString
            drawPolyline()

            if (!mapClicked) {
                mMap.animateCamera(
                    CameraUpdateFactory.newLatLng(
                        LatLng(
                            location.latitude,
                            location.longitude
                        )
                    )
                )
            }
        } else if (accuracy >= 10) { // ?????? ?????????(??????)??? 10m ????????? ?????? ?????? ????????? ???????????? ??????
            speedView.setTextColor(Color.parseColor("#808080"))
        } else if (accuracy < 10 && location.distanceTo(lastLocation) < 1) { // ?????? ?????????(??????)??? 10m ???????????? 1m/s ???????????? ????????? ??????
            // ????????? ?????? ?????? ?????? 0.0 km/h??? ??????
            speedView.text = R.string.defaultSpeed.toString()
            speedView.setTextColor(Color.parseColor("#808080"))
        }
    }

    private fun drawPolyline() {
        val polylineOptions = PolylineOptions()
        polylineOptions.color(Color.RED)
        polylineOptions.width(16F)
        polylineOptions.addAll(latLngList)
        mMap.addPolyline(polylineOptions)
    }

    private fun checkGPS(): Boolean {
        var gps = false
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            gps = true
        return gps
    }
}