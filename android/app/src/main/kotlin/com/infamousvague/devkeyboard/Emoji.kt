package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * The emoji plugin's data: every emoji in Unicode's latest standard (Emoji 18.0, from emojibase-data 18, MIT), with
 * its name, CLDR keywords, shortcodes (emojibase's and GitHub's) and skin tones, as assets/emoji/emoji.tsv. Emoji
 * this phone's font cannot draw yet are left out, so nothing shows as a box.
 */
object Emoji {
    data class Entry(val emoji: String, val group: Int, val version: Float, val label: String, val tags: List<String>, val skins: List<String>, val codes: List<String>) {
        /** This emoji in [tone] (1..5), where it has skin tones. */
        fun toned(tone: Int) = if (tone in 1..5 && skins.size == 5) skins[tone - 1] else emoji
    }

    /** Emojibase's groups in order (2, components, is not picked from), each with the emoji its tab shows. */
    val GROUPS = listOf(0 to "😀", 1 to "👋", 3 to "🐻", 4 to "🍔", 5 to "✈️", 6 to "⚽", 7 to "💡", 8 to "💟", 9 to "🏳️")

    @Volatile var all: List<Entry> = emptyList()
        private set
    @Volatile private var loading = false
    private val byWord = HashMap<String, MutableList<Entry>>()

    fun load(context: Context, done: () -> Unit = {}) {
        if (all.isNotEmpty()) { done(); return }
        if (loading) return
        loading = true
        val app = context.applicationContext
        thread(name = "emoji", isDaemon = true) {
            val paint = Paint()
            val list = runCatching {
                app.assets.open("emoji/emoji.tsv").bufferedReader().readLines().mapNotNull { line ->
                    val f = line.split('\t')
                    if (f.size < 7) return@mapNotNull null
                    if (!paint.hasGlyph(f[0])) return@mapNotNull null
                    Entry(
                        f[0], f[1].toIntOrNull() ?: 0, f[2].toFloatOrNull() ?: 0f, f[3],
                        f[4].split(' ').filter { it.isNotEmpty() }.map { it.replace('_', ' ') },
                        f[5].split(' ').filter { it.isNotEmpty() && paint.hasGlyph(it) },
                        f[6].split(' ').filter { it.isNotEmpty() },
                    )
                }
            }.getOrDefault(emptyList())
            synchronized(byWord) {
                for (e in list) {
                    val words = e.label.lowercase().split(' ', '-', ':') + e.tags.map { it.lowercase() } + e.codes
                    for (w in words.toSet()) if (w.length >= 2) byWord.getOrPut(w) { ArrayList() } += e
                }
            }
            all = list
            loading = false
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }
    }

    /**
     * Emoji for a search, best first: a shortcode or name that is the query, then ones that start with it, then
     * keywords that do, then names that contain it. Every word of a two-word query must match.
     */
    fun search(query: String, limit: Int = 60): List<Entry> {
        val q = query.trim().lowercase().removePrefix(":").removeSuffix(":")
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Entry, Int>>()
        for (e in all) {
            var total = 0
            var ok = true
            val label = e.label.lowercase()
            for (w in words) {
                val s = when {
                    e.codes.any { it == w } || label == w -> 100
                    e.codes.any { it.startsWith(w) } -> 60
                    label.split(' ', '-', ':').any { it.startsWith(w) } -> 50
                    e.tags.any { it.lowercase() == w } -> 45
                    e.tags.any { it.lowercase().startsWith(w) } -> 30
                    label.contains(w) -> 15
                    else -> 0
                }
                if (s == 0) { ok = false; break }
                total += s
            }
            if (ok) scored += e to total
        }
        return scored.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { all.indexOf(it.first) }).take(limit).map { it.first }
    }

    /** Emoji whose name or keyword is exactly [word] (pizza → 🍕), for suggestions while typing. */
    fun forWord(word: String, limit: Int = 2): List<Entry> {
        val w = word.lowercase()
        if (w.length < 3) return emptyList()
        return synchronized(byWord) { byWord[w]?.take(limit) ?: emptyList() }
    }

    // --- recently used ------------------------------------------------------------------------------------------

    private const val RECENT_FILE = "emoji_recent"

    fun recent(context: Context): List<String> =
        context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE).getString("recent", "")!!.split(' ').filter { it.isNotEmpty() }

    fun used(context: Context, emoji: String) {
        val now = listOf(emoji) + recent(context).filter { it != emoji }
        context.getSharedPreferences(RECENT_FILE, Context.MODE_PRIVATE).edit().putString("recent", now.take(32).joinToString(" ")).apply()
    }
}

/** Emoji in a grid, drawn rather than one view each (there are nearly two thousand): only the rows on screen. */
@SuppressLint("ViewConstructor")
class EmojiGrid(context: Context, private val onPick: (String) -> Unit) : View(context) {
    private var items: List<String> = emptyList()
    /** Where each section starts, as an index into [items], with its title. */
    private var sections: List<Pair<Int, String>> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var titleColor = 0xFF8A9393.toInt()
        set(value) { field = value; invalidate() }
    private val cell get() = width / columns.toFloat()
    private val columns get() = maxOf(6, (width / dp(46f)).toInt())
    private val titleH get() = dp(28f)
    private var layoutRows: List<Row> = emptyList()

