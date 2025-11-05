//package com.ambientai.core.context
//
//import android.Manifest
//import android.content.Context
//import android.content.pm.PackageManager
//import android.location.Location
//import androidx.core.app.ActivityCompat
//import com.google.android.gms.location.LocationServices
//import kotlinx.coroutines.suspendCancellableCoroutine
//import kotlin.coroutines.resume
//
///**
// * Provides current location context as named zones.
// * Returns "home", "office", or "unknown" based on GPS coordinates.
// */
//class LocationContextProvider(
//    private val context: Context
//) : ContextProvider<String>(
//    key = "location",
//    cacheDurationMs = 5 * 60 * 1000 // 5 minutes
//) {
//    private val fusedLocationClient by lazy {
//        LocationServices.getFusedLocationProviderClient(context)
//    }
//
//    // TODO: Load from SharedPreferences or JSON config file
//    private val zones = mapOf(
//        "home" to Zone(42.331427, -83.045754, radius = 100f), // Detroit example
//        "office" to Zone(42.335260, -83.049301, radius = 150f)
//    )
//
//    override suspend fun fetch(): String = suspendCancellableCoroutine { cont ->
//        if (ActivityCompat.checkSelfPermission(
//                context,
//                Manifest.permission.ACCESS_FINE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            cont.resume("unknown")
//            return@suspendCancellableCoroutine
//        }
//
//        fusedLocationClient.lastLocation
//            .addOnSuccessListener { location ->
//                val zone = location?.let { findNearestZone(it) } ?: "unknown"
//                cont.resume(zone)
//            }
//            .addOnFailureListener {
//                cont.resume("unknown")
//            }
//    }
//
//    private fun findNearestZone(location: Location): String {
//        return zones.entries.firstOrNull { (_, zone) ->
//            val results = FloatArray(1)
//            Location.distanceBetween(
//                location.latitude, location.longitude,
//                zone.lat, zone.lng,
//                results
//            )
//            results[0] < zone.radius
//        }?.key ?: "unknown"
//    }
//
//    private data class Zone(
//        val lat: Double,
//        val lng: Double,
//        val radius: Float // meters
//    )
//}