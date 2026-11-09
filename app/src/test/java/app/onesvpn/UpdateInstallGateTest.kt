package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class UpdateInstallGateTest {
    @Test fun deniedInstallPermissionNeverRunsDownload() {
        var downloadCalls = 0
        val result = UpdateInstallGate.runWhenPermitted(false) {
            downloadCalls++
            "Downloaded 100 MB"
        }
        assertNull(result)
        assertEquals(0, downloadCalls)
    }

    @Test fun grantedInstallPermissionRunsExactlyOneDownload() {
        var downloadCalls = 0
        val result = UpdateInstallGate.runWhenPermitted(true) {
            downloadCalls++
            "APK verified"
        }
        assertEquals("APK verified", result)
        assertEquals(1, downloadCalls)
    }

    @Test fun downloadFailureIsPropagatedNotMisreportedAsPermissionProblem() {
        assertThrows(IllegalStateException::class.java) {
            UpdateInstallGate.runWhenPermitted(true) {
                throw IllegalStateException("Network error")
            }
        }
    }

    @Test fun deniedPermissionNeverTouchesNetworkOrDiskCallbacks() {
        val actions = mutableListOf<String>()
        val result = UpdateInstallGate.runWhenPermitted(false) {
            actions.add("download")
            actions.add("write")
            actions.add("verify")
        }
        assertNull(result)
        assertTrue(actions.isEmpty())
    }
}
