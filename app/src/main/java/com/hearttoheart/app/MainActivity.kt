package com.hearttoheart.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.hearttoheart.app.data.HeartMessage
import com.hearttoheart.app.data.MessageCategory
import com.hearttoheart.app.data.MessageHistory
import com.hearttoheart.app.data.MessageSender
import com.hearttoheart.app.data.PairingRepository
import com.hearttoheart.app.data.Partner
import com.hearttoheart.app.data.PartnerPreferencesRepository
import com.hearttoheart.app.data.PartnerPrefs
import com.hearttoheart.app.data.StoredMessage
import com.hearttoheart.app.services.AlarmService
import com.hearttoheart.app.ui.screens.HistoryScreen
import com.hearttoheart.app.ui.screens.HomeScreen
import com.hearttoheart.app.ui.screens.PairingScreen
import com.hearttoheart.app.ui.screens.ScanQRScreen
import com.hearttoheart.app.ui.screens.SettingsScreen
import com.hearttoheart.app.ui.screens.ShowQRScreen
import com.hearttoheart.app.ui.theme.HeartToHeartTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Navigation destinations for the app.
 */
sealed class Screen {
    object Home : Screen()
    object History : Screen()
    object Settings : Screen()
    object Pairing : Screen()
    object ShowQR : Screen()
    object ScanQR : Screen()
}

/**
 * Main activity for Heart-to-Heart app.
 * Handles Firebase auth, permission requests, and navigation.
 */
class MainActivity : ComponentActivity() {
    
    private lateinit var auth: FirebaseAuth
    private lateinit var pairingRepository: PairingRepository
    private lateinit var messageSender: MessageSender
    private lateinit var messageHistory: MessageHistory
    private lateinit var partnerPrefsRepository: PartnerPreferencesRepository
    private val sendScope = CoroutineScope(Dispatchers.Main)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            Log.d(TAG, "Permission $permission: $isGranted")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Firebase Auth
        auth = Firebase.auth
        
        // Initialize repositories
        pairingRepository = PairingRepository(this)
        messageHistory = MessageHistory(this)
        messageSender = MessageSender(this)
        partnerPrefsRepository = PartnerPreferencesRepository(this)
        
        // Sign in anonymously if not already signed in
        signInAnonymously()
        
        // Request necessary permissions
        requestPermissions()
        
        // Get FCM token
        getFCMToken()
        
        setContent {
            HeartToHeartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                    var partner by remember { mutableStateOf<Partner?>(null) }
                    var partnerPrefs by remember { mutableStateOf(PartnerPrefs()) }
                    var lastReceivedMessage by remember { mutableStateOf<StoredMessage?>(null) }
                    var allMessages by remember { mutableStateOf<List<StoredMessage>>(emptyList()) }
                    val scope = rememberCoroutineScope()
                    
                    // Load partner from local storage
                    LaunchedEffect(Unit) {
                        pairingRepository.getPartner().collect { savedPartner ->
                            partner = savedPartner
                        }
                    }
                    
                    // Load partner preferences
                    LaunchedEffect(Unit) {
                        partnerPrefsRepository.getPreferences().collect { prefs ->
                            partnerPrefs = prefs
                        }
                    }
                    
                    // Load last received message
                    LaunchedEffect(Unit) {
                        messageHistory.getLastReceivedMessage().collect { message ->
                            lastReceivedMessage = message
                        }
                    }
                    
                    // Load all messages for history
                    LaunchedEffect(Unit) {
                        messageHistory.getMessages().collect { messages ->
                            allMessages = messages
                        }
                    }
                    
                    when (currentScreen) {
                        Screen.Home -> {
                            HomeScreen(
                                partner = partner,
                                partnerPrefs = partnerPrefs,
                                lastReceivedMessage = lastReceivedMessage,
                                onSendMessage = { message ->
                                    sendMessage(message, partner)
                                },
                                onPairClick = {
                                    currentScreen = Screen.Pairing
                                },
                                onHistoryClick = {
                                    currentScreen = Screen.History
                                },
                                onSettingsClick = {
                                    currentScreen = Screen.Settings
                                }
                            )
                        }
                        
                        Screen.History -> {
                            HistoryScreen(
                                messages = allMessages,
                                onNavigateBack = { currentScreen = Screen.Home }
                            )
                        }
                        
                        Screen.Settings -> {
                            SettingsScreen(
                                currentPrefs = partnerPrefs,
                                onNavigateBack = { currentScreen = Screen.Home },
                                onPrefsUpdated = {
                                    // Preferences are updated via DataStore and will be reflected automatically
                                },
                                onUnpair = {
                                    // Clear partner and go back home
                                    currentScreen = Screen.Home
                                }
                            )
                        }
                        
                        Screen.Pairing -> {
                            PairingScreen(
                                onNavigateBack = { currentScreen = Screen.Home },
                                onShowMyQR = { currentScreen = Screen.ShowQR },
                                onScanQR = { currentScreen = Screen.ScanQR }
                            )
                        }
                        
                        Screen.ShowQR -> {
                            ShowQRScreen(
                                onNavigateBack = { currentScreen = Screen.Pairing },
                                onPairingComplete = {
                                    Toast.makeText(this, "💕 Connected!", Toast.LENGTH_SHORT).show()
                                    currentScreen = Screen.Home
                                }
                            )
                        }
                        
                        Screen.ScanQR -> {
                            ScanQRScreen(
                                onNavigateBack = { currentScreen = Screen.Pairing },
                                onPairingComplete = {
                                    Toast.makeText(this, "💕 Connected!", Toast.LENGTH_SHORT).show()
                                    currentScreen = Screen.Home
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    private fun signInAnonymously() {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    Log.d(TAG, "Anonymous sign in success: ${result.user?.uid}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous sign in failed", e)
                    Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            Log.d(TAG, "Already signed in: ${auth.currentUser?.uid}")
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Camera permission for QR scanning
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }
    
    private fun getFCMToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d(TAG, "FCM Token: $token")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get FCM token", e)
            }
    }
    
    /**
     * Send a message to partner via Cloud Function, or locally for testing.
     */
    private fun sendMessage(message: HeartMessage, partner: Partner?) {
        Log.d(TAG, "Sending message: ${message.category.name} - ${message.note}")
        
        if (partner != null) {
            // Send to partner via Cloud Function
            Toast.makeText(
                this,
                "Sending ${message.category.emoji} to ${partner.displayName}...",
                Toast.LENGTH_SHORT
            ).show()
            
            sendScope.launch {
                val result = messageSender.sendMessage(partner, message)
                
                if (result.isSuccess) {
                    Log.d(TAG, "Message sent successfully!")
                    Toast.makeText(
                        this@MainActivity,
                        "${message.category.emoji} Sent!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.e(TAG, "Failed to send message: $error")
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to send: $error",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            // Test mode - no partner, trigger locally
            Toast.makeText(
                this,
                "Test: ${message.category.emoji} ${message.category.displayName}... (5 sec delay)",
                Toast.LENGTH_LONG
            ).show()
            
            // Trigger notification locally for testing
            android.os.Handler(mainLooper).postDelayed({
                Log.d(TAG, "Triggering local notification for testing")
                AlarmService.start(this, message.category, message.note.ifEmpty { "Message from your love 💕" })
            }, 5000) // 5 second delay
        }
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}
