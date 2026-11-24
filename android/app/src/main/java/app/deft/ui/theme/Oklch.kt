package app.deft.ui.theme

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** An OKLCH colour. [h] is in degrees and [alpha] is 0..1. Mirrors tools/deft-design/src/oklch.js. */
data class Oklch(val l: Double, val c: Double, val h: Double, val alpha: Double = 1.0) {
    /** CSS `oklch()` text, as published in theme.css. */
    fun css(): String {
        val a = if (alpha >= 1.0) "" else " / ${fmt(alpha)}"
        return "oklch(${fmt(l)} ${fmt(c)} ${fmt(h)}$a)"
    }

    /** Clamped sRGB as ARGB (0xAARRGGBB). */
    fun toArgb(): Long {
        val rgb = toRgb255()
        val a = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt().toLong()
        return (a shl 24) or (rgb[0].toLong() shl 16) or (rgb[1].toLong() shl 8) or rgb[2].toLong()
    }

    private fun toRgb255(): IntArray {
        val rad = Math.toRadians(h)
        val a = c * cos(rad)
        val b = c * sin(rad)
        val lp = l + 0.3963377774 * a + 0.2158037573 * b
        val mp = l - 0.1055613458 * a - 0.0638541728 * b
        val sp = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = lp * lp * lp
        val m3 = mp * mp * mp
        val s3 = sp * sp * sp
        val lin = doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
        )
        return IntArray(3) { i -> (OklchMath.encodeSrgb(lin[i].coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0) * 255).roundToInt() }
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    companion object {
        val WHITE = Oklch(1.0, 0.0, 0.0)
        val BLACK = Oklch(0.0, 0.0, 0.0)
    }
}

/** Colour maths shared by the accent model: parsing, OKLCH <-> sRGB, WCAG contrast. */
object OklchMath {
    private val oklchRe = Regex(
        """oklch\(\s*([0-9.]+%?)\s+([0-9.]+)\s+([0-9.]+)\s*(?:/\s*([0-9.]+%?)\s*)?\)""",
        RegexOption.IGNORE_CASE,
    )

    /** Parses `#rgb`, `#rrggbb` or `oklch(L C H [/ A])`. Returns null for anything else. */
    fun parse(text: String): Oklch? {
        val s = text.trim()
        if (s.startsWith("#")) return fromHex(s)
        val m = oklchRe.matchEntire(s) ?: return null
        fun num(v: String): Double? = if (v.endsWith("%")) v.dropLast(1).toDoubleOrNull()?.div(100) else v.toDoubleOrNull()
        val l = num(m.groupValues[1]) ?: return null
        val c = m.groupValues[2].toDoubleOrNull() ?: return null
        val h = m.groupValues[3].toDoubleOrNull() ?: return null
        val a = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { num(it) } ?: 1.0
        if (l !in 0.0..1.0 || c !in 0.0..0.5) return null
        return Oklch(l, c, ((h % 360) + 360) % 360, a.coerceIn(0.0, 1.0))
    }

    fun fromHex(hex: String): Oklch? {
        var raw = hex.trim().removePrefix("#")
        if (raw.length == 3) raw = raw.map { "$it$it" }.joinToString("")
        if (raw.length != 6 || raw.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        val value = raw.toLongOrNull(16) ?: return null
        return fromArgb(0xFF000000L or value)
    }

    /** sRGB ARGB to OKLCH. */
    fun fromArgb(argb: Long): Oklch {
        val r = decode(((argb shr 16) and 0xFF) / 255.0)
        val g = decode(((argb shr 8) and 0xFF) / 255.0)
        val b = decode((argb and 0xFF) / 255.0)
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val lp = Math.cbrt(l)
        val mp = Math.cbrt(m)
        val sp = Math.cbrt(s)
        val L = 0.2104542553 * lp + 0.7936177850 * mp - 0.0040720468 * sp
        val a = 1.9779984951 * lp - 2.4285922050 * mp + 0.4505937099 * sp
        val bb = 0.0259040371 * lp + 0.7827717662 * mp - 0.8086757660 * sp
        val c = sqrt(a * a + bb * bb)
        var h = Math.toDegrees(atan2(bb, a))
        if (h < 0) h += 360
        return Oklch(L, c, h, ((argb shr 24) and 0xFF) / 255.0)
    }

    /** WCAG relative luminance of an opaque colour. */
    fun luminance(argb: Long): Double {
        fun lin(shift: Int): Double = decode(((argb shr shift) and 0xFF) / 255.0)
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    /** WCAG contrast ratio between two opaque colours, 1..21. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [fg] with alpha blended over the opaque [bg]. Returns opaque ARGB. */
    fun composite(fg: Long, bg: Long): Long {
        val a = ((fg shr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Long {
            val f = (fg shr shift) and 0xFF
            val d = (bg shr shift) and 0xFF
            return (f * a + d * (1 - a)).roundToInt().toLong()
        }
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Hue distance in degrees, 0..180. */
    fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    private fun decode(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    internal fun encodeSrgb(x: Double): Double = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
}
