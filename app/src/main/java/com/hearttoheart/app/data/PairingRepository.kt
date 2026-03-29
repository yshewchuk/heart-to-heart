package com.hearttoheart.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

// DataStore for local preferences
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "heart_to_heart_prefs")

/**
 * Repository for handling pairing operations.
 * Manages Firestore pairing handshake and local partner storage.
 */
class PairingRepository(private val context: Context) {
    
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    
    private var pairingListener: ListenerRegistration? = null
    
    companion object {
        private const val TAG = "PairingRepository"
        
        // Firestore collections
        private const val USERS_COLLECTION = "users"
        private const val PAIRING_REQUESTS_COLLECTION = "pairingRequests"
        
        // DataStore keys
        private val PARTNER_UID_KEY = stringPreferencesKey("partner_uid")
        private val PARTNER_FCM_TOKEN_KEY = stringPreferencesKey("partner_fcm_token")
        private val PARTNER_NAME_KEY = stringPreferencesKey("partner_name")
        private val PARTNER_PAIRED_AT_KEY = stringPreferencesKey("partner_paired_at")
        private val PARTNER_ENCRYPTION_KEY = stringPreferencesKey("partner_encryption_key")
        private val MY_DECRYPTION_KEY = stringPreferencesKey("my_decryption_key")
        private val USER_ACCOUNTS_KEY = stringPreferencesKey("user_accounts")
        private const val MAX_USER_ACCOUNTS = 10
    }

    data class UserAccountEntry(
        val anonymousUid: String,
        val pairedPartnerUid: String? = null,
        val pairedAt: Long? = null,
        val encryptionKey: String? = null,
        val displayName: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("anonymousUid", anonymousUid)
            put("pairedPartnerUid", pairedPartnerUid)
            put("pairedAt", pairedAt)
            put("encryptionKey", encryptionKey)
            put("displayName", displayName)
        }

