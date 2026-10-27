package app.olauncher.core.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import app.olauncher.R
import app.olauncher.core.AppContainer
import app.olauncher.core.waypoint
import app.olauncher.databinding.FragmentWpPageBinding
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.ui.BaseFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Base for Waypoint settings screens: binds the shared page layout and rebuilds rows from state.
 * Fragments stay thin: they read state, build rows, and forward clicks.
 */
abstract class WpPageFragment : BaseFragment() {
    private var _binding: FragmentWpPageBinding? = null
    protected val binding get() = requireNotNull(_binding)
    protected val container: AppContainer get() = requireContext().waypoint

    abstract val pageTitle: Int
    open val pageSubtitle: Int = 0

    /** Called on view creation and on [rebuild]. */
    abstract fun build(page: PageBuilder)

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWpPageBinding.inflate(inflater, parent, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.pageTitle.setText(pageTitle)
        if (container.prefs.glassTheme) {
            GlassStyler.clearPageBackground(binding.pageRoot)
            GlassStyler.styleClock(binding.pageTitle)
            binding.pageTitle.isAllCaps = true
            binding.pageTitle.textSize = 30f
            GlassStyler.styleCaption(binding.pageSubtitle)
            binding.pageSubtitle.textSize = 11f
            binding.pageSubtitle.setLineSpacing(0f, 1.25f)
        }
        if (pageSubtitle != 0) {
            binding.pageSubtitle.setText(pageSubtitle)
            binding.pageSubtitle.isVisible = true
        }
        rebuild()
    }

    fun rebuild() {
        val b = _binding ?: return
        val page = PageBuilder(b.pageBody)
        page.clear()
        build(page)
    }

    /** Runs [block] tied to the view lifecycle (cancelled when the page closes). */
    protected fun launchUi(block: suspend CoroutineScope.() -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(block = block)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Navigates to a Waypoint destination with the same fade used by upstream settings. */
fun Fragment.openPage(@IdRes destination: Int, args: Bundle? = null) {
    val options = NavOptions.Builder()
        .setEnterAnim(R.anim.fade_enter).setExitAnim(R.anim.fade_exit)
        .setPopEnterAnim(R.anim.fade_enter).setPopExitAnim(R.anim.fade_exit)
        .build()
    runCatching { findNavController().navigate(destination, args, options) }
}

/**
 * Starting another activity (SAF picker, system settings) normally sends the launcher back to its
 * home screen in onStop. Flows that must survive the round trip mark themselves active here.
 */
object ExternalFlow {
    @Volatile
    var active: Boolean = false

    fun start(fragment: Fragment, intent: Intent) {
        active = true
        runCatching { fragment.startActivity(intent) }.onFailure { active = false }
    }
}
