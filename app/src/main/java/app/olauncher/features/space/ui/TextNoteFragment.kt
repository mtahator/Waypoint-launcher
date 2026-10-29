package app.olauncher.features.space.ui

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.core.ui.chooseOne
import app.olauncher.core.ui.focusAndShowKeyboard
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.ChatStyle
import app.olauncher.features.space.domain.Categories
import app.olauncher.helper.createDialog
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * A written Space note: title, text and images. Saved when you leave; categorised and scanned for
 * to-dos/reminders ("remind me to … tomorrow at 5") automatically.
 */
class TextNoteFragment : Fragment() {

    private lateinit var style: ChatStyle
    private lateinit var title: EditText
    private lateinit var body: EditText
    private lateinit var imagesRow: LinearLayout
    private lateinit var categoryView: TextView
    private var noteId = 0L
    private var images = mutableListOf<String>()
    private var category = ""
    private var loaded = false
    private var deleted = false
    private var creating = false

    private val space get() = requireContext().waypoint.space

    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        viewLifecycleOwner.lifecycleScope.launch {
            uris.forEach { u -> space.copyImage(u)?.let { images += it } }
            bindImages()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, savedInstanceState: Bundle?): View {
        noteId = savedInstanceState?.getLong(ARG_ID) ?: requireArguments().getLong(ARG_ID)
        val ctx = requireContext()
        style = ChatStyle(ctx)
        val root = FrameLayout(ctx).apply { if (!style.glass) setBackgroundColor(style.scrim) }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(style.dp(4), 0, style.dp(8), style.dp(6)) }
        bar.addView(style.action("‹") { requireActivity().onBackPressedDispatcher.onBackPressed() }.apply { textSize = 26f })
        categoryView = style.caption("").apply {
            setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6)); background = style.rounded(0, 100, style.hairline)
            setOnClickListener { chooseCategory() }
        }
        bar.addView(categoryView)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_image); scaleType = ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(style.ink)
            contentDescription = getString(R.string.wp_add_image)
            setOnClickListener { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }, LinearLayout.LayoutParams(style.dp(44), style.dp(44)))
        bar.addView(style.action("⋯") { menu() }.apply { textSize = 22f })
        column.addView(bar)

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(style.dp(20), 0, style.dp(20), style.dp(40)) }
        scroll.addView(content)
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        title = EditText(ctx).apply {
            setHint(R.string.wp_note_title_hint); setTextColor(style.ink); setHintTextColor(style.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(0, style.dp(8), 0, style.dp(8))
        }
        content.addView(title)
        imagesRow = LinearLayout(ctx)
        content.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(imagesRow) })
        body = EditText(ctx).apply {
            setHint(R.string.wp_note_body_hint); setTextColor(style.ink); setHintTextColor(style.faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); background = null; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setLineSpacing(0f, 1.2f); minLines = 8
            setPadding(0, style.dp(10), 0, style.dp(10))
        }
        content.addView(body, LinearLayout.LayoutParams(-1, -2))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            column.setPadding(0, bars.top + style.dp(8), 0, max(bars.bottom, ime.bottom))
            insets
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (noteId != 0L && !loaded) space.notes.get(noteId)?.let { n ->
                title.setText(n.title); body.setText(n.body); images = n.images.toMutableList(); category = n.category
            }
            loaded = true
            bindImages(); bindCategory()
            if (noteId == 0L) body.focusAndShowKeyboard()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(ARG_ID, noteId)
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        if (!loaded || deleted) return
        val t = title.text.toString(); val b = body.text.toString()
        if (noteId == 0L && t.isBlank() && b.isBlank() && images.isEmpty()) return
        if (noteId == 0L && creating) return // first save still in flight
        if (noteId == 0L) creating = true
        val c = requireContext().waypoint
        val snapshot = images.toList()
        val chosen = category
        c.scope.launch {
            noteId = c.space.saveText(noteId, t, b, snapshot)
            creating = false
            if (chosen.isNotBlank()) c.space.setCategory(noteId, chosen)
        }
    }

    private fun bindCategory() {
        categoryView.text = category.ifBlank { getString(R.string.wp_category_auto) }
    }

    private fun chooseCategory() {
        val options = listOf(getString(R.string.wp_category_auto)) + Categories.ALL
        requireContext().chooseOne(R.string.wp_category, options) { i -> category = if (i == 0) "" else options[i]; bindCategory() }
    }

    private fun bindImages() {
        imagesRow.removeAllViews()
        val ctx = requireContext()
        images.forEach { name ->
            val file = space.imageFile(name)
            val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, b)
            var s = 1; while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= 480) s *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) ?: return@forEach
            imagesRow.addView(ImageView(ctx).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = style.rounded(style.codeBg, 14)
                setOnLongClickListener {
                    ctx.createDialog(R.string.wp_remove_image, R.string.wp_delete, onAction = { images.remove(name); bindImages() }).showRespectingStatusBar()
                    true
                }
            }, LinearLayout.LayoutParams(style.dp(160), style.dp(160)).apply { marginEnd = style.dp(8); bottomMargin = style.dp(6) })
        }
    }

    private fun menu() {
        requireContext().chooseOne(R.string.wp_space, listOf(getString(R.string.wp_add_image), getString(R.string.wp_delete))) { i ->
            if (i == 0) pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else requireContext().createDialog(R.string.wp_delete, R.string.wp_delete, message = R.string.wp_note_delete_message, onAction = {
                deleted = true
                val c = requireContext().waypoint
                val id = noteId
                if (id != 0L) c.scope.launch { c.space.delete(id) }
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }).showRespectingStatusBar()
        }
    }

    companion object {
        private const val ARG_ID = "note"
        fun newInstance(id: Long) = TextNoteFragment().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
    }
}
