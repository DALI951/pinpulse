package com.pinpulse.radar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
                            onSignIn = { email, name, avatar ->
                                viewModel.signInWithGoogleIdentity(email, name, avatar)
                                fetchImmediateLocation()
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fetchImmediateLocation()
    }

    fun fetchImmediateLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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
    }
}
