package com.infamousvague.devkeyboard

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * The typing trainer on the phone (docs/trainer.md; Matt, 2026-11-11: "add a 'trainer' where it has a typeracer style
 * thing where we type stories out and it tracks everything like wpm and missed words etc. I want the keyboard to
 * slowly adapt each run to shift the touch areas and try different layouts like more condensed or less condensed and
 * after several runs it should build a customized profile for the user and be able to track common mistakes and help
 * the user correct them").
 *
 * The app and the keyboard are one process, so this object is where they meet. The trainer's screen (lib/trainer/,
 * through [TrainerChannel]) starts a run and ends it; while one is on, [TrainerKeys] writes down what the keyboard is
 * seen to do in the screen's own field. The reckoning is plain Kotlin with no Android in it ([TrainerScore],
 * [TrainerAims], [LayoutTrials], [TrainerBook]); this file is the wiring, and what a run hands on:
 * - each key's nudge takes one bounded step in Adaptive keys' own model ([AdaptiveKeys.trained]): the trainer has no
 *   model of touch areas of its own;
 * - a word mistyped is taught to "Your corrections" ([Mistakes]), under that memory's own rules and its own switch,
 *   and only where the spelling typed is no word at all and the word meant is one of the lists';
 * - everything else stays in the trainer's file, [FILE] in the app's no-backup files, until it is forgotten.
 *
 * What a run never teaches: a story is not the person's writing, so no word pair is counted for next-word prediction
 * and nothing goes to Mockingbird; nothing is counted for typing stats, the accuracy record or an anonymous report.
 * The screen's field asks keyboards not to learn from it, which every one of those already obeys, and it is one of
 * this app's own fields, which they never learn from either. Nothing here opens a connection.
 *
 * A trial layout is only ever worn, never set: [heightFor] answers for the one run that asked, in this app's own
 * field. A run that ends, a screen that is left and an app that dies each leave the person's setting as it was,
 * because it was never changed.
 *
 * Every call is on the main thread but for [load], which reads the file.
 */
object Trainer {
    const val FILE = "trainer.json"
    private const val TAG = "GhostKeyboard"

    /** How many story parts are remembered as read, and how many runs the screen is given for its history. */
    private const val MAX_READ = 400
    private const val HISTORY = 40

    /**
     * A run in hand. [height] is the key height it is typed on and [trial] whether the layout trials asked for it;
     * [wear] is that height where it is not the person's own ([ownHeight]), which is all the keyboard needs to know.
     */
    class Session(
        val id: String, val title: String, val part: Int, val parts: Int, val text: String, val practice: Boolean,
        val height: String, val trial: Boolean, val ownHeight: String, val layout: String,
    ) {
        val recorder = TrainerRecorder(text)
        val wear: String? get() = height.takeIf { it != ownHeight }
    }

    @Volatile var session: Session? = null
        private set

    /**
     * A line for the system log: which keys a run is on and what it came to, never a word that was typed (the log is
     * no place for anyone's words). It must not cost a run anything, with no phone under it least of all.
     */
    private fun note(message: String) { runCatching { Log.i(TAG, "trainer: $message") } }

    /** A run put in hand, or taken out of it, with no phone under it: TrainerKeysTest's way in. */
    internal fun hold(s: Session?) { session = s }

    /** The time a key was pressed at, in milliseconds that only ever go forward (the phone's uptime; a test's own count). */
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /** The keyboard's side, while the keyboard has a view: it reads the field, and puts on what a run asks for. */
    @Volatile var keys: WeakReference<TrainerKeys>? = null

    /** The trainer's screen: told where a run stands after each key, and when a run is dropped under it (the app was left). */
    @Volatile var onProgress: ((Map<String, Any?>) -> Unit)? = null
    @Volatile var onLeft: (() -> Unit)? = null

    private var book: TrainerBook? = null
    private var stories: List<Story>? = null
    private var vocabulary: List<String>? = null
    private val disk = Executors.newSingleThreadExecutor { r -> Thread(r, "trainer").apply { isDaemon = true } }

