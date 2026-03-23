package com.hearttoheart.app.data

/**
 * Defines the four notification categories with their properties.
 * Each category has different sound behavior and intensity levels.
 */
enum class MessageCategory(
    val displayName: String,
    val emoji: String,
    val description: String,
    val channelId: String,
    val intensity: Intensity
) {
    FLUTTER(
        displayName = "Flutter",
        emoji = "🦋",
        description = "Thinking of you",
        channelId = "channel_flutter",
        intensity = Intensity.LOW
    ),
    
    NUDGE(
        displayName = "Nudge",
        emoji = "👋",
        description = "Get their attention",
        channelId = "channel_nudge",
        intensity = Intensity.MEDIUM
    ),
    
    HEARTBEAT(
        displayName = "Heartbeat",
        emoji = "❤️",
        description = "Something important",
        channelId = "channel_heartbeat",
        intensity = Intensity.HIGH
    ),
    
    LIFELINE(
        displayName = "Lifeline",
        emoji = "🚨",
        description = "Need you now",
        channelId = "channel_lifeline",
        intensity = Intensity.CRITICAL
    );
    
    enum class Intensity {
        LOW,      // Silent/vibrate only
        MEDIUM,   // Standard notification
        HIGH,     // Bypasses silent mode
        CRITICAL  // Bypasses DND, volume ramp
    }
}

/**
 * Represents a message to be sent to the partner.
 */
data class HeartMessage(
    val category: MessageCategory,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    init {
        require(note.length <= 140) { "Note must be 140 characters or less" }
    }
}

/**
 * Represents a paired partner contact.
 */
data class Partner(
    val uid: String,
    val fcmToken: String,
    val displayName: String = "My Love",
    val pairedAt: Long = System.currentTimeMillis(),
    val encryptionKey: String? = null  // Shared secret for E2E encryption
)
