package com.hearttoheart.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// DataStore for local preferences
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "heart_to_heart_prefs")

/**
 * Repository for handling pairing operations.
 * Manages Firestore pairing handshake and local partner storage.
 * Supports multiple pairings via per-pairing Firebase anonymous accounts.
 */
class PairingRepository(private val context: Context) {
    
    private val firestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = Firebase.auth
    
    private var pairingListener: ListenerRegistration? = null
    
    // Manager for per-pairing user accounts
    val userAccountsManager = UserAccountsManager(context)
    
    companion object {
        private const val TAG = "PairingRepository"
        
        // Firestore collections
        private const val USERS_COLLECTION = "users"
        private const val PAIRING_REQUESTS_COLLECTION = "pairingRequests"
        
        // Legacy DataStore keys (for migration)
        private val PARTNER_UID_KEY = stringPreferencesKey("partner_uid")
        private val PARTNER_FCM_TOKEN_KEY = stringPreferencesKey("partner_fcm_token")
        private val PARTNER_NAME_KEY = stringPreferencesKey("partner_name")
        private val PARTNER_PAIRED_AT_KEY = stringPreferencesKey("partner_paired_at")
        private val PARTNER_ENCRYPTION_KEY = stringPreferencesKey("partner_encryption_key")
        private val MY_DECRYPTION_KEY = stringPreferencesKey("my_decryption_key")
        
        private const val MAX_PAIRINGS = 10
    }
    
    /**
     * Create a new Firebase anonymous account for a new pairing.
     * This is called when generating a QR code for "Pair with New User".
     *
     * @return The created UserAccount with the new anonymous UID
     */
    suspend fun createAnonymousAccount(): Result<UserAccount> {
        return try {
            // Sign in anonymously - this creates a NEW Firebase anonymous account
            val user = suspendCancellableCoroutine { continuation ->
                auth.signInAnonymously()
                    .addOnSuccessListener { result ->
                        Log.d(TAG, "Created new anonymous account: ${result.user?.uid}")
                        continuation.resume(result.user!!)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to create anonymous account", e)
                        continuation.resumeWithException(e)
                    }
            }
            
            // Generate encryption key for this account
            val encryptionKey = EncryptionHelper.generateKey()
            val decryptionKey = EncryptionHelper.generateKey()
            
            // Create account entry
            val account = UserAccount(
                uid = user.uid,
                encryptionKey = encryptionKey,
                decryptionKey = decryptionKey
            )
            
            // Save to DataStore
            userAccountsManager.saveAccount(account)
            
            Log.d(TAG, "Created anonymous account: ${account.uid}")
            Result.success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create anonymous account", e)
            Result.failure(e)
        }
    }
    
    /**
     * Check if an account is already paired.
     */
    fun isAccountPaired(accountUid: String): Flow<Boolean> {
        return userAccountsManager.isAccountPaired(accountUid)
    }
    
    /**
     * Get all accounts.
     */
    fun getAllAccounts(): Flow<Map<String, UserAccount>> {
        return userAccountsManager.getAccounts()
    }
    
    /**
     * Get all paired accounts.
     */
    fun getPairedAccounts(): Flow<List<UserAccount>> {
        return userAccountsManager.getPairedAccounts()
    }
    
