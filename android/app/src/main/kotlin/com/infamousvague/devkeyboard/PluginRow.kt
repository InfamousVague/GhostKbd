package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** The small drawn icons the plugin row's buttons use. */
enum class RowIcon {
    SETTINGS, HIDE, GIF, BACK, FORWARD, SEARCH, KEY, MIC, DELETE, SHIFT, ENTER, CARET_LEFT, CARET_RIGHT, CARET_UP, CARET_DOWN, OFFLINE,
    // The tools' and states' own (RowIconsMore.kt).
    CLIPBOARD, EMOJI, GIF_TEXT, SPARKLE, WAND, FEATHER, GHOST, INCOGNITO, UNDO, REDO, BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK,
    LINK, HEADING, LIST, TASK, QUOTE, WIKI, HASH, TODAY, CALLOUT, TABLE, MATH, DIAGRAM, DIVIDER, TRASH, CLOSE, WARNING,
    RETRY, CHECK, NOTE, PIN,
}

/**
 * One thing a plugin puts in the row above the keys.
 *
 * - [label]: text, a suggestion or a chip's name. [bold] is the pick (Gboard's middle suggestion).
 * - [icon]: a drawn icon instead of, or before, the label.
 * - [view]: a view of the plugin's own, shown as it is (a password manager's inline suggestion).
 * - [wide]: a suggestion: shares the row's width with the others, a third each, as Gboard's do.
 * - [fill]: takes all the width the others leave (a search bar).
 * - [tint]: the chip's colour (RowTint): its icon in the hue, on a faint ground of it.
 * - [busy]: the AI is working on it: drawn as a ThinkingView, a sparkle and the words with a light passing over them.
 * - [description]: what a reader says for an icon with no words (TalkBack).
 * - [dim]: drawn faint: something that cannot be used as things stand (a network chip while Offline is on, which
 *   still says why when tapped), or a line that only says something (the clipboard's "Hold a clip to pin it").
 * - [code]: a one-time code from a text message, drawn as a CodeChip: a chip of its own, the [label] as the code in
 *   its groups, with the time it has left running round its edge.
 * - [onLong]: what holding it does, where a tap is not all there is to it (a clip in the clipboard's list is pinned
 *   or unpinned by a hold). The hold has its own, longer tick.
 * - [guard]: an arrow (Undo, Redo), which its own tap can move or take out of the row: for a moment after it is
 *   tapped nothing else is taken where it stood ([TapGuard]).
 */
class RowItem(
    val id: String,
    val label: CharSequence? = null,
    val icon: RowIcon? = null,
    val bold: Boolean = false,
    val wide: Boolean = false,
    val chip: Boolean = false,
    val view: View? = null,
    val fill: Boolean = false,
    val tint: RowTint? = null,
    val busy: Boolean = false,
    val description: String? = null,
    val dim: Boolean = false,
    val code: RowCode? = null,
    val guard: Boolean = false,
    val onLong: (() -> Unit)? = null,
    val onTap: (() -> Unit)? = null,
)

/**
 * A tap that moves its own button. An arrow is in the row only while it has something to do, so the last Undo takes
 * the Undo arrow away and Redo stands where it stood; and an emoji chip that came with a word goes with the word, and
 * every tool after it slides along. A thumb that is still tapping must not land on whatever took the arrow's place:
 * for [holdMs] after an arrow ([RowItem.guard]) is tapped, nothing else is taken where it stood. The arrow itself is
 * taken wherever it is.
 *
 * No Android in it (TapGuardTest). Places are across the screen, in pixels; times in milliseconds.
 */
class TapGuard(private val holdMs: Long = HOLD_MS) {
    private var id: String? = null
    private var from = 0
    private var to = 0
    private var at = 0L

    /** The arrow [id], standing from [from] to [to], was tapped at [now]. */
    fun tapped(id: String, from: Int, to: Int, now: Long) {
        this.id = id; this.from = from; this.to = to; at = now
    }

    /** Whether a tap at [now] on the item [id], standing from [from] to [to], is taken. */
    fun takes(id: String, from: Int, to: Int, now: Long): Boolean {
        val arrow = this.id ?: return true
        if (id == arrow || now < at || now - at >= holdMs) return true
        // On the arrow's place where it covers more than half of the narrower of the two: a neighbour that only
        // touches the place is taken as ever.
        val shared = minOf(to, this.to) - maxOf(from, this.from)
        return shared * 2 <= minOf(to - from, this.to - this.from)
    }

