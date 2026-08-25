package de.x0bubbuff.needlebub.developer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeveloperDataStoreInstrumentedTest {
    private lateinit var store: DeveloperDataStore

    @Before
    fun setUp() {
        store = DeveloperDataStore(ApplicationProvider.getApplicationContext())
        store.clearCaptures()
    }

    @After
    fun tearDown() {
        store.clearCaptures()
        store.close()
    }

    @Test
    fun insertsAnEncryptedCaptureWithAndroidKeystore() {
        val id = store.insertCapture(JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", "com.example.sender")
            .put("notificationKeyHash", "fixture-key")
            .put("title", "Security code")
            .put("body", "Your code is 123456"))

        assertNotNull(id)
        assertEquals(1, store.summary().count)
    }
}
