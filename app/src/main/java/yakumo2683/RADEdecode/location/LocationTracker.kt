package yakumo2683.RADEdecode.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.floor

/**
 * GPS location tracker with Maidenhead grid square calculation.
 * Uses FusedLocationProviderClient for battery-efficient updates.
 */
class LocationTracker(private val context: Context) {

    data class LocationState(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,
        val gridSquare: String = "",
        val hasPermission: Boolean = false,
        val isTracking: Boolean = false
    )

    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state.asStateFlow()

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    fun startTracking() {
        if (!hasLocationPermission()) {
            _state.value = _state.value.copy(hasPermission = false)
            return
        }

        _state.value = _state.value.copy(hasPermission = true)

        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(context)

            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30000)
                .setMinUpdateIntervalMillis(10000)
                .setMinUpdateDistanceMeters(50f)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { updateLocation(it) }
                }
            }

            fusedClient?.requestLocationUpdates(
                request, locationCallback!!, Looper.getMainLooper()
            )

            // Get last known location immediately
            fusedClient?.lastLocation?.addOnSuccessListener { loc ->
                loc?.let { updateLocation(it) }
            }

            _state.value = _state.value.copy(isTracking = true)
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(hasPermission = false)
        }
    }

    fun stopTracking() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null
        _state.value = _state.value.copy(isTracking = false)
    }

    private fun updateLocation(location: Location) {
        val grid = toMaidenhead(location.latitude, location.longitude)
        _state.value = _state.value.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            gridSquare = grid
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /**
         * Convert latitude/longitude to 6-character Maidenhead grid square.
         * e.g. (35.6762, 139.6503) → "PM95ur"
         */
        fun toMaidenhead(lat: Double, lon: Double): String {
            val adjLon = lon + 180.0
            val adjLat = lat + 90.0

            val field1 = ('A' + (adjLon / 20.0).toInt())
            val field2 = ('A' + (adjLat / 10.0).toInt())

            val square1 = ('0' + ((adjLon % 20.0) / 2.0).toInt())
            val square2 = ('0' + ((adjLat % 10.0) / 1.0).toInt())

            val sub1 = ('a' + ((adjLon % 2.0) * 12.0).toInt())
            val sub2 = ('a' + ((adjLat % 1.0) * 24.0).toInt())

            return "$field1$field2$square1$square2$sub1$sub2"
        }
    }
}
