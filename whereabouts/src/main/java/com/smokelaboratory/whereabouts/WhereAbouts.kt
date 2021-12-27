package com.smokelaboratory.whereabouts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WhereAbouts {

    private var requestIntentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var requestPermissionLauncher: ActivityResultLauncher<Array<String>>? = null

    private var contextActivity: ComponentActivity? = null
    private var activity: ComponentActivity? = null
    private var fragment: Fragment? = null

    private var locationCallback: LocationCallback? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null

    private var permissionContinuation: Continuation<Boolean>? = null
    private var intentSenderContinuation: Continuation<LocationResult>? = null

    constructor(activity: ComponentActivity) {
        this.activity = activity
        this.contextActivity = activity

        registerPermissionCallBack()
        registerIntentSenderCallBack()
    }

    constructor(fragment: Fragment) {
        this.fragment = fragment
        this.contextActivity = fragment.activity

        registerPermissionCallBack()
        registerIntentSenderCallBack()
    }

    private fun registerPermissionCallBack() {
        val permissionResponse = ActivityResultCallback<Map<String, Boolean>> {

            permissionContinuation?.resume(it.values.all { it })
        }

        requestPermissionLauncher =
            if (fragment == null)
                activity!!.registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                    permissionResponse
                )
            else
                fragment!!.registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                    permissionResponse
                )
    }

    private fun registerIntentSenderCallBack() {

        val intentSenderResponse = ActivityResultCallback<ActivityResult> { result ->
            if (result.resultCode == Activity.RESULT_OK)
                intentSenderContinuation?.resume(
                    LocationResult(
                        null,
                        LocationResultTypeEnum.Success
                    )
                )
            else
                intentSenderContinuation?.resume(
                    LocationResult(
                        null,
                        if ((contextActivity!!.getSystemService(
                                Context.LOCATION_SERVICE
                            ) as LocationManager).isProviderEnabled(
                                LocationManager.GPS_PROVIDER
                            )
                        ) LocationResultTypeEnum.HighPrecisionNATryAgainPreferablyWithInternet
                        else
                            LocationResultTypeEnum.LocationOptimizationPermissionNotGranted
                    )
                )
        }

        requestIntentSenderLauncher =
            if (fragment == null)
                activity!!.registerForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                    intentSenderResponse
                )
            else
                fragment!!.registerForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                    intentSenderResponse
                )
    }

    @Throws
    suspend fun fetchLocation(): Location {
        val locationResult = if (askPermission())
            fetchLastLocation()
        else
            LocationResult(null, LocationResultTypeEnum.LocationPermissionNotGranted)

        when (locationResult.resultType) {
            LocationResultTypeEnum.Success -> return locationResult.location!!
            LocationResultTypeEnum.DeviceInFlightMode -> throw FlightModeException()
            LocationResultTypeEnum.LocationPermissionNotGranted -> throw LocationPermissionException()
            LocationResultTypeEnum.LocationOptimizationPermissionNotGranted -> throw AccuracyPermissionException()
            LocationResultTypeEnum.HighPrecisionNATryAgainPreferablyWithInternet -> throw PrecisionException()
        }
    }

    private suspend fun askPermission(): Boolean {
        return suspendCoroutine {
            permissionContinuation = it

            requestPermissionLauncher?.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLastLocation(): LocationResult {
        val lastLocation = suspendCoroutine<Location?> {
            fusedLocationClient = contextActivity?.let {
                LocationServices.getFusedLocationProviderClient(it)
            }
            val task = fusedLocationClient?.lastLocation

            task?.addOnSuccessListener { location: Location? ->
                it.resume(location)
            }
            task?.addOnFailureListener { _ ->
                it.resume(null)
            }
        }
        return if (lastLocation != null)
            LocationResult(lastLocation, LocationResultTypeEnum.Success)
        else
            onLocationFetchFailed()
    }

    @SuppressLint("MissingPermission")
    private suspend fun onLocationFetchFailed(): LocationResult {
        return if (isInFlightMode())
            LocationResult(null, LocationResultTypeEnum.DeviceInFlightMode)
        else {
            val locationResult = suspendCoroutine<LocationResult> {
                intentSenderContinuation = it

                locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                        it.resume(
                            LocationResult(
                                locationResult.lastLocation,
                                LocationResultTypeEnum.Success
                            )
                        )
                        fusedLocationClient?.removeLocationUpdates(locationCallback!!)
                    }

                    @SuppressLint("MissingPermission")
                    override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                        super.onLocationAvailability(locationAvailability)
                        if (!locationAvailability.isLocationAvailable) {
                            it.resume(
                                LocationResult(
                                    null,
                                    LocationResultTypeEnum.HighPrecisionNATryAgainPreferablyWithInternet
                                )
                            )
                            fusedLocationClient?.removeLocationUpdates(locationCallback!!)
                        }
                    }
                }

                val locationRequest = LocationRequest.create().apply {
                    interval = 10000
                    fastestInterval = 2000
                    priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                    numUpdates = 1
                }

                val task: Task<LocationSettingsResponse> =
                    (LocationServices.getSettingsClient(contextActivity!!)).checkLocationSettings(
                        (LocationSettingsRequest.Builder()
                            .addLocationRequest(locationRequest)).build()
                    )

                task.addOnSuccessListener {
                    Looper.myLooper()?.let { looper ->
                        fusedLocationClient?.requestLocationUpdates(
                            locationRequest,
                            locationCallback!!,
                            looper
                        )
                    }
                }

                task.addOnFailureListener { exception ->
                    if (exception is ResolvableApiException) {
                        try {
                            requestIntentSenderLauncher?.launch(
                                IntentSenderRequest.Builder(
                                    exception.resolution
                                ).build()
                            )
                        } catch (sendEx: IntentSender.SendIntentException) {
                        }
                    }
                }
            }

            when (locationResult.resultType) {
                LocationResultTypeEnum.Success ->
                    if (locationResult.location == null)
                        onLocationFetchFailed()
                    else
                        locationResult
                else ->
                    locationResult
            }
        }
    }

    private fun isInFlightMode(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1)
            Settings.System.getInt(
                contextActivity!!.contentResolver,
                Settings.System.AIRPLANE_MODE_ON,
                0
            ) != 0
        else
            Settings.Global.getInt(
                contextActivity!!.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
    }

    private data class LocationResult(
        val location: Location?,
        val resultType: LocationResultTypeEnum
    )

    private enum class LocationResultTypeEnum {
        Success,
        DeviceInFlightMode,
        LocationPermissionNotGranted,
        LocationOptimizationPermissionNotGranted,
        HighPrecisionNATryAgainPreferablyWithInternet
    }

    class LocationPermissionException : Exception()
    class FlightModeException : Exception()
    class AccuracyPermissionException : Exception()
    class PrecisionException : Exception()
}