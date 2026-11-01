package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualStoreTest {

    @Test
    fun everyManualStoreHasItsOwnKeyAndWebPage() {
        val stores = ManualStore.all
        assertEquals(stores.size, stores.map { it.key }.distinct().size)
        stores.forEach { store ->
            assertTrue(store.key.isNotBlank())
            assertTrue(store.title.isNotBlank())
            assertTrue(store.subtitle.isNotBlank())
            assertTrue(store.storePackages.isNotEmpty())
            assertTrue(store.webUrl.startsWith("https://"))
        }
    }

    @Test
    fun packageNamesAreNotReusedByDifferentStores() {
        val packages = ManualStore.all.flatMap { it.storePackages }
        assertEquals(packages.size, packages.distinct().size)
    }

    @Test
    fun popularManufacturerStoresArePresent() {
        val byKey = ManualStore.all.associateBy { it.key }

        val palm = byKey.getValue("palm_store")
        assertTrue(palm.subtitle.contains("Tecno"))
        assertTrue(palm.subtitle.contains("Infinix"))
        assertTrue(palm.subtitle.contains("itel"))
        assertTrue("com.transsion.palmstore" in palm.storePackages)

        assertTrue("com.amazon.venezia" in byKey.getValue("amazon_appstore").storePackages)
        assertTrue("com.lenovo.leos.appstore" in byKey.getValue("lenovo_app_store").storePackages)
        assertTrue("com.zte.appstore" in byKey.getValue("zte_app_store").storePackages)
    }
}
