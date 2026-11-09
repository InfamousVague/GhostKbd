package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One GIPHY result: a small preview to show, and the GIF to send. */
data class Gif(val id: String, val preview: String, val full: String, val pageUrl: String, val ratio: Float)

/**
 * The GIF plugin's search: GIPHY's API (https://developers.giphy.com), keyed by the build's GIPHY_API_KEY or the key
 * pasted in Settings › GIFs. Results are rated PG-13 at most.
 */
object Giphy {
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /**
     * The key to use. GIPHY keys are letters and digits only, so anything else a paste brings along (quotes, spaces,
     * an invisible character from a web page) is dropped.
     */
    fun key(prefs: KeyboardPrefs): String = prefs.giphyKey.filter { it.isLetterOrDigit() }.ifBlank { BuildConfig.GIPHY_KEY }

    /** A failed request, with what the person should read. */
    class GiphyError(message: String) : Exception(message)

    /**
     * Trending GIFs for an empty [query], else a search. [done] runs on the main thread with the GIFs, or with null
     * and the reason it failed, in words for the panel.
     */
    fun search(prefs: KeyboardPrefs, query: String, done: (List<Gif>?, String?) -> Unit) {
        val key = key(prefs)
        if (key.isBlank()) { done(null, "Add a GIPHY key in Settings › GIFs"); return }
        pool.execute {
            val result = runCatching {
                val q = query.trim()
                val url = if (q.isEmpty()) "https://api.giphy.com/v1/gifs/trending?api_key=$key&limit=30&rating=pg-13"
                else "https://api.giphy.com/v1/gifs/search?api_key=$key&q=${URLEncoder.encode(q, "UTF-8")}&limit=30&rating=pg-13"
                val body = fetchApi(url)
                val data = runCatching { JSONObject(body).getJSONArray("data") }.getOrElse { throw GiphyError("GIPHY sent back something unexpected") }
                // One odd result is skipped, not the whole search.
                (0 until data.length()).mapNotNull { i -> runCatching { gif(data.getJSONObject(i)) }.getOrNull() }
            }
            result.exceptionOrNull()?.let { android.util.Log.w("Giphy", "search failed", it) }
            val reason = result.exceptionOrNull()?.let { e ->
                if (e is GiphyError) e.message else "Couldn’t reach GIPHY: ${if (e is java.io.IOException) "no connection" else e.javaClass.simpleName}"
            }
            main.post { done(result.getOrNull(), reason) }
        }
    }

    private fun gif(o: JSONObject): Gif? {
        val images = o.getJSONObject("images")
        val small = images.optJSONObject("fixed_width_small") ?: images.optJSONObject("fixed_width") ?: return null
        val full = images.optJSONObject("downsized") ?: images.optJSONObject("original") ?: small
        val w = small.optString("width").toFloatOrNull() ?: 100f
        val h = small.optString("height").toFloatOrNull() ?: 100f
        return Gif(o.getString("id"), small.getString("url"), full.getString("url"), o.optString("url"), if (h > 0) w / h else 1f)
    }

    /** An API call's body, or a [GiphyError] saying in words why GIPHY refused it. */
    private fun fetchApi(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "GhostKeyboard")
        try {
            val code = c.responseCode
            if (code in 200..299) return c.inputStream.use { String(it.readBytes()) }
            throw GiphyError(
                when (code) {
                    401, 403 -> "GIPHY turned down the key in Settings › GIFs. Check it is the API key (not an SDK key) and copied whole."
                    429 -> "This GIPHY key has used up its searches for now (a beta key gets 100 an hour). Try again later."
                    else -> "GIPHY answered with an error ($code). Try again in a moment."
                },
            )
        } finally {
            c.disconnect()
        }
    }

    fun load(url: String, done: (ByteArray?) -> Unit) {
        pool.execute {
            val bytes = runCatching { fetch(url) }.getOrNull()
            main.post { done(bytes) }
        }
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "GhostKeyboard")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** A GIF's bytes as something to show: animated on Android 9 and later, its first frame before. */
    fun drawable(context: Context, bytes: ByteArray): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
            (d as? Animatable)?.start()
            d
        } else {
            BitmapDrawable(context.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
    }.getOrNull()

    /** Saves a GIF to send where [GifProvider] can hand it to the app, and returns its content URI. */
    fun save(context: Context, gif: Gif, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, "gifs").apply { mkdirs() }
        // Keep the folder small: the last 20 sent are enough for any app still reading one.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        val file = File(dir, "${gif.id}.gif")
        file.writeBytes(bytes)
        return Uri.parse("content://${context.packageName}.gifs/${file.name}")
    }
}

/** Hands a saved GIF (cache/gifs) to the app it is sent to, read-only, for as long as that app was granted it. */
class GifProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        return File(File(context!!.cacheDir, "gifs"), name).takeIf { it.isFile }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun getType(uri: Uri) = "image/gif"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
        return android.database.MatrixCursor(cols).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * The GIF panel: results in a grid over the keys, the plugin row above it holding the search. Tapping a GIF sends it.
 */
@SuppressLint("ViewConstructor")
class GifPanel(context: Context, private val onPick: (Gif) -> Unit) : FrameLayout(context) {
    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; setBackgroundColor(value.background); status.setTextColor(value.hint); credit.setTextColor(value.hint) }

    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(context).apply { gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(24f), 0, dp(24f), 0) }
    private val credit = TextView(context).apply {
        text = "Powered by GIPHY"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setPadding(dp(8f), dp(2f), dp(8f), dp(4f))
    }
    private var generation = 0

    init {
        scroll.addView(grid)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(credit, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    fun showMessage(text: String) {
        generation++
        grid.removeAllViews()
        status.text = text
        status.visibility = View.VISIBLE
    }

    fun show(gifs: List<Gif>) {
        val gen = ++generation
        grid.removeAllViews()
        if (gifs.isEmpty()) { showMessage("No GIFs found"); return }
        status.visibility = View.GONE
        val rowH = dp(96f)
        val gap = dp(3f)
        var row: LinearLayout? = null
        gifs.forEachIndexed { i, gif ->
            if (i % 3 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH).apply { bottomMargin = gap })
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(theme.functionKey) }
                clipToOutline = true
                contentDescription = "GIF"
                setOnClickListener { onPick(gif) }
            }
            row!!.addView(image, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = gap; rightMargin = gap })
            Giphy.load(gif.preview) { bytes ->
                if (gen != generation || bytes == null) return@load
                image.setImageDrawable(Giphy.drawable(context, bytes))
            }
        }
        scroll.scrollTo(0, 0)
    }
}
