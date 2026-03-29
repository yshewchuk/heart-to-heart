package com.yurishewchuk.hearttoheart.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

/**
 * Handles sending heart messages to partners via Firebase Cloud Functions.
 */
class MessageSender(private val context: Context? = null) {
    
    private val functions: FirebaseFunctions = Firebase.functions
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val messageHistory: MessageHistory? = context?.let { MessageHistory(it) }
    
    companion object {
        private const val TAG = "MessageSender"
    }
    
    /**
     * Send a heart message to the partner.
     * 
     * @param partner The partner to send to (contains FCM token and encryption key)
     * @param message The message to send (category + note)
     * @return Result indicating success or failure
     */
    suspend fun sendMessage(
        senderAccountUid: String,
        partner: Partner,
        message: HeartMessage
    ): Result<String> {
        if (auth.currentUser == null) {
            Log.e(TAG, "Cannot send message: not authenticated")
            return Result.failure(Exception("Not authenticated"))
        }
        
        Log.d(TAG, "Sending ${message.category.name} to partner ${partner.uid}")
        
        // Encrypt the note if we have an encryption key
        val noteToSend = if (partner.encryptionKey != null && message.note.isNotEmpty()) {
            val encrypted = EncryptionHelper.encrypt(message.note, partner.encryptionKey)
            if (encrypted != null) {
                Log.d(TAG, "Note encrypted successfully")
                encrypted
            } else {
                Log.w(TAG, "Encryption failed, sending plaintext")
                message.note
            }
        } else {
            message.note
        }
        
        val targetFcmToken = if (partner.fcmToken.isNotBlank()) {
            partner.fcmToken
        } else {
            fetchPartnerFcmToken(partner.uid)
                ?: return Result.failure(Exception("Partner FCM token not found"))
        }

        val data = hashMapOf(
            "targetFcmToken" to targetFcmToken,
            "category" to message.category.name,
            "note" to noteToSend,
            "senderUid" to senderAccountUid,
            "encrypted" to (partner.encryptionKey != null && message.note.isNotEmpty())
        )
        
        return try {
            val result = functions
                .getHttpsCallable("sendHeart")
                .call(data)
                .await()
            
            @Suppress("UNCHECKED_CAST")
            val response = result.data as? Map<String, Any>
            val messageId = response?.get("messageId") as? String ?: "unknown"
            
            // Save to local history (with original plaintext note)
            messageHistory?.saveMessage(
                senderAccountUid,
                StoredMessage(
                    category = message.category,
                    note = message.note,  // Store plaintext locally
                    timestamp = message.timestamp,
                    isSent = true
                )
            )
            
            Log.d(TAG, "Message sent successfully: $messageId")
            Result.success(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update the user's FCM token in Firestore via Cloud Function.
     * Call this when the FCM token changes.
     */
    suspend fun updateFcmToken(token: String): Result<Unit> {
        val data = hashMapOf(
            "fcmToken" to token
        )
        
        return try {
            functions
                .getHttpsCallable("updateFcmToken")
                .call(data)
                .await()
            
            Log.d(TAG, "FCM token updated successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update FCM token", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchPartnerFcmToken(partnerUid: String): String? {
        return try {
            val userDoc = firestore.collection("users")
                .document(partnerUid)
                .get()
                .await()
            userDoc.getString("fcmToken")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch partner FCM token", e)
            null
        }
    }
}
