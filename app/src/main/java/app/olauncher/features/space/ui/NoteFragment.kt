package app.olauncher.features.space.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.olauncher.R
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.promptText
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.ChatActivity
import app.olauncher.features.assistant.ui.ChatStyle
import app.olauncher.features.assistant.ui.Markdown
import app.olauncher.features.space.data.Note
import app.olauncher.helper.createDialog
import app.olauncher.helper.showToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Date

/** One voice note: play it, read the summary and transcript, copy/share, ask Wayfinder about it. */
class NoteFragment : Fragment() {

    private lateinit var style: ChatStyle
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var play: TextView
    private lateinit var seek: SeekBar
    private lateinit var summary: TextView
    private lateinit var transcript: TextView
    private lateinit var status: TextView
    private lateinit var picture: ImageView
    private lateinit var playerRow: LinearLayout
    private lateinit var tasksHeader: TextView
    private lateinit var tasksBox: LinearLayout
    private lateinit var transcriptHeader: TextView
    private var note: Note? = null
    private var player: MediaPlayer? = null
    private val noteId get() = requireArguments().getLong(ARG_ID)
    private val space get() = requireContext().waypoint.space

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.background) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(style.dp(4), 0, style.dp(8), style.dp(6)) }
        bar.addView(style.action("‹") { requireActivity().onBackPressedDispatcher.onBackPressed() }.apply { textSize = 26f })
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(style.action("⋯") { menu() }.apply { textSize = 22f })
        column.addView(bar)

        val scroll = ScrollView(ctx)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(style.dp(20), 0, style.dp(20), style.dp(32)) }
        scroll.addView(body)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        title = TextView(ctx, null, 0, R.style.TextLarge).apply { setTextColor(style.ink); textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setOnClickListener { rename() } }
        body.addView(title)
        meta = style.caption().apply { setPadding(0, style.dp(4), 0, style.dp(14)) }
        body.addView(meta)

        picture = ImageView(ctx).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; clipToOutline = true
            background = style.rounded(style.codeBg, 18); isVisible = false
        }
        body.addView(picture, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = style.dp(12) })
        playerRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(style.dp(8), style.dp(8), style.dp(14), style.dp(8))
            background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 100, style.hairline)
        }
        play = TextView(ctx, null, 0, R.style.TextLarge).apply {
            text = "▶"; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); textSize = 18f
            background = style.rounded(0xFFD71921.toInt(), 100)
            setOnClickListener { togglePlay() }
        }
        playerRow.addView(play, LinearLayout.LayoutParams(style.dp(44), style.dp(44)))
        seek = SeekBar(ctx).apply { max = 1000 }
        playerRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(playerRow, LinearLayout.LayoutParams(-1, -2))

        status = style.caption().apply { setPadding(0, style.dp(14), 0, 0) }
        body.addView(status)

        body.addView(section(getString(R.string.wp_space_summary)))
        summary = text().also { body.addView(it) }
        tasksHeader = section(getString(R.string.wp_space_reminders)).also { body.addView(it) }
        tasksBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }.also { body.addView(it) }
        transcriptHeader = section(getString(R.string.wp_space_transcript)).also { body.addView(it) }
        transcript = text().apply { setTextIsSelectable(true) }.also { body.addView(it) }

        val actions = LinearLayout(ctx).apply { setPadding(0, style.dp(18), 0, 0) }
        actions.addView(style.action(getString(R.string.wp_space_copy)) { copy() })
        actions.addView(style.action(getString(R.string.wp_image_share)) { share() })
        actions.addView(style.action(getString(R.string.wp_space_ask)) { ask() }.apply { setTextColor(0xFFD71921.toInt()) })
        body.addView(actions)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) player?.let { it.seekTo((it.duration * p / 1000L).toInt()) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(0, bars.top + style.dp(8), 0, bars.bottom)
            insets
        }
        return root
    }

    private fun section(text: String) = style.caption(text.uppercase()).apply { letterSpacing = 0.14f; setPadding(0, style.dp(22), 0, style.dp(6)) }
    private fun text() = TextView(requireContext(), null, 0, R.style.TextSmall).apply {
        setTextColor(style.ink); textSize = 16f; setLineSpacing(0f, 1.2f); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setShadowLayer(0f, 0f, 0f, 0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (true) { progress(); delay(250) } }
                reload()
                space.changes.filter { it == noteId || it == 0L }.collect { reload() }
            }
        }
    }

    private suspend fun reload() {
        val n = space.notes.get(noteId) ?: run { if (isAdded) requireActivity().onBackPressedDispatcher.onBackPressed(); return }
        note = n
        if (view == null) return
        title.text = n.displayTitle
        val sec = n.durationMs / 1000
        val date = "${DateFormat.getMediumDateFormat(requireContext()).format(Date(n.created))} ${DateFormat.getTimeFormat(requireContext()).format(Date(n.created))}"
        meta.text = listOfNotNull(n.category.ifBlank { null }, date, if (n.kind == Note.Kind.VOICE) "%d:%02d".format(sec / 60, sec % 60) else null).joinToString(" · ")
        meta.setOnClickListener { chooseCategory(n) }
        playerRow.isVisible = n.kind == Note.Kind.VOICE
        picture.isVisible = n.images.isNotEmpty()
        n.images.firstOrNull()?.let { name ->
            val f = space.imageFile(name)
            val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, b)
            var smp = 1; while (b.outWidth / (smp * 2) >= resources.displayMetrics.widthPixels) smp *= 2
            picture.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = smp }))
        }
        transcriptHeader.text = getString(if (n.kind == Note.Kind.SCREENSHOT) R.string.wp_space_text_in_image else R.string.wp_space_transcript).uppercase()
        val linked = space.notes.tasks().filter { it.noteId == n.id }
        tasksHeader.isVisible = linked.isNotEmpty(); tasksBox.isVisible = linked.isNotEmpty()
        tasksBox.removeAllViews()
        linked.forEach { t ->
            val due = if (t.due > 0) " · " + android.text.format.DateUtils.formatDateTime(requireContext(), t.due,
                android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL) else ""
            tasksBox.addView(text().apply {
                text = (if (t.done) "✓ " else if (t.remind) "🔔 " else "○ ") + t.title + due
                setPadding(0, style.dp(4), 0, style.dp(4))
                setOnClickListener { SpaceEditors.task(this@NoteFragment, t) }
            })
        }
        status.text = when (n.status) {
            Note.Status.TRANSCRIBING -> getString(if (n.kind == Note.Kind.SCREENSHOT) R.string.wp_space_reading else R.string.wp_space_transcribing)
            Note.Status.SUMMARIZING -> getString(R.string.wp_space_summarizing)
            Note.Status.ERROR -> n.error + "  ·  " + getString(R.string.wp_chat_retry)
            Note.Status.RECORDED -> getString(R.string.wp_space_set_model)
            Note.Status.DONE -> ""
        }
        status.isVisible = status.text.isNotEmpty()
        status.setTextColor(if (n.status == Note.Status.ERROR) 0xFFFF6B5E.toInt() else style.faint)
        status.setOnClickListener {
            if (n.status == Note.Status.ERROR || n.status == Note.Status.RECORDED) {
                if (n.kind == Note.Kind.VOICE) space.process(n.id) else viewLifecycleOwner.lifecycleScope.launch { space.analyze(n.id) }
            }
        }
        summary.text = Markdown.render(n.summary, style.codeBg, style.density)
        summary.isVisible = n.summary.isNotBlank()
        (summary.parent as ViewGroup).getChildAt((summary.parent as ViewGroup).indexOfChild(summary) - 1).isVisible = n.summary.isNotBlank()
        transcript.text = n.transcript.ifBlank { "—" }
    }

    private fun togglePlay() {
        val n = note ?: return
        val p = player
        if (p != null && p.isPlaying) { p.pause(); play.text = "▶"; return }
        if (p != null) { p.start(); play.text = "❚❚"; return }
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(space.audioFile(n).path)
                setOnCompletionListener { play.text = "▶"; seek.progress = 0 }
                prepare(); start()
            }
            play.text = "❚❚"
        }.onFailure { requireContext().showToast(it.message) }
    }

    private fun progress() {
        val p = player ?: return
        if (p.isPlaying && p.duration > 0) seek.progress = (p.currentPosition * 1000L / p.duration).toInt()
    }

    private fun plainText(): String {
        val n = note ?: return ""
        return listOf(n.displayTitle, n.summary, n.transcript).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun copy() {
        val ctx = requireContext()
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), plainText()))
        ctx.showToast(getString(R.string.wp_copied_message))
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, plainText()) }, null))
    }

    /** Start a conversation that has this note as context. */
    private fun ask() {
        val n = note ?: return
        val c = requireContext().waypoint
        if (!c.chatEngine.isConfigured()) { requireContext().showToast(getString(R.string.wp_chat_setup_title)); return }
        requireContext().promptText(R.string.wp_space_ask, "", hint = R.string.wp_space_ask_hint, multiLine = true) { q ->
            val question = q.ifBlank { getString(R.string.wp_space_ask_default) }
            val text = "${getString(R.string.wp_space_ask_context)}\n\n\"\"\"\n${n.transcript.ifBlank { n.summary }}\n\"\"\"\n\n$question"
            viewLifecycleOwner.lifecycleScope.launch {
                val id = c.chatEngine.startConversation(text)
                c.chats.rename(id, "${n.displayTitle}: ${question.take(30)}")
                (activity as? ChatActivity)?.showConversation(id)
            }
        }
    }

    private fun chooseCategory(n: Note) {
        val options = app.olauncher.features.space.domain.Categories.ALL
        requireContext().chooseOne(R.string.wp_category, options) { i ->
            viewLifecycleOwner.lifecycleScope.launch { space.setCategory(n.id, options[i]) }
        }
    }

    private fun rename() {
        val n = note ?: return
        requireContext().promptText(R.string.rename, n.title) { t -> viewLifecycleOwner.lifecycleScope.launch { space.rename(n.id, t) } }
    }

    private fun menu() {
        val n = note ?: return
        requireContext().chooseOne(R.string.wp_space, listOf(getString(R.string.rename), getString(R.string.wp_space_retranscribe), getString(R.string.wp_delete))) { i ->
            when (i) {
                0 -> rename()
                1 -> if (n.kind == Note.Kind.VOICE) space.process(n.id) else viewLifecycleOwner.lifecycleScope.launch { space.analyze(n.id) }
                else -> requireContext().createDialog(R.string.wp_delete, R.string.wp_delete, message = R.string.wp_space_delete_message, onAction = {
                    releasePlayer()
                    val c = requireContext().waypoint
                    c.scope.launch { c.space.delete(n.id) }
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }).showRespectingStatusBar()
            }
        }
    }

    private fun releasePlayer() { player?.release(); player = null }

    override fun onStop() {
        player?.takeIf { it.isPlaying }?.pause()
        if (view != null) play.text = "▶"
        super.onStop()
    }

    override fun onDestroyView() { releasePlayer(); super.onDestroyView() }

    companion object {
        private const val ARG_ID = "note"
        fun newInstance(id: Long) = NoteFragment().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
    }
}
