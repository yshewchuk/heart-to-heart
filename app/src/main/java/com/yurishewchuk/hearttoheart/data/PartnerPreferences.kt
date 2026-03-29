package com.yurishewchuk.hearttoheart.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.exifinterface.media.ExifInterface
import com.yurishewchuk.hearttoheart.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val Context.partnerPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "partner_preferences")

/**
 * Available notification icons.
 */
enum class NotificationIcon(val emoji: String, val displayName: String, val drawableRes: Int) {
    HEART("❤️", "Heart", R.drawable.ic_notif_heart),
    ROCKET("🚀", "Rocket", R.drawable.ic_notif_rocket),
    FIRE("🔥", "Fire", R.drawable.ic_notif_fire),
    EGGPLANT("🍆", "Eggplant", R.drawable.ic_notif_eggplant),
    PEACH("🍑", "Peach", R.drawable.ic_notif_peach),
    CHILLI("🌶️", "Chilli", R.drawable.ic_notif_chilli)
}

/**
 * Preset nicknames with their emojis.
 */
val NICKNAME_PRESETS = listOf(
    "Bae" to "💕",
    "Dear" to "💗",
    "Darling" to "💖",
    "Boo" to "👻",
    "Honey" to "🍯",
    "Love" to "❤️",
    "Sweetheart" to "💝",
    "Baby" to "👶"
)

/**
 * Partner customization preferences.
 */
data class PartnerPrefs(
    val nickname: String = "My Love",
    val profilePictureUri: String? = null,
    val notificationIcon: NotificationIcon = NotificationIcon.HEART
)

/**
 * Repository for partner customization preferences.
 */
class PartnerPreferencesRepository(private val context: Context) {
    
    companion object {
        private const val KEY_PREFIX_NICKNAME = "partner_nickname_"
        private const val KEY_PREFIX_PROFILE_PICTURE = "partner_profile_picture_"
        private const val KEY_PREFIX_NOTIFICATION_ICON = "notification_icon_"
    }
    
    /**
     * Get partner preferences as a Flow.
     */
    fun getPreferences(accountUid: String): Flow<PartnerPrefs> {
        return context.partnerPrefsDataStore.data.map { prefs ->
            PartnerPrefs(
                nickname = prefs[nicknameKey(accountUid)] ?: "My Love",
                profilePictureUri = prefs[profilePictureKey(accountUid)],
                notificationIcon = prefs[notificationIconKey(accountUid)]?.let {
                    try { NotificationIcon.valueOf(it) } catch (e: Exception) { NotificationIcon.HEART }
                } ?: NotificationIcon.HEART
            )
        }
    }
    
    /**
     * Update nickname.
     */
    suspend fun setNickname(accountUid: String, nickname: String) {
        context.partnerPrefsDataStore.edit { prefs ->
            prefs[nicknameKey(accountUid)] = nickname.ifBlank { "My Love" }
        }
    }
    
    /**
     * Update profile picture by copying it to internal storage.
     * This ensures the image persists even after the app is restarted.
     */
    suspend fun setProfilePicture(accountUid: String, uri: Uri?) {
        if (uri == null) {
            // Delete the saved profile picture
            deleteProfilePicture(accountUid)
            context.partnerPrefsDataStore.edit { prefs ->
                prefs.remove(profilePictureKey(accountUid))
            }
            return
        }
        
        // Copy image to internal storage
        val savedPath = withContext(Dispatchers.IO) {
            try {
                // Read and rotate the bitmap according to EXIF
                val inputStreamForExif = context.contentResolver.openInputStream(uri)
                val exif = inputStreamForExif?.let { ExifInterface(it) }
                val orientation = exif?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                ) ?: ExifInterface.ORIENTATION_NORMAL
                inputStreamForExif?.close()
                
                // Load bitmap
                val inputStream = context.contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                
                if (originalBitmap == null) return@withContext null
                
                // Rotate if needed
                val rotationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                
                val finalBitmap = if (rotationDegrees != 0f) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees) }
                    Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                } else {
                    originalBitmap
                }
                
                // Save to internal storage
                val file = File(context.filesDir, profilePictureFileName(accountUid))
                FileOutputStream(file).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                
                // Clean up
                if (finalBitmap != originalBitmap) {
                    originalBitmap.recycle()
                }
                
                file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        
        // Save the path to DataStore
        if (savedPath != null) {
            context.partnerPrefsDataStore.edit { prefs ->
                prefs[profilePictureKey(accountUid)] = savedPath
            }
        }
    }
    
    /**
     * Delete the saved profile picture file.
     */
    private fun deleteProfilePicture(accountUid: String) {
        try {
            val file = File(context.filesDir, profilePictureFileName(accountUid))
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            // Ignore deletion errors
        }
    }
    
    /**
     * Update notification icon.
     */
    suspend fun setNotificationIcon(accountUid: String, icon: NotificationIcon) {
        context.partnerPrefsDataStore.edit { prefs ->
            prefs[notificationIconKey(accountUid)] = icon.name
        }
    }
    
    /**
     * Clear all preferences.
     */
    suspend fun clearPreferences(accountUid: String) {
        context.partnerPrefsDataStore.edit { prefs ->
            prefs.remove(nicknameKey(accountUid))
            prefs.remove(profilePictureKey(accountUid))
            prefs.remove(notificationIconKey(accountUid))
        }
    }

    private fun nicknameKey(accountUid: String) = stringPreferencesKey("$KEY_PREFIX_NICKNAME$accountUid")
    private fun profilePictureKey(accountUid: String) = stringPreferencesKey("$KEY_PREFIX_PROFILE_PICTURE$accountUid")
    private fun notificationIconKey(accountUid: String) = stringPreferencesKey("$KEY_PREFIX_NOTIFICATION_ICON$accountUid")
    private fun profilePictureFileName(accountUid: String) = "partner_profile_$accountUid.jpg"
}