    /** The key height the keyboard wears in a field of [fieldPackage]: a trial's, only in this app's own field and only while its run is on. Null: the person's own. */
    fun heightFor(fieldPackage: String?, ownPackage: String): String? = session?.takeIf { fieldPackage == ownPackage }?.wear

    /** Whether the keys' nudges are in use whatever the Adaptive keys plugin says: in this app's own field, while a run is on. */
    fun adapts(fieldPackage: String?, ownPackage: String): Boolean = session != null && fieldPackage == ownPackage

    @Synchronized private fun book(context: Context): TrainerBook =
        book ?: (runCatching { File(context.noBackupFilesDir, FILE).takeIf { it.exists() }?.readText()?.let(TrainerCodec::read) }.getOrNull() ?: TrainerBook()).also { book = it }

    /** Whether the trainer's file has been read: until then [load] is asked for off the main thread. */
    val loaded: Boolean @Synchronized get() = book != null

    /** Reads the trainer's file and its stories, where they have not been: the one part of this that may wait on a disk. */
    fun load(context: Context) { book(context); stories(context) }

    @Synchronized private fun stories(context: Context): List<Story> =
        stories ?: runCatching { context.assets.open("trainer/stories.txt").bufferedReader().use { TrainerStories.parse(it.readText()) } }
            .onFailure { Log.w(TAG, "the trainer's stories could not be read", it) }.getOrDefault(emptyList()).also { stories = it }

    private fun vocabulary(context: Context): List<String> = vocabulary ?: TrainerStories.vocabulary(stories(context)).also { vocabulary = it }

