package com.droiddeck.launcher.ui

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTabsTest {
<<<<<<< HEAD
    @Test fun storeLibraryInstalledAmazonWithoutStore() {
        assertEquals(listOf("store", "library", "installed"), tabsFor(Store.GOG))
        assertEquals(listOf("library", "installed"), tabsFor(Store.AMAZON))
        assertEquals("library", tabFor(Store.AMAZON, "store"))
        assertEquals("library", tabFor(Store.GOG, "all"))
        assertEquals("installed", tabFor(Store.EPIC, "installed"))
=======
    @Test fun libraryAndStoreAmazonLibraryOnly() {
        assertEquals(listOf("library", "store"), tabsFor(Store.GOG))
        assertEquals(listOf("library", "store"), tabsFor(Store.ITCH))
        assertEquals(listOf("library"), tabsFor(Store.AMAZON))
        assertEquals("library", tabFor(Store.AMAZON, "store"))
        assertEquals("library", tabFor(Store.GOG, "all"))
        assertEquals("library", tabFor(Store.EPIC, "installed"))
>>>>>>> upstream/main
        assertEquals("store", tabFor(Store.GOG, "store"))
    }
}
