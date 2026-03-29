package com.hearttoheart.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearttoheart.app.data.MessageCategory
import com.hearttoheart.app.data.AccountSelectionRepository
import com.hearttoheart.app.services.AlarmService
import com.hearttoheart.app.data.PartnerPreferencesRepository
import com.hearttoheart.app.ui.theme.CoralDark
import com.hearttoheart.app.ui.theme.HeartToHeartTheme
import com.hearttoheart.app.ui.theme.LifelineColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * Full-screen activity that displays over the lock screen for Lifeline alerts.
 * Features a pulsing background and slide-to-dismiss action.
 */
class AlarmActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure window to show over lock screen
        setupWindowFlags()
        
        val categoryName = intent.getStringExtra(EXTRA_CATEGORY) ?: MessageCategory.LIFELINE.name
        val category = MessageCategory.valueOf(categoryName)
        val note = intent.getStringExtra(EXTRA_NOTE) ?: ""
        val accountSelectionRepository = AccountSelectionRepository(this)
        val alarmAccountUid = resolveAlarmAccountUid(
            explicitAccountUid = intent.getStringExtra(EXTRA_ACCOUNT_UID),
            accountSelectionRepository = accountSelectionRepository
        )
        
        // Load partner preferences
        val prefsRepository = PartnerPreferencesRepository(this)
        val prefs = runBlocking { 
            try { alarmAccountUid?.let { prefsRepository.getPreferences(it).first() } }
            catch (e: Exception) { null }
        }
        val partnerNickname = prefs?.nickname?.ifBlank { null } ?: "your love"
        val customIcon = prefs?.notificationIcon?.emoji ?: "❤️"
        
        setContent {
            HeartToHeartTheme(darkTheme = false) {
                AlarmScreen(
                    category = category,
                    note = note,
                    partnerNickname = partnerNickname,
                    customIcon = customIcon,
                    onDismiss = {
                        AlarmService.stop(this)
                        finish()
                    }
                )
            }
        }
    }
    
    private fun setupWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_NOTE = "extra_note"
        const val EXTRA_ACCOUNT_UID = "extra_account_uid"
    }

    private fun resolveAlarmAccountUid(
        explicitAccountUid: String?,
        accountSelectionRepository: AccountSelectionRepository
    ): String? {
        if (!explicitAccountUid.isNullOrBlank()) return explicitAccountUid
        return runBlocking {
            val pairedAccounts = accountSelectionRepository.getPairedAccounts().first()
            val selectedAccountUid = accountSelectionRepository.getSelectedAccountUid().first()
            when {
                !selectedAccountUid.isNullOrBlank() && pairedAccounts.containsKey(selectedAccountUid) -> selectedAccountUid
                pairedAccounts.isNotEmpty() -> pairedAccounts.keys.sorted().first()
                else -> null
            }
        }
    }
}

@Composable
fun AlarmScreen(
    category: MessageCategory,
    note: String,
    partnerNickname: String = "your love",
    customIcon: String = "❤️",
    onDismiss: () -> Unit
) {
    // Pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    val backgroundColor by infiniteTransition.animateColor(
        initialValue = LifelineColor.copy(alpha = 0.8f),
        targetValue = CoralDark,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "backgroundColor"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        backgroundColor,
                        LifelineColor.copy(alpha = pulseAlpha)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Custom icon indicator
            Text(
                text = customIcon,
                fontSize = 64.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Category name
            Text(
                text = category.displayName.uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 4.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "from $partnerNickname",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Sender avatar
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(120.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Note text (if provided)
            if (note.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"$note\"",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Slide to dismiss
            SlideToAcknowledge(
                onDismiss = onDismiss
            )
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun SlideToAcknowledge(
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    val maxOffset = with(density) { 200.dp.toPx() }
    
    val isDismissed = offsetX >= maxOffset * 0.8f
    
    LaunchedEffect(isDismissed) {
        if (isDismissed) {
            onDismiss()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color.White.copy(alpha = 0.2f)),
        contentAlignment = Alignment.CenterStart
    ) {
        // Track text
        Text(
            text = "Slide to acknowledge →",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.Center)
        )
        
        // Slider thumb
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .size(60.dp)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color.White)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < maxOffset * 0.8f) {
                                offsetX = 0f // Snap back
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(0f, maxOffset)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Slide",
                tint = LifelineColor,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