    /**
     * Get an account by UID.
     */
    fun getAccount(uid: String): Flow<UserAccount?> {
        return userAccountsManager.getAccount(uid)
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
     * Get the current user's UID (legacy - from primary anonymous account).
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
    suspend fun sendPairingRequest(targetUserId: String, theirEncryptionKey: String? = null): Result<Unit> {
        val myUserId = getCurrentUserId() ?: return Result.failure(Exception("Not signed in"))
        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))
        
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
     * Decline a pairing request for a specific account.
     */
    suspend fun declinePairingRequestForAccount(
        accountUid: String,
        request: PairingRequest
    ): Result<Unit> {
        return try {
            // Delete the pairing request
            firestore.collection(USERS_COLLECTION)
                .document(accountUid)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(request.requesterUid)
                .delete()
                .await()

            Log.d(TAG, "Pairing request declined from: ${request.requesterUid} for account $accountUid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decline pairing request for account", e)
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

    // ===== Per-Account Pairing Methods =====

    /**
     * Initialize a specific account's Firestore document.
     * Called when the account is created (QR generation time).
     */
    suspend fun initializeAccountDocument(accountUid: String): Result<Unit> {
        val fcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))

        return try {
            val userDoc = hashMapOf(
                "fcmToken" to fcmToken,
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            firestore.collection(USERS_COLLECTION)
                .document(accountUid)
                .set(userDoc, com.google.firebase.firestore.SetOptions.merge())
                .await()

            Log.d(TAG, "Account document initialized: $accountUid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize account document", e)
            Result.failure(e)
        }
    }

    /**
     * Send a pairing request FROM a specific account.
     * Used when scanning a QR code - creates request from the account identified by the scanned QR.
     *
     * @param accountUid The UID of the account to send from (from the scanned QR)
     * @param targetUserId The UID of the user to pair with (from QR code)
     * @param theirEncryptionKey The encryption key from the QR code (generated by the other user)
     */
    suspend fun sendPairingRequestFromAccount(
        accountUid: String,
        targetUserId: String,
        theirEncryptionKey: String? = null
    ): Result<Unit> {
        // Check if account is already paired
        val isPaired = userAccountsManager.isAccountPaired(accountUid).first()
        if (isPaired) {
            return Result.failure(Exception("This account is already paired"))
        }

        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))

        if (targetUserId == accountUid) {
            return Result.failure(Exception("Cannot pair with yourself"))
        }