        companion object {
            fun fromJson(json: JSONObject): UserAccountEntry? {
                return try {
                    UserAccountEntry(
                        anonymousUid = json.getString("anonymousUid"),
                        pairedPartnerUid = json.optString("pairedPartnerUid", null),
                        pairedAt = if (json.has("pairedAt") && !json.isNull("pairedAt")) {
                            json.getLong("pairedAt")
                        } else {
                            null
                        },
                        encryptionKey = json.optString("encryptionKey", null),
                        displayName = json.optString("displayName", null)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    
    /**
     * Save our own decryption key (used when receiving messages).
     */
    suspend fun saveMyDecryptionKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[MY_DECRYPTION_KEY] = key
        }
        Log.d(TAG, "Saved my decryption key")
    }
    
    /**
     * Get our decryption key for receiving messages.
     */
    fun getMyDecryptionKey(): Flow<String?> {
        return context.dataStore.data.map { prefs ->
            prefs[MY_DECRYPTION_KEY]
        }
    }
    
    /**
     * Get the current user's UID.
     */
    fun getCurrentUserId(): String? = auth.currentUser?.uid

    suspend fun createAnonymousAccountForPairing(
        displayName: String? = null
    ): Result<UserAccountEntry> {
        return try {
            val existingAccounts = getUserAccounts().first()
            if (existingAccounts.size >= MAX_USER_ACCOUNTS) {
                return Result.failure(Exception("You can only have up to $MAX_USER_ACCOUNTS pairings"))
            }

            val signInResult = auth.signInAnonymously().await()
            val uid = signInResult.user?.uid
                ?: return Result.failure(Exception("Failed to create anonymous account"))

            val encryptionKey = EncryptionHelper.generateKey()
            saveMyDecryptionKey(encryptionKey)
            initializeUserDocument()

            val entry = UserAccountEntry(
                anonymousUid = uid,
                encryptionKey = encryptionKey,
                displayName = displayName
            )
            saveOrUpdateUserAccount(entry)

            Log.d(TAG, "Created anonymous pairing account: $uid")
            Result.success(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating anonymous pairing account", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get the current FCM token.
     */
    suspend fun getFcmToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get FCM token", e)
            null
        }
    }
    
    /**
     * Initialize or update the current user's document in Firestore.
     * Called when the app starts to ensure the user document exists.
     */
    suspend fun initializeUserDocument(): Result<Unit> {
        val userId = getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        val fcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        
        return try {
            val userDoc = hashMapOf(
                "fcmToken" to fcmToken,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )
            
            // Use set with merge to create or update
            firestore.collection(USERS_COLLECTION)
                .document(userId)
                .set(userDoc, com.google.firebase.firestore.SetOptions.merge())
                .await()
            
            Log.d(TAG, "User document initialized: $userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize user document", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send a pairing request to another user.
     * Called after scanning their QR code.
     * 
     * @param targetUserId The UID of the user to pair with (from QR code)
     * @param theirEncryptionKey The encryption key from the QR code (generated by the other user)
     */
    suspend fun sendPairingRequest(targetUserId: String, theirEncryptionKey: String? = null, verificationCode: String? = null): Result<Unit> {
        val myUserId = getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        val myAccount = getUserAccounts().first()[myUserId]
        if (myAccount?.pairedPartnerUid != null) {
            return Result.failure(Exception("This account is already paired and cannot send a new request"))
        }
        
        if (targetUserId == myUserId) {
            return Result.failure(Exception("Cannot pair with yourself"))
        }
        
        // Generate our own encryption key to send back (for them to use when sending to us)
        val myEncryptionKey = EncryptionHelper.generateKey()
        
        // Save our key for decrypting messages we receive
        saveMyDecryptionKey(myEncryptionKey)
        
        return try {
            val request = hashMapOf(
                "requesterUid" to myUserId,
                "requesterFcmToken" to myFcmToken,
                "requesterEncryptionKey" to myEncryptionKey,  // Our key for them to use when sending to us
                "verificationCode" to (verificationCode ?: ""),  // Verification code from QR
                "requestedAt" to com.google.firebase.Timestamp.now(),
                "status" to "pending"
            )
            
            // Write to the target user's pairingRequests subcollection
            firestore.collection(USERS_COLLECTION)
                .document(targetUserId)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(myUserId)
                .set(request)
                .await()
            
            // Save their encryption key locally (we'll use it when sending to them)
            if (theirEncryptionKey != null) {
                saveEncryptionKeyTemporarily(targetUserId, theirEncryptionKey)
            }
            
            Log.d(TAG, "Pairing request sent to: $targetUserId with encryption")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send pairing request", e)
            Result.failure(e)
        }
    }
    
    // Temporary storage for encryption key during pairing handshake
    private var pendingEncryptionKey: Pair<String, String>? = null  // (targetUserId, encryptionKey)
    
    private fun saveEncryptionKeyTemporarily(targetUserId: String, key: String) {
        pendingEncryptionKey = Pair(targetUserId, key)
    }
    
    fun getPendingEncryptionKey(targetUserId: String): String? {
        return if (pendingEncryptionKey?.first == targetUserId) pendingEncryptionKey?.second else null
    }
    
    /**
     * Listen for incoming pairing requests.
     * Returns a Flow of pairing requests.
     */
    fun observePairingRequests(): Flow<List<PairingRequest>> = callbackFlow {
        val userId = getCurrentUserId()
        if (userId == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        
        val listener = firestore.collection(USERS_COLLECTION)
            .document(userId)
            .collection(PAIRING_REQUESTS_COLLECTION)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to pairing requests", error)
                    return@addSnapshotListener
                }
                
                val requests = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        PairingRequest(
                            requesterUid = doc.getString("requesterUid") ?: return@mapNotNull null,
                            requesterFcmToken = doc.getString("requesterFcmToken") ?: return@mapNotNull null,
                            requesterEncryptionKey = doc.getString("requesterEncryptionKey"),
                            verificationCode = doc.getString("verificationCode"),
                            requestedAt = doc.getTimestamp("requestedAt")?.toDate()?.time ?: 0L,
                            status = doc.getString("status") ?: "pending"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing pairing request", e)
                        null
                    }
                } ?: emptyList()
                
                trySend(requests)
            }
        
        awaitClose { listener.remove() }
    }
    
    /**
     * Accept a pairing request.
     * This completes the pairing handshake.
     * 
     * @param request The pairing request to accept
     * @param myEncryptionKey Our encryption key (from the QR code we showed)
     */
    suspend fun acceptPairingRequest(request: PairingRequest, myEncryptionKey: String? = null): Result<Unit> {
        val myUserId = getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        
        return try {
            // 1. Update the request status to accepted and include our encryption key
            val updateData = hashMapOf<String, Any>(
                "status" to "accepted"
            )
            if (myEncryptionKey != null) {
                updateData["accepterEncryptionKey"] = myEncryptionKey
            }
            
            firestore.collection(USERS_COLLECTION)
                .document(myUserId)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(request.requesterUid)
                .update(updateData)
                .await()
            
            // 2. Update my user document with partner info
            firestore.collection(USERS_COLLECTION)
                .document(myUserId)
                .update("partnerId", request.requesterUid)
                .await()
            
            // 3. Send a reciprocal pairing confirmation to the requester
            val confirmation = hashMapOf(
                "requesterUid" to myUserId,
                "requesterFcmToken" to myFcmToken,
                "requesterEncryptionKey" to (myEncryptionKey ?: ""),  // Our key for them to receive
                "requestedAt" to com.google.firebase.Timestamp.now(),
                "status" to "accepted"  // Already accepted
            )
            
            firestore.collection(USERS_COLLECTION)
                .document(request.requesterUid)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(myUserId)
                .set(confirmation)
                .await()
            
            // 4. Save partner locally with their encryption key
            // We use THEIR key when sending TO them
            savePartnerLocally(
                Partner(
                    uid = request.requesterUid,
                    fcmToken = request.requesterFcmToken,
                    displayName = "My Love",
                    pairedAt = System.currentTimeMillis(),
                    encryptionKey = request.requesterEncryptionKey  // Their key
                )
            )
            val storedAccount = getUserAccounts().first()[myUserId]
            saveOrUpdateUserAccount(
                UserAccountEntry(
                    anonymousUid = myUserId,
                    pairedPartnerUid = request.requesterUid,
                    pairedAt = System.currentTimeMillis(),
                    encryptionKey = myEncryptionKey ?: storedAccount?.encryptionKey,
                    displayName = storedAccount?.displayName
                )
            )
            
            Log.d(TAG, "Pairing accepted with: ${request.requesterUid}, encryption: ${request.requesterEncryptionKey != null}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept pairing request", e)
            Result.failure(e)
        }
    }
    
    /**
     * Decline a pairing request.
     */
    suspend fun declinePairingRequest(request: PairingRequest): Result<Unit> {
        val myUserId = getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        
        return try {
            // Delete the pairing request
            firestore.collection(USERS_COLLECTION)
                .document(myUserId)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(request.requesterUid)
                .delete()
                .await()
            
            Log.d(TAG, "Pairing request declined from: ${request.requesterUid}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decline pairing request", e)
            Result.failure(e)
        }
    }
    
    /**
     * Listen for when our pairing request gets accepted.
     * Called after we send a request to wait for acceptance.
     */
    fun observeMyRequestStatus(targetUserId: String, requesterUid: String? = null): Flow<PairingStatus> = callbackFlow {
        val myUserId = requesterUid ?: getCurrentUserId()
        if (myUserId == null) {
            trySend(PairingStatus.Error("Not signed in"))
            close()
            return@callbackFlow
        }
        
        // Listen to the request we sent
        val listener = firestore.collection(USERS_COLLECTION)
            .document(targetUserId)
            .collection(PAIRING_REQUESTS_COLLECTION)
            .document(myUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to request status", error)
                    trySend(PairingStatus.Error(error.message ?: "Unknown error"))
                    return@addSnapshotListener
                }
                
                if (snapshot == null || !snapshot.exists()) {
                    trySend(PairingStatus.Pending)
                    return@addSnapshotListener
                }
                
                val status = snapshot.getString("status")
                when (status) {
                    "accepted" -> trySend(PairingStatus.Accepted)
                    "rejected" -> trySend(PairingStatus.Rejected)
                    else -> trySend(PairingStatus.Pending)
                }
            }
        
        awaitClose { listener.remove() }
    }
    
    /**
     * Complete pairing after our request was accepted.
     * Fetches the partner's FCM token and encryption key, saves locally.
     */
    suspend fun completePairing(partnerUserId: String, accountUid: String? = null): Result<Partner> {
        val myUserId = accountUid ?: getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        
        return try {
            // Get partner's user document to get their FCM token
            val partnerDoc = firestore.collection(USERS_COLLECTION)
                .document(partnerUserId)
                .get()
                .await()
            
            val partnerFcmToken = partnerDoc.getString("fcmToken")
                ?: return Result.failure(Exception("Partner FCM token not found"))
            
            // Get the accepted request to retrieve the encryption key they sent
            val requestDoc = firestore.collection(USERS_COLLECTION)
                .document(partnerUserId)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(myUserId)
                .get()
                .await()
            
            // The accepter's encryption key is what we use to send TO them
            val partnerEncryptionKey = requestDoc.getString("accepterEncryptionKey")
                ?: getPendingEncryptionKey(partnerUserId)  // Fall back to key from QR code
            
            // Update my document with partner ID
            firestore.collection(USERS_COLLECTION)
                .document(myUserId)
                .update("partnerId", partnerUserId)
                .await()
            
            // Save partner locally with their encryption key
            val partner = Partner(
                uid = partnerUserId,
                fcmToken = partnerFcmToken,
                displayName = "My Love",
                pairedAt = System.currentTimeMillis(),
                encryptionKey = partnerEncryptionKey
            )
            savePartnerLocally(partner)
            val storedAccount = getUserAccounts().first()[myUserId]
            saveOrUpdateUserAccount(
                UserAccountEntry(
                    anonymousUid = myUserId,
                    pairedPartnerUid = partnerUserId,
                    pairedAt = System.currentTimeMillis(),
                    encryptionKey = storedAccount?.encryptionKey,
                    displayName = storedAccount?.displayName
                )
            )
            
            // Clear temporary key storage
            pendingEncryptionKey = null
            
            Log.d(TAG, "Pairing completed with: $partnerUserId, encryption: ${partnerEncryptionKey != null}")
            Result.success(partner)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete pairing", e)
            Result.failure(e)
        }
    }
    
    /**
     * Save partner info to local DataStore.
     */
    suspend fun savePartnerLocally(partner: Partner) {
        context.dataStore.edit { prefs ->
            prefs[PARTNER_UID_KEY] = partner.uid
            prefs[PARTNER_FCM_TOKEN_KEY] = partner.fcmToken
            prefs[PARTNER_NAME_KEY] = partner.displayName
            prefs[PARTNER_PAIRED_AT_KEY] = partner.pairedAt.toString()
            if (partner.encryptionKey != null) {
                prefs[PARTNER_ENCRYPTION_KEY] = partner.encryptionKey
            }
        }
        Log.d(TAG, "Partner saved locally: ${partner.uid}, encrypted: ${partner.encryptionKey != null}")
    }
    
    /**
     * Get the locally saved partner.
     */
    fun getPartner(): Flow<Partner?> {
        return context.dataStore.data.map { prefs ->
            val uid = prefs[PARTNER_UID_KEY] ?: return@map null
            val fcmToken = prefs[PARTNER_FCM_TOKEN_KEY] ?: return@map null
            val name = prefs[PARTNER_NAME_KEY] ?: "My Love"
            val pairedAt = prefs[PARTNER_PAIRED_AT_KEY]?.toLongOrNull() ?: 0L
            val encryptionKey = prefs[PARTNER_ENCRYPTION_KEY]
            
            Partner(
                uid = uid,
                fcmToken = fcmToken,
                displayName = name,
                pairedAt = pairedAt,
                encryptionKey = encryptionKey
            )
        }
    }
    
    /**
     * Check if we have a paired partner.
     */
    suspend fun hasPairedPartner(): Boolean {
        return getPartner().first() != null
    }
    
    /**
     * Update partner's FCM token (in case it changes).
     */
    suspend fun updatePartnerFcmToken(newToken: String) {
        context.dataStore.edit { prefs ->
            if (prefs[PARTNER_UID_KEY] != null) {
                prefs[PARTNER_FCM_TOKEN_KEY] = newToken
            }
        }
    }
    
    /**
     * Clear partner data (unpair).
     */
    suspend fun clearPartner() {
        context.dataStore.edit { prefs ->
            prefs.remove(PARTNER_UID_KEY)
            prefs.remove(PARTNER_FCM_TOKEN_KEY)
            prefs.remove(PARTNER_NAME_KEY)
            prefs.remove(PARTNER_PAIRED_AT_KEY)
            prefs.remove(PARTNER_ENCRYPTION_KEY)
            prefs.remove(MY_DECRYPTION_KEY)
        }
        Log.d(TAG, "Partner cleared")
    }

    fun getUserAccounts(): Flow<Map<String, UserAccountEntry>> {
        return context.dataStore.data.map { prefs ->
            parseUserAccounts(prefs[USER_ACCOUNTS_KEY])
        }
    }

    private suspend fun saveOrUpdateUserAccount(account: UserAccountEntry) {
        context.dataStore.edit { prefs ->
            val existing = parseUserAccounts(prefs[USER_ACCOUNTS_KEY]).toMutableMap()
            existing[account.anonymousUid] = account
            prefs[USER_ACCOUNTS_KEY] = serializeUserAccounts(existing)
        }
    }

    private fun parseUserAccounts(rawJson: String?): Map<String, UserAccountEntry> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(rawJson)
            root.keys().asSequence().mapNotNull { uid ->
                val accountJson = root.optJSONObject(uid) ?: return@mapNotNull null
                UserAccountEntry.fromJson(accountJson)?.let { uid to it }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeUserAccounts(accounts: Map<String, UserAccountEntry>): String {
        val root = JSONObject()
        accounts.forEach { (uid, account) ->
            root.put(uid, account.toJson())
        }
        return root.toString()
    }
    
    /**
     * Clean up listeners.
     */
    fun cleanup() {
        pairingListener?.remove()
        pairingListener = null
    }
}

/**
 * Represents a pairing request from another user.
 */
data class PairingRequest(
    val requesterUid: String,
    val requesterFcmToken: String,
    val requesterEncryptionKey: String?,  // Their key for E2E encryption
    val verificationCode: String?,  // 6-digit code for pairing verification
    val requestedAt: Long,
    val status: String
)

/**
 * Generate a 6-digit verification code for pairing.
 */
fun generateVerificationCode(): String {
    return (100000..999999).random().toString()
}

/**
 * Status of a pairing request.
 */
sealed class PairingStatus {
    object Pending : PairingStatus()
    object Accepted : PairingStatus()
    object Rejected : PairingStatus()
    data class Error(val message: String) : PairingStatus()
}
