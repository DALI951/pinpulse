package com.pinpulse.radar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pinpulse.radar.ui.screens.AuthScreen
import com.pinpulse.radar.ui.screens.MainScreen
import com.pinpulse.radar.ui.theme.PinPulseTheme
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prune obsolete APKs from previous updates if the app is now running that version or newer
        com.pinpulse.radar.util.UpdateManager.cleanupOldInstalledApks(this)

        fetchImmediateLocation()

        setContent {
            PinPulseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = RadarDarkBg
                ) {
                    val currentUser by viewModel.currentUser.collectAsState()

                    if (currentUser == null) {
                        AuthScreen(
                            onSignIn = { email, name, avatar, pin ->
                                viewModel.signInWithGoogleIdentity(email, name, avatar)
                                viewModel.setSecurityPin(pin)
                                fetchImmediateLocation()
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }

        if (intent?.action == "SHOW_SIREN_PIN") {
            viewModel.showSirenPinDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "SHOW_SIREN_PIN") {
            viewModel.showSirenPinDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if an update was already downloaded so the UI is ready without re-downloading
        viewModel.refreshUpdateStatus()
        fetchImmediateLocation()
    }

    fun fetchImmediateLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            val fused = LocationServices.getFusedLocationProviderClient(this)
            try {
                fused.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        viewModel.updateMyLocation(loc.latitude, loc.longitude, loc.speed)
                    }
                }
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                    if (loc != null) {
                        viewModel.updateMyLocation(loc.latitude, loc.longitude, loc.speed)
                    }
                }
            } catch (e: Exception) {}
        }
        // Always start LocationTrackingService (uses GPS/Cell when enabled, and silent Data Service fallback when GPS is off)
        com.pinpulse.radar.service.LocationTrackingService.start(this)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.pinpulse.radar.util.SirenHelper.isCurrentlyPlaying()) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
                    android.view.KeyEvent.KEYCODE_POWER -> {
                        if (viewModel.hasSecurityPin()) {
                            viewModel.showSirenPinDialog()
                        } else {
                            viewModel.stopSirenDirectly(this)
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun requestBatteryOptimizationExemption(force: Boolean = false) {
        val prefs = com.pinpulse.radar.data.AppPreferences(this)
        if (!force && prefs.hasPromptedBatteryExemption) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                prefs.hasPromptedBatteryExemption = true
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {}
                }
            }
        }
    }

    /**
     * Native 1-Tap "Turn On Location (GPS)" popup inside the app using Google Play Services
     * LocationSettingsRequest, with fallback to Location Settings screen.
     */
    fun promptTurnOnGpsDialog() {
        try {
            val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2500L
            ).build()
            val builder = com.google.android.gms.location.LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true)

            val task = LocationServices.getSettingsClient(this).checkLocationSettings(builder.build())
            task.addOnSuccessListener {
                fetchImmediateLocation()
            }
            task.addOnFailureListener { exception ->
                if (exception is com.google.android.gms.common.api.ResolvableApiException) {
                    try {
                        exception.startResolutionForResult(this, 9522)
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (_: Exception) {}
                    }
                } else {
                    try {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9522) {
            fetchImmediateLocation()
        }
    }
}
