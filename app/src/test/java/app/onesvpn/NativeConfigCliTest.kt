package app.onesvpn

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Optional host validation with the pinned sing-box CLI; Android JNI has a separate test. */
class NativeConfigCliTest {
    @Test fun nativeParserAcceptsSupportedConfigurations() {
        val executable = System.getenv("SING_BOX_CHECK")
        assumeTrue("Set SING_BOX_CHECK to the pinned 1.14.4 executable", !executable.isNullOrBlank())
        val version = ProcessBuilder(executable!!, "version").redirectErrorStream(true).start()
        val versionText = version.inputStream.bufferedReader().readText()
        check(version.waitFor(10, TimeUnit.SECONDS) && versionText.startsWith("sing-box version 1.14.4\n"))
        val variants = listOf("security=tls", "security=tls&type=ws&path=%2Fws&host=example.org",
            "security=tls&type=grpc&serviceName=test", "security=reality&flow=xtls-rprx-vision&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab12&sni=example.org")
        variants.forEach { query ->
            val profile = parseProfile("vless://00000000-0000-4000-8000-000000000001@vpn.example.org:443?$query")
            val file = Files.createTempFile("synthetic-vpn-config-", ".json").toFile()
            try {
                file.writeText(SingBoxConfig.buildTunConfig(profile).toString())
                val process = ProcessBuilder(executable, "check", "-c", file.absolutePath).redirectErrorStream(true).start()
                check(process.waitFor(20, TimeUnit.SECONDS)) { "Native check timed out" }
                assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            } finally { file.delete() }
        }
    }
}
