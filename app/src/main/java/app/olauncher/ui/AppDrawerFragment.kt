package app.olauncher.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall
import app.olauncher.core.waypoint
import app.olauncher.features.appearance.ui.GlassStyler
import app.olauncher.features.assistant.domain.AskCommand
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.olauncher.core.ui.focusAndShowKeyboard
import app.olauncher.features.quickanswers.domain.QuickAnswers
import app.olauncher.features.worldclock.data.WorldClockRepository
import app.olauncher.features.worldclock.domain.City
import app.olauncher.features.worldclock.domain.CitySearch
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import app.olauncher.features.math.domain.MathProgram
import app.olauncher.features.math.ui.GraphFragment
import app.olauncher.features.math.ui.GraphView
import app.olauncher.features.quickanswers.domain.ExchangeRates
import app.olauncher.features.quickanswers.platform.ExchangeRateRepository
import app.olauncher.helper.isDarkThemeOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import app.olauncher.features.assistant.platform.GeminiHandoff

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
        }

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.let { field ->
                // Typed text follows its own direction (Latin from the left, Persian from the right),
                // with a visible cursor so the field feels like a text field
                field.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                field.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                field.isCursorVisible = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) field.setTextCursorDrawable(R.drawable.wp_cursor)
                field.setHintTextColor(field.currentTextColor and 0x00FFFFFF or 0x66000000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (requireContext().waypoint.prefs.glassTheme) {
            // Waypoint glass: the blurred window shows through; search becomes a hairline capsule
            GlassStyler.clearPageBackground(binding.root)
            GlassStyler.styleField(binding.search)
            (binding.search.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = (16 * resources.displayMetrics.density).toInt()
                binding.search.layoutParams = it
            }
            searchTextView?.setShadowLayer(0f, 0f, 0f, 0)
            GlassStyler.styleCaption(binding.geminiTitle, accentDot = true)
            GlassStyler.styleCaption(binding.quickAnswerDetail)
            GlassStyler.styleCaption(binding.geminiCancel)
            GlassStyler.styleCaption(binding.geminiCount)
            binding.geminiInput.background = GlassStyler.card(requireContext(), 22f)
            binding.geminiInput.setShadowLayer(0f, 0f, 0f, 0)
            binding.quickAnswerValue.setShadowLayer(0f, 0f, 0f, 0)
            binding.geminiSend.typeface = Typeface.MONOSPACE
            binding.geminiSend.letterSpacing = 0.12f
        }
        initComposer()
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (copyQuickAnswer()) return true
                if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    if (searchExtras && AskCommand.isCommand(newText)) {
                        openComposer(newText.trimStart().drop(1).trimStart())
                        return true
                    }
                    val answered = showQuickAnswer(newText)
                    // "=" puts the search in calculator mode: no app matches, no auto-launch
                    val calcMode = searchExtras && newText.trimStart().startsWith("=")
                    binding.recyclerView.visibility = if (calcMode) View.INVISIBLE else View.VISIBLE
                    adapter.allowAutoLaunch = !answered && !calcMode && !isSearchComposing()
                    adapter.filter.filter(if (calcMode) "" else newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    /** Gemini composer and quick answers only belong to the normal app drawer, not app pickers. */
    private val searchExtras get() = flag == Constants.FLAG_LAUNCH_APP

    // ---- Quick answers (Spotlight-style: 12×3, 15% of 80, 5 km to mi, time in tokyo) ----

    private var quickAnswer: QuickAnswers.Answer? = null
    private var citySearch: CitySearch? = null
    private var cityLoading = false

    private var rates: ExchangeRates? = null
    private var ratesState = RatesState.IDLE
    private var ratesJob: Job? = null
    private var ratesFailedAt = 0L
    private var miniGraph: GraphView? = null

    private enum class RatesState { IDLE, FETCHING, FAILED }

    /** @return true if an answer is shown for [text]. */
    private fun showQuickAnswer(text: String): Boolean {
        val trimmed = text.trimStart()
        val forced = trimmed.startsWith("=")
        val query = if (forced) trimmed.drop(1) else text
        val answer = if (searchExtras)
            QuickAnswers.answer(query, forced, cityLookup(query), rates, is24h = is24Hour()) else null
        quickAnswer = answer
        binding.quickAnswer.isVisible = answer != null
        if (answer != null) {
            binding.quickAnswerValue.text = answer.value
            binding.quickAnswerDetail.text = detailFor(answer)
            val g = prefs.appLabelAlignment
            binding.quickAnswerValue.gravity = g
            binding.quickAnswerDetail.gravity = g
            answer.currency?.let { ensureRates() }
        }
        showMiniGraph(answer?.graph?.takeIf { it.isNotEmpty() })
        return answer != null
    }

    private fun detailFor(a: QuickAnswers.Answer): String {
        if (a.graph != null) return getString(R.string.wp_graph_open)
        if (a.currency == null) return "${a.detail}  ·  ${getString(R.string.wp_tap_copy_short)}"
        val r = rates
        if (r == null) return when {
            !requireContext().waypoint.prefs.onlineRates -> getString(R.string.wp_rates_off)
            ratesState == RatesState.FAILED -> getString(R.string.wp_rates_failed)
            else -> getString(R.string.wp_rates_fetching)
        }
        val age = android.text.format.DateUtils.getRelativeTimeSpanString(
            r.fetchedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
        )
        return getString(R.string.wp_rates_age, a.detail, age)
    }

    /**
     * Rates for a currency answer: cached ones at once; the network only after typing pauses, only if the
     * cache is stale and the setting allows it, one request at a time.
     */
    private fun ensureRates() {
        val repo = requireContext().waypoint.exchangeRates
        if (ratesJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (rates != null && now - rates!!.fetchedAt < ExchangeRateRepository.MAX_AGE_MS) return
        ratesJob = viewLifecycleOwner.lifecycleScope.launch {
            if (rates == null) rates = repo.cached()
            val stale = rates == null || System.currentTimeMillis() - rates!!.fetchedAt >= ExchangeRateRepository.MAX_AGE_MS
            val backoff = System.currentTimeMillis() - ratesFailedAt < 60_000 // don't retry on every keystroke
            if (stale && !backoff && requireContext().waypoint.prefs.onlineRates) {
                delay(450) // wait for typing to pause
                ratesState = RatesState.FETCHING
                val fresh = repo.get()
                ratesState = if (fresh == null) RatesState.FAILED else RatesState.IDLE
                if (fresh == null) ratesFailedAt = System.currentTimeMillis()
                if (fresh != null) rates = fresh
            }
            if (_binding != null) showQuickAnswer(binding.search.query.toString())
        }
    }

    /** A small, non-interactive plot under the answer for `sin(x)`, `x^2+y^2=9`, … */
    private fun showMiniGraph(expr: String?) {
        if (expr == null) { miniGraph?.isVisible = false; return }
        val g = miniGraph ?: GraphView(requireContext(), interactive = false).also { v ->
            v.dark = requireContext().isDarkThemeOn()
            v.panelColor = if (requireContext().isDarkThemeOn()) 0x66000000 else 0x99FFFFFF.toInt()
            binding.quickAnswer.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (150 * resources.displayMetrics.density).toInt()).apply { topMargin = (10 * resources.displayMetrics.density).toInt() })
            miniGraph = v
        }
        g.isVisible = true
        g.program = MathProgram(listOf(expr))
    }

    private fun openGraph(expr: String) {
        binding.search.hideKeyboard()
        findNavController().navigate(R.id.graphFragment, bundleOf(GraphFragment.ARG_EXPR to expr))
    }

    private fun is24Hour() = android.text.format.DateFormat.is24HourFormat(requireContext())

    /** City lookup for "time in …"; the offline city list loads on first use only. */
    private fun cityLookup(text: String): ((String) -> City?)? {
        val t = text.trimStart().lowercase()
        if (!(t.startsWith("time") || t.endsWith(" time") || t.startsWith("ساعت") || t.startsWith("زمان"))) return null
        citySearch?.let { s -> return { name -> s.search(name, 1).firstOrNull() } }
        if (!cityLoading) {
            cityLoading = true
            val ctx = requireContext().applicationContext
            val container = ctx.waypoint
            viewLifecycleOwner.lifecycleScope.launch {
                citySearch = runCatching {
                    WorldClockRepository(ctx, container.prefs).loadSearch(container.dispatchers.io)
                }.getOrNull()
                if (_binding != null) showQuickAnswer(binding.search.query.toString())
            }
        }
        return null
    }

    private fun copyQuickAnswer(): Boolean {
        val answer = quickAnswer ?: return false
        answer.graph?.let { openGraph(it); return true }
        if (answer.copy.isEmpty()) return true
        val ctx = requireContext()
        ctx.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), answer.copy))
        ctx.showToast(getString(R.string.wp_copied, answer.copy))
        return true
    }

    // ---- Gemini composer ----

    private val composerBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeComposer(showKeyboard = true)
    }

    private fun initComposer() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, composerBack)
        binding.quickAnswer.setOnClickListener { copyQuickAnswer() }
        binding.geminiCancel.setOnClickListener { closeComposer(showKeyboard = true) }
        binding.geminiSend.setOnClickListener { sendToGemini() }
        binding.geminiInput.doAfterTextChanged { text ->
            val n = text?.trim()?.length ?: 0
            binding.geminiCount.text = if (n == 0) "" else n.toString()
            binding.geminiSend.alpha = if (n == 0) 0.4f else 1f
            binding.geminiSend.isEnabled = n > 0
        }
    }

    private fun openComposer(initial: String) {
        binding.search.setQuery("", false)
        binding.searchRow.isVisible = false
        binding.quickAnswer.isVisible = false
        // INVISIBLE keeps the list's weight, so the composer stays at the top, above the keyboard
        binding.recyclerView.visibility = View.INVISIBLE
        binding.geminiComposer.isVisible = true
        composerBack.isEnabled = true
        binding.geminiInput.setText(initial)
        binding.geminiInput.setSelection(binding.geminiInput.length())
        binding.geminiInput.focusAndShowKeyboard()
    }

    private fun closeComposer(showKeyboard: Boolean) {
        if (_binding == null) return
        composerBack.isEnabled = false
        binding.geminiInput.text = null
        binding.geminiComposer.isVisible = false
        binding.searchRow.isVisible = true
        binding.recyclerView.visibility = View.VISIBLE
        if (showKeyboard) binding.search.showKeyboard() else binding.geminiInput.hideKeyboard()
    }

    private fun sendToGemini() {
        val question = binding.geminiInput.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) return
        binding.geminiInput.hideKeyboard()
        if (GeminiHandoff.ask(requireContext(), question)) closeComposer(showKeyboard = false)
        else requireContext().showToast(getString(R.string.wp_gemini_unavailable))
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                viewModel.selectedApp(appModel, flag)
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    closeDrawer()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty())
                    closeDrawer()
                if (prefs.firstHide) {
                    binding.search.hideKeyboard()
                    prefs.firstHide = false
                    viewModel.showDialog.postValue(Constants.Dialog.HIDDEN)
                    findNavController().navigate(R.id.action_appListFragment_to_settingsFragment2)
                }
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                findNavController().popBackStack(R.id.mainFragment, false)
            }
        )

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    checkMessageAndExit()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        else if (requireContext().isSystemAnimationsDisabled().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
    }

    private fun initClickListeners() {
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            closeDrawer()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not())
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    private fun checkMessageAndExit() {
        if (!closeDrawer()) return
        if (flag == Constants.FLAG_LAUNCH_APP)
            viewModel.checkForMessages.call()
    }

    // Pops only the drawer. A plain popBackStack() from a late overscroll frame could pop the
    // home screen too, leaving a drawer that auto-launch and back can't close (#766)
    private fun closeDrawer() = findNavController().popBackStack(R.id.appListFragment, true)

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }
}