    companion object {
        /** A quick thumb taps three to five times a second: two of its taps. */
        const val HOLD_MS = 600L
    }
}

/**
 * The plugin row: a strip above the keys that plugins fill, scrolling sideways when they offer more than fits.
 * Autocorrect's suggestions, swipe typing's alternatives, a password manager's logins, the GIF key... each plugin
 * hands the service its items and the service puts them here in order (DevKeyboardService.refreshRow). A hide button
 * is at its end when the old toolbar setting is on.
 *
 * The everyday tools (the clipboard, emoji, undo and redo) stand at the row's start, before the suggested words, and
 * stay there whatever the row is suggesting (Matt, 2026-11-12: "Clipboard, forward and back, and emoji should be left
 * aligned on the toolbar before the auto suggest"; and 2026-11-10: "the word suggestion row is too long move things
 * like the back forward clipboard emoji and gif stuff to the front of the list and remove the settings cog from the
 * toolbar"). Only on a row too narrow for them, two words and a tool's room ([pins]: a keyboard made small for one
 * hand) are they the first of the tools after the words instead. There is no settings button: a long press on the
 * { } key or the comma opens Settings, as it always has.
 *
 * Words (Matt, 2026-11-12: "Center the auto complete words and only show about three of them reserve some space on
 * the right to show at least one toolbar icon"): whenever the row suggests words it shows about three, sharing what
 * the pinned tools leave (in the row's middle where it is wide enough for that); the other tools follow them in the
 * strip, and room for one is kept at the row's end, so the first of them (GIFs, where they are on) is whole in view
 * and the rest are a slide away ([WordRoom]). Where three words would each be too narrow to read, two are shown.
 * Undo and Redo are among the everyday tools only while each has something to do. Whether the everyday tools are
 * pinned is asked as if both arrows were there ([setItems]'s most), so the row keeps its shape as they come and go;
 * and what takes the place of an arrow that its own tap took away takes no tap for a moment ([TapGuard]).
 *
 * The row's first tool keeps a little off the screen's edge ([EDGE_DP]; Matt, 2026-11-11: "We need a small bit of
 * padding to the left of the toolbar the undo button is right against the edge of the phone screen"): the pinned
 * tools by their own padding, and a strip that opens with a tool or a chip by the strip's, which scrolls away with
 * it. Suggested words span the row in thirds, edge to edge, as before.
 */
