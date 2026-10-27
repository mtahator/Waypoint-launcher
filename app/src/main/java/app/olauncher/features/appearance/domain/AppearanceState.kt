package app.olauncher.features.appearance.domain

/** Resolved look of the launcher window. Pure; computed from flags + whether the theme is dark. */
data class AppearanceState(val showWallpaper: Boolean, val backgroundArgb: Int) {
    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TRANSPARENT = 0

        /**
         * Monochrome hides the wallpaper (the launcher cannot desaturate it) and draws an opaque
         * neutral background. OLED black forces true #000000 in a dark theme.
         */
        fun resolve(monochrome: Boolean, oledBlack: Boolean, darkTheme: Boolean): AppearanceState = when {
            oledBlack && darkTheme -> AppearanceState(false, BLACK)
            monochrome -> AppearanceState(false, if (darkTheme) BLACK else WHITE)
            else -> AppearanceState(true, TRANSPARENT)
        }
    }
}
