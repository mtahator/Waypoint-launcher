package app.olauncher.features.gestures.domain

/** Whitelisted things a home gesture may do. Encoded with stable string discriminators. */
sealed interface GestureAction {
    data class OpenApp(val packageName: String, val userHandleId: Long) : GestureAction
    data object ToggleLauncherMonochrome : GestureAction
    data object ToggleSystemGrayscale : GestureAction
    data object OpenWorldClockPanel : GestureAction
    data object None : GestureAction

    companion object {
        fun encode(action: GestureAction): String = when (action) {
            is OpenApp -> "open_app:${action.packageName}:${action.userHandleId}"
            ToggleLauncherMonochrome -> "toggle_monochrome"
            ToggleSystemGrayscale -> "toggle_system_grayscale"
            OpenWorldClockPanel -> "open_world_clock"
            None -> "none"
        }

        /** Unknown or malformed values decode to [fallback], never throw. */
        fun decode(raw: String?, fallback: GestureAction = ToggleLauncherMonochrome): GestureAction {
            if (raw.isNullOrBlank()) return fallback
            return when {
                raw == "toggle_monochrome" -> ToggleLauncherMonochrome
                raw == "toggle_system_grayscale" -> ToggleSystemGrayscale
                raw == "open_world_clock" -> OpenWorldClockPanel
                raw == "none" -> None
                raw.startsWith("open_app:") -> {
                    val parts = raw.removePrefix("open_app:").split(':')
                    val user = parts.getOrNull(1)?.toLongOrNull()
                    if (parts.size == 2 && parts[0].isNotBlank() && user != null) OpenApp(parts[0], user) else fallback
                }
                else -> fallback
            }
        }
    }
}
