package com.rm.acidulous.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Every colour the app draws, by what it is for rather than what it looks
 * like.
 *
 * The dark set is the look the app has always had, lifted out of the two
 * hundred-odd literals that used to be scattered through the screens; the
 * light set is the same design read on paper instead of glass. Nothing
 * outside this file should name a colour: a literal at a use site is a
 * colour that cannot follow the theme, and it will be the one thing that
 * stays black when everything else turns white.
 *
 * Colours that belong to an object rather than to the interface are the
 * exception and are the same in both - a drawbar is brown or white because
 * organ drawbars are, and a track's stripe is how that track is recognised.
 */
@Immutable
data class AcidColors(
    val dark: Boolean,
    // Ground, from the deepest surface up to the most raised.
    val bgDeep: Color,
    val bg: Color,
    val sunken: Color,
    val panel: Color,
    val panelAlt: Color,
    val bar: Color,
    val card: Color,
    val cardAlt: Color,
    val cardHi: Color,
    val control: Color,
    val controlAlt: Color,
    val raised: Color,
    // Rules and grids.
    val line: Color,
    val lineStrong: Color,
    val gridBar: Color,
    val gridBeat: Color,
    val gridStep: Color,
    // Type, loudest to quietest, and what reads on a filled accent.
    val text: Color,
    val textHi: Color,
    val textMid: Color,
    val textDim: Color,
    val textFaint: Color,
    /**
     * What goes *on* [accent] - and only on it. It is near black in both
     * themes, so on [accentDim], which the dark theme makes a dark olive, it
     * is dark on dark. The clip-mode button did that and the label vanished.
     */
    val onAccent: Color,
    // The four voices the app speaks in: amber for what you are doing, teal
    // for what a thing is, green for on, red for danger.
    val accent: Color,
    val accentSoft: Color,
    /** Amber and green as a background rather than as a mark. */
    val accentDim: Color,
    val greenDim: Color,
    val teal: Color,
    val green: Color,
    val pink: Color,
    val red: Color,
    // The roll: row shading, then the same three for the name gutter.
    val rowWhite: Color,
    val rowBlack: Color,
    val rowOut: Color,
    val gutterWhite: Color,
    val gutterBlack: Color,
    val gutterOut: Color,
    val note: Color,
    val noteEdge: Color,
    val noteSel: Color,
    val noteSelEdge: Color,
    val selectBand: Color,
    val selectEdge: Color,
    // The keyboard.
    val keyWhite: Color,
    val keyBlack: Color,
    val keyEdge: Color,
    val keyLabel: Color,
    val keyLabelBlack: Color,
    val blackKey: Color,
    val blackKeyEdge: Color,
    // Scenes and clips on the song page.
    val sceneQueued: Color,
    val sceneProgress: Color,
    val padSelected: Color,
    // Knobs, wheels and meters.
    val knobPointer: Color,
    val wheelBg: Color,
    val wheelRidge: Color,
    val wheelCentre: Color,
    val wheelTab: Color,
    // Mosaic's zone map.
    val zoneOn: Color,
    val zoneOff: Color,
    val zoneOnEdge: Color,
    val zoneOffEdge: Color,
    // Nexus's canvas.
    val nodeBg: Color,
    val nodeEdge: Color,
    val canvasGrid: Color,
    val cable: Color,
    // Odds and ends.
    val overlay: Color,
    val overlayStrong: Color,
    val scrollbar: Color,
    val tip: Color,
)

