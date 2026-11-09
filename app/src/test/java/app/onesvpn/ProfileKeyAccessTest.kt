package app.onesvpn

import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ProfileKeyAccessTest {
    @Test fun missingKeyMustNotReplaceEncryptedProfilesKey() {
        var created = false
        assertThrows(IllegalStateException::class.java) {
            ProfileKeyAccess.obtain<String>(true, { null }, { created = true; "replacement" })
        }
        assertFalse(created)
    }
    @Test fun concurrentFirstUseCreatesExactlyOneKey() {
        val stored = AtomicReference<Any?>()
        val creations = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((1..24).map { Callable {
                ProfileKeyAccess.obtain(false, { stored.get() }, {
                    Thread.sleep(10)
                    creations.incrementAndGet()
                    Any().also(stored::set)
                })
            } }).map { it.get() }
            assertEquals(1, creations.get())
            assertTrue(results.all { it === stored.get() })
        } finally { executor.shutdownNow() }
    }
}
