package com.pinpulse.radar.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarDarkBg
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.util.UserAvatar
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
fun AuthScreen(
    onSignIn: (email: String, displayName: String, avatarBase64: String?, securityPin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()

    // Responsive screen size detection for older, compact, or high-font-scale Android phones
    val screenHeightDp = configuration.screenHeightDp
    val screenWidthDp = configuration.screenWidthDp
    val isVeryCompact = screenHeightDp < 620 || screenWidthDp < 340
    val isCompact = screenHeightDp < 740 || screenWidthDp < 380

    val outerHorizontalPadding = when {
        isVeryCompact -> 10.dp
        isCompact -> 14.dp
        else -> 20.dp
    }
    val outerVerticalPadding = when {
        isVeryCompact -> 10.dp
        isCompact -> 14.dp
        else -> 20.dp
    }
    val cardPadding = when {
        isVeryCompact -> 16.dp
        isCompact -> 20.dp
        else -> 24.dp
    }
    val logoBoxSize = when {
        isVeryCompact -> 46.dp
        isCompact -> 54.dp
        else -> 64.dp
    }
    val logoIconSize = when {
        isVeryCompact -> 24.dp
        isCompact -> 28.dp
        else -> 34.dp
    }
    val avatarSize = when {
        isVeryCompact -> 60.dp
        isCompact -> 68.dp
        else -> 78.dp
    }
    val titleFontSize = when {
        isVeryCompact -> 20.sp
        isCompact -> 22.sp
        else -> 26.sp
    }
    val sectionSpacer = when {
        isVeryCompact -> 10.dp
        isCompact -> 14.dp
        else -> 20.dp
    }
    val fieldSpacer = if (isVeryCompact) 6.dp else 8.dp
    val primaryButtonHeight = if (isCompact) 46.dp else 50.dp

    var emailInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var pinErrorMessage by remember { mutableStateOf<String?>(null) }
    var avatarBase64 by remember { mutableStateOf<String?>(null) }
    var isGoogleAccountSelected by remember { mutableStateOf(false) }

    // Photo picker for custom avatar from phone
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            avatarBase64 = compressImageToBase64(uri, context)
        }
    }

    // Fallback Android native Account Picker (selects actual Google Account on device)
    val nativeAccountPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                val cleanName = accountName.substringBefore("@").replace(".", " ")
                    .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                emailInput = accountName
                if (nameInput.isBlank()) {
                    nameInput = cleanName
                }
                isGoogleAccountSelected = true
                pinErrorMessage = null
            }
        }
    }

    // Google Sign-In Client Intent Launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.result
            if (account != null && !account.email.isNullOrBlank()) {
                val realEmail = account.email ?: ""
                val realName = account.displayName ?: realEmail.substringBefore("@")
                val photoUrl = account.photoUrl?.toString()
                emailInput = realEmail
                nameInput = realName
                if (!photoUrl.isNullOrEmpty() && avatarBase64 == null) {
                    avatarBase64 = photoUrl
                }
                isGoogleAccountSelected = true
                pinErrorMessage = null
                return@rememberLauncherForActivityResult
            }
        } catch (e: Exception) {
            // Google Play Services client not configured with web client ID, launch native Android Account Picker
        }

        // Launch native Account Picker as reliable fallback
        try {
            val intent = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), null, null, null, null
            )
            nativeAccountPicker.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Please choose your Google Account", Toast.LENGTH_SHORT).show()
        }
    }

    fun startGoogleSignIn() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            client.signOut() // Clear previous cache to always allow account selection
            googleSignInLauncher.launch(client.signInIntent)
        } catch (e: Exception) {
            try {
                val intent = AccountManager.newChooseAccountIntent(
                    null, null, arrayOf("com.google"), null, null, null, null
                )
                nativeAccountPicker.launch(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Please enter your Display Name and Email below", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun submitGoogleAccountSetup() {
        val finalName = nameInput.trim().ifBlank { emailInput.substringBefore("@").ifBlank { "User" } }
        if (pinInput.length < 4) {
            pinErrorMessage = "PIN must be between 4 and 6 digits."
        } else if (pinInput != confirmPinInput) {
            pinErrorMessage = "PIN and confirmation do not match."
        } else {
            focusManager.clearFocus()
            onSignIn(emailInput.trim(), finalName, avatarBase64, pinInput)
        }
    }

    fun submitManualSignIn() {
        val finalName = nameInput.trim()
        if (finalName.isBlank()) {
            pinErrorMessage = "Please enter your Display Name"
        } else if (pinInput.length < 4) {
            pinErrorMessage = "Security PIN is required (4 to 6 digits)"
        } else if (pinInput != confirmPinInput) {
            pinErrorMessage = "PIN and confirmation do not match"
        } else {
            focusManager.clearFocus()
            val finalEmail = emailInput.trim().ifEmpty { "${finalName.lowercase().replace(" ", "")}@gmail.com" }
            onSignIn(finalEmail, finalName, avatarBase64, pinInput)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RadarDarkBg)
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(if (isCompact) 22.dp else 28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(cardPadding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // PinPulse Brand Logo Icon
                    Box(
                        modifier = Modifier
                            .size(logoBoxSize)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(2.dp, PulseGreen, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = "PinPulse Logo",
                            tint = PulseGreen,
                            modifier = Modifier.size(logoIconSize)
                        )
                    }

                    Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

                    Text(
                        text = "PinPulse",
                        fontSize = titleFontSize,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )

                    Text(
                        text = "Live Friends & Group Location Radar",
                        fontSize = if (isCompact) 12.sp else 13.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    // Avatar Picker
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(2.dp, if (avatarBase64 != null) PulseGreen else RadarBlue, CircleShape)
                            .clickable { photoPicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBase64 != null) {
                            UserAvatar(
                                avatarSource = avatarBase64,
                                displayName = nameInput.ifBlank { "Me" },
                                modifier = Modifier.fillMaxSize(),
                                fallbackTextSize = if (isCompact) 20.sp else 24.sp
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Add Photo",
                                    tint = RadarBlue,
                                    modifier = Modifier.size(if (isCompact) 20.dp else 24.dp)
                                )
                                Text("Photo", fontSize = 10.sp, color = RadarBlue)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Tap to pick avatar from phone", fontSize = 11.sp, color = TextMuted)

                    Spacer(modifier = Modifier.height(sectionSpacer))

                    if (isGoogleAccountSelected) {
                        // Google Account Selected Step: Show profile and require PIN setup!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F172A))
                                .border(1.dp, PulseGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            UserAvatar(
                                avatarSource = avatarBase64,
                                displayName = nameInput,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(nameInput, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                Text(emailInput, color = TextMuted, fontSize = 11.sp)
                            }
                            Text("✓ Google", color = PulseGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it; pinErrorMessage = null },
                            label = { Text("Display Name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = TextMuted) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "🔒 Create Your Security PIN (Required)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Choose a 4-6 digit PIN required to silence the emergency siren.",
                            fontSize = 11.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= 6) {
                                    pinInput = it
                                    pinErrorMessage = null
                                }
                            },
                            label = { Text("Choose 4-6 digit PIN *") },
                            placeholder = { Text("e.g. 5821") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PulseGreen) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(fieldSpacer))

                        OutlinedTextField(
                            value = confirmPinInput,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= 6) {
                                    confirmPinInput = it
                                    pinErrorMessage = null
                                }
                            },
                            label = { Text("Confirm PIN *") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PulseGreen) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitGoogleAccountSetup() }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (pinErrorMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = pinErrorMessage ?: "",
                                fontSize = 12.sp,
                                color = DangerRed,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { submitGoogleAccountSetup() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(primaryButtonHeight),
                            colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Complete Setup & Enter Radar",
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                isGoogleAccountSelected = false
                                pinErrorMessage = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "← Change Account / Edit Manually",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        // 1. PRIMARY: Sign In with Google Account Picker Button
                        Button(
                            onClick = { startGoogleSignIn() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(primaryButtonHeight),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "G ",
                                    fontSize = if (isCompact) 18.sp else 20.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF4285F4)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Sign in with Google",
                                    color = Color(0xFF1F2937),
                                    fontSize = if (isCompact) 14.sp else 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(if (isCompact) 12.dp else 16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(Color(0xFF334155))
                            )
                            Text(
                                text = " or enter custom ",
                                fontSize = 11.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(Color(0xFF334155))
                            )
                        }

                        Spacer(modifier = Modifier.height(if (isCompact) 10.dp else 14.dp))

                        // Name Input
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it; pinErrorMessage = null },
                            label = { Text("Display Name *") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = TextMuted) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(fieldSpacer))

                        // Google Email Input
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it; pinErrorMessage = null },
                            label = { Text("Google Account / Email (Optional)") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = TextMuted) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(fieldSpacer))

                        // Security PIN Input (Required)
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= 6) {
                                    pinInput = it
                                    pinErrorMessage = null
                                }
                            },
                            label = { Text("Choose Security PIN (4-6 digits) *") },
                            placeholder = { Text("4-6 digits") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PulseGreen) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(fieldSpacer))

                        // Confirm Security PIN Input
                        OutlinedTextField(
                            value = confirmPinInput,
                            onValueChange = {
                                if (it.all { c -> c.isDigit() } && it.length <= 6) {
                                    confirmPinInput = it
                                    pinErrorMessage = null
                                }
                            },
                            label = { Text("Confirm Security PIN *") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = PulseGreen) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitManualSignIn() }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "🔒 Required to stop the siren if someone rings your phone",
                            fontSize = 11.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)
                        )

                        if (pinErrorMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = pinErrorMessage ?: "",
                                fontSize = 12.sp,
                                color = DangerRed,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(if (isCompact) 12.dp else 16.dp))

                        // Manual Continue Button
                        Button(
                            onClick = { submitManualSignIn() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(primaryButtonHeight),
                            colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Continue & Enter Radar",
                                color = Color.Black,
                                fontSize = if (isCompact) 14.sp else 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun compressImageToBase64(uri: Uri, context: Context): String? {
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val original = BitmapFactory.decodeStream(inputStream) ?: return null
        val scaled = Bitmap.createScaledBitmap(original, 128, 128, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val bytes = stream.toByteArray()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}
