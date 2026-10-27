package app.olauncher.features.worldclock.ui

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.openPage
import app.olauncher.features.worldclock.domain.DayOffset
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** All cities with live time, local date and day offset. Opened from home or a gesture. */
class WorldClockPanelFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_world_clock

    override fun build(page: PageBuilder) {
        val entries = container.prefs.worldClocks
        val now = Instant.now()
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        page.card {
            if (entries.isEmpty()) note(getString(R.string.wp_no_cities))
            entries.forEach { e ->
                val zone = DayOffset.parseZone(e.zoneId)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPaddingRelative(dp(8), dp(10), dp(8), dp(10))
                    contentDescription = e.label
                }
                row.addView(TextView(context, null, 0, R.style.TextSmall).apply {
                    text = e.label
                    setShadowLayer(0f, 0f, 0f, 0)
                })
                if (zone == null) {
                    row.addView(TextView(context, null, 0, R.style.TextSmallLight).apply {
                        text = getString(R.string.wp_unknown_zone_remove)
                        setShadowLayer(0f, 0f, 0f, 0)
                    })
                } else {
                    row.addView(TextClock(context, null, 0, R.style.TextLarge).apply {
                        timeZone = e.zoneId
                        format12Hour = "h:mm a"
                        format24Hour = "HH:mm"
                        textSize = 40f
                        setShadowLayer(0f, 0f, 0f, 0)
                        gravity = Gravity.START
                    })
                    val days = DayOffset.daysBetween(e.zoneId, now, ZoneId.systemDefault()) ?: 0
                    val date = dateFormat.format(now.atZone(zone))
                    row.addView(TextView(context, null, 0, R.style.TextSmallLight).apply {
                        text = if (days == 0) date else "$date · ${WorldClockRowsBinder.formatOffset(context, days)}"
                        textSize = 14f
                        setShadowLayer(0f, 0f, 0f, 0)
                    })
                }
                add(row)
            }
        }
        page.card {
            row(getString(R.string.wp_manage_cities)) { openPage(R.id.worldClockSettingsFragment) }
        }
    }
}
