package com.rm.acidulous.util

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/**
 * `"%.1f dB".format(x)` everywhere the app is. On Android and the desktop it
 * is Java's String.format, as it always was - in the device's locale. A
 * browser has no Java, so there it is [javaFormat]: the part of Java's
 * format the app's code and strings use, the way Java does it.
 */
expect fun String.format(vararg args: Any?): String

/**
 * Java's String.format for what the app writes: %d %s %f %x %%, a width, the
 * 0 + - flags, a precision on %f, and positions (%2$s). Anything else stays
 * as written. Decimals round half up from the double's exact value, as Java
 * does, and use a point.
 */
fun javaFormat(pattern: String, args: Array<out Any?>): String {
    val out = StringBuilder()
    var next = 0
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        if (c != '%') { out.append(c); i++; continue }
        val m = SPEC.matchAt(pattern, i)
        if (m == null) { out.append(c); i++; continue }
        i += m.value.length
        val (position, flags, widthText, precisionText, conversion) = m.destructured
        if (conversion == "%") { out.append('%'); continue }
        if (conversion == "n") { out.append('\n'); continue }
        val index = if (position.isNotEmpty()) position.dropLast(1).toInt() - 1 else next++
        val arg = args.getOrNull(index)
        val precision = precisionText.drop(1).toIntOrNull()
        var body = when (conversion) {
            "d" -> integer(arg, flags)
            "x", "X" -> hex(arg).let { if (conversion == "X") it.uppercase() else it }
            "f" -> fixed(arg, precision ?: 6, flags)
            "s", "S" -> (if (arg == null) "null" else arg.toString()).let {
                val cut = if (precision != null && it.length > precision) it.substring(0, precision) else it
                if (conversion == "S") cut.uppercase() else cut
            }
            "c" -> arg.toString()
            "b" -> (arg != null && arg != false).toString()
            else -> m.value
        }
        val width = widthText.toIntOrNull() ?: 0
        if (body.length < width) {
            body = when {
                '-' in flags -> body.padEnd(width)
                '0' in flags && conversion in "dfxX" -> {
                    val sign = if (body.startsWith("-") || body.startsWith("+")) body.substring(0, 1) else ""
                    sign + body.substring(sign.length).padStart(width - sign.length, '0')
                }
                else -> body.padStart(width)
            }
        }
        out.append(body)
    }
    return out.toString()
}

private val SPEC = Regex("%(\\d+\\$)?([-+ 0,#]*)(\\d+)?(\\.\\d+)?([dxXfsScbn%])")

private fun integer(arg: Any?, flags: String): String {
    val v = when (arg) {
        is Long -> arg
        is Int -> arg.toLong()
        is Short -> arg.toLong()
        is Byte -> arg.toLong()
        is Number -> arg.toLong()
        else -> return arg.toString()
    }
    return if ('+' in flags && v >= 0) "+$v" else if (' ' in flags && v >= 0) " $v" else v.toString()
}

private fun hex(arg: Any?): String = when (arg) {
    is Int -> arg.toUInt().toString(16)
    is Long -> arg.toULong().toString(16)
    is Byte -> arg.toUByte().toString(16)
    is Short -> arg.toUShort().toString(16)
    else -> arg.toString()
}

private fun fixed(arg: Any?, precision: Int, flags: String): String {
    val v = (arg as? Number)?.toDouble() ?: return arg.toString()
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) (if ('+' in flags) "+Infinity" else "Infinity") else "-Infinity"
    // Java rounds the shortest decimal that reads back as the value - what
    // toString gives - half up, not the double's exact binary value: 0.005 is
    // "0.01" at two places, though the double itself is a hair under.
    val (digits, point) = decimal(abs(if (arg is Float) arg.toString().toDouble() else v), arg)
    val keep = point + precision // digits before the rounding place
    val rounded = StringBuilder()
    var carry: Boolean
    if (keep < 0) {
        repeat(precision + 1) { rounded.append('0') }
        carry = false
    } else {
        val padded = digits.padEnd(keep + 1, '0')
        rounded.append(padded.substring(0, keep))
        carry = padded[keep] >= '5'
    }
    val chars = rounded.toString().toCharArray()
    var k = chars.size - 1
    while (carry && k >= 0) {
        if (chars[k] == '9') { chars[k] = '0'; k-- } else { chars[k] = chars[k] + 1; carry = false }
    }
    var whole = chars.concatToString()
    var intLen = if (keep < 0) 1 else point
    if (carry) { whole = "1$whole"; intLen++ }
    if (keep < 0) whole = "0".repeat(1 + precision)
    val intPart = if (intLen <= 0) "0" else whole.substring(0, intLen).trimStart('0').ifEmpty { "0" }
    val fracPart = if (precision == 0) "" else whole.substring(maxOf(intLen, 0)).padStart(precision, '0').takeLast(precision)
    val body = if (precision == 0) intPart else "$intPart.$fracPart"
    // As Java does: a negative value keeps its sign even rounded to nothing
    // (-0.4 is "-0"), and so does negative zero.
    val negative = v < 0 || (v == 0.0 && 1.0 / v < 0)
    return when {
        negative -> "-$body"
        '+' in flags -> "+$body"
        ' ' in flags -> " $body"
        else -> body
    }
}

/**
 * The shortest decimal of [v] (non-negative) as its digits and where the point
 * falls: 12.5 is ("125", 2), 0.005 is ("5", -2). From toString, which is the
 * shortest on the JVM and in a browser alike, in either of its notations.
 */
private fun decimal(v: Double, original: Any?): Pair<String, Int> {
    val text = (if (original is Float) original.let { kotlin.math.abs(it) }.toString() else v.toString()).lowercase()
    val (mantissa, exponent) = text.split('e').let { it[0] to (it.getOrNull(1)?.toInt() ?: 0) }
    val intPart = mantissa.substringBefore('.')
    val fracPart = mantissa.substringAfter('.', "")
    var digits = intPart + fracPart
    var point = intPart.length + exponent
    val lead = digits.indexOfFirst { it != '0' }
    if (lead < 0) return "0" to 1
    digits = digits.substring(lead)
    point -= lead
    return digits.trimEnd('0').ifEmpty { "0" } to point
}