/** The look the app was built in. */
val DarkColors = AcidColors(
    dark = true,
    bgDeep = Color(0xFF15151A),
    bg = Color(0xFF1B1B1E),
    sunken = Color(0xFF17171A),
    panel = Color(0xFF1F1F23),
    panelAlt = Color(0xFF202024),
    bar = Color(0xFF232326),
    card = Color(0xFF26262B),
    cardAlt = Color(0xFF2A2A2F),
    cardHi = Color(0xFF33333A),
    control = Color(0xFF2E2E33),
    controlAlt = Color(0xFF2A2A30),
    raised = Color(0xFF3A3A40),
    line = Color(0xFF3E3E44),
    lineStrong = Color(0xFF44444C),
    gridBar = Color(0xFF8A8A92),
    gridBeat = Color(0xFF55555C),
    gridStep = Color(0xFF3A3A40),
    text = Color(0xFFFFFFFF),
    textHi = Color(0xFFDDDDE2),
    textMid = Color(0xFFBBBBBB),
    textDim = Color(0xFF9A9AA2),
    textFaint = Color(0xFF66666E),
    onAccent = Color(0xFF1B1B1E),
    accent = Color(0xFFFFB454),
    accentSoft = Color(0xFFFFE0A0),
    accentDim = Color(0xFF3A3226),
    greenDim = Color(0xFF2E4A3E),
    teal = Color(0xFF7FD1B9),
    green = Color(0xFF3F7D5E),
    pink = Color(0xFFE07A9A),
    red = Color(0xFFE74C3C),
    rowWhite = Color(0xFF2C2C30),
    rowBlack = Color(0xFF232326),
    rowOut = Color(0xFF202023),
    gutterWhite = Color(0xFF303036),
    gutterBlack = Color(0xFF191A1D),
    gutterOut = Color(0xFF151517),
    note = Color(0xFF4E8F73),
    noteEdge = Color(0xFF7FD1B9),
    noteSel = Color(0xFFF2F2F0),
    noteSelEdge = Color(0xFFB7E3CF),
    selectBand = Color(0x33FFFFFF),
    selectEdge = Color(0xCCFFFFFF),
    keyWhite = Color(0xFFECECE6),
    keyBlack = Color(0xFF43434C),
    keyEdge = Color(0xFF6A6A76),
    keyLabel = Color(0xFF6A6A72),
    keyLabelBlack = Color(0xFF9A9AA2),
    blackKey = Color(0xFF141416),
    blackKeyEdge = Color(0xFF0B0B0C),
    sceneQueued = Color(0xFF7A5A24),
    sceneProgress = Color(0xFF55A583),
    padSelected = Color(0xFF3F4A55),
    knobPointer = Color(0xFFE8E8E4),
    wheelBg = Color(0xFF1E1E23),
    wheelRidge = Color(0xFF2A2A31),
    wheelCentre = Color(0xFF45454F),
    wheelTab = Color(0x66FFFFFF),
    zoneOn = Color(0x883F7D5E),
    zoneOff = Color(0x552E6E8E),
    zoneOnEdge = Color(0xFF7FD1B9),
    zoneOffEdge = Color(0xFF4A7A8C),
    nodeBg = Color(0xFF26262E),
    nodeEdge = Color(0xFF3A3A45),
    canvasGrid = Color(0xFF1E1E24),
    cable = Color(0x9959C2A8),
    overlay = Color(0x22FFFFFF),
    overlayStrong = Color(0x33FFFFFF),
    scrollbar = Color(0xB08E8E98),
    tip = Color(0xEE17171A),
)

/**
 * The same instrument in daylight.
 *
 * Two things do not survive a straight inversion and are handled by hand.
 * Amber at full brightness disappears on white, so anything made of amber
 * *type* is a deeper amber here while amber *fills* keep their heat. And a
 * selected note is the brightest thing in the roll on dark, which on paper
 * has to become the darkest - inverted, not lightened.
 */
