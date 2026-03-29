package com.hearttoheart.app.services

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.hearttoheart.app.HeartToHeartApp
import com.hearttoheart.app.MainActivity
import com.hearttoheart.app.R
import com.hearttoheart.app.data.MessageCategory
import com.hearttoheart.app.data.NotificationIcon
import com.hearttoheart.app.data.AccountSelectionRepository
import com.hearttoheart.app.data.PartnerPreferencesRepository
import com.hearttoheart.app.data.PartnerPrefs
import com.hearttoheart.app.ui.AlarmActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import android.graphics.Matrix
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * Foreground service that plays alarm sounds bypassing silent mode.
 * Uses STREAM_ALARM and volume escalation for Lifeline alerts.
 */
class AlarmService : Service() {
    
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var volumeJob: Job? = null
    private var audioManager: AudioManager? = null
    private var originalVolume: Int = 0
    private var currentCategory: MessageCategory? = null
    private var customNotificationIcon: NotificationIcon = NotificationIcon.HEART
    private var partnerNickname: String = "your love"
    private var profilePictureBitmap: Bitmap? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var partnerPrefsRepository: PartnerPreferencesRepository
    private lateinit var accountSelectionRepository: AccountSelectionRepository
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        partnerPrefsRepository = PartnerPreferencesRepository(this)
        accountSelectionRepository = AccountSelectionRepository(this)
        
        // Preferences are loaded from each start intent in onStartCommand.
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle dismiss action
        if (intent?.action == ACTION_DISMISS) {
            stopSelfAndNotify()
            return START_NOT_STICKY
        }
        
        val category = intent?.getStringExtra(EXTRA_CATEGORY)?.let { 
            MessageCategory.valueOf(it) 
        } ?: MessageCategory.NUDGE
        val note = intent?.getStringExtra(EXTRA_NOTE) ?: ""
        loadPreferencesForAccount(resolveAlarmAccountUid(intent))
        
        currentCategory = category
        
        // Acquire wake lock to keep CPU running
        acquireWakeLock()
        
        // Start as foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification(category, note))
        
        // Play alarm based on category
        when (category) {
            MessageCategory.FLUTTER -> playFlutter(note)
            MessageCategory.NUDGE -> playNudge(note)
            MessageCategory.HEARTBEAT -> playHeartbeat(note)
            MessageCategory.LIFELINE -> playLifeline(note)
        }
        
