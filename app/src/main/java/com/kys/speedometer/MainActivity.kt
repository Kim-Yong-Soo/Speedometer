package com.kys.speedometer

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
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
    private lateinit var polylineList: MutableList<PolylineOptions>
    private var mapClicked = false

    private lateinit var maxSpeedView: TextView
    private lateinit var speedView: TextView
    private lateinit var accuracyView: TextView
    private lateinit var avgSpeedView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        latLngList = mutableListOf()
        speedList = mutableListOf()
        polylineList = mutableListOf()

        if (isNetworkAvailable(this)) {
            val mapFragmentManager = supportFragmentManager
            val mapFragment =
                mapFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
            mapFragment.getMapAsync(this)

            maxSpeedView = findViewById<TextView>(R.id.maxSpeedTv)
            speedView = findViewById<TextView>(R.id.currentSpeedTv)
            accuracyView = findViewById<TextView>(R.id.accuracyTv)
            avgSpeedView = findViewById<TextView>(R.id.avgSpeedTv)

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
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallBack)
    }


    override fun onResume() {
        super.onResume()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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

        val locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 200
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallBack, Looper.getMainLooper()
        )
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw      = connectivityManager.activeNetwork ?: return false
            val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false

            return when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true
                else -> false
            }
        } else {
            return connectivityManager.activeNetworkInfo?.isConnected ?: false
        }
    }

    override fun onMapReady(p0: GoogleMap) {
        mMap = p0

        mMap.setOnCameraMoveListener { // 사용자가 지도를 움직였을 때, 5초 후 다시 현재 위치로 돌아오도록 함
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

        var speed = (location.speed * 3.6F).toDouble()
        val accuracy = location.accuracy
        val speedString: String

        accuracyView.text = String.format("%d m", location.accuracy.toInt())

        if (latLngList.isEmpty()) { // 초기 지도 카메라 위치 지정
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

        // 위치 반경 10m 미만일 시 정확하다고 가정함
        // 이전 위치를 가지고 비교했을 때, 2 m/s 이상으로 이동했을 경우
        if (accuracy < 15 && location.distanceTo(lastLocation) > 2) {
            speedView.setTextColor(Color.parseColor("#585858"))
            if (speed < 10) { // 속도 10 이하일 경우
                if (speed < 1) // 속도 1 이하일 경우 0.0으로 설정 (초기에 속도가 튀는 것을 방지) -> 아직 해결해야 하는 부분
                    speed = 0.0
                speedString = String.format("%.1f km/h", speed)
            } else
                speedString = String.format("%d km/h", speed.toInt())

            // 최고 속도
            if (speed > maxSpeed) {
                maxSpeed = speed
                if (maxSpeed < 10)
                    maxSpeedView.text = String.format("%.1f km/h", maxSpeed)
                else
                    maxSpeedView.text = String.format("%d km/h", maxSpeed.toInt())
            }

            // 리스트 정리 및 지도 설정
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
        } else if (accuracy >= 15) { // 위치 정확도(오차)가 10m 이상일 경우 현재 속도를 회색으로 변경
            speedView.setTextColor(Color.parseColor("#808080"))
        }
    }

    private fun drawPolyline() {
        val polylineOptions = PolylineOptions()
        polylineOptions.color(Color.RED)
        polylineOptions.width(16F)
        polylineOptions.addAll(latLngList)
        polylineList.add(polylineOptions)
        mMap.addPolyline(polylineOptions)
    }

    private fun checkGPS(): Boolean {
        var gps = false
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            gps = true
        return gps
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.resetAction -> {
                resetAll()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun resetAll() {
        latLngList = mutableListOf()
        speedList = mutableListOf()
        maxSpeed = 0.0

        speedView.text = "0.0 km/h"
        maxSpeedView.text = "0.0 km/h"
        avgSpeedView.text = "0.0 km/h"
        mMap.clear()
    }
}