package com.infamousvague.devkeyboard

/**
 * The stories the typing trainer has people type (assets/trainer/stories.txt; docs/trainer.md). Plain Kotlin with no
 * Android in it: Trainer.kt hands this the file's text.
 *
 * Every story was written for the trainer, none is anyone else's text, and each is told in parts of about fifty
 * words: a run types one part, and the next run goes on with the story. The file is plain text:
 * ```
 * # The lighthouse cat
 * The first part, on one line.
 * The second part, on one line.
 *
 * # The next story
 * ```
 * A part holds only what the letters page types without a detour: letters, spaces, full stops and a few commas. No
 * apostrophes (a contraction typed without one is another word as often as not), no digits, no quotes
 * (TrainerStoriesTest holds the file to it, and to every word being one autocorrect leaves alone when typed cleanly).
 */
class Story(val id: String, val title: String, val parts: List<String>)

/** One part of a story: what a run types. [id] is the story's id and the part's number ("lighthouse-cat-2"). */
class StoryPart(val story: Story, val index: Int) {
    val id: String get() = "${story.id}-${index + 1}"
    val text: String get() = story.parts[index]
    val words: Int get() = TrainerText.words(text).size
}

object TrainerStories {
    /** The stories in [text], in its order. A line starting "# " names a story; every other line that is not blank is one of its parts. */
    fun parse(text: String): List<Story> {
        val out = ArrayList<Story>()
        var title: String? = null
        var parts = ArrayList<String>()
        fun close() {
            val t = title ?: return
            if (parts.isNotEmpty()) out += Story(slug(t), t, parts)
            parts = ArrayList()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("# ")) { close(); title = line.substring(2).trim() } else if (!line.startsWith("#") && title != null) parts += line.replace(Regex("\\s+"), " ")
        }
        close()
        return out
    }

    private fun slug(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * What to type next, given the parts [read] so far (their ids, oldest first): the part after the last one read,
     * while its story has one; else the first part of the story left alone longest (one never read, in the file's
     * order, before any that was). So no part comes round again until every other has been typed.
     */
    fun next(stories: List<Story>, read: List<String>): StoryPart? {
        if (stories.isEmpty()) return null
        val last = read.lastOrNull()
        if (last != null) {
            for (s in stories) for (i in s.parts.indices) if (StoryPart(s, i).id == last && i + 1 < s.parts.size) return StoryPart(s, i + 1)
        }
        // When each story was last touched: the place of its latest part in what was read, -1 for never.
        fun touched(s: Story): Int = read.indexOfLast { id -> s.parts.indices.any { StoryPart(s, it).id == id } }
        val story = stories.minByOrNull { touched(it) } ?: return null
        return StoryPart(story, 0)
    }

    /** Every word the stories use, in lower case, without the punctuation around it: what a practice run draws on for a letter pair. */
    fun vocabulary(stories: List<Story>): List<String> =
        stories.flatMap { s -> s.parts.flatMap { TrainerText.words(it) } }.map { TrainerText.core(it).lowercase() }.filter { it.length >= 3 }.distinct()
}
