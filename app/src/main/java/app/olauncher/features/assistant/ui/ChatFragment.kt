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
    private lateinit var send: TextView
    private lateinit var titleView: TextView
    private lateinit var modelView: TextView
    private var conversationId = 0L
    private var messages: List<Message> = emptyList()
    private var busy = false
    private var imageMode = false
    private lateinit var imageToggle: TextView
    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(12)

    private val container get() = requireContext().waypoint

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        conversationId = savedInstanceState?.getLong(ARG_ID) ?: requireArguments().getLong(ARG_ID)
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.background) }
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
        column.addView(bar)

        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx).apply { stackFromEnd = true }
            adapter = Adapter()
            clipToPadding = false
            setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
            itemAnimator = null
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // composer
        val composer = LinearLayout(ctx).apply {
            gravity = Gravity.BOTTOM
            setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(10))
        }
        imageToggle = TextView(ctx, null, 0, R.style.TextLarge).apply {
            text = "▣"
            gravity = Gravity.CENTER
            textSize = 20f
            contentDescription = getString(R.string.wp_image_mode)
            setOnClickListener { toggleImageMode() }
        }
        composer.addView(imageToggle, LinearLayout.LayoutParams(style.dp(44), style.dp(48)).apply { marginEnd = style.dp(6) })
        input = EditText(ctx).apply {
            setHint(R.string.wp_chat_hint)
            setTextColor(style.ink)
            setHintTextColor(style.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 1; maxLines = 6
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 22, style.hairline)
            setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(12))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = TextView(ctx, null, 0, R.style.TextLarge).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            background = style.rounded(0xFFD71921.toInt(), 100)
            contentDescription = getString(R.string.wp_send_to_gemini)
            setOnClickListener { onSend() }
        }
        composer.addView(send, LinearLayout.LayoutParams(style.dp(48), style.dp(48)).apply { marginStart = style.dp(8) })
        column.addView(composer)
        input.doAfterTextChanged { updateSend() }
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
        val atEnd = !list.canScrollVertically(1)
        list.adapter?.notifyDataSetChanged()
        if (atEnd) scrollToEnd()
    }

    private fun scrollToEnd() {
        val n = list.adapter?.itemCount ?: 0
        if (n > 0) list.scrollToPosition(n - 1)
    }

    private fun updateSend() {
        if (!::send.isInitialized) return
        send.text = if (busy) "■" else "↑"
        val canSend = busy || input.text.isNotBlank()
        send.alpha = if (canSend) 1f else 0.4f
        send.contentDescription = getString(if (busy) R.string.wp_chat_stop else R.string.wp_send_to_gemini)
    }

    private fun toggleImageMode() {
        if (!imageMode && !container.chatEngine.isImageConfigured()) {
            requireContext().showToast(getString(R.string.wp_image_needs_model)); return
        }
        imageMode = !imageMode
        styleImageToggle()
    }

    private fun styleImageToggle() {
        imageToggle.setTextColor(if (imageMode) 0xFFFFFFFF.toInt() else style.ink)
        imageToggle.background = if (imageMode) style.rounded(0xFFD71921.toInt(), 100) else style.rounded(0, 100, style.hairline)
        input.setHint(if (imageMode) R.string.wp_image_hint else R.string.wp_chat_hint)
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
        imageMode = false; styleImageToggle()
        if (conversationId == 0L) {
            viewLifecycleOwner.lifecycleScope.launch {
                conversationId = engine.startConversation(text, image) // the change collector reads the new id
                reload()
            }
        } else engine.send(conversationId, text, image)
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

    private fun copy(text: String) {
        val ctx = requireContext()
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        ctx.showToast(getString(R.string.wp_copied_message))
    }

    // ---- messages

    private inner class Holder(val root: LinearLayout, val bubble: TextView, val pictures: LinearLayout, val meta: TextView) : RecyclerView.ViewHolder(root)

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
            root.addView(meta, LinearLayout.LayoutParams(-2, -2))
            return Holder(root, bubble, pictures, meta)
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
                    m.content.isEmpty() && busy && last -> "●  ●  ●"
                    m.error -> m.content
                    else -> Markdown.render(m.content, style.codeBg, style.density)
                }
                h.bubble.setTextColor(if (m.error) 0xFFFF6B5E.toInt() else style.ink)
                if (m.content.isEmpty() && m.images.isNotEmpty()) h.bubble.isVisible = false
            }
            bindPictures(h.pictures, m)
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