@SuppressLint("ViewConstructor")
class PluginRow(context: Context, private val onHide: () -> Unit, private val onArrow: (Int) -> Unit = {}) : LinearLayout(context) {
    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; restyle() }

    var showHide = false
        set(value) { if (field != value) { field = value; hide.visibility = if (value) VISIBLE else GONE; again() } }

    /** The everyday tools while they are pinned at the row's start ([setItems]). */
    private val lead = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE; setPadding(dp(EDGE_DP), 0, 0, 0) }
    private val hide = IconButton(context, RowIcon.HIDE).apply { setOnClickListener { onHide() }; visibility = GONE }

    /**
     * Arrow keys (Settings › Row tools › Arrow keys), SwiftKey's way: ← ↑ ↓ → pinned at the row's end, there whatever
     * the row is showing, each moving the cursor and repeating while held.
     */
    /** Condensed keys: a slimmer strip, so the chips keep less room above and below them. */
    var condensed = false
        set(value) { if (field != value) { field = value; again() } }
    var showArrows = false
        set(value) { if (field != value) { field = value; arrows.visibility = if (value) VISIBLE else GONE; again() } }
    private val arrowButtons = listOf(
        RowIcon.CARET_LEFT to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        RowIcon.CARET_UP to android.view.KeyEvent.KEYCODE_DPAD_UP,
        RowIcon.CARET_DOWN to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        RowIcon.CARET_RIGHT to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    ).map { (icon, code) -> IconButton(context, icon).also { repeating(it) { onArrow(code) } } }
    private val arrows = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        for (b in arrowButtons) addView(b, LayoutParams(dp(36f), LayoutParams.MATCH_PARENT))
    }

    /** [action] on touch, then again and again while held: 400 ms before the first repeat, then every 50 ms. */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeating(view: View, action: () -> Unit) {
        val again = object : Runnable { override fun run() { action(); view.postDelayed(this, 50) } }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); v.postDelayed(again, 400) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { v.isPressed = false; v.removeCallbacks(again) }
            }
            true
        }
    }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }
    private val strip = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    /** What the strip shows now, and what is pinned before it. */
    private var items: List<RowItem> = emptyList()
    private var pinned: List<RowItem> = emptyList()
    /** What [setItems] was last given, to lay out again when the row's width or look changes. */
    private var given: List<RowItem> = emptyList()
    private var givenLead: List<RowItem> = emptyList()
    private var givenLeadAt = -1
    private var givenMost = 0
    private val guard = TapGuard()
    private val spot = IntArray(2)

    /** Whether the row is suggesting words now (or keeping their room: DevKeyboardService.heldWords). */
    val showsWords: Boolean get() = items.any { it.wide }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        scroll.addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(lead, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(arrows, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(hide, LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        restyle()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /** Lays the row out afresh from what it was last given: its width, its look or what is pinned at its ends changed. */
    private fun again() {
        items = emptyList()
        pinned = listOf(RowItem("\u0000"))
        setItems(given, givenLead, givenLeadAt, givenMost)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Unfolded, turned on its side: whether the everyday tools fit beside three words is asked again.
        if (w != oldw && oldw > 0) post { again() }
    }

    private fun look(list: List<RowItem>) = list.map { listOf(it.id, it.label?.toString(), it.bold, it.view, it.tint, it.busy, it.dim) }

    /** The width the strip and what is pinned before it share: the row's, less the arrow keys and the hide button. */
    private fun shared(): Int =
        (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - (if (showArrows) dp(36f) * arrowButtons.size else 0) - (if (showHide) dp(44f) else 0)

    /**
     * Whether [count] everyday tools are pinned at the row's start as the row is now (Matt, 2026-11-12: "Clipboard,
     * forward and back, and emoji should be left aligned on the toolbar before the auto suggest"): on every row that
     * has room for them, two words and a tool's room at its end ([WordRoom.pins]), which is every phone. Only a very
     * narrow row (a keyboard made small for one hand) has them after the words instead. In 0.16.0 they were pinned
     * only beside words that could still stand in the row's middle, so on a phone they followed the words; before
     * that, wherever three words of 92dp fitted beside them.
     */
    fun pins(count: Int): Boolean = WordRoom.pins(shared(), count * dp(LEAD_DP) + dp(EDGE_DP), count, dp(END_DP), dp(WORD_MIN_DP))

    /**
     * Fills the row with [next]. [leading] are the everyday tools: pinned at the row's start where they and three
     * words fit ([pins]); else put into the strip before the item at [leadAt] (the first of the other tools), or
     * left out where [leadAt] is negative (a narrow row showing only words). [most] is how many everyday tools there
     * are when both arrows are among them: whether they are pinned is asked of that number, so a row does not turn
     * from one shape to the other as Undo or Redo comes and goes.
     */
    fun setItems(next: List<RowItem>, leading: List<RowItem> = emptyList(), leadAt: Int = -1, most: Int = leading.size) {
        given = next; givenLead = leading; givenLeadAt = leadAt; givenMost = most
        val pin = leading.isNotEmpty() && pins(maxOf(most, leading.size))
        val at = leadAt.coerceAtMost(next.size)
        @Suppress("NAME_SHADOWING")
        val next = if (pin || leading.isEmpty() || at < 0) next else next.take(at) + leading + next.drop(at)
        val front = if (pin) leading else emptyList()
        // The strip is filled again whenever what is pinned before it changed, even with nothing to put in it: a chip
        // that was tapped away as Undo lit up (a pasted clip, a one-time code, in an empty field) must not stay drawn.
        var refill = false
        if (look(front) != look(pinned)) {
            refill = true
            pinned = front
            lead.removeAllViews()
            for (item in front) {
                val lp = LinearLayout.LayoutParams(dp(LEAD_DP - 4f), ViewGroup.LayoutParams.MATCH_PARENT)
                lp.leftMargin = dp(2f); lp.rightMargin = dp(2f); lp.topMargin = dp(if (condensed) 4f else 6f); lp.bottomMargin = dp(if (condensed) 4f else 6f)
                lead.addView(makeView(item).also { it.minimumWidth = 0 }, lp)
            }
            lead.visibility = if (front.isEmpty()) GONE else VISIBLE
            // What the strip has to share changed with it: its words are measured again.
            items = emptyList()
        }
        // The same words again (a refresh with nothing new) keeps the row as it is, scroll and all.
        if (!refill && look(next) == look(items)) return
        // The same buttons with one lit or dimmed (Undo, after a tap on it) stay where they were scrolled to: the
        // arrows must not slide out from under the thumb that is tapping them.
        val keepScroll = if (next.map { it.id } == items.map { it.id }) scroll.scrollX else 0
        items = next
        strip.removeAllViews()
        // Off the screen's edge: the pinned tools carry the padding themselves; a strip that opens with a tool or a
        // chip carries it at its start. Either way the words beside or after them have that much less to share.
        val edge = if (front.isNotEmpty() || next.firstOrNull()?.wide == false) dp(EDGE_DP) else 0
        // What is laid out: [next], less any word there is no room for (below).
        var laid = next
        var views = laid.map { item -> item.view?.also { (it.parent as? ViewGroup)?.removeView(it) } ?: makeView(item) }
        var toolsAt = at
        // A chip that stands before the words (a clip copied a moment ago, "Incognito on") takes its room out of
        // theirs, and so does one between them and the tools (an emoji for the word), so the tool at the row's end
        // is still in view; a long one is cut short at a third of the row.
        fun taken(i: Int): Int {
            val v = views[i]
            if (v is TextView) v.maxWidth = shared() / 3
            v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(dp(36f), MeasureSpec.EXACTLY))
            return v.measuredWidth + if (laid[i].chip || laid[i].view != null || laid[i].busy) dp(8f) else 0
        }
        fun wordRoom(): WordRoom {
            val firstWord = laid.indexOfFirst { it.wide }
            val lastWord = laid.indexOfLast { it.wide }
            val ahead = (0 until firstWord).sumOf { taken(it) }
            val after = if (lastWord < 0) 0 else (lastWord + 1 until toolsAt).sumOf { taken(it) }
            // Whether the words stand in the row's middle is asked as if both arrows were pinned, as whether the
            // tools are pinned is: on a phone, where they would not fit there with Undo and Redo beside them, they
            // share what is left in every state, and the tool at the row's end keeps its place as the arrows come and go.
            val pinnedMost = if (front.isEmpty()) 0 else maxOf(most, front.size) * dp(LEAD_DP)
            return WordRoom.of(
                total = shared(), before = front.size * dp(LEAD_DP) + edge + ahead, words = laid.count { it.wide }, firstWord = firstWord,
                lastWord = lastWord, count = laid.size, end = dp(END_DP), least = dp(WORD_MIN_DP), after = after,
                beforeMost = pinnedMost + edge + ahead,
            )
        }
        var words = wordRoom()
        // About three words: where the tools pinned before them leave no room for three that can be read, the last
        // of them (the least likely: the pick stands first or in the middle) is left out, and the others share it.
        val surplus = laid.count { it.wide } - words.shown
        if (surplus > 0) {
            val gone = laid.indices.filter { laid[it].wide }.takeLast(surplus).toSet()
            views = views.filterIndexed { i, _ -> i !in gone }
            laid = laid.filterIndexed { i, _ -> i !in gone }
            if (toolsAt >= 0) toolsAt -= gone.count { it < toolsAt }
            words = wordRoom()
        }
        strip.setPadding((if (front.isEmpty()) edge else 0) + words.lead, 0, 0, 0)
        for ((i, item) in laid.withIndex()) {
            val v = views[i]
            val lp = if (item.fill) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) else LinearLayout.LayoutParams(
                if (item.wide) words.width else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (item.view != null) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            if (item.chip || item.view != null || item.busy) { lp.leftMargin = dp(4f); lp.rightMargin = dp(4f); lp.topMargin = dp(if (condensed) 4f else 6f); lp.bottomMargin = dp(if (condensed) 4f else 6f) }
            // One word or two stand in the middle of the room three would have had.
            if (item.wide && i == words.first) lp.leftMargin += words.gap
            if (item.wide && i == words.last) lp.rightMargin += words.gap
            strip.addView(v, lp)
        }
        // A filling item (a search bar, the AI's words, an error) shares the row with the buttons beside it and is cut
        // short to leave them room: a long error no longer pushes Retry and the close button off the end.
        val room = shared() - front.size * dp(LEAD_DP) - edge
        if (laid.any { it.fill }) {
            var others = 0
            for (i in 0 until strip.childCount) {
                if (laid.getOrNull(i)?.fill == true) continue
                val c = strip.getChildAt(i)
                val lp = c.layoutParams as LinearLayout.LayoutParams
                if (lp.width > 0) others += lp.width else {
                    c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(dp(36f), MeasureSpec.EXACTLY))
                    others += c.measuredWidth
                }
                others += lp.leftMargin + lp.rightMargin
            }
            val fills = laid.count { it.fill }
            val cap = ((room - others) / fills - dp(8f)).coerceAtLeast(room / 3)
            for (i in 0 until strip.childCount) if (laid.getOrNull(i)?.fill == true) when (val c = strip.getChildAt(i)) {
                is TextView -> c.maxWidth = cap
                is ThinkingView -> c.maxWidth = cap
            }
        }
        scroll.scrollTo(keepScroll, 0)
    }

    /**
     * How the suggested words share the row (Matt, 2026-11-12: "Center the auto complete words and only show about
     * three of them reserve some space on the right to show at least one toolbar icon"). Three words, a third each of
     * what is theirs; one or two stand in the middle of that ([gap] before the first and after the last). Where tools
     * follow them in the strip, room for one is kept at the row's end, so a tool is always in view and says there
     * are more to slide to. And the words stand in the row's middle: as much is kept clear before them ([lead], less
     * what the pinned tools already take) as after them, as long as a word still has [least] to be read in.
     *
     * No Android in it, so the sums can be tried off the phone (WordRoomTest). All in pixels.
     */
    class WordRoom(val width: Int, val lead: Int, val gap: Int, val first: Int, val last: Int, val shown: Int = 0) {
        companion object {
            /** The most words that share the row; more than that and the rest slide. */
            const val MOST = 3

            /** The narrowest a word is laid out: three quarters of what it is read in comfortably ([least]). */
            fun floor(least: Int): Int = least * 3 / 4

            /**
             * Whether [count] everyday tools, taking [before] of a row [total] wide, are pinned at its start:
             * wherever two words that can still be read fit between them and the room for one tool ([end]) kept
             * at the row's other end.
             */
            fun pins(total: Int, before: Int, count: Int, end: Int, least: Int): Boolean = count > 0 && total - before - end >= 2 * floor(least)

            /**
             * [total] is the row's width for the strip and what is pinned before it; [before] what stands before the
             * words (the pinned tools, the edge, a chip that leads the strip); [words] how many of the strip's [count]
             * items are words, the first at [firstWord] and the last at [lastWord]; [end] the room one tool takes;
             * [least] the least a word is read in; [after] what stands between the words and the tools (an emoji for
             * the word). [shown] in what comes back is how many of the words there is room for: fewer than [words]
             * where three would each be narrower than [floor], and the caller leaves the last of them out.
             * [beforeMost] is what [before] comes to when every pinned tool is there (Undo and Redo both): the words
             * stand in the row's middle only where they could then too, so a row is of one kind in all its states.
             */
            fun of(total: Int, before: Int, words: Int, firstWord: Int, lastWord: Int, count: Int, end: Int, least: Int, after: Int = 0, beforeMost: Int = before): WordRoom {
                if (words == 0) return WordRoom(0, 0, 0, -1, -1, 0)
                // Tools after the words: one of them is kept in view, past whatever chip came with the words.
                val keep = if (lastWord < count - 1) end + after else 0
                // In the row's middle only where the words open the strip (a chip about this very moment may stand
                // before them) and three still have room.
                val side = maxOf(before, keep)
                val centred = firstWord == 0 && (total - 2 * side) / MOST >= least && (total - 2 * maxOf(beforeMost, keep)) / MOST >= least
                val area = if (centred) total - 2 * side else total - before - keep
                // More than three (nothing gives the row more today): a third each, and the rest slide.
                val third = area / MOST
                if (third >= least) return WordRoom(third, if (centred) side - before else 0, if (words < MOST) (MOST - words) * third / 2 else 0, firstWord, lastWord, words)
                // A tight row (the tools pinned before the words on a phone, a chip before them): three share what
                // there is, down to three quarters of what a word is read in; where they would be narrower than
                // that, two share it, or one has it; and one word or two are as wide as a word is read in where
                // that fits. A single word wider than the room slides.
                var shown = minOf(words, MOST)
                while (shown > 1 && area / shown < floor(least)) shown--
                val width = maxOf(third, minOf(least, area / shown), floor(least), 1)
                return WordRoom(width, 0, ((area - shown * width) / 2).coerceAtLeast(0), firstWord, lastWord, if (words > MOST) words else shown)
            }
        }
    }

    private fun makeView(item: RowItem): View {
        if (item.busy) {
            return ThinkingView(context, item.label ?: "Thinking…", theme).apply {
                isClickable = item.onTap != null
                setOnClickListener { tap(item, it) }
            }
        }
        if (item.code != null) {
            return CodeChip(context, item.label ?: "", item.code, theme).apply {
                contentDescription = item.description
                setOnClickListener { tap(item, it) }
                isClickable = item.onTap != null
                if (item.dim) alpha = DIM
            }
        }
        if (item.icon != null && item.label == null) {
            return IconButton(context, item.icon).apply {
                color = item.tint?.ink(theme) ?: theme.toolbarIcon
                minimumWidth = dp(44f)
                contentDescription = item.description
                // A tool's chip: the icon alone on a pill, in its colour where it has one.
                if (item.chip) background = pressable(true, item.tint)
                setOnClickListener { tap(item, it) }
                if (item.dim) alpha = DIM
                isClickable = item.onTap != null
                holdable(this, item)
            }
        }
        return TextView(context).apply {
            text = item.label
            gravity = if (item.fill) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.wide) 16f else 14f)
            typeface = Typeface.create(if (item.bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setTextColor(theme.text)
            val padX = if (item.wide) dp(6f) else dp(12f)
            setPadding(padX, 0, padX, 0)
            background = pressable(item.chip, item.tint)
            contentDescription = item.description
            setOnClickListener { tap(item, it) }
            isClickable = item.onTap != null
            holdable(this, item)
            if (item.dim) alpha = DIM
            if (item.icon != null) {
                setCompoundDrawablesRelative(IconDrawable(item.icon, item.tint?.ink(theme) ?: theme.toolbarIcon, dp(18f)), null, null, null)
                compoundDrawablePadding = dp(6f)
            }
        }
    }

    private fun tap(item: RowItem, view: View) {
        val action = item.onTap ?: return
        // An arrow that was just tapped has moved or gone, and this stands where it stood: not what the thumb is after.
        view.getLocationOnScreen(spot)
        val now = android.os.SystemClock.uptimeMillis()
        if (!guard.takes(item.id, spot[0], spot[0] + view.width, now)) return
        if (item.guard) guard.tapped(item.id, spot[0], spot[0] + view.width, now)
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        try { action() } catch (t: Throwable) { android.util.Log.e("GhostKeyboard", "row item ${item.id} failed", t) }
    }

    /** Gives [view] the item's hold, where it has one ([RowItem.onLong]): a longer tick, and the tap is not taken as well. */
    private fun holdable(view: View, item: RowItem) {
        val action = item.onLong ?: return
        view.setOnLongClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            try { action() } catch (t: Throwable) { android.util.Log.e("GhostKeyboard", "row item ${item.id} hold failed", t) }
            true
        }
    }

    private fun pressable(chip: Boolean, tint: RowTint? = null): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(color) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(theme.keyPressed))
            addState(intArrayOf(), shape(if (!chip) 0 else tint?.wash(theme) ?: theme.functionKey))
        }
    }

    private companion object {
        /** How faint a [RowItem.dim] item is drawn. */
        const val DIM = 0.45f
        /** The room one pinned everyday tool takes, in dp. */
        const val LEAD_DP = 40f
        /** What the row's first tool keeps between itself and the screen's edge, in dp. */
        const val EDGE_DP = 6f
        /** The room kept at the row's end for the first of the tools that follow the words: one tool's chip. */
        const val END_DP = 48f
        /** The least a suggested word is read in. */
        const val WORD_MIN_DP = 80f
    }

    private fun restyle() {
        setBackgroundColor(theme.background)
        hide.color = theme.toolbarIcon
        arrowButtons.forEach { it.color = theme.toolbarIcon }
        again()
    }

    /** A drawn icon button: no images to ship, and it takes the theme's colour. */
    private class IconButton(context: Context, val icon: RowIcon) : View(context) {
        var color = 0xFF444746.toInt()
            set(value) { field = value; invalidate() }
        private val drawer = IconDrawable(icon, color, 0)

        init {
            isClickable = true
            val bg = StateListDrawable()
            bg.addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22808080) })
            background = bg
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()
            setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            val s = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
            drawer.ink = color
            drawer.setBounds(((width - s) / 2).toInt(), ((height - s) / 2).toInt(), ((width + s) / 2).toInt(), ((height + s) / 2).toInt())
            drawer.draw(canvas)
        }
    }
}

