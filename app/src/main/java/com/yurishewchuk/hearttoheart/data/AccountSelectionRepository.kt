package com.yurishewchuk.hearttoheart.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Handles account selection and account-scoped unpairing concerns.
 * This keeps PairingRepository focused on pairing handshake behavior.
 */
class AccountSelectionRepository(private val context: Context) {

    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "AccountSelectionRepo"
        private const val USERS_COLLECTION = "users"
        private val USER_ACCOUNTS_KEY = stringPreferencesKey("user_accounts")
        private val SELECTED_ACCOUNT_UID_KEY = stringPreferencesKey("selected_account_uid")
    }

    fun getSelectedAccountUid(): Flow<String?> {
        return context.heartToHeartPreferencesDataStore.data.map { prefs ->
            prefs[SELECTED_ACCOUNT_UID_KEY]
        }
    }

    suspend fun setSelectedAccountUid(accountUid: String?) {
        context.heartToHeartPreferencesDataStore.edit { prefs ->
            if (accountUid.isNullOrBlank()) {
                prefs.remove(SELECTED_ACCOUNT_UID_KEY)
            } else {
                prefs[SELECTED_ACCOUNT_UID_KEY] = accountUid
            }
        }
    }

    fun getPairedAccounts(): Flow<Map<String, PairingRepository.UserAccountEntry>> {
        return getUserAccounts().map { accounts ->
            accounts.filterValues { it.pairedPartnerUid != null }
        }
    }

    suspend fun unpairAccount(accountUid: String): Result<Unit> {
        return try {
            val existingAccounts = getUserAccounts().first().toMutableMap()
            val account = existingAccounts[accountUid]
                ?: return Result.failure(Exception("Account not found"))

            val partnerUid = account.pairedPartnerUid
            if (!partnerUid.isNullOrBlank()) {
                try {
                    firestore.collection(USERS_COLLECTION)
                        .document(accountUid)
                        .update("partnerId", FieldValue.delete())
                        .await()
                } catch (_: Exception) {
                    // Best-effort cleanup. Local unpair still proceeds.
                }
            }

            existingAccounts.remove(accountUid)
            context.heartToHeartPreferencesDataStore.edit { prefs ->
                prefs[USER_ACCOUNTS_KEY] = serializeUserAccounts(existingAccounts)
                val selectedUid = prefs[SELECTED_ACCOUNT_UID_KEY]
                if (selectedUid == accountUid) {
                    val fallbackUid = existingAccounts.values
                        .firstOrNull { it.pairedPartnerUid != null }
                        ?.anonymousUid
                    if (fallbackUid == null) {
                        prefs.remove(SELECTED_ACCOUNT_UID_KEY)
                    } else {
                        prefs[SELECTED_ACCOUNT_UID_KEY] = fallbackUid
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpair account: $accountUid", e)
            Result.failure(e)
        }
    }

    fun getUserAccounts(): Flow<Map<String, PairingRepository.UserAccountEntry>> {
        return context.heartToHeartPreferencesDataStore.data.map { prefs ->
            parseUserAccounts(prefs[USER_ACCOUNTS_KEY])
        }
    }

    private fun parseUserAccounts(rawJson: String?): Map<String, PairingRepository.UserAccountEntry> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(rawJson)
            root.keys().asSequence().mapNotNull { uid ->
                val accountJson = root.optJSONObject(uid) ?: return@mapNotNull null
                uid to PairingRepository.UserAccountEntry.fromJson(accountJson)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serializeUserAccounts(accounts: Map<String, PairingRepository.UserAccountEntry>): String {
        val root = JSONObject()
        accounts.forEach { (uid, account) ->
            root.put(uid, account.toJson())
        }
        return root.toString()
    }
}
