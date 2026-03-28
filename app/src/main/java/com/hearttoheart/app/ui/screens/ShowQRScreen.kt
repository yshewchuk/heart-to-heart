package com.hearttoheart.app.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hearttoheart.app.data.EncryptionHelper
import com.hearttoheart.app.data.PairingRepository
import com.hearttoheart.app.data.PairingRequest
import com.hearttoheart.app.ui.theme.Coral
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ShowQRScreen"

/**
 * Screen that displays the user's QR code for their partner to scan.
 * Also listens for incoming pairing requests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowQRScreen(
    onNavigateBack: () -> Unit,
    onPairingComplete: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { PairingRepository(context) }
    val scope = rememberCoroutineScope()
    
    var userId by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var incomingRequest by remember { mutableStateOf<PairingRequest?>(null) }
    var isAccepting by remember { mutableStateOf(false) }
    
    // Our encryption key - used when partner sends messages TO us
    var myEncryptionKey by remember { mutableStateOf<String?>(null) }
    
    // Initialize user document and generate QR code
    LaunchedEffect(Unit) {
        try {
            Log.d(TAG, "Starting QR screen initialization...")
            
            // Each pairing uses a fresh anonymous account (one-time QR).
            val accountResult = repository.createNewAccountForPairing()
            if (accountResult.isFailure) {
                error = accountResult.exceptionOrNull()?.message ?: "Failed to create pairing account"
                isLoading = false
                return@LaunchedEffect
            }

            val accountUid = accountResult.getOrNull()?.anonymousUid
            userId = accountUid
            Log.d(TAG, "Pairing account UID: $userId")

            if (accountUid != null) {
                // Try to initialize user document in Firestore
                val initResult = repository.initializeUserDocumentForUser(accountUid)
                if (initResult.isFailure) {
                    Log.w(TAG, "Failed to init user doc: ${initResult.exceptionOrNull()?.message}")
                    // Continue anyway - we can still show QR code
                }

                // Generate encryption key for E2E encryption (partner uses this to encrypt messages TO us)
                myEncryptionKey = EncryptionHelper.generateKey()
                repository.saveMyDecryptionKeyForAccount(accountUid, myEncryptionKey!!)
                Log.d(TAG, "Generated encryption key for pairing account")

                // Generate QR code with deep link including encryption key
                val deepLink = "heart-to-heart://pair?uid=$accountUid&key=$myEncryptionKey"
                Log.d(TAG, "Generating QR for: heart-to-heart://pair?uid=$accountUid&key=<hidden>")
                qrBitmap = generateQRCode(deepLink, 512)
                Log.d(TAG, "QR bitmap generated: ${qrBitmap != null}")
                isLoading = false
            } else {
                Log.e(TAG, "Failed to create pairing account UID")
                error = "Could not create pairing account. Please try again."
                isLoading = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing QR screen", e)
            error = e.message ?: "Failed to initialize"
            isLoading = false
        }
    }
    
    // Listen for incoming pairing requests
    LaunchedEffect(userId) {
        if (userId == null) return@LaunchedEffect
        
        repository.observePairingRequestsForUser(userId!!).collect { requests ->
            // Show the most recent pending request
            incomingRequest = requests.maxByOrNull { it.requestedAt }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share Your Code") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(color = Coral)
                }
                error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "⚠️",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    val scrollState = rememberScrollState()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(24.dp)
                    ) {
                        // Header
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Coral,
                            modifier = Modifier.size(40.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "Show this to your partner",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        
                        Text(
                            text = "They'll scan it with their Heart-to-Heart app",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(20.dp))
                        
                        // QR Code
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            shadowElevation = 8.dp,
                            color = Color.White,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                qrBitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "QR Code",
                                        modifier = Modifier.size(200.dp)
                                    )
                                } ?: run {
                                    Icon(
                                        imageVector = Icons.Default.QrCode2,
                                        contentDescription = null,
                                        modifier = Modifier.size(200.dp),
                                        tint = Color.LightGray
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Waiting indicator
                        if (incomingRequest == null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Coral
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Waiting for your partner to scan...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                            }
                        }
                        
                        // Incoming request card
                        incomingRequest?.let { request ->
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Coral.copy(alpha = 0.15f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "💕 Pairing Request!",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    
                                    Spacer(modifier = Modifier.height(4.dp))
                                    
                                    Text(
                                        text = "Someone wants to connect with you",
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                    
                                    // Show verification code from request
                                    request.verificationCode?.takeIf { it.isNotEmpty() }?.let { code ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "Verification Code",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                                Text(
                                                    text = code.chunked(3).joinToString(" "),
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 4.sp,
                                                    color = Coral
                                                )
                                            }
                                        }
                                        
                                        Text(
                                            text = "Confirm this matches your partner's screen",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Decline button
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    repository.declinePairingRequest(request)
                                                    incomingRequest = null
                                                }
                                            },
                                            enabled = !isAccepting,
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Decline")
                                        }
                                        
                                        // Accept button
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isAccepting = true
                                                    val accountUid = userId
                                                    if (accountUid == null) {
                                                        error = "Missing pairing account"
                                                        isAccepting = false
                                                        return@launch
                                                    }
                                                    // Pass our encryption key so they can send encrypted messages to us
                                                    val result = repository.acceptPairingRequestForAccount(accountUid, request, myEncryptionKey)
                                                    isAccepting = false
                                                    if (result.isSuccess) {
                                                        onPairingComplete()
                                                    } else {
                                                        error = result.exceptionOrNull()?.message
                                                    }
                                                }
                                            },
                                            enabled = !isAccepting,
                                            colors = ButtonDefaults.buttonColors(containerColor = Coral),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            if (isAccepting) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    color = Color.White,
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text("Accept ❤️")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Generate a QR code bitmap from a string.
 */
private fun generateQRCode(content: String, size: Int): Bitmap? {
    return try {
        Log.d(TAG, "generateQRCode: content=$content, size=$size")
        
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val blackColor = android.graphics.Color.BLACK
        val whiteColor = android.graphics.Color.WHITE
        
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) blackColor else whiteColor)
            }
        }
        
        Log.d(TAG, "QR code bitmap created successfully")
        bitmap
    } catch (e: Exception) {
        Log.e(TAG, "Failed to generate QR code", e)
        null
    }
}
