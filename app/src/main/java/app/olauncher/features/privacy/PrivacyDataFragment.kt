package app.olauncher.features.privacy

import app.olauncher.R
import app.olauncher.core.ui.PageBuilder
import app.olauncher.core.ui.WpPageFragment
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast

/** What is stored, and one-tap deletion of all of it. */
class PrivacyDataFragment : WpPageFragment() {
    override val pageTitle = R.string.wp_privacy_and_data
    override val pageSubtitle = R.string.wp_privacy_subtitle

    private var counts: Pair<Int, Int>? = null

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        launchUi {
            counts = container.chats.count() to container.space.notes.all().size
            rebuild()
        }
    }

    override fun build(page: PageBuilder) {
        page.card {
            header(getString(R.string.wp_data_summary))
            row(getString(R.string.wp_chats), counts?.first?.toString() ?: "…")
            row(getString(R.string.wp_space), counts?.second?.toString() ?: "…")
            note(getString(R.string.wp_privacy_promise))
        }
        page.card {
            header(getString(R.string.wp_assistant))
            row(getString(R.string.wp_delete_chats)) {
                confirm(R.string.wp_delete_chats, R.string.wp_delete_chats_message) { container.chats.deleteAll(); container.chatEngine.pruneImages() }
            }
            row(getString(R.string.wp_delete_notes)) {
                confirm(R.string.wp_delete_notes, R.string.wp_delete_notes_message) { container.space.deleteAll() }
            }
            row(getString(R.string.wp_memory_clear)) {
                confirm(R.string.wp_memory_clear, R.string.wp_memory_clear_message) { container.chats.clearMemories(); container.chatEngine.memoryEdited() }
            }
            row(getString(R.string.wp_api_key_forget)) { container.apiKeys.set(null); rebuild() }
            note(getString(R.string.wp_assistant_privacy_note))
        }
        page.card {
            header(getString(R.string.wp_rates_title))
            toggle(getString(R.string.wp_rates_toggle), container.prefs.onlineRates, getString(R.string.wp_rates_toggle_hint)) {
                container.prefs.onlineRates = it
            }
            row(getString(R.string.wp_rates_clear)) { launchUi { container.exchangeRates.clear() } }
        }
    }

    private fun confirm(title: Int, message: Int, block: suspend () -> Unit) {
        requireContext().createDialog(title, R.string.wp_delete, message = message, onAction = {
            launchUi {
                block()
                requireContext().showToast(R.string.wp_deleted)
                refresh()
            }
        }).showRespectingStatusBar()
    }
}