        return try {
            // Generate our own encryption key to send back (for them to use when sending to us)
            val myEncryptionKey = EncryptionHelper.generateKey()

            val request = hashMapOf(
                "requesterUid" to accountUid,
                "requesterFcmToken" to myFcmToken,
                "requesterEncryptionKey" to myEncryptionKey,
                "requestedAt" to com.google.firebase.Timestamp.now(),
                "status" to "pending"
            )

            // Write to the target user's pairingRequests subcollection
            firestore.collection(USERS_COLLECTION)
                .document(targetUserId)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(accountUid)
                .set(request)
                .await()

            // Update our account with their encryption key for when they accept
            val account = userAccountsManager.getAccount(accountUid).first()
            if (account != null) {
                val updatedAccount = account.copy(decryptionKey = theirEncryptionKey)
                userAccountsManager.updateAccount(updatedAccount)
            }

            Log.d(TAG, "Pairing request sent from account $accountUid to: $targetUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send pairing request from account", e)
            Result.failure(e)
        }
    }

    /**
     * Listen for incoming pairing requests for a specific account.
     * This account is waiting for someone to scan its QR code.
     */
    fun observePairingRequestsForAccount(accountUid: String): Flow<List<PairingRequest>> = callbackFlow {
        val listener = firestore.collection(USERS_COLLECTION)
            .document(accountUid)
            .collection(PAIRING_REQUESTS_COLLECTION)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to pairing requests for account $accountUid", error)
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
     * Accept a pairing request for a specific account.
     * The account was displayed as QR and someone sent a request.
     *
     * @param accountUid The account that showed the QR code
     * @param request The pairing request to accept
     */
    suspend fun acceptPairingRequestForAccount(
        accountUid: String,
        request: PairingRequest
    ): Result<Unit> {
        // Check if account is already paired
        val isPaired = userAccountsManager.isAccountPaired(accountUid).first()
        if (isPaired) {
            return Result.failure(Exception("This account is already paired"))
        }

        val myFcmToken = getFcmToken() ?: return Result.failure(Exception("No FCM token"))

        return try {
            // Get our account to get the encryption key
            val account = userAccountsManager.getAccount(accountUid).first()
                ?: return Result.failure(Exception("Account not found"))

            // Our encryption key - they use this to send encrypted messages to us
            val myEncryptionKey = account.encryptionKey ?: EncryptionHelper.generateKey()

            // 1. Update the request status to accepted
            val updateData = hashMapOf<String, Any>(
                "status" to "accepted"
            )

            firestore.collection(USERS_COLLECTION)
                .document(accountUid)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(request.requesterUid)
                .update(updateData)
                .await()

            // 2. Update requester's user document with partner info
            firestore.collection(USERS_COLLECTION)
                .document(request.requesterUid)
                .update("partnerId", accountUid)
                .await()

            // 3. Send a reciprocal pairing confirmation to the requester
            val confirmation = hashMapOf(
                "requesterUid" to accountUid,
                "requesterFcmToken" to myFcmToken,
                "requesterEncryptionKey" to myEncryptionKey,
                "requestedAt" to com.google.firebase.Timestamp.now(),
                "status" to "accepted"
            )

            firestore.collection(USERS_COLLECTION)
                .document(request.requesterUid)
                .collection(PAIRING_REQUESTS_COLLECTION)
                .document(accountUid)
                .set(confirmation)
                .await()

            // 4. Update our account as paired
            val updatedAccount = account.copy(
                pairedPartnerUid = request.requesterUid,
                pairedAt = System.currentTimeMillis(),
                encryptionKey = myEncryptionKey
            )
            userAccountsManager.updateAccount(updatedAccount)

            Log.d(TAG, "Pairing accepted for account $accountUid with: ${request.requesterUid}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept pairing request for account", e)
            Result.failure(e)
        }
    }

    /**
     * Listen for when our pairing request gets accepted (for a specific account).
     * Called after we send a request from a specific account.
     */
    fun observePairingStatusForAccount(accountUid: String, targetUserId: String): Flow<PairingStatus> = callbackFlow {
        val listener = firestore.collection(USERS_COLLECTION)
            .document(targetUserId)
            .collection(PAIRING_REQUESTS_COLLECTION)
            .document(accountUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to request status for account $accountUid", error)
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
     * Complete pairing after our request was accepted (for a specific account).
     * Fetches the partner's info and updates the account.
     */
    suspend fun completePairingForAccount(
        accountUid: String,
        partnerUserId: String
    ): Result<Partner> {
        return try {
            // Check if account is already paired
            val existingAccount = userAccountsManager.getAccount(accountUid).first()
            if (existingAccount?.pairedPartnerUid != null) {
                return Result.failure(Exception("This account is already paired"))
            }

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
                .document(accountUid)
                .get()
                .await()

            // The accepter's encryption key is what we use to send TO them
            val partnerEncryptionKey = requestDoc.getString("accepterEncryptionKey")

            // Update partner's document with our account UID
            firestore.collection(USERS_COLLECTION)
                .document(partnerUserId)
                .update("partnerId", accountUid)
                .await()

            // Update our account as paired
            val updatedAccount = existingAccount?.copy(
                pairedPartnerUid = partnerUserId,
                pairedAt = System.currentTimeMillis(),
                encryptionKey = partnerEncryptionKey
            ) ?: UserAccount(
                uid = accountUid,
                pairedPartnerUid = partnerUserId,
                pairedAt = System.currentTimeMillis(),
                encryptionKey = partnerEncryptionKey
            )
            userAccountsManager.updateAccount(updatedAccount)

            // Save partner locally
            val partner = Partner(
                uid = partnerUserId,
                fcmToken = partnerFcmToken,
                displayName = "My Love",
                pairedAt = System.currentTimeMillis(),
                encryptionKey = partnerEncryptionKey
            )
            savePartnerLocally(partner)

            Log.d(TAG, "Pairing completed for account $accountUid with: $partnerUserId")
            Result.success(partner)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete pairing for account", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an account and unpair.
     * This removes the local account and optionally signs out from Firebase.
     */
    suspend fun deleteAccount(accountUid: String): Result<Unit> {
        return try {
            // Delete from DataStore
            userAccountsManager.deleteAccount(accountUid)

            // Sign out from this specific account
            // Note: In Firebase Anonymous auth, you can't directly "delete" an anonymous account
            // from the client. You can only sign out. The account remains but becomes inaccessible.
            // For true cleanup, you would need Firebase Admin SDK on a backend.

            Log.d(TAG, "Account deleted: $accountUid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account", e)
            Result.failure(e)
        }
    }

    /**
     * Get the partner for a specific account.
     */
    fun getPartnerForAccount(accountUid: String): Flow<Partner?> {
        return userAccountsManager.getAccount(accountUid).map { account ->
            account?.pairedPartnerUid?.let { partnerUid ->
                // We need to get partner info from the legacy Partner storage
                // or reconstruct from account data
                null // Will be implemented with PartnerPreferences
            }
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

/**
 * Represents a user account (Firebase anonymous auth account) for a pairing.
 * Each pairing gets its own Firebase anonymous account.
 */
data class UserAccount(
    val uid: String,
    val pairedPartnerUid: String? = null,
    val pairedAt: Long? = null,
    val encryptionKey: String? = null,  // Key for encrypting messages TO partner
    val decryptionKey: String? = null,   // Key for decrypting messages FROM partner
    val displayName: String = "My Love",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Manages the Map of user accounts stored in DataStore.
 * Each account is keyed by its Firebase anonymous UID.
 */
class UserAccountsManager(private val context: Context) {
    
    private val userAccountsDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_accounts")
    
    companion object {
        private val USER_ACCOUNTS_KEY = stringPreferencesKey("user_accounts_json")
        private const val MAX_ACCOUNTS = 10
    }
    
    /**
     * Get all user accounts as a Flow.
     */
    fun getAccounts(): Flow<Map<String, UserAccount>> = userAccountsDataStore.data.map { prefs ->
        val json = prefs[USER_ACCOUNTS_KEY] ?: return@map emptyMap()
        try {
            parseUserAccountsJson(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /**
     * Get a specific account by UID.
     */
    fun getAccount(uid: String): Flow<UserAccount?> = getAccounts().map { accounts ->
        accounts[uid]
    }
    
    /**
     * Get all paired (completed) accounts.
     */
    fun getPairedAccounts(): Flow<List<UserAccount>> = getAccounts().map { accounts ->
        accounts.values.filter { it.pairedPartnerUid != null }
    }
    
    /**
     * Get all unpaired (pending) accounts.
     */
    fun getUnpairedAccounts(): Flow<List<UserAccount>> = getAccounts().map { accounts ->
        accounts.values.filter { it.pairedPartnerUid == null }
    }
    
    /**
     * Check if an account is already paired.
     */
    fun isAccountPaired(uid: String): Flow<Boolean> = getAccount(uid).map { account ->
        account?.pairedPartnerUid != null
    }
    
    /**
     * Save a new account to DataStore.
     */
    suspend fun saveAccount(account: UserAccount) {
        userAccountsDataStore.edit { prefs ->
            val currentJson = prefs[USER_ACCOUNTS_KEY]
            val accounts = if (currentJson != null) {
                try {
                    parseUserAccountsJson(currentJson).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }
            
            accounts[account.uid] = account
            prefs[USER_ACCOUNTS_KEY] = userAccountsToJson(accounts)
        }
    }
    
    /**
     * Update an existing account.
     */
    suspend fun updateAccount(account: UserAccount) {
        userAccountsDataStore.edit { prefs ->
            val currentJson = prefs[USER_ACCOUNTS_KEY]
            val accounts = if (currentJson != null) {
                try {
                    parseUserAccountsJson(currentJson).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }
            
            if (accounts.containsKey(account.uid)) {
                accounts[account.uid] = account
                prefs[USER_ACCOUNTS_KEY] = userAccountsToJson(accounts)
            }
        }
    }
    
    /**
     * Delete an account by UID.
     */
    suspend fun deleteAccount(uid: String) {
        userAccountsDataStore.edit { prefs ->
            val currentJson = prefs[USER_ACCOUNTS_KEY]
            if (currentJson != null) {
                val accounts = try {
                    parseUserAccountsJson(currentJson).toMutableMap()
                } catch (e: Exception) {
                    mutableMapOf()
                }
                accounts.remove(uid)
                prefs[USER_ACCOUNTS_KEY] = userAccountsToJson(accounts)
            }
        }
    }
    
    /**
     * Get the number of accounts.
     */
    fun getAccountCount(): Flow<Int> = getAccounts().map { it.size }
    
    /**
     * Check if max accounts reached.
     */
    fun canCreateAccount(): Flow<Boolean> = getAccountCount().map { count ->
        count < MAX_ACCOUNTS
    }
    
    /**
     * Simple JSON serialization for Map<String, UserAccount>.
     * Format: { "uid1": {"pairedPartnerUid":null,...}, "uid2": {...} }
     */
    private fun userAccountsToJson(accounts: Map<String, UserAccount>): String {
        val sb = StringBuilder()
        sb.append("{")
        accounts.entries.forEachIndexed { index, (uid, account) ->
            if (index > 0) sb.append(",")
            sb.append("\"$uid\":{")
            sb.append("\"uid\":\"${account.uid}\",")
            sb.append("\"pairedPartnerUid\":${account.pairedPartnerUid?.let { "\"$it\"" } ?: "null"},")
            sb.append("\"pairedAt\":${account.pairedAt ?: "null"},")
            sb.append("\"encryptionKey\":${account.encryptionKey?.let { "\"$it\"" } ?: "null"},")
            sb.append("\"decryptionKey\":${account.decryptionKey?.let { "\"$it\"" } ?: "null"},")
            sb.append("\"displayName\":\"${account.displayName}\",")
            sb.append("\"createdAt\":${account.createdAt}")
            sb.append("}")
        }
        sb.append("}")
        return sb.toString()
    }
    
    /**
     * Parse JSON back to Map<String, UserAccount>.
     */
    private fun parseUserAccountsJson(json: String): Map<String, UserAccount> {
        if (json.isBlank() || json == "{}") return emptyMap()
        
        val result = mutableMapOf<String, UserAccount>()
        val content = json.trim().removePrefix("{").removeSuffix("}")
        if (content.isBlank()) return result
        
        // Simple parsing - find each "uid":{...} block
        var pos = 0
        while (pos < content.length) {
            val uidStart = content.indexOf("\"", pos)
            if (uidStart == -1) break
            val uidEnd = content.indexOf("\"", uidStart + 1)
            if (uidEnd == -1) break
            val uid = content.substring(uidStart + 1, uidEnd)
            
            // Find the opening brace for this entry
            val braceStart = content.indexOf("{", uidEnd)
            if (braceStart == -1) break
            
            // Find matching closing brace
            var depth = 1
            var braceEnd = braceStart + 1
            while (depth > 0 && braceEnd < content.length) {
                when (content[braceEnd]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                braceEnd++
            }
            
            val entryJson = content.substring(braceStart, braceEnd)
            result[uid] = parseUserAccountJson(uid, entryJson)
            
            pos = braceEnd
        }
        
        return result
    }
    
    private fun parseUserAccountJson(uid: String, json: String): UserAccount {
        val map = mutableMapOf<String, String?>()
        val content = json.trim().removePrefix("{").removeSuffix("}")
        
        // Parse key:value pairs
        var pos = 0
        while (pos < content.length) {
            // Skip whitespace and commas
            while (pos < content.length && (content[pos] == ' ' || content[pos] == ',')) pos++
            if (pos >= content.length) break
            
            // Find key
            val keyStart = pos
            val keyEnd = content.indexOf("\"", keyStart + 1)
            if (keyEnd == -1) break
            val key = content.substring(keyStart + 1, keyEnd)
            
            // Find colon
            val colonPos = content.indexOf(":", keyEnd)
            if (colonPos == -1) break
            
            // Find value
            var valuePos = colonPos + 1
            while (valuePos < content.length && content[valuePos] == ' ') valuePos++
            if (valuePos >= content.length) break
            
            val value: String? = when {
                content.substring(valuePos).startsWith("null") -> {
                    valuePos += 4
                    null
                }
                content[valuePos] == '"' -> {
                    val strEnd = content.indexOf("\"", valuePos + 1)
                    if (strEnd == -1) {
                        pos = content.length
                        break
                    }
                    val strValue = content.substring(valuePos + 1, strEnd)
                    valuePos = strEnd + 1
                    strValue
                }
                else -> {
                    // Number
                    var numEnd = valuePos
                    while (numEnd < content.length && content[numEnd] !in ",}") numEnd++
                    val numValue = content.substring(valuePos, numEnd).trim()
                    valuePos = numEnd
                    numValue
                }
            }
            
            map[key] = value
            pos = valuePos
        }
        
        return UserAccount(
            uid = uid,
            pairedPartnerUid = map["pairedPartnerUid"],
            pairedAt = map["pairedAt"]?.toLongOrNull(),
            encryptionKey = map["encryptionKey"],
            decryptionKey = map["decryptionKey"],
            displayName = map["displayName"] ?: "My Love",
            createdAt = map["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        )
    }
}
