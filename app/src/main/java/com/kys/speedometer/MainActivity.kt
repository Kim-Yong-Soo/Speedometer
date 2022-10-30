package com.kys.speedometer

import android.Manifest
import android.content.Context
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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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

    private lateinit var maxSpeedView: TextView
    private lateinit var speedView: TextView
    private lateinit var accuracyView: TextView
    private lateinit var avgSpeedView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        latLngList = mutableListOf()
        speedList = mutableListOf()

        maxSpeedView = findViewById(R.id.maxSpeedTv)
        speedView = findViewById(R.id.currentSpeedTv)
        accuracyView = findViewById(R.id.accuracyTv)
        avgSpeedView = findViewById(R.id.avgSpeedTv)
    }

    private fun firstSet() {
        checkGPS()
        Log.d("networkAvailable", isNetworkAvailable(this).toString())
        if (isNetworkAvailable(this)) {
            isDone = true
            val mapFragmentManager = supportFragmentManager
            val mapFragment =
                mapFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
            mapFragment.getMapAsync(this)

            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            locationCallBack = object : LocationCallback() {
                override fun onLocationResult(locResult: LocationResult) {
                    setSpeed(locResult.locations[0])
                }
            }

            mFirebaseAnalytics = FirebaseAnalytics.getInstance(this)
            MobileAds.initialize(this) {}

            val mAdView = findViewById<AdView>(R.id.adView)
            val adRequest = AdRequest.Builder().build()
            mAdView.loadAd(adRequest)

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val locationRequest = LocationRequest.create().apply {
                    interval = 1000
                    fastestInterval = 500
                    priority = Priority.PRIORITY_HIGH_ACCURACY
                }

                fusedLocationClient.requestLocationUpdates(
                    locationRequest, locationCallBack, Looper.getMainLooper()
                )
            }
        }
    }

    private var isDone = false

    override fun onPause() {
        super.onPause()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED && isDone
        ) {
            fusedLocationClient.removeLocationUpdates(locationCallBack)
            isDone = false
        }
    }

    override fun onResume() {
        super.onResume()
        if ((shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        ) {
            // 이전에 퍼미션을 거부했을 경우
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle("위치 권한 비활성화")
                .setMessage("앱 사용을 위해 위치 권한 활성화가 필요합니다\n정확한 정보를 위해 정확한 위치로 활성화가 필요합니다.")
                .setPositiveButton("설정") { _, _ ->
                    permissionResultLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    permissionResultLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                .setNegativeButton("취소") { dialog, _ ->
                    dialog.dismiss()
                }
            builder.create().show()
        } else {
            // 최초로 퍼미션 요청을 받았을 경우
            permissionResultLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionResultLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (!isNetworkAvailable(this)) {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle("인터넷 비활성화").setMessage("앱 사용을 위해 인터넷 연결이 필요합니다")
                .setPositiveButton("설정") { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        startActivity(Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
                    else
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                .setNegativeButton("취소") { dialog, _ ->
                    dialog.dismiss()
                }
            builder.create().show()
        }
    }

    private val permissionResultLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted && !isDone) {
                firstSet()
            }
        }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val nw = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false

        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    override fun onMapReady(p0: GoogleMap) {
        mMap = p0
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
        }

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

    fun setSpeed(location: Location) {
        var speed = (location.speed * 3.6F).toDouble()
        val accuracy = location.accuracy // 추후에 파일로 저장하려면 list 만들고 추가해 사용
        var status = true // true: 정상(검정색), false: 비정상(회색)

        accuracyView.text = String.format("%d m", accuracy.toInt())

        if (speed < 1)
            speed = 0.0

        if (latLngList.isEmpty()) {
            latLngList.add(LatLng(location.latitude, location.longitude))
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngList.last(), 14F))
            return
        } else if (accuracy <= 15 && !(speedList.isNotEmpty() && speedList.last() == 0.0 && speed == 0.0)) {
            // 위치 반경 15m 이하일 시 정확하다고 가정
            // 만약 속도가 이전에도 0, 현재도 0이라면 정지 상태
            speedView.text = String.format("%d km/h", speed.toInt())

            if (speed > maxSpeed) {
                maxSpeed = speed
                maxSpeedView.text = String.format("%d km/h", maxSpeed.toInt())
            }

            latLngList.add(LatLng(location.latitude, location.longitude))
            speedList.add(speed)
            avgSpeedView.text = String.format("%.1f km/h", speedList.sum() / speedList.size)
            drawPolyline()

            if (!mapClicked)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(latLngList.last()))

        } else if (accuracy >= 15) {
            status = false
        }

        speedView.setTextColor(if (status) getColor(R.color.colorSpeedText) else Color.parseColor("#808080"))
    }

    private fun drawPolyline() {
        val polylineOptions =
            PolylineOptions().addAll(latLngList).width(16F)
                .color(Color.RED).geodesic(true)
        mMap.addPolyline(polylineOptions)
    }

    private fun checkGPS() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle("위치 서비스 비활성화").setMessage("앱 사용을 위해 위치 권한 활성화가 필요합니다")
                .setPositiveButton("설정") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("취소") { dialog, _ ->
                    dialog.dismiss()
                }
            builder.create().show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.resetAction -> {
                AlertDialog.Builder(this)
                    .setTitle("초기화 하시겠습니까?")
                    .setPositiveButton(
                        "예"
                    ) { _, _ -> resetAll() }
                    .setNegativeButton(
                        "아니요"
                    ) { _, _ -> }
                    .setMessage("이동 경로와 모든 속도가 초기화됩니다.")
                    .create()
                    .show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun resetAll() {
        if (latLngList.isNotEmpty())
            mMap.clear()

        latLngList = mutableListOf()
        speedList = mutableListOf()
        maxSpeed = 0.0

        speedView.text = getString(R.string.defaultSpeed)
        speedView.setTextColor(getColor(R.color.colorSpeedText))
        maxSpeedView.text = getString(R.string.defaultSpeed)
        avgSpeedView.text = getString(R.string.defaultAvgSpeed)
    }
}