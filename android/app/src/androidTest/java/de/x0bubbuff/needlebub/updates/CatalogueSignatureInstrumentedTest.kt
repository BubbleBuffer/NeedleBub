package de.x0bubbuff.needlebub.updates

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogueSignatureInstrumentedTest {
    @Test
    fun verifiesAnEd25519CatalogueWithoutUsingAndroidKeystore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalogue = context.assets.open(OfficialCatalogue.CATALOGUE_ASSET).use { it.readBytes() }
        val signature = context.assets.open(OfficialCatalogue.SIGNATURE_ASSET).bufferedReader().use { it.readText() }
        val publicKey = context.assets.open(OfficialCatalogue.PUBLIC_KEY_ASSET).bufferedReader().use { it.readText() }
        assertArrayEquals(
            catalogue,
            CatalogueSignature.verify(catalogue, signature, publicKey),
        )
    }
}
