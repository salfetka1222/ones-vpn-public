package app.onesvpn

import org.junit.Assert.*
import org.junit.Test

class ForegroundNotificationPolicyTest {
    @Test fun firstPostingIsAlwaysRequired() {
        assertTrue(ForegroundNotificationPolicy.needsUpdate(null, "Подключено"))
    }

    @Test fun periodicHealthSuccessDoesNotPostAgain() {
        assertFalse(ForegroundNotificationPolicy.needsUpdate("Подключено", "Подключено"))
    }

    @Test fun lossRecoveryAndShutdownAreVisible() {
        assertTrue(ForegroundNotificationPolicy.needsUpdate("Подключено", "Ожидаем сеть"))
        assertTrue(ForegroundNotificationPolicy.needsUpdate("Ожидаем сеть", "Подключено"))
        assertTrue(ForegroundNotificationPolicy.needsUpdate("Подключено", "Отключение"))
    }

    @Test fun afterRemovingNotificationNextConnectionMustPostAgain() {
        assertTrue(ForegroundNotificationPolicy.needsUpdate(null, "Подключение"))
    }
}
