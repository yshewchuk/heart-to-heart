package com.yurishewchuk.hearttoheart.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards against the schema-drift bug where the partner FCM token was written
 * during pairing but never read back, because UserAccountEntry had no field for it.
 */
@RunWith(RobolectricTestRunner::class)
class UserAccountEntryTest {

    @Test
    fun `serialization round-trip preserves all fields including partnerFcmToken`() {
        val entry = PairingRepository.UserAccountEntry(
            anonymousUid = "uid_abc",
            pairedPartnerUid = "partner_xyz",
            pairedAt = 1_700_000_000_000L,
            encryptionKey = "key_123",
            partnerFcmToken = "fcm_token_456",
            displayName = "Alex"
        )

        val restored = PairingRepository.UserAccountEntry.fromJson(entry.toJson())

        assertEquals(entry.anonymousUid, restored.anonymousUid)
        assertEquals(entry.pairedPartnerUid, restored.pairedPartnerUid)
        assertEquals(entry.pairedAt, restored.pairedAt)
        assertEquals(entry.encryptionKey, restored.encryptionKey)
        assertEquals(entry.partnerFcmToken, restored.partnerFcmToken)
        assertEquals(entry.displayName, restored.displayName)
    }

    @Test
    fun `fromJson tolerates legacy entries without partnerFcmToken`() {
        // Legacy JSON written before the field existed must still parse.
        val legacyJson = JSONObject(
            mapOf(
                "anonymousUid" to "uid_abc",
                "pairedPartnerUid" to "partner_xyz",
                "encryptionKey" to "key_123",
                "displayName" to "Alex"
            )
        )

        val restored = PairingRepository.UserAccountEntry.fromJson(legacyJson)

        assertNull(restored.partnerFcmToken)
        assertEquals("partner_xyz", restored.pairedPartnerUid)
    }

    @Test
    fun `blank optional fields become null`() {
        val json = JSONObject(
            mapOf(
                "anonymousUid" to "uid_abc",
                "partnerFcmToken" to "",
                "displayName" to ""
            )
        )

        val restored = PairingRepository.UserAccountEntry.fromJson(json)

        assertNull(restored.partnerFcmToken)
        assertNull(restored.displayName)
    }
}