    /** One line of the grid: a section's title, or emoji from [start] to [end]. */
    private data class Row(val y: Float, val title: String?, val start: Int, val end: Int)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    fun set(items: List<String>, sections: List<Pair<Int, String>>) {
        this.items = items
        this.sections = sections
        relayout()
        requestLayout()
        invalidate()
    }

    /** Where a section's title sits, for the tabs to scroll to. */
    fun sectionY(index: Int): Int = layoutRows.firstOrNull { it.title != null && sections.indexOfFirst { s -> s.second == it.title } == index }?.y?.toInt() ?: 0

    private fun relayout() {
        if (width == 0) return
        val rows = ArrayList<Row>()
        var y = 0f
        for ((i, section) in sections.withIndex()) {
            val start = section.first
            val end = if (i + 1 < sections.size) sections[i + 1].first else items.size
            if (start >= end) continue
            rows += Row(y, section.second, start, start); y += titleH
            var at = start
            while (at < end) { rows += Row(y, null, at, minOf(end, at + columns)); y += cell; at += columns }
        }
        layoutRows = rows
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w != width) { layoutRows = emptyList() }
        val cols = maxOf(6, (w / dp(46f)).toInt())
        val c = w / cols.toFloat()
        var h = 0f
        for ((i, s) in sections.withIndex()) {
            val end = if (i + 1 < sections.size) sections[i + 1].first else items.size
            val n = end - s.first
            if (n > 0) h += titleH + ((n + cols - 1) / cols) * c
        }
        setMeasuredDimension(w, h.toInt() + dp(8f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    override fun onDraw(canvas: Canvas) {
        if (layoutRows.isEmpty()) relayout()
        val clip = canvas.clipBounds
        val c = cell
        paint.textSize = c * 0.62f
        titlePaint.textSize = dp(12.5f)
        titlePaint.color = titleColor
        titlePaint.isFakeBoldText = true
        for (row in layoutRows) {
            val h = if (row.title != null) titleH else c
            if (row.y + h < clip.top || row.y > clip.bottom) continue
            if (row.title != null) { canvas.drawText(row.title.uppercase(), dp(12f), row.y + titleH * 0.7f, titlePaint); continue }
            for (i in row.start until row.end) {
                val col = i - row.start
                val m = paint.fontMetrics
                canvas.drawText(items[i], col * c + c / 2, row.y + c / 2 - (m.ascent + m.descent) / 2, paint)
            }
        }
    }

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val c = cell
            val row = layoutRows.firstOrNull { it.title == null && e.y >= it.y && e.y < it.y + c } ?: return false
            val i = row.start + (e.x / c).toInt()
            if (i < row.end) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onPick(items[i]) }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = taps.onTouchEvent(event)
}

/** The emoji panel over the keys: a tab for each group (recent first) and the grid, in the skin tone chosen. */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val onPick: (String) -> Unit) : FrameLayout(context) {
    var theme: KeyboardTheme = KeyboardTheme.DARK
        set(value) { field = value; setBackgroundColor(value.background); grid.titleColor = value.hint; tabs.forEach { it.setTextColor(value.text) } }

    private val grid = EmojiGrid(context) { onPick(it) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }
    private val tabBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tabs = ArrayList<TextView>()
    private val status = TextView(context).apply { gravity = Gravity.CENTER; text = "Loading emoji…" }

    init {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40f)))
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /** Fills the panel: recent, then every group, each emoji in [tone]. */
    fun show(recent: List<String>, tone: Int) {
        val entries = Emoji.all
        if (entries.isEmpty()) { status.visibility = View.VISIBLE; return }
        status.visibility = View.GONE
        val items = ArrayList<String>()
        val sections = ArrayList<Pair<Int, String>>()
        val tabFor = ArrayList<String>()
        if (recent.isNotEmpty()) { sections += items.size to "Recent"; tabFor += "🕘"; items += recent }
        val names = mapOf(0 to "Smileys", 1 to "People", 3 to "Animals & nature", 4 to "Food & drink", 5 to "Travel & places", 6 to "Activities", 7 to "Objects", 8 to "Symbols", 9 to "Flags")
        for ((group, icon) in Emoji.GROUPS) {
            sections += items.size to (names[group] ?: "")
            tabFor += icon
            entries.filter { it.group == group }.forEach { items += it.toned(tone) }
        }
        grid.set(items, sections)
        tabBar.removeAllViews(); tabs.clear()
        tabFor.forEachIndexed { i, icon ->
            val tab = TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(10f), 0, dp(10f), 0)
                setOnClickListener { scroll.smoothScrollTo(0, grid.sectionY(i)) }
            }
            tabs += tab
            tabBar.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        scroll.scrollTo(0, 0)
    }
}
