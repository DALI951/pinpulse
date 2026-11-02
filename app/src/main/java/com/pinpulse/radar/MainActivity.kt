package com.pinpulse.radar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.pinpulse.radar.ui.screens.AuthScreen
import com.pinpulse.radar.ui.screens.MainScreen
import com.pinpulse.radar.ui.theme.PinPulseTheme
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
