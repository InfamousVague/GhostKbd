package com.infamousvague.devkeyboard

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG path data ("M3 12h4l3 8...") as an android.graphics.Path: every command, absolute and relative, arcs made into
 * cubic curves. Enough for the icon packs' drawings, with no library to pull in.
 */
object SvgPath {
    private val cache = HashMap<String, Path>()

    fun parse(d: String): Path = synchronized(cache) { cache.getOrPut(d) { build(d) } }

    private fun build(d: String): Path {
        val path = Path()
        val tokens = tokenize(d)
        var i = 0
        var cmd = 'M'
        var x = 0f; var y = 0f
        var startX = 0f; var startY = 0f
        var lastCx = 0f; var lastCy = 0f
        var lastCmd = ' '
        fun num(): Float = (tokens[i++] as Float)
        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Char) { cmd = t; i++ } else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f; val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { x = ox + num(); y = oy + num(); path.moveTo(x, y); startX = x; startY = y }
                'L' -> { x = ox + num(); y = oy + num(); path.lineTo(x, y) }
                'H' -> { x = (if (rel) x else 0f) + num(); path.lineTo(x, y) }
                'V' -> { y = (if (rel) y else 0f) + num(); path.lineTo(x, y) }
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num()
                    x = ox + num(); y = oy + num()
                    path.cubicTo(x1, y1, x2, y2, x, y); lastCx = x2; lastCy = y2
                }
                'S' -> {
                    val reflect = lastCmd.uppercaseChar() in "CS"
                    val x1 = if (reflect) 2 * x - lastCx else x; val y1 = if (reflect) 2 * y - lastCy else y
                    val x2 = ox + num(); val y2 = oy + num()
                    x = ox + num(); y = oy + num()
                    path.cubicTo(x1, y1, x2, y2, x, y); lastCx = x2; lastCy = y2
                }
                'Q' -> {
                    val x1 = ox + num(); val y1 = oy + num()
                    x = ox + num(); y = oy + num()
                    path.quadTo(x1, y1, x, y); lastCx = x1; lastCy = y1
                }
                'T' -> {
                    val reflect = lastCmd.uppercaseChar() in "QT"
                    val x1 = if (reflect) 2 * x - lastCx else x; val y1 = if (reflect) 2 * y - lastCy else y
                    x = ox + num(); y = oy + num()
                    path.quadTo(x1, y1, x, y); lastCx = x1; lastCy = y1
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    val ex = ox + num(); val ey = oy + num()
                    arc(path, x, y, ex, ey, rx, ry, rot, large, sweep)
                    x = ex; y = ey
                }
                'Z' -> { path.close(); x = startX; y = startY }
                else -> i++
            }
            lastCmd = cmd
        }
        return path
    }

    /** Numbers and commands; flags of an arc may run together ("a7 7 0 105 13"), so they are read one digit each. */
    private fun tokenize(d: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        var cmd = ' '
        var argIndex = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() && c != 'e' && c != 'E' -> { out += c; cmd = c; argIndex = 0; i++ }
                c == ',' || c.isWhitespace() -> i++
                else -> {
                    // An arc's 4th and 5th arguments are flags: a single 0 or 1.
                    if (cmd.uppercaseChar() == 'A' && (argIndex % 7 == 3 || argIndex % 7 == 4) && (c == '0' || c == '1')) {
                        out += (c - '0').toFloat(); i++; argIndex++; continue
                    }
                    val start = i
                    if (d[i] == '-' || d[i] == '+') i++
                    var dot = false
                    while (i < d.length) {
                        val ch = d[i]
                        if (ch.isDigit()) i++
                        else if (ch == '.' && !dot) { dot = true; i++ }
                        else if ((ch == 'e' || ch == 'E') && i + 1 < d.length) { i++; if (d[i] == '-' || d[i] == '+') i++ }
                        else break
                    }
                    out += d.substring(start, i).toFloat()
                    argIndex++
                }
            }
        }
        return out
    }

    /** SVG's endpoint arc as cubic curves (the W3C's implementation notes, F.6). */
    private fun arc(path: Path, x1: Float, y1: Float, x2: Float, y2: Float, rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
        if (rx == 0.0 || ry == 0.0) { path.lineTo(x2, y2); return }
        val phi = angle * PI / 180
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(uy, ux); val b = atan2(vy, vx)
            return b - a
        }
        val theta1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        // The sweep, in [0, 2pi) the positive way round, then taken the other way where the arc sweeps negatively.
        var delta = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry) % (2 * PI)
        if (delta < 0) delta += 2 * PI
        if (!sweep && delta > 0) delta -= 2 * PI
        val segments = ceil(abs(delta) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = delta / segments
        val t = 4.0 / 3.0 * tan(step / 4)
        var th = theta1
        for (s in 0 until segments) {
            val c1 = cos(th); val s1 = sin(th)
            val c2 = cos(th + step); val s2 = sin(th + step)
            fun px(c: Double, sn: Double) = cosP * rx * c - sinP * ry * sn + cx
            fun py(c: Double, sn: Double) = sinP * rx * c + cosP * ry * sn + cy
            val e1x = -rx * s1; val e1y = ry * c1
            val e2x = -rx * s2; val e2y = ry * c2
            val q1x = px(c1, s1) + t * (cosP * e1x - sinP * e1y)
            val q1y = py(c1, s1) + t * (sinP * e1x + cosP * e1y)
            val q2x = px(c2, s2) - t * (cosP * e2x - sinP * e2y)
            val q2y = py(c2, s2) - t * (sinP * e2x + cosP * e2y)
            path.cubicTo(q1x.toFloat(), q1y.toFloat(), q2x.toFloat(), q2y.toFloat(), px(c2, s2).toFloat(), py(c2, s2).toFloat())
            th += step
        }
    }
}
