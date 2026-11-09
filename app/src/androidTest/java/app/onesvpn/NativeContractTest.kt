package app.onesvpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.libbox.Libbox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on arm64 Android with the actual pinned AAR, not a mock of JNI. Does not establish VPN. */
@RunWith(AndroidJUnit4::class)
class NativeContractTest {
    @Test fun pinnedEngineAcceptsEverySupportedTransportWithoutPersistingSecrets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val variants = listOf("security=tls", "security=tls&type=ws&path=%2Fws&host=example.org",
            "security=tls&type=grpc&serviceName=test", "security=reality&flow=xtls-rprx-vision&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab12&sni=example.org")
        variants.forEach { query ->
            val profile = parseProfile("vless://00000000-0000-4000-8000-000000000001@vpn.example.org:443?$query")
            assertTrue("Native config rejected a synthetic fixture", NativeEngine.validate(context, profile).isSuccess)
        }
        assertEquals("1.14.4", Libbox.version())
        assertTrue(context.filesDir.resolve("libbox/configuration.json").isDirectory)
    }
}
