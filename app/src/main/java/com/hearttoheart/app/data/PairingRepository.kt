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
        private const val MAX_ACCOUNTS = 10
        
        // Firestore collections
        private const val USERS_COLLECTION = "users"
        private const val PAIRING_REQUESTS_COLLECTION = "pairingRequests"
        
        // DataStore keys (new multi-account model)
        private val USER_ACCOUNTS_JSON_KEY = stringPreferencesKey("user_accounts_json")
        private val SELECTED_ACCOUNT_UID_KEY = stringPreferencesKey("selected_account_uid")

        // Legacy single-partner keys (kept for migration/back-compat)
        private val LEGACY_PARTNER_UID_KEY = stringPreferencesKey("partner_uid")
        private val LEGACY_PARTNER_FCM_TOKEN_KEY = stringPreferencesKey("partner_fcm_token")
        private val LEGACY_PARTNER_NAME_KEY = stringPreferencesKey("partner_name")
        private val LEGACY_PARTNER_PAIRED_AT_KEY = stringPreferencesKey("partner_paired_at")
        private val LEGACY_PARTNER_ENCRYPTION_KEY = stringPreferencesKey("partner_encryption_key")
        private val LEGACY_MY_DECRYPTION_KEY = stringPreferencesKey("my_decryption_key")
    }

    data class UserAccountEntry(
        val uid: String,
        val partnerUid: String? = null,
        val partnerFcmToken: String? = null,
        val partnerName: String? = null,
        val pairedAt: Long? = null,
        val partnerEncryptionKey: String? = null,
        val myDecryptionKey: String? = null
    ) {
        fun isPaired(): Boolean = !partnerUid.isNullOrBlank()
    }

    data class PairingAccountInfo(
        val uid: String,
        val myDecryptionKey: String?
    )

    private fun encodeAccounts(accounts: Map<String, UserAccountEntry>): String {
        val root = JSONObject()
        accounts.values.forEach { entry ->
            val obj = JSONObject().apply {
                put("uid", entry.uid)
                put("partnerUid", entry.partnerUid)
                put("partnerFcmToken", entry.partnerFcmToken)
                put("partnerName", entry.partnerName)
                put("pairedAt", entry.pairedAt)
                put("partnerEncryptionKey", entry.partnerEncryptionKey)
                put("myDecryptionKey", entry.myDecryptionKey)
            }
            root.put(entry.uid, obj)
        }
        return root.toString()
    }

    private fun decodeAccounts(json: String?): Map<String, UserAccountEntry> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            val keys = root.keys()
            val out = mutableMapOf<String, UserAccountEntry>()
            while (keys.hasNext()) {
                val uid = keys.next()
                val obj = root.optJSONObject(uid) ?: continue
                out[uid] = UserAccountEntry(
                    uid = obj.optString("uid", uid),
                    partnerUid = obj.optString("partnerUid").takeIf { it.isNotBlank() },
                    partnerFcmToken = obj.optString("partnerFcmToken").takeIf { it.isNotBlank() },
                    partnerName = obj.optString("partnerName").takeIf { it.isNotBlank() },
                    pairedAt = obj.optLong("pairedAt").takeIf { it > 0 },
                    partnerEncryptionKey = obj.optString("partnerEncryptionKey").takeIf { it.isNotBlank() },
                    myDecryptionKey = obj.optString("myDecryptionKey").takeIf { it.isNotBlank() }
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode accounts JSON", e)
            emptyMap()
        }
    }

    private suspend fun readAccounts(): Map<String, UserAccountEntry> {
        val prefs = context.dataStore.data.first()
        return decodeAccounts(prefs[USER_ACCOUNTS_JSON_KEY])
    }

    private suspend fun writeAccounts(accounts: Map<String, UserAccountEntry>) {
        context.dataStore.edit { prefs ->
            prefs[USER_ACCOUNTS_JSON_KEY] = encodeAccounts(accounts)
        }
    }

    /**
     * Best-effort migration from legacy single-partner storage into the new accounts map,
     * keyed by the currently signed-in anonymous UID.
     */
    private suspend fun migrateLegacyIfNeeded() {
        val prefs = context.dataStore.data.first()
        val alreadyMigrated = !prefs[USER_ACCOUNTS_JSON_KEY].isNullOrBlank()
        if (alreadyMigrated) return

        val currentUid = getCurrentUserId() ?: return
        val legacyPartnerUid = prefs[LEGACY_PARTNER_UID_KEY]
        val legacyPartnerToken = prefs[LEGACY_PARTNER_FCM_TOKEN_KEY]
        val legacyPartnerName = prefs[LEGACY_PARTNER_NAME_KEY]
        val legacyPairedAt = prefs[LEGACY_PARTNER_PAIRED_AT_KEY]?.toLongOrNull()
        val legacyPartnerEnc = prefs[LEGACY_PARTNER_ENCRYPTION_KEY]
        val legacyMyDec = prefs[LEGACY_MY_DECRYPTION_KEY]

        val entry = UserAccountEntry(
            uid = currentUid,
            partnerUid = legacyPartnerUid,
            partnerFcmToken = legacyPartnerToken,
            partnerName = legacyPartnerName,
            pairedAt = legacyPairedAt,
            partnerEncryptionKey = legacyPartnerEnc,
            myDecryptionKey = legacyMyDec
        )

        val accounts = if (legacyPartnerUid.isNullOrBlank() && legacyMyDec.isNullOrBlank()) {
            emptyMap()
        } else {
            mapOf(currentUid to entry)
        }

        context.dataStore.edit { editPrefs ->
            editPrefs[USER_ACCOUNTS_JSON_KEY] = encodeAccounts(accounts)
            editPrefs[SELECTED_ACCOUNT_UID_KEY] = currentUid
        }
    }

    private suspend fun getSelectedAccountUidInternal(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[SELECTED_ACCOUNT_UID_KEY]
    }

    suspend fun setSelectedAccountUid(uid: String?) {
        context.dataStore.edit { prefs ->
            if (uid.isNullOrBlank()) {
                prefs.remove(SELECTED_ACCOUNT_UID_KEY)
            } else {
                prefs[SELECTED_ACCOUNT_UID_KEY] = uid
            }
        }
    }

    private suspend fun getAccountEntry(uid: String): UserAccountEntry? {
        migrateLegacyIfNeeded()
        return readAccounts()[uid]
    }

    private suspend fun upsertAccountEntry(entry: UserAccountEntry) {
        migrateLegacyIfNeeded()
        val accounts = readAccounts().toMutableMap()
        accounts[entry.uid] = entry
        writeAccounts(accounts)
    }

    /**
     * Create a fresh anonymous Firebase account to be used for a new pairing.
     * This intentionally switches the current FirebaseAuth user.
     *
     * The returned key is the one embedded in the QR and stored locally for decrypting incoming messages.
     */
    suspend fun createFreshAnonymousAccountForPairing(generateQrKey: Boolean = true): Result<PairingAccountInfo> {
        return try {
            migrateLegacyIfNeeded()

            val accounts = readAccounts()
            if (accounts.size >= MAX_ACCOUNTS) {
                return Result.failure(IllegalStateException("Maximum of $MAX_ACCOUNTS pairings reached"))
            }

            val authResult = auth.signInAnonymously().await()
            val newUid = authResult.user?.uid ?: return Result.failure(Exception("Failed to create anonymous account"))

            val myKey = if (generateQrKey) EncryptionHelper.generateKey() else null
            val entry = UserAccountEntry(
                uid = newUid,
                myDecryptionKey = myKey
            )
            upsertAccountEntry(entry)
            setSelectedAccountUid(newUid)

            // Best-effort; failure shouldn't block QR generation
            initializeUserDocument()

            Log.d(TAG, "Created fresh anonymous account for pairing: $newUid (qrKey=${generateQrKey})")
            Result.success(PairingAccountInfo(uid = newUid, myDecryptionKey = myKey))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create fresh anonymous account", e)
            Result.failure(e)
        }
    }
    
    /**
     * Save our own decryption key (used when receiving messages).
     */
    suspend fun saveMyDecryptionKey(key: String) {
        val uid = getCurrentUserId() ?: run {
            Log.w(TAG, "Cannot save decryption key: not signed in")
            return
        }
        migrateLegacyIfNeeded()
        val existing = getAccountEntry(uid) ?: UserAccountEntry(uid = uid)
        upsertAccountEntry(existing.copy(myDecryptionKey = key))
        Log.d(TAG, "Saved my decryption key for account: $uid")
    }
    
    /**
     * Get our decryption key for receiving messages.
     */
    fun getMyDecryptionKey(): Flow<String?> {
        return context.dataStore.data.map { prefs ->
            // This is keyed by the currently active FirebaseAuth user.
            // (Per-sender routing is implemented in a later PR.)
            val uid = auth.currentUser?.uid
            if (uid.isNullOrBlank()) return@map null
            val accounts = decodeAccounts(prefs[USER_ACCOUNTS_JSON_KEY])
            accounts[uid]?.myDecryptionKey ?: prefs[LEGACY_MY_DECRYPTION_KEY]
        }
    }
    
    /**
     * Get the current user's UID.
     */
    fun getCurrentUserId(): String? = auth.currentUser?.uid
    
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
        
        if (targetUserId == myUserId) {
            return Result.failure(Exception("Cannot pair with yourself"))
        }

        // One-time QR enforcement: a paired account cannot initiate a new pairing.
        val myEntry = getAccountEntry(myUserId)
        if (myEntry?.isPaired() == true) {
            return Result.failure(Exception("This account is already paired and cannot start a new pairing"))
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

        // One-time QR enforcement: a paired account should not accept a new pairing request.
        val myEntry = getAccountEntry(myUserId)
        if (myEntry?.isPaired() == true) {
            return Result.failure(Exception("This account is already paired"))
        }
        
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
    fun observeMyRequestStatus(targetUserId: String): Flow<PairingStatus> = callbackFlow {
        val myUserId = getCurrentUserId()
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
    suspend fun completePairing(partnerUserId: String): Result<Partner> {
        val myUserId = getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        
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
        val myUid = getCurrentUserId() ?: run {
            Log.w(TAG, "Cannot save partner: not signed in")
            return
        }
        migrateLegacyIfNeeded()

        val existing = getAccountEntry(myUid) ?: UserAccountEntry(uid = myUid)
        upsertAccountEntry(
            existing.copy(
                partnerUid = partner.uid,
                partnerFcmToken = partner.fcmToken,
                partnerName = partner.displayName,
                pairedAt = partner.pairedAt,
                partnerEncryptionKey = partner.encryptionKey
            )
        )

        // Also populate legacy keys for backward compatibility with screens not yet refactored.
        context.dataStore.edit { prefs ->
            prefs[LEGACY_PARTNER_UID_KEY] = partner.uid
            prefs[LEGACY_PARTNER_FCM_TOKEN_KEY] = partner.fcmToken
            prefs[LEGACY_PARTNER_NAME_KEY] = partner.displayName
            prefs[LEGACY_PARTNER_PAIRED_AT_KEY] = partner.pairedAt.toString()
            if (partner.encryptionKey != null) {
                prefs[LEGACY_PARTNER_ENCRYPTION_KEY] = partner.encryptionKey
            } else {
                prefs.remove(LEGACY_PARTNER_ENCRYPTION_KEY)
            }
        }

        Log.d(TAG, "Partner saved locally for account=$myUid partner=${partner.uid}, encrypted: ${partner.encryptionKey != null}")
    }
    
    /**
     * Get the locally saved partner.
     */
    fun getPartner(): Flow<Partner?> {
        return context.dataStore.data.map { prefs ->
            val activeUid = prefs[SELECTED_ACCOUNT_UID_KEY] ?: auth.currentUser?.uid
            if (!activeUid.isNullOrBlank()) {
                val accounts = decodeAccounts(prefs[USER_ACCOUNTS_JSON_KEY])
                val entry = accounts[activeUid]
                if (entry?.partnerUid != null && entry.partnerFcmToken != null) {
                    return@map Partner(
                        uid = entry.partnerUid,
                        fcmToken = entry.partnerFcmToken,
                        displayName = entry.partnerName ?: "My Love",
                        pairedAt = entry.pairedAt ?: 0L,
                        encryptionKey = entry.partnerEncryptionKey
                    )
                }
            }

            // Legacy fallback
            val uid = prefs[LEGACY_PARTNER_UID_KEY] ?: return@map null
            val fcmToken = prefs[LEGACY_PARTNER_FCM_TOKEN_KEY] ?: return@map null
            val name = prefs[LEGACY_PARTNER_NAME_KEY] ?: "My Love"
            val pairedAt = prefs[LEGACY_PARTNER_PAIRED_AT_KEY]?.toLongOrNull() ?: 0L
            val encryptionKey = prefs[LEGACY_PARTNER_ENCRYPTION_KEY]
            Partner(uid = uid, fcmToken = fcmToken, displayName = name, pairedAt = pairedAt, encryptionKey = encryptionKey)
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
        val myUid = getCurrentUserId() ?: return
        migrateLegacyIfNeeded()
        val existing = getAccountEntry(myUid) ?: return
        if (!existing.partnerUid.isNullOrBlank()) {
            upsertAccountEntry(existing.copy(partnerFcmToken = newToken))
        }

        // Legacy for now
        context.dataStore.edit { prefs ->
            if (prefs[LEGACY_PARTNER_UID_KEY] != null) {
                prefs[LEGACY_PARTNER_FCM_TOKEN_KEY] = newToken
            }
        }
    }
    
    /**
     * Clear partner data (unpair).
     */
    suspend fun clearPartner() {
        val myUid = getCurrentUserId()
        migrateLegacyIfNeeded()
        if (!myUid.isNullOrBlank()) {
            val accounts = readAccounts().toMutableMap()
            val existing = accounts[myUid]
            if (existing != null) {
                accounts[myUid] = existing.copy(
                    partnerUid = null,
                    partnerFcmToken = null,
                    partnerName = null,
                    pairedAt = null,
                    partnerEncryptionKey = null,
                    myDecryptionKey = null
                )
                writeAccounts(accounts)
            }
        }

        // Legacy clear (until all screens are migrated)
        context.dataStore.edit { prefs ->
            prefs.remove(LEGACY_PARTNER_UID_KEY)
            prefs.remove(LEGACY_PARTNER_FCM_TOKEN_KEY)
            prefs.remove(LEGACY_PARTNER_NAME_KEY)
            prefs.remove(LEGACY_PARTNER_PAIRED_AT_KEY)
            prefs.remove(LEGACY_PARTNER_ENCRYPTION_KEY)
            prefs.remove(LEGACY_MY_DECRYPTION_KEY)
        }

        Log.d(TAG, "Partner cleared")
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
