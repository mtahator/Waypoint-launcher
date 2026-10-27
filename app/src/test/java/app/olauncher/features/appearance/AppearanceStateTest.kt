package app.olauncher.features.appearance

import app.olauncher.features.appearance.domain.AppearanceState
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceStateTest {
    @Test
    fun defaultShowsWallpaper() {
        assertEquals(AppearanceState(true, AppearanceState.TRANSPARENT), AppearanceState.resolve(false, false, darkTheme = true))
        assertEquals(AppearanceState(true, AppearanceState.TRANSPARENT), AppearanceState.resolve(false, false, darkTheme = false))
    }

    @Test
    fun monochromeHidesWallpaperWithNeutralBackground() {
        assertEquals(AppearanceState(false, AppearanceState.BLACK), AppearanceState.resolve(true, false, darkTheme = true))
        assertEquals(AppearanceState(false, AppearanceState.WHITE), AppearanceState.resolve(true, false, darkTheme = false))
    }

    @Test
    fun oledBlackOnlyInDarkTheme() {
        assertEquals(AppearanceState(false, AppearanceState.BLACK), AppearanceState.resolve(false, true, darkTheme = true))
        assertEquals(AppearanceState(true, AppearanceState.TRANSPARENT), AppearanceState.resolve(false, true, darkTheme = false))
        assertEquals(AppearanceState(false, AppearanceState.WHITE), AppearanceState.resolve(true, true, darkTheme = false))
    }
}
