package app.olauncher.features.assistant.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.promptText
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.data.Conversation
import kotlinx.coroutines.launch

/** All conversations, newest first. Tap to continue; long-press to rename or delete. */
class ChatListFragment : Fragment() {

    private lateinit var style: ChatStyle
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var setup: LinearLayout
    private var items: List<Conversation> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.scrim) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(style.dp(20), 0, style.dp(8), style.dp(8)) }
        header.addView(style.tabs(space = false) { (activity as ChatActivity).showTab(it) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(style.action(getString(R.string.wp_settings_short)) { (activity as ChatActivity).show(AssistantSettingsFragment()) })
        header.addView(style.action(getString(R.string.wp_new_chat)) { (activity as ChatActivity).show(ChatFragment.newInstance(0)) }.apply {
            setTextColor(0xFFFFFFFF.toInt()); background = style.rounded(0xFFD71921.toInt(), 100)
        })
        column.addView(header)

        setup = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(20), style.dp(18), style.dp(20), style.dp(18))
            background = style.rounded(style.assistantBubble.takeIf { it != 0 } ?: style.codeBg, 22)
            addView(TextView(ctx, null, 0, R.style.TextMedium).apply { setText(R.string.wp_chat_setup_title); setTextColor(style.ink) })
            addView(style.caption(getString(R.string.wp_chat_setup_text)).apply { setPadding(0, style.dp(6), 0, style.dp(10)) })
            addView(style.action(getString(R.string.wp_chat_setup_button)) { (activity as ChatActivity).show(AssistantSettingsFragment()) }.apply {
                gravity = Gravity.START; setPadding(0, style.dp(6), 0, style.dp(6)); setTextColor(0xFFD71921.toInt())
            })
        }
        column.addView(setup, LinearLayout.LayoutParams(-1, -2).apply { setMargins(style.dp(16), style.dp(8), style.dp(16), style.dp(8)) })

        empty = style.caption(getString(R.string.wp_chats_empty)).apply { gravity = Gravity.CENTER; setPadding(style.dp(24), style.dp(48), style.dp(24), 0) }
        column.addView(empty)

        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = Adapter()
            clipToPadding = false
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(0, bars.top + style.dp(16), 0, 0)
            list.setPadding(style.dp(8), 0, style.dp(8), bars.bottom + style.dp(16))
            insets
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val engine = requireContext().waypoint.chatEngine
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reload()
                engine.changes.collect { reload() }
            }
        }
    }

    private suspend fun reload() {
        val c = requireContext().waypoint
        items = c.chats.conversations()
        if (view == null) return
        list.adapter?.notifyDataSetChanged()
        empty.isVisible = items.isEmpty() && c.chatEngine.isConfigured()
        setup.isVisible = !c.chatEngine.isConfigured()
    }

    private fun options(conv: Conversation) {
        val ctx = requireContext()
        val chats = ctx.waypoint.chats
        ctx.chooseOne(R.string.wp_chat_options, listOf(getString(R.string.rename), getString(R.string.wp_delete))) { i ->
            if (i == 0) ctx.promptText(R.string.rename, conv.title) { t ->
                viewLifecycleOwner.lifecycleScope.launch { chats.rename(conv.id, t.ifBlank { conv.title }); reload() }
            } else viewLifecycleOwner.lifecycleScope.launch {
                ctx.waypoint.chatEngine.stop(conv.id)
                chats.delete(conv.id); ctx.waypoint.chatEngine.pruneImages(); reload()
            }
        }
    }

    private inner class Holder(val root: LinearLayout, val title: TextView, val preview: TextView, val time: TextView) :
        RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(14))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(style.dp(8), style.dp(4), style.dp(8), style.dp(4)) }
                background = style.rounded(style.assistantBubble, 18, style.hairline)
                isClickable = true; isFocusable = true
            }
            val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            val title = TextView(ctx, null, 0, R.style.TextMedium).apply { setTextColor(style.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val time = style.caption()
            top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(time)
            val preview = TextView(ctx, null, 0, R.style.TextSmall).apply {
                setTextColor(style.faint); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, style.dp(4), 0, 0); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
            root.addView(top); root.addView(preview)
            return Holder(root, title, preview, time)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = items[position]
            h.title.text = c.title
            h.title.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            // plain text: markdown marks (**bold**, `code`, # headings, [n] citations) read as noise in a one-line preview
            h.preview.text = c.preview.replace(Regex("""\*\*|__|`+|^#+\s*|\[\d{1,2}]"""), "").replace(Regex("\\s+"), " ").trim()
            h.time.text = DateUtils.getRelativeTimeSpanString(c.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            h.root.setOnClickListener { (activity as ChatActivity).show(ChatFragment.newInstance(c.id)) }
            h.root.setOnLongClickListener { options(c); true }
        }
    }
}
