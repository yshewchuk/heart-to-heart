package com.hearttoheart.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hearttoheart.app.data.PairingRepository
import com.hearttoheart.app.data.PairingStatus
import com.hearttoheart.app.data.generateVerificationCode
import com.hearttoheart.app.ui.theme.Coral
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Screen that scans a partner's QR code to initiate pairing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQRScreen(
    onNavigateBack: () -> Unit,
    onPairingComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { PairingRepository(context) }
    val scope = rememberCoroutineScope()
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    var scannedUserId by remember { mutableStateOf<String?>(null) }
    var scannedEncryptionKey by remember { mutableStateOf<String?>(null) }  // Partner's encryption key from QR
    var scannedVerificationCode by remember { mutableStateOf<String?>(null) }  // Verification code from QR
    var isSendingRequest by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingStatus by remember { mutableStateOf<PairingStatus?>(null) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }
    
    // Request camera permission on launch
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        // Ensure we have an auth session for this pairing attempt.
        // For multi-account support we create a fresh anonymous account at scan time.
        val created = repository.createFreshAnonymousAccountForPairing()
        if (created.isSuccess) {
            repository.initializeUserDocument()
        } else {
            error = created.exceptionOrNull()?.message ?: "Failed to prepare pairing"
        }
    }
    
    // Listen for pairing status when we've sent a request
    LaunchedEffect(scannedUserId) {
        if (scannedUserId == null) return@LaunchedEffect
        
        repository.observeMyRequestStatus(scannedUserId!!).collect { status ->
            pairingStatus = status
            
            if (status == PairingStatus.Accepted) {
                // Complete pairing
                val result = repository.completePairing(scannedUserId!!)
                if (result.isSuccess) {
                    onPairingComplete()
                } else {
                    error = result.exceptionOrNull()?.message
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Partner's Code") },
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
                .background(Color.Black)
                .padding(padding)
        ) {
            when {
                !hasCameraPermission -> {
                    // No camera permission
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Coral
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "We need camera access to scan your partner's QR code",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = Coral)
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
                
                scannedUserId != null -> {
                    // Pairing in progress
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        when (pairingStatus) {
                            is PairingStatus.Error -> {
                                Text(
                                    text = "⚠️",
                                    style = MaterialTheme.typography.displayLarge
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = (pairingStatus as PairingStatus.Error).message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                OutlinedButton(onClick = {
                                    scannedUserId = null
                                    scannedEncryptionKey = null
                                    pairingStatus = null
                                    error = null
                                }) {
                                    Text("Try Again")
                                }
                            }
                            PairingStatus.Rejected -> {
                                Text(
                                    text = "😔",
                                    style = MaterialTheme.typography.displayLarge
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Request was declined",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                OutlinedButton(onClick = {
                                    scannedUserId = null
                                    scannedEncryptionKey = null
                                    pairingStatus = null
                                }) {
                                    Text("Try Again")
                                }
                            }
                            else -> {
                                // Pending or sending
                                if (isSendingRequest) {
                                    CircularProgressIndicator(color = Coral, modifier = Modifier.size(64.dp))
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        text = "Sending request...",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    // Show verification code prominently
                                    scannedVerificationCode?.let { code ->
                                        Text(
                                            text = "Show this code to your partner",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Spacer(modifier = Modifier.height(16.dp))
                                        
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(24.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "Verification Code",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = code.chunked(3).joinToString(" "),
                                                    style = MaterialTheme.typography.displaySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Coral
                                                )
                                            }
                                        }
                                        
                                        Spacer(modifier = Modifier.height(16.dp))
                                        
                                        Text(
                                            text = "Your partner should see this same code on their screen before accepting",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Spacer(modifier = Modifier.height(24.dp))
                                        
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
                                                text = "Waiting for them to accept...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                error != null -> {
                    // Error state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "⚠️",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { error = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Coral)
                        ) {
                            Text("Try Again")
                        }
                    }
                }
                
                else -> {
                    // Camera preview
                    CameraPreview(
                        onQRCodeScanned = { qrContent ->
                            // Parse the deep link: heart-to-heart://pair?uid=USER_ID&key=ENCRYPTION_KEY
                            val qrData = parseDeepLink(qrContent)
                            if (qrData.userId != null) {
                                scannedUserId = qrData.userId
                                scannedEncryptionKey = qrData.encryptionKey
                                // Generate verification code locally for security
                                val generatedCode = generateVerificationCode()
                                scannedVerificationCode = generatedCode
                                isSendingRequest = true
                                
                                scope.launch {
                                    // Pass their encryption key and our generated verification code
                                    val result = repository.sendPairingRequest(
                                        qrData.userId, 
                                        qrData.encryptionKey,
                                        generatedCode
                                    )
                                    isSendingRequest = false
                                    
                                    if (result.isFailure) {
                                        error = result.exceptionOrNull()?.message ?: "Failed to send request"
                                        scannedUserId = null
                                        scannedEncryptionKey = null
                                        scannedVerificationCode = null
                                    }
                                }
                            } else {
                                error = "Invalid QR code. Make sure you're scanning a Heart-to-Heart code."
                            }
                        }
                    )
                    
                    // Overlay with scan frame
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Scan frame
                        Box(
                            modifier = Modifier
                                .size(280.dp)
                                .border(
                                    width = 3.dp,
                                    color = Coral,
                                    shape = RoundedCornerShape(24.dp)
                                )
                        )
                        
                        // Instructions at bottom
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Point at your partner's QR code",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Camera preview with QR code scanning using ML Kit.
 */
@Composable
private fun CameraPreview(
    onQRCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasScanned by remember { mutableStateOf(false) }
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val executor = Executors.newSingleThreadExecutor()
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            if (hasScanned) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            
                            @androidx.camera.core.ExperimentalGetImage
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                
                                val scanner = BarcodeScanning.getClient()
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        for (barcode in barcodes) {
                                            if (barcode.valueType == Barcode.TYPE_URL ||
                                                barcode.valueType == Barcode.TYPE_TEXT) {
                                                barcode.rawValue?.let { value ->
                                                    if (value.contains("heart-to-heart://") && !hasScanned) {
                                                        hasScanned = true
                                                        onQRCodeScanned(value)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e("ScanQR", "Barcode scanning failed", e)
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    Log.e("ScanQR", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Parse the deep link to extract the user ID and encryption key.
 * Format: heart-to-heart://pair?uid=USER_ID&key=ENCRYPTION_KEY
 * 
 * @return Pair of (userId, encryptionKey) - encryptionKey may be null for old QR codes
 */
private data class QRCodeData(
    val userId: String?,
    val encryptionKey: String?
)

private fun parseDeepLink(deepLink: String): QRCodeData {
    return try {
        if (!deepLink.startsWith("heart-to-heart://pair")) return QRCodeData(null, null)
        
        val uri = android.net.Uri.parse(deepLink)
        val userId = uri.getQueryParameter("uid")
        val encryptionKey = uri.getQueryParameter("key")
        
        QRCodeData(userId, encryptionKey)
    } catch (e: Exception) {
        QRCodeData(null, null)
    }
}
