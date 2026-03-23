package com.hearttoheart.app.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hearttoheart.app.data.EncryptionHelper
import com.hearttoheart.app.data.MessageCategory
import com.hearttoheart.app.data.MessageHistory
import com.hearttoheart.app.data.PairingRepository
import com.hearttoheart.app.data.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging service that handles incoming heart signals.
 * Processes the FCM data payload and triggers the appropriate alarm type.
 */
class HeartFCMService : FirebaseMessagingService() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageHistory by lazy { MessageHistory(applicationContext) }
    private val pairingRepository by lazy { PairingRepository(applicationContext) }
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        // TODO: Save token locally and update in Firestore for paired partner
    }
    
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received from: ${message.from}")
        
        // Extract data payload
        val data = message.data
        val categoryStr = data["category"] ?: "NUDGE"
        var note = data["note"] ?: ""
        val senderUid = data["sender_uid"] ?: ""
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
        val isEncrypted = data["encrypted"]?.toBoolean() ?: false
        
        Log.d(TAG, "Category: $categoryStr, Encrypted: $isEncrypted, Sender: $senderUid")
        
        // Parse category
        val category = try {
            MessageCategory.valueOf(categoryStr.uppercase())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown category: $categoryStr, defaulting to NUDGE")
            MessageCategory.NUDGE
        }
        
        // Process message (decrypt if needed) and save/display
        serviceScope.launch {
            // Decrypt note if encrypted
            if (isEncrypted && note.isNotEmpty()) {
                try {
                    val decryptionKey = pairingRepository.getMyDecryptionKey().first()
                    if (decryptionKey != null) {
                        val decrypted = EncryptionHelper.decrypt(note, decryptionKey)
                        Log.d(TAG, "Note decrypted successfully")
                        note = decrypted
                    } else {
                        Log.w(TAG, "No decryption key available")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt note", e)
                }
            }
            
            // Save to local history
            try {
                messageHistory.saveMessage(
                    StoredMessage(
                        category = category,
                        note = note,
                        timestamp = timestamp,
                        isSent = false
                    )
                )
                Log.d(TAG, "Message saved to history")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save message to history", e)
            }
            
            // Trigger the alarm service (on main thread via the service context)
            AlarmService.start(this@HeartFCMService, category, note)
        }
    }
    
    companion object {
        private const val TAG = "HeartFCMService"
    }
}