    /** Writes the book off the main thread, by way of a temporary file. */
    private fun save(context: Context) {
        val text = TrainerCodec.write(book(context))
        val app = context.applicationContext
        disk.execute {
            runCatching {
                val file = File(app.noBackupFilesDir, FILE)
                val tmp = File(file.parentFile, "$FILE.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.onFailure { Log.w(TAG, "the trainer's file was not written", it) }
        }
    }

    /** Whether Ghost Keyboard is the keyboard in use: a run measures no other. */
    private fun selected(context: Context): Boolean {
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return ComponentName.unflattenFromString(current)?.packageName == context.packageName
    }

    private fun ownHeight(prefs: KeyboardPrefs): String = prefs.keyHeight.takeIf { it in LayoutTrials.HEIGHTS } ?: "normal"

    /** "Try different layouts", as the start screen sets it. */
    fun setTrials(context: Context, on: Boolean) {
        book(context).trials = on
        save(context)
    }

    /**
     * A run begins: the next part of the stories, or with [practice] a run of the person's own mistakes. The switch
     * for trying layouts is read here and nowhere else: on, the layout trials say which height this run is typed on;
     * off, it is the person's own. Refused, with why, where Ghost Keyboard is not the keyboard in use or the keys
     * being typed on cannot type the stories.
     */
    fun start(context: Context, practice: Boolean): Map<String, Any?> {
        leave(told = true)
        val prefs = KeyboardPrefs(context)
        if (!selected(context)) return mapOf("error" to "keyboard")
        val language = prefs.languageState()
        if (language.spec.script != Script.LATIN) return mapOf("error" to "layout")
        val book = book(context)
        val own = ownHeight(prefs)
        val all = stories(context)
        val next = if (practice) null else TrainerStories.next(all, book.read) ?: return mapOf("error" to "stories")
        val text = next?.text ?: book.practice(vocabulary(context), System.currentTimeMillis()) ?: return mapOf("error" to "practice")
        // Practice runs are lists of words, no measure of a layout: they are typed on the person's own.
        book.layouts.anchor(own)
        val trial = book.trials && !practice
        val height = if (trial) book.layouts.next() else own
        if (trial) save(context)
        val s = Session(
            id = next?.id ?: "practice", title = next?.story?.title ?: "Your mistakes", part = (next?.index ?: 0) + 1, parts = next?.story?.parts?.size ?: 1,
            text = text, practice = practice, height = height, trial = trial, ownHeight = own, layout = language.spec.id,
        )
        session = s
        keys?.get()?.changed()
        note("a run on $height keys (own $own, trial $trial, practice $practice)")
        return mapOf(
            "ok" to true, "title" to s.title, "part" to s.part, "parts" to s.parts, "text" to s.text, "words" to TrainerText.words(s.text).size, "practice" to practice,
            "height" to height, "heightLabel" to TrainerMaps.label(height), "trial" to trial, "changed" to (s.wear != null), "ownHeightLabel" to TrainerMaps.label(own),
        )
    }

    /**
     * The run in hand is dropped, uncounted, and the keyboard goes back to the person's own layout. [told] says the
     * screen itself asked (it left the run, or is starting another); otherwise it is told the run went under it.
     */
    fun leave(told: Boolean = false) {
        if (session == null) return
        session = null
        keys?.get()?.changed()
        note("the run was dropped")
        if (!told) runCatching { onLeft?.invoke() }
    }

    /** The screen's field changed: the keyboard looks at it again (a word tapped in the row, a stroke read). */
    fun look() { keys?.get()?.look() }

    /** Where the run [s] stands, told to the screen. */
    fun progressed(s: Session) {
        if (session !== s) return
        val to = onProgress ?: return
        to(TrainerMaps.progress(s.recorder.progress(clock())))
    }

    /**
     * The run ends and is scored. Counted (enough of the story typed), it joins the history, its taps move each key's
     * nudge a step, its mistakes are remembered, and a trial run is set against the others on its layout. Either way
     * the keyboard is back on the person's own layout before this returns.
     */
    fun finish(context: Context): Map<String, Any?> {
        val s = session ?: return mapOf("error" to "none")
        // What the row or a stroke changed since the last key.
        keys?.get()?.look()
        session = null
        keys?.get()?.changed()
        val prefs = KeyboardPrefs(context)
        val board = Layouts.spec(s.layout)?.positions ?: Layouts.QWERTY.positions
        val result = TrainerScore.of(s.text, s.recorder.steps, board)
        val book = book(context)
        val counted = result.counted && !s.recorder.overrun
        var nudged: Map<Char, TrainerAims.Aim> = emptyMap()
        var taught = 0
        var move: LayoutTrials.Move? = null
        if (counted) {
            book.add(
                RunLine(System.currentTimeMillis(), s.id, s.title, s.practice, s.height, s.trial, result.ms, result.wpm, result.accuracy, result.words, result.right, result.firstTry, result.backspaces, result.mended.size, result.spoiled.size),
                result, s.layout,
            )
            if (!s.practice) { book.read += s.id; while (book.read.size > MAX_READ) book.read.removeAt(0) }
            if (s.trial) move = book.layouts.record(s.height, result.score)
            nudged = runCatching { step(context, book, s.layout, result) }.onFailure { Log.w(TAG, "the trainer's steps were not taken", it) }.getOrDefault(emptyMap())
            taught = runCatching { teach(context, prefs, result) }.getOrDefault(0)
            save(context)
        }
        note("the run ended, counted $counted, ${Math.round(result.wpm)} words a minute, ${result.hits} of ${result.taps} taps on their key, ${nudged.size} keys moved, ${taught} mistakes taught")
        // Why a run was not counted: too little of the story typed, over too soon to have been typed, or far too long.
        val why = when {
            counted -> null
            result.typedShare < TrainerScore.COUNTED -> "short"
            result.ms < TrainerScore.MIN_MS -> "quick"
            else -> "long"
        }
        return TrainerMaps.result(result, nudged, taught) + mapOf(
            "counted" to counted, "why" to why, "title" to s.title, "part" to s.part, "parts" to s.parts, "practice" to s.practice,
            "height" to s.height, "heightLabel" to TrainerMaps.label(s.height), "trial" to s.trial, "changed" to (s.wear != null),
            "moved" to move?.let { mapOf("from" to TrainerMaps.label(it.from), "to" to TrainerMaps.label(it.to)) },
            "layouts" to TrainerMaps.layouts(book.layouts), "runs" to book.storyRuns, "profileAt" to TrainerBook.PROFILE_RUNS,
        )
    }

    /**
     * After a counted run: every key the run has taps for is counted in Adaptive keys' model for [layout], and where
     * the trainer's measure of it says so its nudge takes one step ([TrainerAims.steps]). What each step came to is
     * added to what the trainer has given that key, so "Forget" can take it back. The keys that moved, and where to.
     */
    private fun step(context: Context, book: TrainerBook, layout: String, result: RunResult): Map<Char, TrainerAims.Aim> {
        val model = AdaptiveKeys.get(context, layout)
        val now = model.aims()
        val touched = result.perKey.filter { it.value.aimed > 0 && model.hasKey(it.key) }.mapValues { it.value.aimed }
        if (touched.isEmpty()) return emptyMap()
        fun at(c: Char): TrainerAims.Aim = now[c]?.let { TrainerAims.Aim(it.dx.coerceIn(-TrainerAims.MAX_SHIFT, TrainerAims.MAX_SHIFT), it.dy.coerceIn(-TrainerAims.MAX_SHIFT, TrainerAims.MAX_SHIFT)) } ?: TrainerAims.Aim(0f, 0f)
        val seen = touched.keys.associateWith { now[it]?.n ?: 0 }
        val from = touched.keys.associateWith(::at)
        val moved = TrainerAims.steps(touched, book.aims[layout].orEmpty(), { seen[it] ?: 0 }) { from.getValue(it) }
        val given = book.given.getOrPut(layout) { HashMap() }
        for ((key, taps) in touched) {
            val to = moved[key]
            if (to != null) given.getOrPut(key) { FloatArray(2) }.let { it[0] += to.dx - from.getValue(key).dx; it[1] += to.dy - from.getValue(key).dy }
            model.trained(key, taps, to?.dx, to?.dy)
        }
        model.save()
        return moved
    }

    /**
     * A run's mistakes, taught to "Your corrections" where its own switch is on. The trainer knows what was meant, so
     * each is a full lesson; and it stays inside that memory's own rules: only a spelling a slip or two from the word
     * meant ([MistakeMemory.alike]), only where the word meant is one of the word lists' and what was typed is no word
     * at all (a real word typed for another is as likely the story misread as a habit), and a mistake is still gone
     * by only once it has been made the same way twice. A word typed right counts for its spelling as one left to
     * stand does ([Mistakes.kept]). How many were taught.
     */
    private fun teach(context: Context, prefs: KeyboardPrefs, result: RunResult): Int {
        if (!prefs.learnMistakes) return 0
        val keyboard = keys?.get() ?: return 0
        val memory = Mistakes.get(context)
        var taught = 0
        for (t in result.tries) {
            if (t.right) { memory.kept(t.meant); continue }
            if (t.typed.length < 2 || !t.typed.all { it.isLetter() } || !t.meant.all { it.isLetter() }) continue
            if (!MistakeMemory.alike(t.typed, t.meant) || !keyboard.listed(t.meant) || keyboard.listed(t.typed)) continue
            memory.learn(t.typed, t.meant)
            taught++
        }
        if (taught > 0) memory.save()
        return taught
    }

    /**
     * The profile put to use in everyday typing, as the person asked on its page. With [aims]: Adaptive keys is
     * added where it is not, and where the taps were measured to land is blended into its model through a typing
     * profile's own door ([TypingProfile.import]). With [height]: the key height the trials point to becomes the
     * person's setting. What was done, a line each.
     */
    fun apply(context: Context, aims: Boolean, height: Boolean): List<String> {
        val prefs = KeyboardPrefs(context)
        val book = book(context)
        val spec = prefs.languageState().spec
        val profile = book.profile(spec.id, spec.positions) ?: return listOf("There is no profile to apply yet")
        val lines = ArrayList<String>()
        if (aims) {
            if (!Plugins.isOn(prefs, Plugins.ADAPTIVE)) { Plugins.set(prefs, Plugins.ADAPTIVE, true); lines += "Adaptive keys added" }
            val model = AdaptiveKeys.get(context, spec.id)
            val before = model.aims().mapValues { it.value.dx to it.value.dy }
            // The aims alone: they are the mean of every tap, slips among them, so the slips are not taught a second time.
            lines += runCatching { TypingProfile.import(context, TrainerCodec.profile(profile, java.time.Instant.now().toString(), slips = false)).lines }.getOrElse { listOf(it.message ?: "The profile could not be applied") }
            val given = book.given.getOrPut(spec.id) { HashMap() }
            for ((key, a) in model.aims()) {
                val was = before[key] ?: (0f to 0f)
                if (a.dx != was.first || a.dy != was.second) given.getOrPut(key) { FloatArray(2) }.let { it[0] += a.dx - was.first; it[1] += a.dy - was.second }
            }
        }
        if (height) profile.suggestion?.let {
            prefs.set(KeyboardPrefs.KEY_HEIGHT, it.height)
            book.layouts.anchor(it.height)
            lines += "Key height set to ${TrainerMaps.label(it.height)}"
        }
        save(context)
        return lines
    }

    /** Every layout open to trial again from where the trials stand. */
    fun again(context: Context) {
        book(context).layouts.again()
        save(context)
    }

    /**
     * Everything the trainer kept is deleted: its runs, its measures, the mistakes it tracked, the layout trials, and
     * its file. The steps it gave the keys' nudges are taken back out of Adaptive keys' model, as far as they are
     * still there ([TrainerAims.takeBack]). What it taught "Your corrections" is that memory's now, and is forgotten
     * from its own page.
     */
    fun forget(context: Context) {
        leave(told = true)
        val book = book(context)
        for ((layout, keys) in book.given) {
            if (Layouts.spec(layout) == null) continue
            runCatching {
                val model = AdaptiveKeys.get(context, layout)
                val now = model.aims()
                for ((key, given) in keys) {
                    val a = now[key] ?: continue
                    val back = TrainerAims.takeBack(a.dx, a.dy, given[0], given[1])
                    model.trained(key, 0, back.dx, back.dy)
                }
                model.save()
            }
        }
        book.forget()
        val app = context.applicationContext
        disk.execute { for (name in listOf(FILE, "$FILE.tmp")) runCatching { File(app.noBackupFilesDir, name).delete() } }
    }

    /** Everything the trainer's screen shows that is not a run in hand. */
    fun state(context: Context): Map<String, Any?> {
        val prefs = KeyboardPrefs(context)
        val book = book(context)
        val all = stories(context)
        val language = prefs.languageState()
        val spec = language.spec
        val own = ownHeight(prefs)
        val next = TrainerStories.next(all, book.read)
        val profile = book.profile(spec.id, spec.positions)
        val story = book.runs.filter { !it.practice }
        return mapOf(
            "selected" to selected(context), "latin" to (spec.script == Script.LATIN), "english" to ("en" in language.dictionaries), "layout" to spec.id,
            "trials" to book.trials, "ownHeight" to own, "ownHeightLabel" to TrainerMaps.label(own),
            "next" to next?.let { mapOf("title" to it.story.title, "part" to it.index + 1, "parts" to it.story.parts.size, "words" to it.words) },
            "stories" to all.size, "storyParts" to all.sumOf { it.parts.size },
            "canPractise" to (book.practice(vocabulary(context), 0L) != null),
            "runs" to story.size, "practiceRuns" to (book.runs.size - story.size), "profileAt" to TrainerBook.PROFILE_RUNS,
            "best" to (story.maxOfOrNull { it.wpm }?.let { Math.round(it) } ?: 0),
            "history" to book.runs.takeLast(HISTORY).reversed().map(TrainerMaps::line),
            "mistakes" to book.mistakes().map(TrainerMaps::mistake), "pairs" to book.weakPairs().map(TrainerMaps::pair), "keys" to book.weakKeys(spec.id).map(TrainerMaps::key),
            "profile" to profile?.let { TrainerMaps.profile(it, TrainerCodec.profile(it, java.time.Instant.now().toString())) },
            "layouts" to TrainerMaps.layouts(book.layouts),
            "adaptive" to Plugins.isOn(prefs, Plugins.ADAPTIVE), "nudged" to AdaptiveKeys.get(context, spec.id).learnedKeys(), "learnMistakes" to prefs.learnMistakes,
            "running" to (session != null),
        )
    }
}
