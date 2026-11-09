package app.onesvpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class EncryptedProfileBackupTest {
    private val link = "vless://550e8400-e29b-41d4-a716-446655440000@example.com:443?security=tls&type=tcp&sni=example.com#One"
    private val profile = parseProfile(link).copy(source = "https://example.com/list?key=sensitive")
    private fun password() = "some-strong-passphrase".toCharArray()

    @Test fun encryptedRoundtripRetainsSubscriptionWithoutLeakingUri() {
        val first = profile.copy(name = "One")
        val key = password()
        try {
            val sealed = EncryptedProfileBackup.encrypt(listOf(first), key)
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains("vless://"))
            assertFalse(String(sealed, Charsets.ISO_8859_1).contains("sensitive"))
            val opened = EncryptedProfileBackup.decrypt(sealed, key)
            assertEquals(1, opened.size)
            assertEquals(first.uri, opened[0].uri)
            assertEquals(first.source, opened[0].source)
            assertEquals(first.name, opened[0].name)
            assertNotEquals(first.id, opened[0].id)
        } finally { key.fill('\u0000') }
    }

    @Test fun wrongPasswordAndTamperedCiphertextAreRejected() {
        val sealed = EncryptedProfileBackup.encrypt(listOf(profile), password())
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedProfileBackup.decrypt(sealed, "different-strong-passphrase".toCharArray())
        }
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedProfileBackup.decrypt(sealed, password())
        }
    }

    @Test fun invalidHeaderShortFileAndWeakPasswordRejected() {
        val sealed = EncryptedProfileBackup.encrypt(listOf(profile), password())
        sealed[0] = 0
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedProfileBackup.decrypt(sealed, password())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedProfileBackup.decrypt(byteArrayOf(1, 2, 3), password())
        }
        assertFalse(EncryptedProfileBackup.validPassword("short".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedProfileBackup.encrypt(listOf(profile), "short".toCharArray())
        }
    }

    @Test fun largeFileRejectedAndNoProfileClobberOnMerge() {
        val tooLarge = ByteArray(24 * 1024 * 1024 + 1)
        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(tooLarge).use(EncryptedProfileBackup::readBounded)
        }
        val existing = listOf(profile)
        val other = parseProfile(link.replace("example.com:443", "example.org:443"))
        val merged = EncryptedProfileBackup.merge(existing, listOf(profile.copy(id = "new-id"), other, other))
        assertEquals(2, merged.size)
        assertSame(profile, merged.first())
        assertEquals(other.uri, merged.last().uri)
    }
}
