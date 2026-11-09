package app.onesvpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in an isolated test install; does not start the VPN service. */
@RunWith(AndroidJUnit4::class)
class ConnectionIntentStoreDeviceTest {
    @Test fun explicitChoiceSurvivesStoreRecreationAndManualClear() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ConnectionIntentStore(context)
        assertTrue(store.clear())
        try {
            store.remember("synthetic-profile-1")
            assertEquals("synthetic-profile-1", ConnectionIntentStore(context).rememberedId())
        } finally {
            assertTrue(store.clear())
        }
        assertNull(ConnectionIntentStore(context).rememberedId())
    }
}
