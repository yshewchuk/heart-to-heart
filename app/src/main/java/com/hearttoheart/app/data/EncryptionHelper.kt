package com.hearttoheart.app.data

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Helper class for AES-GCM encryption/decryption of messages.
 * Uses a shared secret key exchanged during pairing.
 */
object EncryptionHelper {
    
    private const val TAG = "EncryptionHelper"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BYTES = 32  // 256-bit key
    private const val GCM_IV_SIZE = 12     // 96-bit IV for GCM
    private const val GCM_TAG_SIZE = 128   // 128-bit auth tag
    
    /**
     * Generate a new random encryption key.
     * Returns the key as a Base64-encoded string for easy storage/transmission.
     */
    fun generateKey(): String {
        val keyBytes = ByteArray(KEY_SIZE_BYTES)
        SecureRandom().nextBytes(keyBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }
    
    /**
     * Encrypt a plaintext message using AES-GCM.
     * 
     * @param plaintext The message to encrypt
     * @param base64Key The Base64-encoded encryption key
     * @return Base64-encoded ciphertext (IV + encrypted data + auth tag), or null on failure
     */
    fun encrypt(plaintext: String, base64Key: String): String? {
        if (plaintext.isEmpty()) return plaintext
        
        return try {
            val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP or Base64.URL_SAFE)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            
            // Generate random IV
            val iv = ByteArray(GCM_IV_SIZE)
            SecureRandom().nextBytes(iv)
            
            // Initialize cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            
            // Encrypt
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            
            // Combine IV + ciphertext for transmission
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            
            Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            null
        }
    }
    
    /**
     * Decrypt a ciphertext message using AES-GCM.
     * 
     * @param ciphertext Base64-encoded ciphertext (IV + encrypted data + auth tag)
     * @param base64Key The Base64-encoded encryption key
     * @return Decrypted plaintext, or the original ciphertext if decryption fails
     */
    fun decrypt(ciphertext: String, base64Key: String): String {
        if (ciphertext.isEmpty()) return ciphertext
        
        return try {
            val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP or Base64.URL_SAFE)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            
            // Decode combined data
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP or Base64.URL_SAFE)
            
            // Extract IV
            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val encryptedData = combined.copyOfRange(GCM_IV_SIZE, combined.size)
            
            // Initialize cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            // Decrypt
            val plaintext = cipher.doFinal(encryptedData)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            // Return original if decryption fails (backwards compatibility)
            ciphertext
        }
    }
    
    /**
     * Check if a key is valid (correct length and Base64 format).
     */
    fun isValidKey(base64Key: String?): Boolean {
        if (base64Key.isNullOrEmpty()) return false
        return try {
            val decoded = Base64.decode(base64Key, Base64.NO_WRAP or Base64.URL_SAFE)
            decoded.size == KEY_SIZE_BYTES
        } catch (e: Exception) {
            false
        }
    }
}
