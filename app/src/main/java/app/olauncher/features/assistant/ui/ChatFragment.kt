package app.olauncher.features.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.focusAndShowKeyboard
import app.olauncher.core.ui.promptText
import app.olauncher.helper.createDialog
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.data.Message
import app.olauncher.helper.showToast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.max

/** One conversation: messages, streaming reply, and a multi-line composer that stays above the keyboard. */
class ChatFragment : Fragment() {

    private lateinit var style: ChatStyle
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var send: android.widget.ImageView
    private lateinit var titleView: TextView
    private lateinit var modelView: TextView
    private var conversationId = 0L
    private var messages: List<Message> = emptyList()
    private var busy = false
    private var imageMode = false
    private var status: String? = null
    private lateinit var imageToggle: android.widget.ImageView
    private lateinit var webToggle: android.widget.ImageView
    private var webMode = false
    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(12)
    private val maps = android.util.LruCache<Long, android.graphics.Bitmap>(8)
    private val rendering = HashSet<Long>()

    /** "nearest…" before location was allowed: ask once, then send whatever the answer. */
    private var pendingSend: (() -> Unit)? = null
    private val locationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        pendingSend?.invoke(); pendingSend = null
    }

    private val container get() = requireContext().waypoint

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        conversationId = savedInstanceState?.getLong(ARG_ID) ?: requireArguments().getLong(ARG_ID)
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.scrim) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // top bar
        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(style.dp(4), 0, style.dp(12), style.dp(6)) }
        bar.addView(style.action("‹") { requireActivity().onBackPressedDispatcher.onBackPressed() }.apply { textSize = 26f })
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(ctx, null, 0, R.style.TextMedium).apply {
            setTextColor(style.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        modelView = style.caption()
        titles.addView(titleView); titles.addView(modelView)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(style.action("⋯") { conversationMenu() }.apply { textSize = 22f; contentDescription = getString(R.string.wp_chat_options) })
        column.addView(bar)

        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx).apply { stackFromEnd = true }
            adapter = Adapter()
            clipToPadding = false
            setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
            itemAnimator = null
        }
        // a new chat starts with a few things to try instead of an empty screen
        empty = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(style.dp(28), 0, style.dp(28), style.dp(40))
            addView(TextView(ctx, null, 0, R.style.TextLarge).apply {
                text = getString(R.string.wp_chat_empty_title); setTextColor(style.ink); gravity = Gravity.CENTER; textSize = 24f
                if (style.glass) app.olauncher.features.appearance.ui.GlassStyler.softShadow(this)
            })
            addView(style.caption(getString(R.string.wp_chat_empty_hint)).apply { gravity = Gravity.CENTER; setPadding(0, style.dp(8), 0, style.dp(18)) })
            listOf(R.string.wp_try_weather, R.string.wp_try_nearby, R.string.wp_try_reminder, R.string.wp_try_explain).forEach { s ->
                addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                    text = getString(s); setTextColor(style.ink); textSize = 14f; gravity = Gravity.CENTER
                    background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 100, style.hairline)
                    setPadding(style.dp(16), style.dp(10), style.dp(16), style.dp(10))
                    setOnClickListener { input.setText(text); input.setSelection(input.text.length); input.focusAndShowKeyboard() }
                }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = style.dp(8) })
            }
        }
        val stack = FrameLayout(ctx)
        stack.addView(list, FrameLayout.LayoutParams(-1, -1))
        stack.addView(empty, FrameLayout.LayoutParams(-1, -1))
        column.addView(stack, LinearLayout.LayoutParams(-1, 0, 1f))

        // composer: one rounded box, the text across the full width on top, the tools and Send underneath
        val composer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 28, style.hairline)
            setPadding(style.dp(8), style.dp(6), style.dp(8), style.dp(8))
        }
        fun toolButton(icon: Int, label: Int, onClick: () -> Unit) = android.widget.ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = getString(label)
            setOnClickListener { onClick() }
        }
        input = EditText(ctx).apply {
            setHint(R.string.wp_chat_hint)
            setTextColor(style.ink)
            setHintTextColor(style.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 2; maxLines = 8
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(6))
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))
        val tools = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        imageToggle = toolButton(R.drawable.ic_image, R.string.wp_image_mode) { toggleImageMode() }
        tools.addView(imageToggle, LinearLayout.LayoutParams(style.dp(42), style.dp(42)).apply { marginEnd = style.dp(8) })
        webToggle = toolButton(R.drawable.ic_globe, R.string.wp_web_mode) { toggleWebMode() }
        tools.addView(webToggle, LinearLayout.LayoutParams(style.dp(42), style.dp(42)))
        tools.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        send = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = style.rounded(0xFFD71921.toInt(), 100)
            contentDescription = getString(R.string.wp_send_to_gemini)
            setOnClickListener { onSend() }
        }
        tools.addView(send, LinearLayout.LayoutParams(style.dp(44), style.dp(44)))
        composer.addView(tools, LinearLayout.LayoutParams(-1, -2))
        // "/": the user's commands as chips above the composer
        suggestions = LinearLayout(ctx).apply { setPadding(style.dp(12), style.dp(4), style.dp(12), 0); isVisible = false }
        column.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(suggestions)
        })
        column.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { setMargins(style.dp(10), style.dp(4), style.dp(10), style.dp(10)) })
        input.doAfterTextChanged { updateSend(); updateSuggestions() }
        styleImageToggle()

        // edge-to-edge: status bar on top, keyboard (or nav bar) below the composer
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            column.setPadding(0, bars.top + style.dp(8), 0, max(ime.bottom, bars.bottom))
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) list.post { scrollToEnd() }
            insets
        }
        updateSend()
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(ARG_ID, conversationId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val engine = container.chatEngine
        modelView.text = if (engine.isConfigured()) container.prefs.apiModel else getString(R.string.wp_chat_setup_title)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { engine.busy.collect { busy = conversationId in it; updateSend(); list.adapter?.notifyDataSetChanged() } }
                launch { engine.status.collect { status = it[conversationId]; list.adapter?.notifyDataSetChanged() } }
                commands = container.chats.commands()
                reload()
                engine.changes.filter { it == conversationId }.collect { reload() }
            }
        }
        if (conversationId == 0L) input.focusAndShowKeyboard()
    }

    private suspend fun reload() {
        val chats = container.chats
        if (conversationId == 0L) {
            titleView.text = getString(R.string.wp_new_chat_title)
            messages = emptyList()
        } else {
            titleView.text = chats.conversation(conversationId)?.title ?: ""
            messages = chats.messages(conversationId)
        }
        if (view == null) return
        empty.isVisible = messages.isEmpty()
        val atEnd = !list.canScrollVertically(1)
        list.adapter?.notifyDataSetChanged()
        if (atEnd) scrollToEnd()
    }

    private fun scrollToEnd() {
        val n = list.adapter?.itemCount ?: 0
        if (n > 0) list.scrollToPosition(n - 1)
    }

    private lateinit var suggestions: LinearLayout
    private lateinit var empty: LinearLayout
    private var commands: List<app.olauncher.features.assistant.domain.ChatCommand> = emptyList()

    private fun updateSuggestions() {
        if (!::suggestions.isInitialized) return
        val t = input.text.toString()
        val typing = t.startsWith("/") && !t.contains(' ') && !t.contains('\n')
        val prefix = t.drop(1).lowercase()
        val matches = if (typing) commands.filter { it.name.startsWith(prefix) } else emptyList()
        suggestions.removeAllViews()
        suggestions.isVisible = matches.isNotEmpty()
        matches.forEach { c ->
            suggestions.addView(TextView(requireContext(), null, 0, R.style.TextSmall).apply {
                text = "/${c.name}"
                setTextColor(style.ink)
                typeface = android.graphics.Typeface.MONOSPACE
                background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 100, style.hairline)
                setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(8))
                setOnClickListener { input.setText("/${c.name} "); input.setSelection(input.text.length) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(8) })
        }
    }

    private fun updateSend() {
        if (!::send.isInitialized) return
        send.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_arrow_up)
        val canSend = busy || input.text.isNotBlank()
        send.alpha = if (canSend) 1f else 0.4f
        send.contentDescription = getString(if (busy) R.string.wp_chat_stop else R.string.wp_send_to_gemini)
    }

    private fun toggleImageMode() {
        if (!imageMode && !container.chatEngine.isImageConfigured()) {
            requireContext().showToast(getString(R.string.wp_image_needs_model)); return
        }
        imageMode = !imageMode
        if (imageMode) webMode = false
        styleImageToggle()
    }

    private fun toggleWebMode() {
        webMode = !webMode
        if (webMode) imageMode = false
        styleImageToggle()
    }

    /** Tool buttons: outlined when off, filled red when on. Web is hidden when search is off in Setup. */
    private fun styleImageToggle() {
        fun paintTool(v: android.widget.ImageView, on: Boolean) {
            v.imageTintList = android.content.res.ColorStateList.valueOf(if (on) 0xFFFFFFFF.toInt() else style.ink)
            v.background = if (on) style.rounded(0xFFD71921.toInt(), 100) else style.rounded(0, 100, style.hairline)
        }
        paintTool(imageToggle, imageMode)
        paintTool(webToggle, webMode)
        input.setHint(when { imageMode -> R.string.wp_image_hint; webMode -> R.string.wp_web_hint; else -> R.string.wp_chat_hint })
        imageToggle.isVisible = container.chatEngine.isImageConfigured()
    }

    private fun onSend() {
        val engine = container.chatEngine
        if (busy) { engine.stop(conversationId); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!engine.isConfigured()) {
            (activity as? ChatActivity)?.show(AssistantSettingsFragment()); return
        }
        input.setText("")
        val image = imageMode
        val web = webMode
        imageMode = false; webMode = false; styleImageToggle()
        val go: () -> Unit = {
            if (conversationId == 0L) {
                viewLifecycleOwner.lifecycleScope.launch {
                    conversationId = engine.startConversation(text, image, web) // the change collector reads the new id
                    reload()
                }
            } else engine.send(conversationId, text, image, web)
        }
        val ctx = requireContext()
        val located = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!image && !located && container.prefs.toolsEnabled && !askedLocation && app.olauncher.features.assistant.domain.Tools.needsLocation(text)) {
            askedLocation = true
            pendingSend = go
            locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        } else go()
    }

    private var askedLocation = false

    /** Places and routes from the tools: a map with numbered pins and a row per place; tap opens the maps app. */
    private fun bindMap(box: LinearLayout, m: Message) {
        box.removeAllViews()
        val data = if (m.isUser) null else app.olauncher.features.assistant.domain.Tools.decodeMap(m.map)
        box.isVisible = data != null
        if (data == null) return
        val ctx = requireContext()
        val width = ((list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - style.dp(24)).coerceAtLeast(style.dp(200))
        val height = (width * 0.56f).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 18, style.hairline)
            clipToOutline = true
        }
        val image = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(style.codeBg)
            contentDescription = getString(R.string.wp_map_open)
            setOnClickListener { openPlace(data.pins.first()) }
        }
        card.addView(image, LinearLayout.LayoutParams(-1, height))
        val cached = maps.get(m.id)
        if (cached != null) image.setImageBitmap(cached)
        else if (rendering.add(m.id)) viewLifecycleOwner.lifecycleScope.launch {
            val bmp = MapCard.render(data, width, height, resources.displayMetrics.density, style.dark)
            rendering.remove(m.id)
            if (bmp != null) { maps.put(m.id, bmp); list.adapter?.notifyItemChanged(messages.indexOfFirst { it.id == m.id }) }
        }
        data.pins.forEachIndexed { i, p ->
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(style.dp(14), style.dp(10), style.dp(14), style.dp(10))
                setOnClickListener { openPlace(p) }
            }
            row.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = if (data.pins.size > 1) "${i + 1}" else "●"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
                background = style.rounded(0xFFD71921.toInt(), 100)
            }, LinearLayout.LayoutParams(style.dp(24), style.dp(24)).apply { marginEnd = style.dp(12) })
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = p.name.ifBlank { getString(R.string.wp_map_place) }; setTextColor(style.ink); textSize = 15f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (p.detail.isNotBlank()) texts.addView(style.caption(p.detail).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(style.caption("↗"))
            card.addView(row)
        }
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(6) })
    }

    private fun openPlace(p: app.olauncher.features.assistant.domain.Tools.Pin) {
        val geo = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(app.olauncher.features.assistant.domain.Tools.geoUri(p)))
        runCatching { startActivity(geo) }.onFailure { openUrl(app.olauncher.features.assistant.domain.Tools.mapsLink(p.lat, p.lon, p.name)) }
    }

    /** Numbered source cards under a web answer (like Perplexity): tap to open in the browser. */
    private fun bindSources(box: LinearLayout, m: app.olauncher.features.assistant.data.Message) {
        box.removeAllViews()
        box.isVisible = !m.isUser && m.sources.isNotEmpty()
        if (!box.isVisible) return
        val ctx = requireContext()
        box.addView(style.caption(getString(R.string.wp_sources).uppercase()).apply { letterSpacing = 0.14f; setPadding(style.dp(4), style.dp(10), 0, style.dp(6)) })
        val scroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx)
        m.sources.forEachIndexed { i, src ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(10))
                background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 14, style.hairline)
                setOnClickListener { openUrl(src.url) }
                contentDescription = "${i + 1}: ${src.title}"
            }
            card.addView(style.caption("${i + 1} · ${src.host}").apply { setTextColor(0xFFD71921.toInt()); maxLines = 1 })
            card.addView(TextView(ctx, null, 0, R.style.TextSmall).apply {
                text = src.title; setTextColor(style.ink); textSize = 13f; maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, style.dp(3), 0, 0)
            })
            row.addView(card, LinearLayout.LayoutParams(style.dp(170), -2).apply { marginEnd = style.dp(8) })
        }
        scroll.addView(row)
        box.addView(scroll)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    /** Generated images under the reply; tap to share or save. */
    private fun bindPictures(box: LinearLayout, m: Message) {
        box.removeAllViews()
        box.isVisible = m.images.isNotEmpty()
        val width = ((list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - style.dp(24)).coerceAtLeast(style.dp(160))
        for (name in m.images) {
            val file = container.chatEngine.imageFile(name)
            val bmp = thumbs.get(name) ?: decode(file, width)?.also { thumbs.put(name, it) } ?: continue
            val view = android.widget.ImageView(requireContext()).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                background = style.rounded(style.codeBg, 18)
                contentDescription = ImageProtocolHelper.describe(messages, m)
                setOnClickListener { imageActions(file) }
            }
            box.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(6) })
        }
    }

    private fun decode(file: java.io.File, width: Int): android.graphics.Bitmap? {
        if (!file.exists()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun imageActions(file: java.io.File) {
        val ctx = requireContext()
        val canSave = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val options = listOfNotNull(getString(R.string.wp_image_share), if (canSave) getString(R.string.wp_image_save) else null)
        ctx.chooseOne(R.string.wp_image_mode, options) { i ->
            if (i == 0) share(file) else if (canSave) save(file)
        }
    }

    private fun share(file: java.io.File) {
        val ctx = requireContext()
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (file.extension == "jpg") "image/jpeg" else "image/${file.extension}"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(send, null))
    }

    /** Copies the image into Pictures/Waypoint (MediaStore, Android 10+: no permission needed). */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun save(file: java.io.File) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(container.dispatchers.io) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "waypoint-${System.currentTimeMillis()}.${file.extension}")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, if (file.extension == "jpg") "image/jpeg" else "image/${file.extension}")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Waypoint")
                    }
                    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            ctx.showToast(getString(if (ok) R.string.wp_image_saved else R.string.wp_image_save_failed))
        }
    }

    /** Rename, delete or start a new conversation from inside the chat. */
    private fun conversationMenu() {
        val ctx = requireContext()
        if (conversationId == 0L) { return }
        val chats = container.chats
        ctx.chooseOne(R.string.wp_chat_options, listOf(getString(R.string.rename), getString(R.string.wp_delete), getString(R.string.wp_new_chat_title))) { i ->
            when (i) {
                0 -> ctx.promptText(R.string.rename, titleView.text.toString()) { t ->
                    viewLifecycleOwner.lifecycleScope.launch { if (t.isNotBlank()) chats.rename(conversationId, t.trim()); reload() }
                }
                1 -> ctx.createDialog(R.string.wp_delete, R.string.wp_delete, message = R.string.wp_delete_chat_message, onAction = {
                    val id = conversationId
                    container.chatEngine.stop(id)
                    container.scope.launch { chats.delete(id); container.chatEngine.pruneImages() }
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }).showRespectingStatusBar()
                else -> (activity as? ChatActivity)?.show(newInstance(0))
            }
        }
    }

    private fun copy(text: String) {
        val ctx = requireContext()
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        ctx.showToast(getString(R.string.wp_copied_message))
    }

    // ---- messages

    private inner class Holder(
        val root: LinearLayout, val bubble: TextView, val pictures: LinearLayout, val sources: LinearLayout, val meta: TextView, val map: LinearLayout,
    ) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = messages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(0, style.dp(5), 0, style.dp(5))
            }
            val bubble = TextView(ctx, null, 0, R.style.TextSmall).apply {
                setTextColor(style.ink)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setLineSpacing(0f, 1.18f)
                setTextIsSelectable(false)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setShadowLayer(0f, 0f, 0f, 0)
            }
            val meta = style.caption().apply { setPadding(style.dp(6), style.dp(4), style.dp(6), 0) }
            val pictures = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            root.addView(bubble, LinearLayout.LayoutParams(-2, -2))
            root.addView(pictures, LinearLayout.LayoutParams(-1, -2))
            val map = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            root.addView(map, LinearLayout.LayoutParams(-1, -2))
            val sources = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            root.addView(sources, LinearLayout.LayoutParams(-1, -2))
            root.addView(meta, LinearLayout.LayoutParams(-2, -2))
            return Holder(root, bubble, pictures, sources, meta, map)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val m = messages[position]
            val last = position == messages.size - 1
            val lp = h.bubble.layoutParams as LinearLayout.LayoutParams
            val maxW = (list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - style.dp(24)
            h.bubble.maxWidth = if (m.isUser) (maxW * 0.84f).toInt() else maxW
            val imagePrompt = app.olauncher.features.assistant.domain.ImageProtocol.command(m.content)
            h.bubble.isVisible = true
            if (m.isUser) {
                lp.gravity = Gravity.END
                h.bubble.background = style.rounded(style.userBubble, 20)
                h.bubble.setPadding(style.dp(14), style.dp(10), style.dp(14), style.dp(10))
                h.bubble.text = if (imagePrompt != null) "▣  $imagePrompt" else m.content
            } else {
                lp.gravity = Gravity.START
                h.bubble.background = if (style.assistantBubble != 0) style.rounded(style.assistantBubble, 20) else null
                h.bubble.setPadding(style.dp(if (style.assistantBubble != 0) 14 else 4), style.dp(8), style.dp(14), style.dp(8))
                val askedImage = messages.getOrNull(position - 1)?.let { p ->
                    p.isUser && app.olauncher.features.assistant.domain.ImageProtocol.command(p.content) != null
                } == true
                h.bubble.text = when {
                    m.content.isEmpty() && busy && last && askedImage -> getString(R.string.wp_generating_image)
                    m.content.isEmpty() && busy && last && status != null -> "◌  $status"
                    m.content.isEmpty() && busy && last -> "●  ●  ●"
                    m.error -> m.content
                    else -> Citations.link(Markdown.render(m.content, style.codeBg, style.density), m.sources, 0xFFD71921.toInt()) { openUrl(it) }
                }
                // citations and [links](…) are tappable
                h.bubble.movementMethod = if (m.sources.isNotEmpty() || m.content.contains("](")) android.text.method.LinkMovementMethod.getInstance() else null
                h.bubble.setTextColor(if (m.error) 0xFFFF6B5E.toInt() else style.ink)
                if (m.content.isEmpty() && m.images.isNotEmpty()) h.bubble.isVisible = false
            }
            bindPictures(h.pictures, m)
            bindSources(h.sources, m)
            bindMap(h.map, m)
            h.bubble.layoutParams = lp
            (h.meta.layoutParams as LinearLayout.LayoutParams).gravity = lp.gravity
            val showRetry = !m.isUser && last && !busy
            h.meta.isVisible = !m.isUser && last && !busy
            h.meta.text = if (m.error) getString(R.string.wp_chat_retry) else getString(R.string.wp_chat_regenerate)
            h.meta.setTextColor(if (m.error) 0xFFD71921.toInt() else style.faint)
            h.meta.setOnClickListener { if (showRetry) container.chatEngine.retry(conversationId) }
            h.bubble.setOnLongClickListener { copy(m.content); true }
        }
    }

    companion object {
        private const val ARG_ID = "conversation"
        fun newInstance(id: Long) = ChatFragment().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
    }
}

/** Accessibility text for a generated image: the prompt that produced it. */
private object ImageProtocolHelper {
    fun describe(all: List<Message>, m: Message): String {
        val i = all.indexOf(m)
        val prompt = all.getOrNull(i - 1)?.takeIf { it.isUser }
            ?.let { app.olauncher.features.assistant.domain.ImageProtocol.command(it.content) }
        return prompt ?: "Generated image"
    }
}
