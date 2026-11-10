// Android's PointF, for tool/lang_check.sh: Autocorrect.swipe takes a finger's path as these, and nothing else of
// Android's. Never part of the app.
package android.graphics

class PointF(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f)