val LightColors = AcidColors(
    dark = false,
    bgDeep = Color(0xFFEDECE7),
    bg = Color(0xFFF4F3EF),
    sunken = Color(0xFFE7E6E0),
    panel = Color(0xFFEBEAE4),
    panelAlt = Color(0xFFE9E8E2),
    bar = Color(0xFFE4E3DC),
    card = Color(0xFFFFFFFF),
    cardAlt = Color(0xFFFAF9F5),
    cardHi = Color(0xFFF0EFE9),
    control = Color(0xFFDEDDD5),
    controlAlt = Color(0xFFE2E1D9),
    raised = Color(0xFFCFCEC5),
    line = Color(0xFFD3D2C9),
    lineStrong = Color(0xFFBDBCB2),
    gridBar = Color(0xFF8A8A84),
    gridBeat = Color(0xFFB6B5AC),
    gridStep = Color(0xFFDAD9D0),
    text = Color(0xFF16161A),
    textHi = Color(0xFF25252A),
    textMid = Color(0xFF4A4A50),
    textDim = Color(0xFF6C6C74),
    textFaint = Color(0xFF87878E),
    onAccent = Color(0xFF1B1B1E),
    accent = Color(0xFFA96A05),
    accentSoft = Color(0xFFDCA23C),
    accentDim = Color(0xFFF6E6C6),
    greenDim = Color(0xFFC3DCCE),
    teal = Color(0xFF23856A),
    green = Color(0xFF3F7D5E),
    pink = Color(0xFFBC4C70),
    red = Color(0xFFC0392B),
    rowWhite = Color(0xFFFFFFFF),
    rowBlack = Color(0xFFECEBE5),
    rowOut = Color(0xFFE4E3DC),
    gutterWhite = Color(0xFFF8F7F2),
    gutterBlack = Color(0xFFDCDBD3),
    gutterOut = Color(0xFFE9E8E2),
    note = Color(0xFF56A184),
    noteEdge = Color(0xFF23856A),
    noteSel = Color(0xFF1F2E28),
    noteSelEdge = Color(0xFF0E1A15),
    selectBand = Color(0x22000000),
    selectEdge = Color(0xCC2A2A2A),
    keyWhite = Color(0xFFFDFDFA),
    keyBlack = Color(0xFF2E2E36),
    keyEdge = Color(0xFF9D9C93),
    keyLabel = Color(0xFF7A7A80),
    keyLabelBlack = Color(0xFFCCCCD2),
    blackKey = Color(0xFF2A2A31),
    blackKeyEdge = Color(0xFF15151A),
    sceneQueued = Color(0xFFD9A94A),
    sceneProgress = Color(0xFF6FBF97),
    padSelected = Color(0xFFC2CCD6),
    knobPointer = Color(0xFF3A3A40),
    wheelBg = Color(0xFFE2E1D9),
    wheelRidge = Color(0xFFD0CFC6),
    wheelCentre = Color(0xFFB4B3A9),
    wheelTab = Color(0x66000000),
    zoneOn = Color(0x883F7D5E),
    zoneOff = Color(0x552E6E8E),
    zoneOnEdge = Color(0xFF23856A),
    zoneOffEdge = Color(0xFF3E6E80),
    nodeBg = Color(0xFFFFFFFF),
    nodeEdge = Color(0xFFC9C8BF),
    canvasGrid = Color(0xFFE2E1DA),
    cable = Color(0x993F8F75),
    overlay = Color(0x14000000),
    overlayStrong = Color(0x22000000),
    scrollbar = Color(0x99666670),
    tip = Color(0xEEE7E6E0),
)

/**
 * Colours that belong to the thing, not to the theme: an organ's drawbars
 * and the stripes that tell one track from another. They do not change
 * between light and dark, because they are not interface.
 */
val DrawbarBrown = Color(0xFF8A6A4A)
val DrawbarWhite = Color(0xFFE8E4DA)
val DrawbarBlack = Color(0xFF6E6E76)

internal val LocalAcidColors = staticCompositionLocalOf { DarkColors }

/** `Acid.colors.accent` - the one way to name a colour outside this file. */
object Acid {
    val colors: AcidColors
        @Composable @ReadOnlyComposable get() = LocalAcidColors.current
}
