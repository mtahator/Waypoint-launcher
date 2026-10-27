package app.olauncher.features.automation.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.core.os.bundleOf
import app.olauncher.R
import app.olauncher.core.ui.ExternalFlow
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.core.ui.openPage
import app.olauncher.features.automation.data.RuleRow

class AutomationListFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_automations
    override val pageSubtitle = R.string.wp_automations_subtitle

    private var rules: List<RuleRow>? = null

    override fun onResume() {
        super.onResume()
        launchUi {
            rules = container.automationRepository.allRules()
            rebuild()
        }
    }

    override fun build(page: PageBuilder) {
        page.card {
            row(getString(R.string.wp_new_rule), value = "+") { openPage(R.id.ruleBuilderFragment) }
        }
        val list = rules ?: return
        page.card {
            header(getString(R.string.wp_rules))
            if (list.isEmpty()) note(getString(R.string.wp_no_rules))
            list.forEach { r ->
                val ctx = requireContext()
                val trigger = r.rule?.let { AutomationText.trigger(ctx, it.trigger) } ?: getString(R.string.wp_rule_invalid)
                val last = r.lastExecution?.let {
                    getString(
                        R.string.wp_last_result, AutomationText.outcome(ctx, it.outcome),
                        DateUtils.getRelativeTimeSpanString(it.startedAt).toString(),
                    )
                }
                row(r.name, value = onOff(r.status.name == "ENABLED"), subtitle = listOfNotNull(trigger, last).joinToString("\n")) {
                    openPage(R.id.ruleDetailsFragment, bundleOf(RuleDetailsFragment.ARG_RULE_ID to r.id))
                }
            }
        }
        page.card {
            header(getString(R.string.wp_reliability))
            note(getString(R.string.wp_reliability_explainer))
            row(getString(R.string.wp_dont_kill_my_app), subtitle = "dontkillmyapp.com") {
                ExternalFlow.start(this@AutomationListFragment, Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")))
            }
        }
    }
}
