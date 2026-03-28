package com.hearttoheart.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
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
    
    private val defaultAuth = FirebaseAuth.getInstance()
    
    private var pairingListener: ListenerRegistration? = null
    
    companion object {
        private const val TAG = "PairingRepository"
        
        // Firestore collections
        private const val USERS_COLLECTION = "users"
        private const val PAIRING_REQUESTS_COLLECTION = "pairingRequests"
        
        // DataStore keys (multi-account)
        private val USER_ACCOUNTS_JSON_KEY = stringPreferencesKey("user_accounts_json")
        private val SELECTED_ACCOUNT_UID_KEY = stringPreferencesKey("selected_account_uid")

        // Legacy single-partner keys (kept for backward compatibility/migration)
        private val PARTNER_UID_KEY = stringPreferencesKey("partner_uid")
        private val PARTNER_FCM_TOKEN_KEY = stringPreferencesKey("partner_fcm_token")
        private val PARTNER_NAME_KEY = stringPreferencesKey("partner_name")
        private val PARTNER_PAIRED_AT_KEY = stringPreferencesKey("partner_paired_at")
        private val PARTNER_ENCRYPTION_KEY = stringPreferencesKey("partner_encryption_key")
        private val MY_DECRYPTION_KEY = stringPreferencesKey("my_decryption_key")
    }

    data class UserAccountEntry(
        val anonymousUid: String,
        val firebaseAppName: String,
        val pairedPartnerUid: String? = null,
        val pairedPartnerFcmToken: String? = null,
        val pairedPartnerName: String? = null,
        val pairedAt: Long? = null,
        val partnerEncryptionKey: String? = null, // used to send TO partner
        val myDecryptionKey: String? = null // used to decrypt messages sent TO this account
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("anonymousUid", anonymousUid)
            put("firebaseAppName", firebaseAppName)
            put("pairedPartnerUid", pairedPartnerUid)
            put("pairedPartnerFcmToken", pairedPartnerFcmToken)
            put("pairedPartnerName", pairedPartnerName)
            put("pairedAt", pairedAt)
            put("partnerEncryptionKey", partnerEncryptionKey)
            put("myDecryptionKey", myDecryptionKey)
        }

        companion object {
            fun fromJson(json: JSONObject): UserAccountEntry? {
                return try {
                    UserAccountEntry(
                        anonymousUid = json.getString("anonymousUid"),
                        firebaseAppName = json.getString("firebaseAppName"),
                        pairedPartnerUid = json.optString("pairedPartnerUid").ifBlank { null },
                        pairedPartnerFcmToken = json.optString("pairedPartnerFcmToken").ifBlank { null },
                        pairedPartnerName = json.optString("pairedPartnerName").ifBlank { null },
                        pairedAt = json.optLong("pairedAt").takeIf { it != 0L },
                        partnerEncryptionKey = json.optString("partnerEncryptionKey").ifBlank { null },
                        myDecryptionKey = json.optString("myDecryptionKey").ifBlank { null }
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    internal fun getOrInitFirebaseApp(appName: String): FirebaseApp {
        return try {
            FirebaseApp.getInstance(appName)
        } catch (_: IllegalStateException) {
            val defaultApp = FirebaseApp.getInstance()
            FirebaseApp.initializeApp(context, defaultApp.options, appName)
        }
    }

    private suspend fun getAccountEntryOrNull(accountUid: String): UserAccountEntry? {
        return readAccountsMap()[accountUid]
    }

    private suspend fun authForAccount(accountUid: String): FirebaseAuth {
        val entry = getAccountEntryOrNull(accountUid) ?: throw IllegalStateException("Unknown account UID")
        val app = getOrInitFirebaseApp(entry.firebaseAppName)
        return FirebaseAuth.getInstance(app)
    }

    private suspend fun firestoreForAccount(accountUid: String): FirebaseFirestore {
        val entry = getAccountEntryOrNull(accountUid) ?: throw IllegalStateException("Unknown account UID")
        val app = getOrInitFirebaseApp(entry.firebaseAppName)
        return FirebaseFirestore.getInstance(app)
    }

    private suspend fun readAccountsMap(): Map<String, UserAccountEntry> {
        val jsonString = context.dataStore.data.first()[USER_ACCOUNTS_JSON_KEY] ?: return emptyMap()
        return try {
            val json = JSONObject(jsonString)
            val keys = json.keys()
            buildMap {
                while (keys.hasNext()) {
                    val uid = keys.next()
                    val entryJson = json.optJSONObject(uid) ?: continue
                    val entry = UserAccountEntry.fromJson(entryJson) ?: continue
                    put(uid, entry)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun writeAccountsMap(map: Map<String, UserAccountEntry>) {
        val json = JSONObject()
        map.forEach { (uid, entry) ->
            json.put(uid, entry.toJson())
        }
        context.dataStore.edit { prefs ->
            prefs[USER_ACCOUNTS_JSON_KEY] = json.toString()
        }
    }

    suspend fun getSelectedAccountUid(): String? {
        return context.dataStore.data.first()[SELECTED_ACCOUNT_UID_KEY]
    }

    suspend fun setSelectedAccountUid(uid: String?) {
        context.dataStore.edit { prefs ->
            if (uid == null) prefs.remove(SELECTED_ACCOUNT_UID_KEY) else prefs[SELECTED_ACCOUNT_UID_KEY] = uid
        }
    }

    /**
     * Create a new anonymous Firebase account dedicated to a new pairing.
     * This account becomes the "selected" account.
     */
    suspend fun createNewAccountForPairing(): Result<UserAccountEntry> {
        return try {
            val appName = "account_${System.currentTimeMillis()}"
            val app = getOrInitFirebaseApp(appName)
            val auth = FirebaseAuth.getInstance(app)
            val result = auth.signInAnonymously().await()
            val uid = result.user?.uid ?: return Result.failure(Exception("Anonymous sign-in returned null UID"))

            val entry = UserAccountEntry(anonymousUid = uid, firebaseAppName = appName)
            val existing = readAccountsMap().toMutableMap()
            existing[uid] = entry
            writeAccountsMap(existing)
            setSelectedAccountUid(uid)
            Log.d(TAG, "Created new pairing account: $uid")
            Result.success(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new pairing account", e)
            Result.failure(e)
        }
    }

    suspend fun getAccountEntry(uid: String): UserAccountEntry? {
        return readAccountsMap()[uid]
    }

    private suspend fun upsertAccountEntry(entry: UserAccountEntry) {
        val existing = readAccountsMap().toMutableMap()
        existing[entry.anonymousUid] = entry
        writeAccountsMap(existing)
    }
    
    /**
     * Save our own decryption key (used when receiving messages).
     */
    suspend fun saveMyDecryptionKey(key: String) {
        val selectedUid = getSelectedAccountUid()
        if (selectedUid != null) {
            val existing = getAccountEntry(selectedUid)
            val current = existing ?: return
            upsertAccountEntry(current.copy(myDecryptionKey = key))
        }
        // Keep legacy key updated for compatibility with older code paths
        context.dataStore.edit { prefs ->
            prefs[MY_DECRYPTION_KEY] = key
        }
        Log.d(TAG, "Saved my decryption key")
    }

    suspend fun saveMyDecryptionKeyForAccount(accountUid: String, key: String) {
        val current = getAccountEntry(accountUid) ?: return
        upsertAccountEntry(current.copy(myDecryptionKey = key))
        if (getSelectedAccountUid() == accountUid) {
            context.dataStore.edit { prefs -> prefs[MY_DECRYPTION_KEY] = key }
        }
        Log.d(TAG, "Saved decryption key for account: $accountUid")
    }
    
    /**
     * Get our decryption key for receiving messages.
     */
    fun getMyDecryptionKey(): Flow<String?> {
        return context.dataStore.data.map { prefs ->
            prefs[MY_DECRYPTION_KEY]
        }
    }

    suspend fun getMyDecryptionKeyForSender(senderUid: String?): String? {
        if (senderUid.isNullOrBlank()) {
            return getMyDecryptionKey().first()
        }
        val entry = readAccountsMap()[senderUid]
        return entry?.myDecryptionKey ?: getMyDecryptionKey().first()
    }
    
    /**
     * Get the current user's UID.
     */
    fun getCurrentUserId(): String? = defaultAuth.currentUser?.uid

    /**
     * Get the selected account UID (preferred), otherwise fall back to default auth user.
     */
    suspend fun getActiveAccountUid(): String? {
        return getSelectedAccountUid() ?: defaultAuth.currentUser?.uid
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
        val userId = getActiveAccountUid() ?: return Result.failure(Exception("Not signed in"))
        return initializeUserDocumentForAccount(userId)
    }

    suspend fun initializeUserDocumentForAccount(userId: String): Result<Unit> {
        val fcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        
        return try {
            val userDoc = hashMapOf(
                "fcmToken" to fcmToken,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )
            
            // Use set with merge to create or update
            val fs = runCatching { firestoreForAccount(userId) }.getOrElse { FirebaseFirestore.getInstance() }
            fs.collection(USERS_COLLECTION)
                .document(userId)
                .set(userDoc, SetOptions.merge())
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
        val myUserId = getActiveAccountUid() ?: return Result.failure(Exception("Not signed in"))
        return sendPairingRequestFromAccount(myUserId, targetUserId, theirEncryptionKey, verificationCode)
    }

    suspend fun sendPairingRequestFromAccount(
        myUserId: String,
        targetUserId: String,
        theirEncryptionKey: String? = null,
        verificationCode: String? = null
    ): Result<Unit> {
        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        
        if (targetUserId == myUserId) {
            return Result.failure(Exception("Cannot pair with yourself"))
        }

        // Enforce one-time QR use: if this account is already paired, don't allow sending another request.
        val myEntry = getAccountEntry(myUserId)
        if (myEntry?.pairedPartnerUid != null) {
            return Result.failure(Exception("This pairing account is already connected. Create a new pairing to connect again."))
        }
        
        // Generate our own encryption key to send back (for them to use when sending to us)
        val myEncryptionKey = EncryptionHelper.generateKey()
        
        // Save our key for decrypting messages we receive
        saveMyDecryptionKeyForAccount(myUserId, myEncryptionKey)
        
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
            val fs = runCatching { firestoreForAccount(myUserId) }.getOrElse { FirebaseFirestore.getInstance() }
            fs.collection(USERS_COLLECTION)
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
        val userId = defaultAuth.currentUser?.uid
        if (userId.isNullOrBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        
        val listener = FirebaseFirestore.getInstance().collection(USERS_COLLECTION)
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

    fun observePairingRequestsForUser(userId: String): Flow<List<PairingRequest>> = callbackFlow {
        val fs = runCatching { firestoreForAccount(userId) }.getOrElse { FirebaseFirestore.getInstance() }
        val listener = fs.collection(USERS_COLLECTION)
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
        val myUserId = getActiveAccountUid() ?: return Result.failure(Exception("Not signed in"))
        return acceptPairingRequestForAccount(myUserId, request, myEncryptionKey)
    }

    suspend fun acceptPairingRequestForAccount(accountUid: String, request: PairingRequest, myEncryptionKey: String? = null): Result<Unit> {
        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        
        return try {
            // 1. Update the request status to accepted and include our encryption key
            val updateData = hashMapOf<String, Any>(
                "status" to "accepted"
            )
            if (myEncryptionKey != null) {
                updateData["accepterEncryptionKey"] = myEncryptionKey
            }
            
            val fs = runCatching { firestoreForAccount(accountUid) }.getOrElse { FirebaseFirestore.getInstance() }
            fs.collection(USERS_COLLECTION)
                .document(accountUid)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(request.requesterUid)
                .update(updateData)
                .await()
            
            // 2. Update my user document with partner info
            fs.collection(USERS_COLLECTION)
                .document(accountUid)
                .update("partnerId", request.requesterUid)
                .await()
            
            // 3. Send a reciprocal pairing confirmation to the requester
            val confirmation = hashMapOf(
                "requesterUid" to accountUid,
                "requesterFcmToken" to myFcmToken,
                "requesterEncryptionKey" to (myEncryptionKey ?: ""),  // Our key for them to receive
                "requestedAt" to com.google.firebase.Timestamp.now(),
                "status" to "accepted"  // Already accepted
            )
            
            fs.collection(USERS_COLLECTION)
                .document(request.requesterUid)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(accountUid)
                .set(confirmation)
                .await()
            
            // 4. Save partner locally with their encryption key
            // We use THEIR key when sending TO them
            val entry = getAccountEntry(accountUid) ?: return Result.failure(Exception("Unknown account"))
            upsertAccountEntry(
                entry.copy(
                    pairedPartnerUid = request.requesterUid,
                    pairedPartnerFcmToken = request.requesterFcmToken,
                    pairedPartnerName = "My Love",
                    pairedAt = System.currentTimeMillis(),
                    partnerEncryptionKey = request.requesterEncryptionKey
                )
            )
            setSelectedAccountUid(accountUid)
            
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
        val myUserId = getActiveAccountUid() ?: return Result.failure(Exception("Not signed in"))
        
        return try {
            // Delete the pairing request
            val fs = runCatching { firestoreForAccount(myUserId) }.getOrElse { FirebaseFirestore.getInstance() }
            fs.collection(USERS_COLLECTION)
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
        val myUserId = defaultAuth.currentUser?.uid
        if (myUserId == null) {
            trySend(PairingStatus.Error("Not signed in"))
            close()
            return@callbackFlow
        }
        
        // Listen to the request we sent
        val listener = FirebaseFirestore.getInstance().collection(USERS_COLLECTION)
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

    fun observeMyRequestStatusForAccount(accountUid: String, targetUserId: String): Flow<PairingStatus> = callbackFlow {
        val fs = runCatching { firestoreForAccount(accountUid) }.getOrElse { FirebaseFirestore.getInstance() }
        val listener = fs.collection(USERS_COLLECTION)
            .document(targetUserId)
            .collection(PAIRING_REQUESTS_COLLECTION)
            .document(accountUid)
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
        val myUserId = getActiveAccountUid() ?: return Result.failure(Exception("Not signed in"))
        return completePairingForAccount(myUserId, partnerUserId)
    }

    suspend fun completePairingForAccount(accountUid: String, partnerUserId: String): Result<Partner> {
        
        return try {
            // Get partner's user document to get their FCM token
            val fs = runCatching { firestoreForAccount(accountUid) }.getOrElse { FirebaseFirestore.getInstance() }
            val partnerDoc = fs.collection(USERS_COLLECTION)
                .document(partnerUserId)
                .get()
                .await()
            
            val partnerFcmToken = partnerDoc.getString("fcmToken")
                ?: return Result.failure(Exception("Partner FCM token not found"))
            
            // Get the accepted request to retrieve the encryption key they sent
            val requestDoc = fs.collection(USERS_COLLECTION)
                .document(partnerUserId)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(accountUid)
                .get()
                .await()
            
            // The accepter's encryption key is what we use to send TO them
            val partnerEncryptionKey = requestDoc.getString("accepterEncryptionKey")
                ?: getPendingEncryptionKey(partnerUserId)  // Fall back to key from QR code
            
            // Update my document with partner ID
            fs.collection(USERS_COLLECTION)
                .document(accountUid)
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
            val entry = getAccountEntry(accountUid) ?: return Result.failure(Exception("Unknown account"))
            upsertAccountEntry(
                entry.copy(
                    pairedPartnerUid = partner.uid,
                    pairedPartnerFcmToken = partner.fcmToken,
                    pairedPartnerName = partner.displayName,
                    pairedAt = partner.pairedAt,
                    partnerEncryptionKey = partner.encryptionKey
                )
            )
            setSelectedAccountUid(accountUid)
            
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
        // Legacy behavior: keep single partner keys updated for the currently-selected account.
        context.dataStore.edit { prefs ->
            prefs[PARTNER_UID_KEY] = partner.uid
            prefs[PARTNER_FCM_TOKEN_KEY] = partner.fcmToken
            prefs[PARTNER_NAME_KEY] = partner.displayName
            prefs[PARTNER_PAIRED_AT_KEY] = partner.pairedAt.toString()
            if (partner.encryptionKey != null) {
                prefs[PARTNER_ENCRYPTION_KEY] = partner.encryptionKey
            }
        }
        Log.d(TAG, "Partner saved locally (legacy keys): ${partner.uid}, encrypted: ${partner.encryptionKey != null}")
    }
    
    /**
     * Get the locally saved partner.
     */
    fun getPartner(): Flow<Partner?> {
        return context.dataStore.data.map { prefs ->
            // Prefer multi-account selected entry if available; otherwise fall back to legacy keys.
            val selectedUid = prefs[SELECTED_ACCOUNT_UID_KEY]
            if (!selectedUid.isNullOrBlank()) {
                val map = try {
                    val jsonString = prefs[USER_ACCOUNTS_JSON_KEY]
                    if (jsonString.isNullOrBlank()) emptyMap() else {
                        val json = JSONObject(jsonString)
                        val keys = json.keys()
                        buildMap<String, UserAccountEntry> {
                            while (keys.hasNext()) {
                                val uid = keys.next()
                                val entryJson = json.optJSONObject(uid) ?: continue
                                val entry = UserAccountEntry.fromJson(entryJson) ?: continue
                                put(uid, entry)
                            }
                        }
                    }
                } catch (_: Exception) {
                    emptyMap()
                }
                val entry = map[selectedUid]
                if (entry?.pairedPartnerUid != null && entry.pairedPartnerFcmToken != null) {
                    return@map Partner(
                        uid = entry.pairedPartnerUid,
                        fcmToken = entry.pairedPartnerFcmToken,
                        displayName = entry.pairedPartnerName ?: "My Love",
                        pairedAt = entry.pairedAt ?: 0L,
                        encryptionKey = entry.partnerEncryptionKey
                    )
                }
            }

            val uid = prefs[PARTNER_UID_KEY] ?: return@map null
            val fcmToken = prefs[PARTNER_FCM_TOKEN_KEY] ?: return@map null
            val name = prefs[PARTNER_NAME_KEY] ?: "My Love"
            val pairedAt = prefs[PARTNER_PAIRED_AT_KEY]?.toLongOrNull() ?: 0L
            val encryptionKey = prefs[PARTNER_ENCRYPTION_KEY]

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
