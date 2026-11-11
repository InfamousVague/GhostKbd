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
 * [TrainerAims], [FixTrial], [LayoutTrials], [TrainerBook]); this file is the wiring, and what a run hands on:
 * - a word mistyped is taught to "Your corrections" ([Mistakes]), under that memory's own rules and its own switch,
 *   and only where the spelling typed is no word at all and the word meant is one of the lists';
 * - everything else stays in the trainer's file, [FILE] in the app's no-backup files, until it is forgotten.
 *
 * A run changes nothing about how everyday typing reads a touch (Matt, 2026-11-11: "make each run after the first
 * test a new fixes and a b test the accuracy and don't suggest switching until its proven we got a better result").
 * The first run is typed on the keyboard as it is. Each story run after it is typed on a new fix ([FixTrial]: the
 * keys' touch areas a step nearer to where the taps land), which only that run wears, in this app's own field
 * ([fixFor]); the keyboard reads each of its touches both ways, and the run's result says what each reading came to.
 * A fix reaches everyday typing, as nudges in Adaptive keys' own model, only when it has been proven better and the
 * person takes it ([apply]).
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

    /** A key counts as moved by the trainer once what it gave the key's nudge adds up to this much of a key, either way. */
    private const val GIVEN = 0.005f

    /**
     * A run in hand. [height] is the key height it is typed on and [trial] whether the layout trials asked for it;
     * [wear] is that height where it is not the person's own ([ownHeight]), which is all the keyboard needs to know.
     * [fix] is where each letter key listens in the fix this run is typed on and tests (null: none, it is typed on
     * the keyboard as it is), [fixNumber] which fix that is, and [differs] the keys it has listening somewhere else
     * than the keyboard as it is.
     */
    class Session(
        val id: String, val title: String, val part: Int, val parts: Int, val text: String, val practice: Boolean,
        val height: String, val trial: Boolean, val ownHeight: String, val layout: String,
        val fix: Map<Char, Pair<Float, Float>>? = null, val fixNumber: Int = 0, val differs: Set<Char> = emptySet(),
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

    /**
     * Where each letter key listens in a field of [fieldPackage], where the run in hand is typed on a fix: in this
     * app's own field only, and only while that run is on. Null: as the keyboard is. A fix is only ever worn, like a
     * trial layout: nothing is written to Adaptive keys' model until a proven fix is taken ([apply]).
     */
    fun fixFor(fieldPackage: String?, ownPackage: String): ((Char) -> Pair<Float, Float>)? {
        val worn = session?.takeIf { fieldPackage == ownPackage }?.fix ?: return null
        return { c -> worn[c.lowercaseChar()] ?: (0f to 0f) }
    }

    /** Where each key listens in everyday typing now: Adaptive keys' nudges where that is added, the keys' middles where it is not. */
    private fun asIs(model: AdaptiveKeys, adaptive: Boolean): (Char) -> Pair<Float, Float> = if (adaptive) { c -> model.offset(c) } else { _ -> 0f to 0f }

    /**
     * Where a first fix starts from, where that is not the keyboard as it is: the steps an earlier version's runs
     * gave the keys in Adaptive keys' model, which were in use in its runs whether or not Adaptive keys was added
     * and which no run has tested. With Adaptive keys added they are the keyboard as it is already.
     */
    private fun seed(book: TrainerBook, model: AdaptiveKeys, layout: String, adaptive: Boolean): Map<Char, TrainerAims.Aim> {
        if (adaptive) return emptyMap()
        val out = HashMap<Char, TrainerAims.Aim>()
        for ((key, given) in book.given[layout].orEmpty()) {
            if (kotlin.math.abs(given[0]) < GIVEN && kotlin.math.abs(given[1]) < GIVEN) continue
            val now = model.offset(key)
            if (now != (0f to 0f)) out[key] = TrainerAims.Aim(now.first, now.second)
        }
        return out
    }

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
     * off, it is the person's own. A story run after the first is typed on a fix, the next one where the last has
     * been tested ([FixTrial.begin]). Refused, with why, where Ghost Keyboard is not the keyboard in use or the keys
     * being typed on cannot type the stories.
     */
    fun start(context: Context, practice: Boolean): Map<String, Any?> {
        leave(told = true)
        val prefs = KeyboardPrefs(context)
        if (!selected(context)) return mapOf("error" to "keyboard")
        val language = prefs.languageState()
        val spec = language.spec
        if (spec.script != Script.LATIN) return mapOf("error" to "layout")
        val book = book(context)
        val own = ownHeight(prefs)
        val all = stories(context)
        val next = if (practice) null else TrainerStories.next(all, book.read) ?: return mapOf("error" to "stories")
        val text = next?.text ?: book.practice(vocabulary(context), System.currentTimeMillis()) ?: return mapOf("error" to "practice")
        // Practice runs are lists of words, no measure of a layout or of a fix: they are typed on the person's own
        // keys, read as everyday typing reads them.
        book.layouts.anchor(own)
        val trial = book.trials && !practice
        val height = if (trial) book.layouts.next() else own
        var worn: Map<Char, Pair<Float, Float>>? = null
        var differs: Set<Char> = emptySet()
        var number = 0
        var made = false
        if (!practice) {
            val adaptive = Plugins.isOn(prefs, Plugins.ADAPTIVE)
            val model = AdaptiveKeys.get(context, spec.id)
            val asIs = asIs(model, adaptive)
            val fixes = book.fixes.getOrPut(spec.id) { FixTrial() }
            val before = fixes.number
            worn = fixes.begin(adaptive, asIs, spec.positions.keys, book.aims[spec.id].orEmpty(), seed(book, model, spec.id, adaptive))
            made = fixes.number != before
            if (worn != null) { number = fixes.number; differs = fixes.differing(spec.positions.keys, asIs) }
        }
        // The fix trial is kept as a run starts whether or not a fix was numbered: a step under what is in use is a step.
        if (trial || made || !practice) save(context)
        val s = Session(
            id = next?.id ?: "practice", title = next?.story?.title ?: "Your mistakes", part = (next?.index ?: 0) + 1, parts = next?.story?.parts?.size ?: 1,
            text = text, practice = practice, height = height, trial = trial, ownHeight = own, layout = spec.id,
            fix = worn, fixNumber = number, differs = differs,
        )
        session = s
        keys?.get()?.changed()
        note("a run on $height keys (own $own, trial $trial, practice $practice), fix $number with ${differs.size} keys moved")
        return mapOf(
            "ok" to true, "title" to s.title, "part" to s.part, "parts" to s.parts, "text" to s.text, "words" to TrainerText.words(s.text).size, "practice" to practice,
            "height" to height, "heightLabel" to TrainerMaps.label(height), "trial" to trial, "changed" to (s.wear != null), "ownHeightLabel" to TrainerMaps.label(own),
            // The fix this run is typed on and tests (0: none, it is typed on the keyboard as it is), and how many keys it has moved.
            "fix" to number, "fixKeys" to differs.size,
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
     * The run ends and is scored. Counted (enough of the story typed), it joins the history, where its taps landed is
     * measured, its mistakes are remembered, a run typed on a fix is set down as that fix's test ([FixTrial.record]),
     * and a trial run is set against the others on its layout. Either way the keyboard is back as the person has it
     * before this returns: their own layout, and every key listening where everyday typing has it.
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
        val fixes = book.fixes[s.layout]
        // The run tested a fix where it was typed on one, and that fix is still the one in hand.
        val tested = if (counted && s.fix != null && fixes != null && fixes.number == s.fixNumber) FixTrial.FixRun(s.fixNumber, result.taps, result.asIs, result.hits, result.won, result.lost) else null
        var verdict: FixTrial.Verdict? = null
        var taught = 0
        var move: LayoutTrials.Move? = null
        if (counted) {
            book.add(
                RunLine(
                    System.currentTimeMillis(), s.id, s.title, s.practice, s.height, s.trial, result.ms, result.wpm, result.accuracy, result.words, result.right, result.firstTry, result.backspaces, result.mended.size, result.spoiled.size,
                    fix = if (tested != null) s.fixNumber else 0, asIs = if (tested != null && result.taps > 0) result.asIs.toFloat() / result.taps else Float.NaN,
                ),
                result, s.layout,
            )
            if (!s.practice) { book.read += s.id; while (book.read.size > MAX_READ) book.read.removeAt(0) }
            if (s.trial) move = book.layouts.record(s.height, result.score)
            if (tested != null && fixes != null) verdict = fixes.record(tested)
            taught = runCatching { teach(context, prefs, result) }.getOrDefault(0)
            save(context)
        }
        note("the run ended, counted $counted, ${Math.round(result.wpm)} words a minute, ${result.hits} of ${result.taps} taps on their key (${result.asIs} as the keyboard is; fix ${s.fixNumber}, ${verdict?.name ?: "untested"}), $taught mistakes taught")
        // Why a run was not counted: too little of the story typed, over too soon to have been typed, or far too long.
        val why = when {
            counted -> null
            result.typedShare < TrainerScore.COUNTED -> "short"
            result.ms < TrainerScore.MIN_MS -> "quick"
            else -> "long"
        }
        val adaptive = Plugins.isOn(prefs, Plugins.ADAPTIVE)
        return TrainerMaps.result(result, s.differs.associateWith { s.fix?.get(it) ?: (0f to 0f) }, taught) + mapOf(
            "counted" to counted, "why" to why, "title" to s.title, "part" to s.part, "parts" to s.parts, "practice" to s.practice,
            "height" to s.height, "heightLabel" to TrainerMaps.label(s.height), "trial" to s.trial, "changed" to (s.wear != null),
            "moved" to move?.let { mapOf("from" to TrainerMaps.label(it.from), "to" to TrainerMaps.label(it.to), "pairs" to it.pairs, "wins" to it.wins) },
            "layouts" to TrainerMaps.layouts(book.layouts, book.runs), "runs" to book.storyRuns, "profileAt" to TrainerBook.PROFILE_RUNS,
            // The test this run was: the fix it was typed on (`worn`), what the two readings of its taps came to
            // (`run`; null where it tested nothing), and where the trial stands with it counted.
            "fix" to TrainerMaps.fix(fixes, adaptive, s.differs.size, verdict) + mapOf("worn" to if (s.fix != null) s.fixNumber else 0, "run" to tested?.let(TrainerMaps::fixRun)),
            // The run's own picture of the keyboard: where this run went wrong, where its taps landed, and where the
            // keys listened while it was typed, with the keys its fix had moved marked.
            "board" to runCatching {
                val model = AdaptiveKeys.get(context, s.layout)
                val asIs = asIs(model, adaptive)
                TrainerMaps.board(
                    board,
                    taps = { c -> result.perKey[c]?.let { it.taps to it.missed } },
                    landing = { c -> result.perKey[c]?.takeIf { it.aimed >= TrainerMaps.RUN_LANDED }?.let { Triple(it.meanX, it.meanY, it.aimed) } },
                    shift = { c -> s.fix?.get(c) ?: asIs(c) },
                    moved = s.differs,
                    slips = result.slipPairs,
                    slipFloor = 1,
                )
            }.getOrNull(),
        )
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
     * What the runs proved, put to use in everyday typing, as the person asked. With [fix]: the fix the runs tested
     * ([FixTrial.testedAt]), where it has been proven better ([FixTrial.Verdict.PROVEN]) and not otherwise. Adaptive
     * keys is added where it is not, and each key the fixes have moved listens in its model where that fix has it, so
     * that everyday typing reads a touch as the runs that proved it did. With [height]: the key height the trials proved better becomes the
     * person's setting. What was done, a line each.
     */
    fun apply(context: Context, fix: Boolean, height: Boolean): List<String> {
        val prefs = KeyboardPrefs(context)
        val book = book(context)
        val spec = prefs.languageState().spec
        val lines = ArrayList<String>()
        if (fix) {
            val fixes = book.fixes[spec.id]
            if (fixes == null || fixes.verdict != FixTrial.Verdict.PROVEN || fixes.testedNumber == 0) lines += "No fix has been proven better yet"
            else {
                val adaptive = Plugins.isOn(prefs, Plugins.ADAPTIVE)
                val model = AdaptiveKeys.get(context, spec.id)
                val before = model.aims().mapValues { Triple(it.value.dx, it.value.dy, it.value.n) }
                val given = book.given.getOrPut(spec.id) { HashMap() }
                fun place(key: Char, dx: Float, dy: Float) {
                    val was = before[key] ?: Triple(0f, 0f, 0)
                    // A key the model would not yet use a nudge for is given the touches it lacks: the trainer measured it on more.
                    val lacking = if (TrainerAims.inUse(dx, dy) != (0f to 0f)) (AdaptiveKeys.MIN_SAMPLES - was.third).coerceAtLeast(0) else 0
                    model.trained(key, lacking, dx, dy)
                    given.getOrPut(key) { FloatArray(2) }.let { it[0] += dx - was.first; it[1] += dy - was.second }
                }
                // With Adaptive keys not added the runs read every key the fixes had not moved at its middle: a
                // nudge left in the model from before is not part of what was proven.
                if (!adaptive) for (key in spec.positions.keys) if (key !in fixes.testedAt && model.offset(key) != (0f to 0f)) place(key, 0f, 0f)
                for ((key, to) in fixes.testedAt) if (key in spec.positions) place(key, to.dx, to.dy)
                model.save()
                if (!adaptive) { Plugins.set(prefs, Plugins.ADAPTIVE, true); lines += "Adaptive keys added" }
                lines += "Everyday typing reads your taps by fix ${fixes.testedNumber} now"
                note("fix ${fixes.testedNumber} was taken")
                fixes.took()
            }
        }
        if (height) {
            val to = book.layouts.suggestion()
            if (to == null) lines += "No key height has been proven better yet"
            else {
                prefs.set(KeyboardPrefs.KEY_HEIGHT, to.height)
                book.layouts.anchor(to.height)
                lines += "Key height set to ${TrainerMaps.label(to.height)}"
            }
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
        val model = AdaptiveKeys.get(context, spec.id)
        val measured = book.aims[spec.id].orEmpty().filter { it.value.total >= TrainerAims.MIN_SAMPLES }
        val adaptive = Plugins.isOn(prefs, Plugins.ADAPTIVE)
        val asIs = asIs(model, adaptive)
        val fixes = book.fixes[spec.id]
        val differs = fixes?.differing(spec.positions.keys, asIs).orEmpty()
        // Whether a story run started now would be typed on a fix: asked of a copy, so that nothing is made by looking.
        val wouldTest = spec.script == Script.LATIN && (fixes?.copy() ?: FixTrial()).begin(adaptive, asIs, spec.positions.keys, book.aims[spec.id].orEmpty(), seed(book, model, spec.id, adaptive)) != null
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
            "layouts" to TrainerMaps.layouts(book.layouts, book.runs),
            "adaptive" to adaptive, "nudged" to model.learnedKeys(), "learnMistakes" to prefs.learnMistakes,
            "running" to (session != null),
            // The test of the fixes as it stands: the fix in hand, the runs that tested one, and whether it is proven.
            "fix" to TrainerMaps.fix(fixes, adaptive, differs.size, next = wouldTest),
            // The keyboard as the pictures draw it, over every run: where the mistakes happen, where the taps land,
            // and where each key listens in the fix in hand, with the keys it has somewhere else than the keyboard as
            // it is marked. `lean` says the second of those in a sentence.
            "board" to TrainerMaps.board(
                spec.positions,
                taps = { c -> book.keys[c]?.let { it.n to it.missed } },
                landing = { c -> measured[c]?.let { Triple(it.x, it.y, it.total) } },
                shift = { c -> fixes?.offset(c, asIs) ?: asIs(c) },
                moved = differs,
                slips = book.slips[spec.id].orEmpty(),
            ) + mapOf("lean" to if (measured.isEmpty()) null else TrainerBook.lean(measured, spec.positions)),
        )
    }
}