/**
 * The row's icons, from the chosen icon pack (RowIcons.kt, Ghost.md's packs): drawn on the 24 grid at whatever size
 * the bounds give, in Ghost.md's look, outlines with a wash inside: the icon's body filled at a third of the ink.
 */
class IconDrawable(private val icon: RowIcon, var ink: Int, size: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { if (size > 0) setBounds(0, 0, size, size) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        drawGlyph(canvas, RowIcons.icon(icon), b.width().toFloat(), ink, paint)
        canvas.restore()
    }

    companion object {
        /**
         * [glyph] in a [size]-pixel square at the canvas' origin, in [ink]: the wash under it (at its own share of the
         * ink, or all of it with [filled]), then its lines stroked or its shapes filled.
         */
        fun drawGlyph(canvas: Canvas, glyph: PackIcon, size: Float, ink: Int, paint: Paint, filled: Boolean = false) {
            canvas.save()
            if (glyph.rotate != 0f) canvas.rotate(glyph.rotate, size / 2f, size / 2f)
            val s = size / glyph.box
            canvas.scale(s, s)
            canvas.translate(-glyph.ox, -glyph.oy)
            val alpha = android.graphics.Color.alpha(ink)
            paint.color = ink
            paint.style = Paint.Style.FILL
            paint.alpha = if (filled) alpha else (glyph.washAlpha * alpha).toInt()
            for (d in glyph.wash) canvas.drawPath(SvgPath.parse(d), paint)
            paint.alpha = alpha
            if (glyph.lines) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * glyph.box / 24f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
            }
            for (d in glyph.paths) canvas.drawPath(SvgPath.parse(d), paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The input view: the drawn keyboard, with the plugin row placed in the strip it leaves between its preview room and
 * its keys, and a plugin panel (GIFs) over the keys when one is open. The keyboard is on top so key previews draw
 * over the row; it hands touches in the row's strip down to the row.
 */
@SuppressLint("ViewConstructor")
class KeyboardFrame(context: Context, val keyboard: KeyboardView, val row: PluginRow, val panel: FrameLayout) : FrameLayout(context) {
    /** The line along the top of the keys that runs while something works (RowStyle.kt). */
    val working = WorkingLine(context)

    init {
        clipChildren = false
        addView(row)
        addView(keyboard)
        addView(panel)
        addView(working)
        panel.visibility = GONE
    }

    private val lineHeight get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics).toInt().coerceAtLeast(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboard.measure(widthMeasureSpec, heightMeasureSpec)
        val w = keyboard.measuredWidth
        val h = keyboard.measuredHeight
        row.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight(), MeasureSpec.EXACTLY))
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((keyboard.keysBottom - keyboard.rowBottom).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY))
        working.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lineHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    /**
     * The row's pixels: from the pixel its strip starts in to one past the pixel the keys start in. The strip's edges
     * fall between pixels, and rounding both down left a hairline of the app showing between the row and the keys;
     * the overlap is under the keyboard's own background, which is drawn over the row.
     */
    private fun rowHeight() = (kotlin.math.ceil(keyboard.rowBottom) - kotlin.math.floor(keyboard.rowTop)).toInt().coerceAtLeast(0) + 1

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        val top = kotlin.math.floor(keyboard.rowTop).toInt()
        row.layout(0, top, row.measuredWidth, top + row.measuredHeight)
        panel.layout(0, keyboard.rowBottom.toInt(), panel.measuredWidth, keyboard.rowBottom.toInt() + panel.measuredHeight)
        // Along the row's lower edge, where the keys begin.
        val line = keyboard.rowBottom.toInt()
        working.layout(0, line - working.measuredHeight, working.measuredWidth, line)
    }

    // Nothing here consumes touches itself: the children decide.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
}
