package app.olauncher.features.automation.platform

/** Whether the home screen is currently resumed: OpenApp may only start activities directly then. */
object HomeVisibility {
    @Volatile
    var isResumed: Boolean = false
}
