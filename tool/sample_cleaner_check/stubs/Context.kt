package android.content

import android.content.res.AssetManager
import java.io.File

/** Stands in for Android's Context off the phone: just enough for Autocorrect to open its dictionaries. */
open class Context(root: File) {
    val applicationContext: Context get() = this
    val assets = AssetManager(root)
}