        return START_NOT_STICKY
    }
    
    /**
     * Check if the screen is off or the device is locked.
     */
    private fun isScreenOffOrLocked(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        val isScreenOn = powerManager.isInteractive
        val isLocked = keyguardManager.isKeyguardLocked
        
        return !isScreenOn || isLocked
    }
    
    private fun createNotification(category: MessageCategory, note: String): Notification {
        val channelId = category.channelId
        
        // Intent to open main activity when notification is tapped
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Intent to open alarm activity (for full screen intent on Lifeline)
        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmActivity.EXTRA_CATEGORY, category.name)
            putExtra(AlarmActivity.EXTRA_NOTE, note)
            intent?.getStringExtra(EXTRA_ACCOUNT_UID)?.let { putExtra(AlarmActivity.EXTRA_ACCOUNT_UID, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Dismiss action
        val dismissIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_DISMISS
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create Person for MessagingStyle (shows photo on the left)
        val senderBuilder = Person.Builder()
            .setName(partnerNickname)
        
        // Add profile picture as the person's icon
        profilePictureBitmap?.let { bitmap ->
            senderBuilder.setIcon(IconCompat.createWithBitmap(bitmap))
        }
        
        val sender = senderBuilder.build()
        
        // Create MessagingStyle with the message
        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(
                note.ifEmpty { category.description },
                System.currentTimeMillis(),
                sender
            )
        
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(customNotificationIcon.drawableRes)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
        
        // Set large icon - use profile picture if available, otherwise heart
        val largeIconBitmap = profilePictureBitmap 
            ?: BitmapFactory.decodeResource(resources, R.drawable.ic_heart)
        builder.setLargeIcon(largeIconBitmap)
        
        // Configure based on category
        when (category) {
            MessageCategory.FLUTTER -> {
                builder
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setOngoing(false) // User can swipe to dismiss
            }
            MessageCategory.NUDGE -> {
                builder
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setOngoing(false) // User can swipe to dismiss
                    .addAction(customNotificationIcon.drawableRes, "Got it", dismissPendingIntent)
            }
            MessageCategory.HEARTBEAT -> {
                builder
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setOngoing(true) // Cannot swipe to dismiss
                    .addAction(customNotificationIcon.drawableRes, "Acknowledge", dismissPendingIntent)
            }
            MessageCategory.LIFELINE -> {
                builder
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setOngoing(true) // Cannot swipe to dismiss
                    .setFullScreenIntent(fullScreenPendingIntent, true) // Shows full screen when locked
                    .addAction(customNotificationIcon.drawableRes, "Acknowledge", dismissPendingIntent)
            }
        }
        
        return builder.build()
    }
    
    private fun playFlutter(note: String) {
        // Gentle vibration only - no sound
        vibrator?.let {
            val pattern = longArrayOf(0, 100, 50, 100, 50, 100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, -1)
            }
        }
        
        // Post a persistent notification, then stop the foreground service
        // The notification stays, but service doesn't need to keep running
        scope.launch {
            delay(500) // Let vibration complete
            postPersistentNotification(MessageCategory.FLUTTER, note)
            stopSelf()
        }
    }
    
    private fun playNudge(note: String) {
        // Play default notification sound
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, notificationUri)
                setVolume(1f, 1f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Ignore if notification sound fails
        }
        
        // Vibration pattern
        vibrator?.let {
            val pattern = longArrayOf(0, 250, 100, 250)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, -1)
            }
        }
        
        // Post persistent notification and stop service
        scope.launch {
            delay(1000) // Let sound/vibration complete
            postPersistentNotification(MessageCategory.NUDGE, note)
            stopSelf()
        }
    }
    
    private fun playHeartbeat(note: String) {
        // Play alarm sound using STREAM_ALARM (bypasses silent mode)
        // Start at 0% volume, will escalate to 50%
        playDefaultAlarm(loop = true, volumePercent = 0.0f)
        
        // Heartbeat vibration pattern - loop it
        vibrator?.let {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = loop from index 0
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 0)
            }
        }
        
        // Start volume escalation: 0% -> 50% over 10 seconds
        startHeartbeatVolumeEscalation(note)
    }
    
    /**
     * Volume escalation for Heartbeat alerts.
     * Gradually increases from 0% to 50% over 10 seconds, then auto-stops.
     */
    private fun startHeartbeatVolumeEscalation(note: String) {
        volumeJob = scope.launch {
            val maxVolumePercent = 0.5f  // Cap at 50%
            val durationMs = 10_000L     // 10 seconds total
            val stepMs = 500L            // Update every 500ms
            val steps = (durationMs / stepMs).toInt()
            
            var currentStep = 0
            while (currentStep < steps && isActive) {
                val progress = currentStep.toFloat() / steps
                val volumePercent = maxVolumePercent * progress
                
                mediaPlayer?.setVolume(volumePercent, volumePercent)
                
                delay(stepMs)
                currentStep++
            }
            
            // Set final volume at 50%
            mediaPlayer?.setVolume(maxVolumePercent, maxVolumePercent)
            
            // Wait a moment at max volume, then stop
            delay(500)
            
            // Auto-stop after 10 seconds - post persistent notification and stop service
            postPersistentNotification(MessageCategory.HEARTBEAT, note)
            stopSelf()
        }
    }
    
    private fun playLifeline(note: String) {
        // Save original volume
        originalVolume = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 5
        
        // Only launch full screen activity if screen is off or locked
        // Otherwise, the heads-up notification is sufficient
        if (isScreenOffOrLocked()) {
            val intent = Intent(this, AlarmActivity::class.java).apply {
                putExtra(AlarmActivity.EXTRA_CATEGORY, MessageCategory.LIFELINE.name)
                putExtra(AlarmActivity.EXTRA_NOTE, note)
                this@AlarmService.intent?.getStringExtra(EXTRA_ACCOUNT_UID)?.let {
                    putExtra(AlarmActivity.EXTRA_ACCOUNT_UID, it)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
        
        // Start volume escalation
        startVolumeEscalation()
        
        // Play alarm sound (loops until dismissed)
        playDefaultAlarm(loop = true, volumePercent = 0.1f)
        
        // Continuous vibration
        vibrator?.let {
            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createWaveform(pattern, 0)) // Loop
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(pattern, 0)
            }
        }
        
        // Service keeps running until user acknowledges
        // No auto-stop for Lifeline
    }
    
    /**
     * Post a notification that persists after the service stops.
     * Used for Flutter and Nudge which don't need continuous service.
     */
    private fun postPersistentNotification(category: MessageCategory, note: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create Person for MessagingStyle (shows photo on the left)
        val senderBuilder = Person.Builder()
            .setName(partnerNickname)
        
        // Add profile picture as the person's icon
        profilePictureBitmap?.let { bitmap ->
            senderBuilder.setIcon(IconCompat.createWithBitmap(bitmap))
        }
        
        val sender = senderBuilder.build()
        
        // Create MessagingStyle with the message
        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(
                note.ifEmpty { category.description },
                System.currentTimeMillis(),
                sender
            )
        
        val notificationBuilder = NotificationCompat.Builder(this, category.channelId)
            .setSmallIcon(customNotificationIcon.drawableRes)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .setPriority(
                if (category == MessageCategory.NUDGE) 
                    NotificationCompat.PRIORITY_HIGH 
                else 
                    NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true) // Dismiss when tapped
        
        // Set large icon - use profile picture if available, otherwise heart
        val largeIconBitmap = profilePictureBitmap 
            ?: BitmapFactory.decodeResource(resources, R.drawable.ic_heart)
        notificationBuilder.setLargeIcon(largeIconBitmap)
        
        val notification = notificationBuilder.build()
        
        // Use a different ID so it doesn't get cancelled when service stops
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }
    
    /**
     * Stop the service and cancel notifications.
     */
    private fun stopSelfAndNotify() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
        notificationManager.cancel(PERSISTENT_NOTIFICATION_ID)
        stopSelf()
    }
    
    /**
     * Volume escalation algorithm for Lifeline alerts.
     * Starts at 10% and increases to 100% over 30 seconds.
     */
    private fun startVolumeEscalation() {
        volumeJob = scope.launch {
            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 15
            val maxVolumePercent = 0.7f  // Cap at 70%
            var currentPercent = 0.1f
            
            while (currentPercent < maxVolumePercent && isActive) {
                val volume = (maxVolume * currentPercent).toInt().coerceIn(1, maxVolume)
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
                mediaPlayer?.setVolume(currentPercent, currentPercent)
                
                delay(3000) // Every 3 seconds
                currentPercent += 0.1f
            }
            
            // Cap at 70%
            val cappedVolume = (maxVolume * maxVolumePercent).toInt().coerceIn(1, maxVolume)
            audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, cappedVolume, 0)
            mediaPlayer?.setVolume(maxVolumePercent, maxVolumePercent)
        }
    }
    
    private fun playAlarmSound(soundUri: Uri, loop: Boolean, volumePercent: Float) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM) // KEY: Bypasses silent mode
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(this@AlarmService, soundUri)
            isLooping = loop
            setVolume(volumePercent, volumePercent)
            prepare()
            start()
        }
    }
    
    private fun playDefaultAlarm(loop: Boolean, volumePercent: Float = 0.5f) {
        val defaultAlarmUri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        playAlarmSound(defaultAlarmUri, loop, volumePercent)
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeartToHeart::AlarmWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun resolveAlarmAccountUid(startIntent: Intent?): String? {
        val explicitAccountUid = startIntent?.getStringExtra(EXTRA_ACCOUNT_UID)
        if (!explicitAccountUid.isNullOrBlank()) {
            return explicitAccountUid
        }

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

    private fun loadPreferencesForAccount(accountUid: String?) {
        customNotificationIcon = NotificationIcon.HEART
        partnerNickname = "your love"
        profilePictureBitmap = null
        runBlocking {
            try {
                val prefs = accountUid?.let { partnerPrefsRepository.getPreferences(it).first() } ?: PartnerPrefs()
                customNotificationIcon = prefs.notificationIcon
                partnerNickname = prefs.nickname.ifBlank { "your love" }

                prefs.profilePictureUri?.let { pathOrUri ->
                    try {
                        profilePictureBitmap = if (pathOrUri.startsWith("/")) {
                            BitmapFactory.decodeFile(pathOrUri)
                        } else {
                            val uri = Uri.parse(pathOrUri)
                            loadAndRotateBitmap(uri)
                        }
                    } catch (_: Exception) {
                        // Use default icon/avatar when profile picture fails to load.
                    }
                }
            } catch (_: Exception) {
                // Keep default values on lookup failure.
            }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Stop volume escalation
        volumeJob?.cancel()
        
        // Restore original volume
        audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
        
        // Stop media player
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
        
        // Stop vibration
        vibrator?.cancel()
        
        // Release wake lock
        releaseWakeLock()
        
        // Cancel coroutines
        scope.cancel()
    }
    
    /**
     * Load a bitmap from URI and rotate it according to EXIF orientation data.
     */
    private fun loadAndRotateBitmap(uri: Uri): Bitmap? {
        return try {
            // First, read EXIF orientation
            val inputStreamForExif = contentResolver.openInputStream(uri)
            val exif = inputStreamForExif?.let { ExifInterface(it) }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
            inputStreamForExif?.close()
            
            // Then load the bitmap
            val inputStreamForBitmap = contentResolver.openInputStream(uri)
            val originalBitmap = BitmapFactory.decodeStream(inputStreamForBitmap)
            inputStreamForBitmap?.close()
            
            if (originalBitmap == null) return null
            
            // Rotate based on EXIF orientation
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0f // Could handle flip too
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> 0f
                else -> 0f
            }
            
            if (rotationDegrees == 0f) {
                originalBitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotatedBitmap = Bitmap.createBitmap(
                    originalBitmap, 0, 0,
                    originalBitmap.width, originalBitmap.height,
                    matrix, true
                )
                if (rotatedBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }
                rotatedBitmap
            }
        } catch (e: Exception) {
            null
        }
    }
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val PERSISTENT_NOTIFICATION_ID = 1002
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_NOTE = "extra_note"
        const val EXTRA_ACCOUNT_UID = "extra_account_uid"
        const val ACTION_DISMISS = "com.hearttoheart.ACTION_DISMISS"
        
        /**
         * Start the alarm service with the given category.
         */
        fun start(context: Context, category: MessageCategory, note: String = "", accountUid: String? = null) {
            val intent = Intent(context, AlarmService::class.java).apply {
                putExtra(EXTRA_CATEGORY, category.name)
                putExtra(EXTRA_NOTE, note)
                accountUid?.let { putExtra(EXTRA_ACCOUNT_UID, it) }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the alarm service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }
}
