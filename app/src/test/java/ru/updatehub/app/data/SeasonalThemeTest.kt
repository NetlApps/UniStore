package ru.updatehub.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SeasonalThemeTest {

    @Test
    fun autoSelectsEffectByDate() {
        assertEquals(
            SeasonalEffect.WINTER,
            SeasonalTheme.autoFor(LocalDate.of(2026, 12, 15))
        )
        assertEquals(
            SeasonalEffect.AUTUMN,
            SeasonalTheme.autoFor(LocalDate.of(2026, 11, 10))
        )
        assertEquals(
            SeasonalEffect.HALLOWEEN,
            SeasonalTheme.autoFor(LocalDate.of(2026, 10, 31))
        )
        assertEquals(
            SeasonalEffect.NEW_YEAR,
            SeasonalTheme.autoFor(LocalDate.of(2026, 12, 27))
        )
        assertEquals(
            SeasonalEffect.WINTER,
            SeasonalTheme.autoFor(LocalDate.of(2027, 1, 7))
        )
        assertEquals(
            SeasonalEffect.WINTER,
            SeasonalTheme.autoFor(LocalDate.of(2026, 12, 25))
        )
    }

    @Test
    fun springAndSummerStayPlain() {
        assertEquals(
            SeasonalEffect.NONE,
            SeasonalTheme.autoFor(LocalDate.of(2026, 4, 12))
        )
        assertEquals(
            SeasonalEffect.NONE,
            SeasonalTheme.autoFor(LocalDate.of(2026, 7, 20))
        )
    }

    @Test
    fun manualSelectionOverridesDate() {
        val summer = LocalDate.of(2026, 7, 20)
        assertEquals(
            SeasonalEffect.WINTER,
            SeasonalTheme.WINTER.resolve(summer)
        )
        assertEquals(
            SeasonalEffect.NEW_YEAR,
            SeasonalTheme.NEW_YEAR.resolve(summer)
        )
        assertEquals(
            SeasonalEffect.NONE,
            SeasonalTheme.OFF.resolve(LocalDate.of(2026, 12, 27))
        )
    }
}
