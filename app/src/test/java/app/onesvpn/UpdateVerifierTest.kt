package app.onesvpn

import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateVerifierTest {
    @Test fun acceptsIncreasingVersionWithSamePackageAndSigner() {
        UpdateVerifier.requireCompatible("app.onesvpn", 6, 7, "app.onesvpn", 7, setOf("release"), setOf("release"))
    }
    @Test fun rejectsOtherPackageSignerAndVersionMismatch() {
        listOf(
            Triple("other.package", 7L, setOf("release")),
            Triple("app.onesvpn", 7L, setOf("attacker")),
            Triple("app.onesvpn", 6L, setOf("release")),
            Triple("app.onesvpn", 8L, setOf("release")),
            Triple("app.onesvpn", 7L, emptySet())
        ).forEach { (pkg, version, signers) ->
            assertThrows(IllegalArgumentException::class.java) {
                UpdateVerifier.requireCompatible("app.onesvpn", 6, 7, pkg, version, setOf("release"), signers)
            }
        }
    }
}
