package com.hearttoheart.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import com.google.firebase.FirebaseApp

class HeartToHeartApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        
        // Create notification channels
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Flutter Channel - Silent/Vibrate only
        val flutterChannel = NotificationChannel(
            CHANNEL_FLUTTER,
            getString(R.string.channel_flutter),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Gentle thoughts from your partner"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 100, 50, 100) // Gentle short pattern
            setSound(null, null) // Silent
        }
        
        // Nudge Channel - Standard notification
        // Sound and vibration handled by AlarmService
        val nudgeChannel = NotificationChannel(
            CHANNEL_NUDGE,
            getString(R.string.channel_nudge),
            NotificationManager.IMPORTANCE_HIGH // High for heads-up display
        ).apply {
            description = "Standard notifications to get attention"
            enableVibration(false) // Vibration handled by AlarmService
            setSound(null, null) // Sound handled by AlarmService
        }
        
        // Heartbeat Channel - Bypasses silent mode
        // Sound is handled by AlarmService for volume control, not by the channel
        val heartbeatChannel = NotificationChannel(
            CHANNEL_HEARTBEAT,
            getString(R.string.channel_heartbeat),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Important messages that play sound even in silent mode"
            enableVibration(false) // Vibration handled by AlarmService
            setBypassDnd(false)
            setSound(null, null) // Sound handled by AlarmService
        }
        
        // Lifeline Channel - Critical alarm, bypasses everything
        // Sound is handled by AlarmService for volume escalation, not by the channel
        val lifelineChannel = NotificationChannel(
            CHANNEL_LIFELINE,
            getString(R.string.channel_lifeline),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Emergency alerts that bypass Do Not Disturb"
            enableVibration(false) // Vibration handled by AlarmService
            setBypassDnd(true)
            setSound(null, null) // Sound handled by AlarmService
        }
        
        notificationManager.createNotificationChannels(
            listOf(flutterChannel, nudgeChannel, heartbeatChannel, lifelineChannel)
        )
    }
    
    companion object {
        const val CHANNEL_FLUTTER = "channel_flutter"
        const val CHANNEL_NUDGE = "channel_nudge"
        const val CHANNEL_HEARTBEAT = "channel_heartbeat"
        const val CHANNEL_LIFELINE = "channel_lifeline"
    }
}
