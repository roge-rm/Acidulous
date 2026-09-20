package com.rm.acidulous.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.model.Manual
import com.rm.acidulous.model.ManualBlock
import com.rm.acidulous.model.ManualKind
import com.rm.acidulous.model.ManualSection
import com.rm.acidulous.ui.theme.Acid

/**
 * The manual, inside the app.
 *
 * **The same words as `manual/` in the repository**, lifted by
 * tools/gen_manual.py rather than copied by hand: a manual that disagrees with
 * the app is worse than no manual, and two hand-kept copies disagree within a
 * release. Nothing here is written here.
 *
 * **Contents first, and a section is its own window** - which is the shape the
 * licences already take a file along, and for the same reason this one cannot
 * be a [TabbedDialog]: that measures every page and takes the tallest, so the
 * longest section would make the short ones a screen of mostly nothing.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<ManualSection?>(null) }

    PlainDialog(title = "Help", onDismiss = onDismiss, dismissLabel = "Done") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListSection("the manual") {
                for (section in Manual.sections) {
                    DialogRow(
                        mark = "›",
                        name = section.title,
                        under = section.summary,
                    ) { reading = section }
                }
            }
        }
    }

    reading?.let { section ->
        PlainDialog(section.title, onDismiss = { reading = null }, dismissLabel = "Back", spacing = 0.dp) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (block in section.blocks) ManualLine(block)
            }
        }
    }
}

/** One line of the manual, in the shape its own mark asked for. */
@Composable
private fun ManualLine(block: ManualBlock) {
    val c = Acid.colors
    val text = inline(block.text)
    when (block.kind) {
        ManualKind.Heading -> Text(
            block.text,
            color = c.teal,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )
        ManualKind.Para -> Text(text, color = c.textMid, fontSize = 12.sp, lineHeight = 17.sp)
        // The mark sits in a column of its own so a wrapped line lines up under
        // the words rather than under the bullet.
        ManualKind.Bullet -> Row(Modifier.fillMaxWidth()) {
            Text("·", color = c.textDim, fontSize = 12.sp, modifier = Modifier.width(14.dp))
            Text(text, color = c.textMid, fontSize = 12.sp, lineHeight = 17.sp)
        }
        ManualKind.Step -> Row(Modifier.fillMaxWidth()) {
            Text("–", color = c.textDim, fontSize = 12.sp, modifier = Modifier.width(14.dp))
            Text(text, color = c.textMid, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/**
 * `**bold**`, `*emphasis*` and `` `code` `` drawn rather than printed.
 *
 * Done here rather than in the generator because it is a *drawing* question:
 * the Markdown stays the manual's own text, and this is the one place that
 * knows what this app's bold and its monospace look like.
 */
internal fun inline(source: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < source.length) {
        val bold = source.indexOf("**", i)
        val code = source.indexOf('`', i)
        val em = emphasisAt(source, i)
        val next = listOf(bold, code, em).filter { it >= 0 }.minOrNull() ?: -1
        if (next < 0) {
            append(source.substring(i))
            return@buildAnnotatedString
        }
        append(source.substring(i, next))
        i = when (next) {
            bold -> span(source, next + 2, "**", SpanStyle(fontWeight = FontWeight.Bold))
            code -> span(source, next + 1, "`", SpanStyle(fontFamily = FontFamily.Monospace))
            else -> span(source, next + 1, "*", SpanStyle(fontWeight = FontWeight.Medium))
        }
    }
}

/** A lone `*`, which is emphasis, as against the `**` of bold. */
private fun emphasisAt(s: String, from: Int): Int {
    var i = s.indexOf('*', from)
    while (i >= 0) {
        if (!s.startsWith("**", i)) return i
        i = s.indexOf('*', i + 2)
    }
    return -1
}

/** Append up to the closing mark in [style]; returns where to carry on. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.span(
    source: String,
    from: Int,
    close: String,
    style: SpanStyle,
): Int {
    val end = source.indexOf(close, from)
    if (end < 0) {
        // An unclosed mark is the manual's own punctuation, not a span.
        append(source.substring(from - close.length))
        return source.length
    }
    pushStyle(style)
    append(source.substring(from, end))
    pop()
    return end + close.length
}
