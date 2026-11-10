package android.content.res

import java.io.File
import java.io.InputStream

/** Stands in for Android's AssetManager off the phone: opens files under the app's assets folder. */
class AssetManager(private val root: File) {
    fun open(path: String): InputStream = File(root, path).inputStream()
}
