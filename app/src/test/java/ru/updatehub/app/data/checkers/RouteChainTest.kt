package ru.updatehub.app.data.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChainTest {

    @Test
    fun `translation proxy keeps path and original query`() {
        val url = translateUrl(
            "https://apkpure.com/-/com.github.android?hl=ru&gl=RU"
        )

        assertTrue(url.startsWith("https://apkpure-com.translate.goog/-/com.github.android?"))
        assertTrue(url.contains("hl=ru"))
        assertTrue(url.contains("_x_tr_sl=auto"))
        assertTrue(url.contains("_x_tr_tl=ru"))
    }

    @Test
    fun `translation proxy rewrites multi-part hosts`() {
        val url = translateUrl("https://www.rustore.ru/catalog/app/com.github.android")

        assertTrue(url.startsWith("https://www-rustore-ru.translate.goog/catalog/app/"))
    }

    @Test
    fun `translation proxy does not double wrap`() {
        val once = translateUrl("https://f-droid.org/api/v1/packages/org.fdroid.fdroid")
        assertEquals(once, translateUrl(once))
    }
}
