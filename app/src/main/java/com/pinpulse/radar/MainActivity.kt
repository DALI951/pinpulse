package com.pinpulse.radar

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pinpulse.radar.ui.screens.AuthScreen
import com.pinpulse.radar.ui.screens.MainScreen
import com.pinpulse.radar.ui.screens.OnboardingSetupScreen
import android.widget.Toast
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.ui.theme.PinPulseTheme
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val _isGpsDisabled = MutableStateFlow(false)
    val isGpsDisabled: StateFlow<Boolean> = _isGpsDisabled.asStateFlow()

    private val gpsSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkAndEnforceGps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prune obsolete APKs from previous updates if the app is now running that version or newer
        com.pinpulse.radar.util.UpdateManager.cleanupOldInstalledApks(this)

        try {
            registerReceiver(gpsSwitchReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        } catch (_: Exception) {}

        checkAndEnforceGps()
        fetchImmediateLocation()

        setContent {
            PinPulseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = RadarDarkBg
                ) {
                    val currentUser by viewModel.currentUser.collectAsState()
                    val hasCompletedSetup by viewModel.hasCompletedSetup.collectAsState()
                    val hasUserCreatedPassword by viewModel.hasUserCreatedPassword.collectAsState()
                    val isGpsOff by isGpsDisabled.collectAsState()

                    if (currentUser == null) {
                        AuthScreen(viewModel = viewModel)
                    } else if (!hasCompletedSetup) {
                        OnboardingSetupScreen(
                            viewModel = viewModel,
                            onFinish = {
                                checkAndEnforceGps()
                                fetchImmediateLocation()
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)

                        if (!hasUserCreatedPassword && currentUser?.email?.isNotBlank() == true) {
                            CreatePasswordPromptDialog(
                                email = currentUser?.email ?: "",
                                onSetPassword = { pass ->
                                    viewModel.setPasswordForCurrentAccount(pass) { success: Boolean, err: String? ->
                                        if (success) {
                                            Toast.makeText(this@MainActivity, "Account secured with password!", Toast.LENGTH_SHORT).show()
                                        } else if (err != null) {
                                            Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDismiss = {
                                    viewModel.markPasswordCreated()
                                }
                            )
                        }
                    }

                    // Persistent Elderly Safety Mode GPS enforcement dialog
                    if (isGpsOff) {
                        ElderlyGpsEnforcementDialog(
                            onTurnOnGps = { promptTurnOnGpsDialog() }
                        )
                    }
                }
            }
        }

        if (intent?.action == "SHOW_SIREN_PIN") {
            viewModel.showSirenPinDialog()
        }
        if (intent?.getBooleanExtra("ACTION_PROMPT_GPS", false) == true) {
            promptTurnOnGpsDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == "SHOW_SIREN_PIN") {
            viewModel.showSirenPinDialog()
        }
        if (intent.getBooleanExtra("ACTION_PROMPT_GPS", false)) {
            promptTurnOnGpsDialog()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(gpsSwitchReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        viewModel.setForegroundActive(true)
        // Check if an update was already downloaded so the UI is ready without re-downloading
        viewModel.refreshUpdateStatus()
        checkAndEnforceGps()
        fetchImmediateLocation()
    }

    override fun onPause() {
        super.onPause()
        viewModel.setForegroundActive(false)
    }

    fun checkAndEnforceGps(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        _isGpsDisabled.value = !isGpsEnabled
        if (!isGpsEnabled) {
            promptTurnOnGpsDialog()
        }
        return isGpsEnabled
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
            } catch (_: SecurityException) {}
        }
        // Always start LocationTrackingService (uses GPS/Cell when enabled, and silent Data Service fallback when GPS is off)
        com.pinpulse.radar.service.LocationTrackingService.start(this)
    }

    /**
     * Request battery optimization exemption so the OS does not freeze the app in background.
     * Only prompts once unless forced (e.g. from Settings).
     */
    fun promptBatteryOptimization(force: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val prefs = com.pinpulse.radar.data.AppPreferences(this)
            if (!force && prefs.hasPromptedBatteryExemption) {
                return
            }
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
                _isGpsDisabled.value = false
                fetchImmediateLocation()
            }
            task.addOnFailureListener { exception ->
                _isGpsDisabled.value = true
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
            _isGpsDisabled.value = true
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9522) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            _isGpsDisabled.value = !isGpsEnabled
            if (isGpsEnabled) {
                fetchImmediateLocation()
            } else {
                checkAndEnforceGps()
            }
        }
    }
}

@Composable
fun ElderlyGpsEnforcementDialog(onTurnOnGps: () -> Unit) {
    Dialog(
        onDismissRequest = { /* Cannot dismiss without turning on GPS */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0x20EF4444), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = "GPS Disabled",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Elderly Protection Active",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "GPS location must remain ON 24/7 for safety and emergency monitoring. GPS cannot be disabled on this device.\n\nPlease tap below to turn GPS back on now.",
                    fontSize = 13.sp,
                    color = Color(0xFFCBD5E1),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onTurnOnGps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
                ) {
                    Text(
                        text = "TURN ON GPS NOW",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                }
            }
        }
    }
}

@Composable
fun CreatePasswordPromptDialog(
    email: String,
    onSetPassword: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔐 Secure Your Account", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column {
                Text(
                    text = "Welcome back! To protect your account ($email) and circle data, please choose a secure password:",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("New Password (min 6 chars)") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = TextMuted
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPass,
                    onValueChange = { confirmPass = it },
                    label = { Text("Confirm Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = TextMuted) },
                    trailingIcon = {
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(
                                imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (confirmPasswordVisible) "Hide password" else "Show password",
                                tint = TextMuted
                            )
                        }
                    },
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error ?: "", color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pass.length < 6) {
                        error = "Password must be at least 6 characters."
                    } else if (pass != confirmPass) {
                        error = "Passwords do not match."
                    } else {
                        onSetPassword(pass)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
            ) {
                Text("Save Password", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later", color = TextMuted)
            }
        },
        containerColor = Color(0xFF1E293B)
    )
}
