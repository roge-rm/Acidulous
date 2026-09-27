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
import com.rm.acidulous.res.*

/**
 * The manual, inside the app.
 *
 * The text comes from manual/ in the repository via tools/gen_manual.py, so the
 * app and the manual can't drift apart. Don't write manual text here.
 *
 * Contents first, each section opens in its own window, and each machine is a
 * page inside that. This can't be a [TabbedDialog] because that sizes every
 * page to the tallest, which would leave short sections mostly empty.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    var reading by remember { mutableStateOf<ManualSection?>(null) }
    var page by remember { mutableStateOf<ManualSection?>(null) }

    PlainDialog(title = stringResource(Res.string.help_title), onDismiss = onDismiss, dismissLabel = stringResource(Res.string.done)) {
        WindowWidth(600.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListSection(stringResource(Res.string.help_manual)) {
                for (section in Manual.sections) {
                    DialogRow(
                        mark = "›",
                        name = section.title,
                        under = section.summary(com.rm.acidulous.AppHost.current.onDesktop),
                    ) { reading = section }
                }
            }
        }
    }

    reading?.let { section ->
        PlainDialog(section.title, onDismiss = { reading = null }, dismissLabel = stringResource(Res.string.help_back), spacing = 0.dp) {
            // A comfortable reading width, about 100 characters.
            WindowWidth(720.dp)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (block in section.blocks) ManualLine(block)
                // Sections with children (the machines) list them under the
                // summary, each opening its own page.
                if (section.children.isNotEmpty()) {
                    ListSection(stringResource(Res.string.help_in_detail)) {
                        for (child in section.children) {
                            DialogRow(mark = "›", name = child.title, under = child.summary(com.rm.acidulous.AppHost.current.onDesktop)) {
                                page = child
                            }
                        }
                    }
                }
            }
        }
    }

    page?.let { child ->
        PlainDialog(child.title, onDismiss = { page = null }, dismissLabel = stringResource(Res.string.help_back), spacing = 0.dp) {
            WindowWidth(720.dp)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (block in child.blocks) ManualLine(block)
            }
        }
    }
}

/** One line of the manual, drawn the way its mark says. */
@Composable
private fun ManualLine(block: ManualBlock) {
    val c = Acid.colors
    // The phone text or the desktop text, see gen_manual.py. A line that's only
    // for the other platform is empty here and not drawn.
    val words = block.text(com.rm.acidulous.AppHost.current.onDesktop)
    if (words.isEmpty()) return
    val text = inline(words)
    when (block.kind) {
        ManualKind.Heading -> Text(
            words,
            color = c.teal,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp),
        )
        // A subheading: body font, brighter and heavier, so it reads as part of
        // the teal heading above it.
        ManualKind.Subheading -> Text(
            words,
            color = c.text,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
        ManualKind.Para -> Text(text, color = c.textMid, fontSize = 12.sp, lineHeight = 17.sp)
        // The bullet has its own column so wrapped lines line up under the
        // text.
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
 * Draws `**bold**`, `*emphasis*` and `` `code` `` instead of printing the
 * marks.
 *
 * Done here rather than in the generator so the manual stays plain Markdown and
 * this is the one place that knows how bold and monospace look in the app.
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

/** A single `*` (emphasis), as opposed to the `**` of bold. */
private fun emphasisAt(s: String, from: Int): Int {
    var i = s.indexOf('*', from)
    while (i >= 0) {
        if (!s.startsWith("**", i)) return i
        i = s.indexOf('*', i + 2)
    }
    return -1
}

/** Append up to the closing mark in [style], returns where to continue. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.span(
    source: String,
    from: Int,
    close: String,
    style: SpanStyle,
): Int {
    val end = source.indexOf(close, from)
    if (end < 0) {
        // An unclosed mark is just punctuation.
        append(source.substring(from - close.length))
        return source.length
    }
    pushStyle(style)
    append(source.substring(from, end))
    pop()
    return end + close.length
}
