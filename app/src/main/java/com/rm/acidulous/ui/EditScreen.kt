package com.rm.acidulous.ui

import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.engine.Position
import com.rm.acidulous.model.Action
import com.rm.acidulous.model.MachineKind
import com.rm.acidulous.model.MachineUi
import com.rm.acidulous.model.Scales
import com.rm.acidulous.model.EVENTOR_SLOTS
import com.rm.acidulous.model.eventorUnit
import com.rm.acidulous.model.withEventor
import com.rm.acidulous.model.withEventorBypass
import com.rm.acidulous.model.withEventorParam
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.rm.acidulous.model.Note
import com.rm.acidulous.model.PPQN
import com.rm.acidulous.model.Song
import com.rm.acidulous.model.Track
import com.rm.acidulous.model.SongEditor
import com.rm.acidulous.model.clipLengthTicks
import com.rm.acidulous.model.withSetting
import com.rm.acidulous.model.emptyClipFor
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.ui.theme.AcidColors

/**
 * The edit screen, phone-sized: header, piano roll, footer, and the
 * machine panel under the roll.
 */
// One slot per eventor, in the order the notes travel through them.
private const val EV_CHORD = 0
private const val EV_SCALE = 1
private const val EV_ARP = 2

@Composable
fun EditScreen(
    song: Song,
    editor: SongEditor,
    trackIndex: Int,
    sceneId: String,
    position: Position,
    playing: Boolean,
    armed: Boolean,
    onArm: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenPatch: () -> Unit = {},
    patchNames: () -> List<String>,
    /** The name, and the notes the keyboard was showing - the range a saved patch remembers. */
    onSavePatch: (name: String, low: Int, high: Int) -> Unit,
    onLoadPatch: (String) -> com.rm.acidulous.model.Patch?,
    factoryPatchNames: () -> List<com.rm.acidulous.model.Patch> = { emptyList() },
    userPatchNames: () -> List<String> = { emptyList() },
    onDeletePatch: (String) -> Unit = {},
    onImportSample: (track: Int, pad: Int) -> Unit = { _, _ -> },
    onImportKit: (track: Int, pad: Int) -> Unit = { _, _ -> },
    onImportSlice: (track: Int) -> Unit = {},
    /** One pad's sample, on its own page with a picture of it. */
    onOpenSample: (pad: Int) -> Unit = {},
    /** Pollen's single sample, which is keyed by name rather than by pad. */
    onImportOneSample: (track: Int) -> Unit = {},
    onImportSoundFont: (track: Int) -> Unit = {},
    onPickPreset: (track: Int) -> Unit = {},
    onImportZoneSamples: (track: Int) -> Unit = {},
    /** For the mixer, which now opens over the editor: the mix is worth
     *  reaching without leaving the machine you are voicing. */
    rackPeaks: FloatArray = FloatArray(16),
    masterPeak: Float = 0f,
    clickOn: Boolean = false,
    onClick: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val track = song.tracks.getOrNull(trackIndex) ?: return
    val scene = song.scenes.firstOrNull { it.id == sceneId } ?: return
    val clip = track.clips[sceneId] ?: song.emptyClipFor(sceneId)
    val ticksPerBar = song.signatureOf(scene).ticksPerBar
    val clipLen = song.clipLengthTicks(sceneId, clip)

    var mode by remember { mutableStateOf(EditMode.Draw) }
    val kind = MachineUi.kindOf(track.machine.type)
    // The machine's alternate editor over the same clip. A drum machine opens on
    // its grid - that is the editor for it; a keyboard machine opens on the roll.
    var steps by remember(trackIndex, kind) { mutableStateOf(kind == MachineKind.Drums) }
    val voices = MachineUi.voicesOf(track.machine.type, track.machine.settings)
    var selectedPad by remember(trackIndex) { mutableStateOf(0) }
    // Subvert only. A drum machine opens on its grid and stays there: the
    // grid *is* the editor for one, and a roll of it - sixteen lanes of
    // one-tick notes you cannot name - answers no question the grid does not
    // answer better. Subvert keeps the choice because its two views are
    // genuinely different instruments to edit in, a roll and a step row.
    //
    // Note what this does not do: `steps` still starts true for drums and
    // simply never changes, so the grid is reached the same way it always
    // was. Nothing below has to learn that drums are a special case.
    val hasSteps = track.machine.type == "Subvert"
    var laneKey by remember { mutableStateOf<String?>(null) }
    val slotTypes = track.effects.map { it.type } + track.eventors.map { it.type }
    val laneKeys = remember(track.machine.type, slotTypes) { automationKeysFor(track) }
    var panel by remember { mutableStateOf(0) } // 0 machine, 1 effects, 2 the mixer - in the same space
    // Which eventor the chips have opened, if any.
    var eventorSlot by remember { mutableStateOf(-1) }
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    var scaleDialog by remember { mutableStateOf(false) }
    // Folding the strip is a preference, not a property of this clip, so it
    // is held for the whole app and across launches - see UiPrefs.
    val landscape = isLandscape()
    // **A fold is about the shape of the screen, so there is one of each per
    // shape.** Sideways a lane costs eighty-eight dp of the three hundred and
    // ninety-three there are, which left the roll about four rows of pitch;
    // both start folded there. Folding one sideways must not put it away
    // upright, and opening one upright must not open it sideways, so the two
    // pairs are separate flags rather than one shared one.
    val autoFolded = if (landscape) UiPrefs.automationFoldedLand else UiPrefs.automationFolded
    val noteFolded = if (landscape) UiPrefs.noteLaneFoldedLand else UiPrefs.noteLaneFolded
    // Which of a note's properties the lane is showing. Per track, like the
    // roll's zoom: it is how you are working, not a property of the music.
    var noteProp by remember(trackIndex) { mutableStateOf(NoteProp.Velocity) }
    // Which pitch the note lane is showing, or every pitch. Keyed on the
    // track, like the property beside it: a filter that survived a jump to
    // another machine would name a pitch that machine has never played.
    var notePitch by remember(trackIndex) { mutableStateOf<Int?>(null) }
    var scaleView by rememberSaveable { mutableStateOf(ScaleView.Dim) }
    // Long clips are paged two bars at a time, as the drum grid is paged one.
    // More than two bars across a phone leaves notes too narrow to grab.
    // Where the roll is looking and how close in, kept per track rather than
    // per clip: it is how *you* like to work, not a property of the music.
    // Nought means "whatever suits the screen", so a phone turning sideways
    // still gets the sideways defaults until a pinch says otherwise.
    // The roll's own slot, measured. It is a `weight(1f)` of whatever the
    // header, the lanes, the panel and the keyboard leave, so only the layout
    // knows it - the same reason `DrumGrid` measures rather than assumes.
    var rollPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // How much taller than its stated height the instrument has been dragged,
    // upright. Live while the finger is down and committed to the store when
    // it lifts, exactly as the turned editor's divider is.
    var keysStretch by remember { androidx.compose.runtime.mutableFloatStateOf(UiPrefs.keysStretch) }
    val scale = LocalUiScale.current
    var zoomRows by rememberSaveable(trackIndex) { mutableStateOf(0f) }
    var zoomTicks by rememberSaveable(trackIndex) { mutableStateOf(0f) }
    var scrollTick by rememberSaveable(trackIndex, sceneId) { mutableStateOf(0f) }

    // The scale lives in a Scale eventor; the chip and its dialog are only a
    // shortcut to the one eventor worth reaching while playing.
    // One slot each, fixed, left to right as the chips are: chord builds the
    // notes, scale corrects them, arp sequences what comes out. Before this
    // the scale took whichever slot was free, which was fine while two of
    // three could run at once and is not now that all three can.
    fun scaleSlot(): Int = EV_SCALE

    fun currentScale(): ScaleSetting {
        val ev = track.eventorAt(scaleSlot())
        return ScaleSetting(
            on = ev.type == "Scale" && !ev.bypass,
            key = Math.round((ev.params["key"] ?: 0f) * 11f),
            scale = Math.round((ev.params["scale"] ?: 0f) * 32f),
            degree = (ev.params["mode"] ?: 0f) >= 0.5f,
            snap = Math.round((ev.params["snap"] ?: 0f) * 2f),
        )
    }

    fun applyScale(s: ScaleSetting) {
        val slot = scaleSlot()
        editor.edit(trackIndex) { t ->
            (if (t.eventorAt(slot).type == "Scale") t else t.withEventor(slot, "Scale"))
                .withEventorParam(slot, "key", s.key / 11f)
                .withEventorParam(slot, "scale", s.scale / 32f)
                .withEventorParam(slot, "mode", if (s.degree) 1f else 0f)
                .withEventorParam(slot, "snap", s.snap / 2f)
                .withEventorBypass(slot, !s.on)
        }
    }
    var lowestPitch by remember {
        val lowest = clip.notes.minOfOrNull { it.pitch } ?: 36
        mutableStateOf((lowest - 3).coerceIn(0, 127 - ROWS))
    }
    val scope = rememberCoroutineScope()

    val playhead = if (playing && song.scenes.getOrNull(position.scene)?.id == sceneId) {
        position.tickInIteration % clipLen
    } else null

    // Two bars at a time in the roll, one in the step views: any more and the
    // notes are too narrow to grab. The count of pages follows from that.
    //
    // **How many rows is worked out from the height there is**, not stated -
    // see RowTarget. Fewer rows sideways falls out of the same arithmetic,
    // because a turned phone has less height to share and a row shorter than
    // about 11dp loses its name. Until the slot has been measured the old
    // counts stand in for one frame, which is what `DrumGrid` does too.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rollDp = with(density) { rollPx.toDp().value }
    val windowDp = with(density) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp().value
    }
    // The ceiling is a size written as a count - three octaves is a row of
    // about four dp - so it is worth the same physically at every setting.
    val maxRows = (MaxRows / scale).roundToInt().coerceAtLeast(MinRows + 1)
    val base = if (landscape) ROWS_LAND else ROWS
    val defaultRows = if (rollPx == 0) base.coerceAtMost(maxRows)
    else rowsForSlot(rollDp, windowDp, scale, base, MinRows, maxRows)
    val rows = (if (zoomRows > 0f) zoomRows else defaultRows.toFloat())
        .toInt().coerceIn(MinRows, maxRows)
    val defaultBars = if (steps) 1 else if (landscape) PAGE_BARS_LAND else PAGE_BARS
    val defaultTicks = (defaultBars * ticksPerBar).toFloat()
    // A pinch never shows less than a beat or more than the whole clip: below
    // a beat there is nothing left to aim at, and beyond the clip there is
    // nothing left to see.
    val pageTicks = (if (zoomTicks > 0f) zoomTicks else defaultTicks)
        .coerceIn(PPQN.toFloat(), clipLen.toFloat().coerceAtLeast(PPQN.toFloat()))
    val maxScroll = (clipLen - pageTicks).coerceAtLeast(0f)
    scrollTick = scrollTick.coerceIn(0f, maxScroll)
    val firstTick = scrollTick.toInt()
    val pages = kotlin.math.ceil(clipLen / pageTicks).toInt().coerceAtLeast(1)
    val page = (scrollTick / pageTicks).toInt().coerceIn(0, pages - 1)
    // While playing, follow the playhead onto its own page rather than
    // leaving the editor staring at a bar that is not sounding.
    val playheadPage = if (playhead != null && clipLen > 0) (playhead / pageTicks).toInt() else -1
    LaunchedEffect(playheadPage, playing) {
        if (playing && playheadPage in 0 until pages) scrollTick = playheadPage * pageTicks
    }


    fun preview(pitch: Int) {
        NativeEngine.noteOn(trackIndex, pitch, 100)
        scope.launch { delay(120); NativeEngine.noteOff(trackIndex, pitch) }
    }

    // `modifier` carries the Scaffold's system-bar padding; without it the
    // footer sits under the navigation bar and its taps become Back.
    Column(modifier.fillMaxSize().background(Acid.colors.bg)) {
        val footerSlot: @Composable () -> Unit = {
        // The bottom bar: what this screen is showing, then the three that
        // end every row in the app - mix, rec, play - at the width and in
        // the order the arranger has them, so nothing you reach for moves
        // when you open a clip. See ui/BottomBar.kt.
        //
        // Undo and redo are anchors here, not a weighted share: they were
        // twenty-six dp wide when they took one, and they sit in the same
        // place and at the same width as the arranger's - see BarAnchor.
        //
        // Sideways the bar is a column against the right edge, and an anchor
        // is then a *height* rather than a width - which the pill asks for as
        // `anchor` without knowing which. That is why the three `if
        // (landscape)` branches that used to be here are gone: see BarScope.
        // How many buttons the left of this row carries: fx, and whichever
        // view toggles this machine has. It varies by machine - a drum track
        // has only fx - and a lone weighted child takes the whole pool, which
        // made fx a ninety-three dp pill beside a row of forty-fours.
        //
        // So they take an anchor's width like everything else, and a spacer
        // holds the right-hand group where it belongs. Three of them will not
        // fit at that width - eight pills and their gaps is 380dp of a
        // phone's 377 - and only then do they share.
        // The strength toggle counts as one of them: a drum machine has
        // only fx on the left otherwise, and this sits in the gap beside it.
        //
        // **One pill, for whichever surface this machine plays with.** Keys
        // read the height of a strike exactly as pads do, and a second pill
        // saying the same thing about the other surface - the one not on
        // screen - would be a control for something you cannot see.
        val padToggle = kind == MachineKind.Drums
        val fullStrength = if (padToggle) UiPrefs.padsFullStrength else UiPrefs.keysFullStrength
        // Fill earns its place only where there is a fill trig to hear. A
        // performance control that is always on this row would cost the seven
        // beside it the width - eight pills and their gaps is already 380 dp
        // of a phone's 377 - and a clip with no fill in it has nothing to say
        // about one. Reaching it while performing is what `Action.Fill` and a
        // pad on a controller are for.
        val hasFill = clip.notes.any {
            it.trig == com.rm.acidulous.model.Trig.Fill || it.trig == com.rm.acidulous.model.Trig.NotFill
        }
        val views = 2 + (if (hasSteps) 1 else 0) + (if (!steps) 1 else 0) + (if (hasFill) 1 else 0)
        BottomBar(
            // Sideways it stands in the header, so it is a row again and the
            // header says where. **And it has no fold of its own any more.**
            // A column against the right edge was worth being able to put
            // away; eight pills in the empty half of a header that was
            // already there cost the roll nothing, and a control to hide
            // something free is a control for nothing.
            inline = landscape,
        ) {
            val view = if (views >= 3) Modifier.barWeight() else anchor
            // fx first, at the head of the row. It is the pair to mix at the
            // other end - both swap what the panel under the roll is showing -
            // and the two beside it are about the roll itself.
            BarButton(
                "fx", view,
                colour = if (panel == 1) Acid.colors.accent else Color.Unspecified,
            ) { panel = if (panel == 1) 0 else 1 }
            // How hard the instrument hits: a wedge for velocity off the
            // height of the strike, a solid block for the same full strength
            // wherever it lands.
            BarButton(
                if (fullStrength) "\u25A0" else "\u25E2", view,
                colour = if (fullStrength) Acid.colors.accent else Color.Unspecified,
            ) {
                if (padToggle) UiPrefs.choosePadsFullStrength(!fullStrength)
                else UiPrefs.chooseKeysFullStrength(!fullStrength)
            }
            if (hasFill) {
                BarHoldButton(
                    "fill",
                    view.mappable(MapTargets.action(com.rm.acidulous.model.Action.Fill.name)),
                    held = UiPrefs.fillHeld,
                ) { UiPrefs.holdFill(it) }
            }
            if (hasSteps) {
                BarButton(if (steps) "\u25A6" else "\u25A4", view) { steps = !steps }
            }
            if (!steps) {
                BarButton(if (mode == EditMode.Draw) "\u270E" else "\u2B1A", view) {
                    mode = if (mode == EditMode.Draw) EditMode.Select else EditMode.Draw
                }
            }
            // The gap that holds the transport against the far end. Not in
            // the header, where the row is only as wide as its pills and a
            // weighted spacer would swallow the header.
            if (!landscape && views < 3) Spacer(Modifier.barSpace())
            BarButton(
                "\u21B6", anchor, enabled = editor.canUndo(trackIndex),
            ) { selection = emptySet(); editor.undo(trackIndex) }
            // Mapping mode, on a long press of redo - the same gesture on the
            // same control in the same place as the arranger's.
            BarButton(
                "\u21B7",
                anchor.onLongPress { UiPrefs.chooseMapMode(!UiPrefs.mapMode) },
                colour = if (UiPrefs.mapMode) Acid.colors.accent else Color.Unspecified,
                enabled = editor.canRedo(trackIndex),
            ) { selection = emptySet(); editor.redo(trackIndex) }
            // One glyph each, as play has always been, so the three read as
            // one group and say the same thing at any width - which retires
            // the two labels that had to be shortened for landscape.
            BarButton(
                "\u21C5", anchor,
                colour = if (panel == 2) Acid.colors.accent else Color.Unspecified,
            ) { panel = if (panel == 2) 0 else 2 }
            BarButton(
                // The glyph carries two states, because the pill carries two
                // controls: a tap arms, a long press turns the click on, and
                // the red ring is already spoken for by the first of them.
                // Dan: "it's hard to tell whether just recording is on or
                // whether both record and metronome are on".
                (if (armed) "\u25CF" else "\u25CB") + if (clickOn) "\u266A" else "",
                // Hold it for the click. The metronome is a thing you want on
                // for one take and off for the next, and it lived two taps
                // deep behind the tempo. Guarded on mapping mode, because
                // mappable already claims a long press there to forget what
                // drives a control.
                anchor.mappable(MapTargets.action(Action.RecordArm.name)),
                border = if (armed) Acid.colors.red else null,
                onLongPress = { if (!UiPrefs.mapMode) onClick(!clickOn) },
            ) { onArm(!armed) }
            BarButton(
                if (playing) "\u25A0" else "\u25B6",
                anchor.mappable(MapTargets.action(Action.PlayStop.name)),
                // Hold it to stop *everything* - every voice, every tail,
                // every held note. The same gesture on the same pill as the
                // arranger's, guarded the same way: `mappable` claims a long
                // press in mapping mode to forget what drives a control.
                onLongPress = { if (!UiPrefs.mapMode) panicEverything() },
            ) {
                if (playing) NativeEngine.transportStop() else NativeEngine.transportPlay(song.scenes.indexOf(scene))
            }
        }
        }

        // Header: back · track · scene · octave. It lays itself out around
        // the camera hole rather than below it, so on a phone with a cutout
        // this row costs no height at all - see ui/Cutout.kt.
        CutoutRow(
            Modifier.fillMaxWidth(),
            // Thin, because the buttons now fill the band: 2 + 44 + 2 is the
            // 6 + 40 + 2 it was, so a bigger target costs the roll nothing.
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            spacing = 2.dp,
        ) {
            // Thirty dp, not forty-two: this arrow is no longer the whole of
            // the way out - the title beside it goes back too - so what has
            // to be hit is the arrow *plus* two hundred dp of title, and the
            // arrow only has to say so. What it gives up goes to the title.
            HeaderButton("◀", width = 30.dp) { onBack() }
            // A frozen clip is playing audio, so nothing edited here is
            // heard until it is thawed - which this does, since the alarm
            // and the way out belong in the same place.
            if (clip.frozen != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val stale = com.rm.acidulous.model.Freeze.stale(song, sceneId, clip)
                HeaderTextButton(
                    "\u2744\uFE0E",
                    color = if (stale) Acid.colors.accent else Acid.colors.teal,
                ) {
                    com.rm.acidulous.model.Freeze.discard(context, song, com.rm.acidulous.model.Freeze.Target(trackIndex, sceneId))
                    editor.editClip(trackIndex, sceneId) { it.copy(frozen = null) }
                }
            }
            // The title goes back too, so the ◀ and everything after it up to
            // the paging is one long back button. It is the widest thing in
            // the header and it did nothing; on a phone held one-handed the
            // arrow alone is a small target at the far corner.
            Text(
                "${track.name} · ${scene.name} · ${clip.bars}b · ${clip.notes.size}n" +
                    (if (clip.automation.isEmpty()) "" else " · ${clip.automation.values.sumOf { it.points.size }}a") +
                    // The selection count reads here rather than in the bar.
                    // It is a reading, and this line is where this screen's
                    // readings are; in the bar it was forty dp reserved
                    // against a number that is usually not there, and that
                    // forty dp is what the buttons beside it needed.
                    (if (selection.isEmpty()) "" else " · ${selection.size} sel"),
                color = Acid.colors.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                // The band's own height rather than fillMaxHeight: the row is
                // a SubcomposeLayout and does not hand children a bounded
                // height to fill.
                modifier = Modifier.flexible()
                    .height(LocalHeaderBand.current)
                    .clickable(onClick = onBack)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Paging lives here rather than in a row of its own: a whole row of
            // chrome to show one number costs more height than a phone has to
            // spare, and the header already has the two buttons it belongs with.
            if (pages > 1) {
                HeaderButton("◀") { scrollTick = ((page - 1 + pages) % pages) * pageTicks }
                Text(
                    "${page + 1}/$pages", color = Acid.colors.accent, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                )
                HeaderButton("▶") { scrollTick = ((page + 1) % pages) * pageTicks }
            }
            // **The transport, sideways.** Dan drew an arrow from the column
            // against the right edge up to this corner: turned, the header is
            // a title and six hundred dp of nothing, and the transport is
            // eight pills looking for a home. Upright there is no such
            // corner - the header is 393 dp wide and the title fills it - so
            // it stays in the bar at the foot of the screen there.
            if (landscape) footerSlot()
            // Last, at the far edge, as it is on the patch editor: a reading
            // rather than a control, so it sits past the things you press.
            LoadMeter()
        }
        // The editor is one stack in portrait and two panes in landscape. The
        // pieces are the same either way; only the arrangement differs, so
        // each is written once here and placed below.
        var octave by rememberSaveable(trackIndex) { mutableStateOf(3) }
        val gridSlot: @Composable ColumnScope.() -> Unit = {
        if (steps && kind == MachineKind.Drums) DrumGrid(
            clip = clip,
            ticksPerBar = ticksPerBar,
            voices = voices,
            playheadTick = playhead,
            firstTick = firstTick,
            visibleTicks = pageTicks.toInt(),
            onScrollTime = { ticks -> scrollTick = (scrollTick + ticks).coerceIn(0f, maxScroll) },
            onZoomTime = { scale ->
                val was = if (zoomTicks > 0f) zoomTicks else defaultTicks
                val span = clipLen.toFloat().coerceAtLeast(PPQN.toFloat())
                val centre = scrollTick + was / 2f
                val want = (was * scale).coerceIn(PPQN.toFloat(), span)
                zoomTicks = want
                scrollTick = (centre - want / 2f).coerceIn(0f, (clipLen - want).coerceAtLeast(0f))
            },
            onSetHit = { tick, note, hit ->
                editor.editClip(trackIndex, sceneId) { c ->
                    val others = c.notes.filter { !(it.tick == tick && it.pitch == note) }
                    c.copy(notes = (if (hit != null) others + hit else others).sortedBy { it.tick })
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) else if (steps) StepEditor(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            barIndex = page,
            onSetStep = { tick, note ->
                editor.editClip(trackIndex, sceneId) { c ->
                    val others = c.notes.filter { it.tick != tick }
                    c.copy(notes = (if (note != null) others + note else others).sortedBy { it.tick })
                }
            },
            onPitchGestureBegin = { editor.beginGesture(trackIndex) },
            onPitchGesture = { tick, note ->
                editor.updateGestureClip(sceneId) { base -> base.copy(notes = base.notes.map { if (it.tick == tick) note else it }) }
            },
            onPitchGestureEnd = { editor.endGesture() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) else PianoRoll(
            clip = clip,
            ticksPerBar = ticksPerBar,
            mode = mode,
            selection = selection,
            playheadTick = playhead,
            lowestPitch = lowestPitch,
            rows = rows,
            scalePitchClasses = Scales.activeFor(track),
            noteSpelling = Scales.spellingFor(track),
            scaleView = scaleView,
            firstTick = firstTick,
            visibleTicks = pageTicks.toInt(),
            onCycleScaleView = {
                // Dim and fit need a scale to dim or fit to, so before one is
                // set the corner does the only useful thing: asks for one.
                if (Scales.activeFor(track) == null) scaleDialog = true
                else scaleView = when (scaleView) {
                    ScaleView.Chromatic -> ScaleView.Dim
                    ScaleView.Dim -> ScaleView.Fold
                    ScaleView.Fold -> ScaleView.Chromatic
                }
            },
            onTapEmpty = { tick, pitch ->
                selection = emptySet()
                editor.editClip(trackIndex, sceneId) { c -> c.copy(notes = c.notes + Note(tick, c.grid, pitch, 100)) }
                preview(pitch)
            },
            onTapNote = { index ->
                selection = emptySet()
                editor.editClip(trackIndex, sceneId) { c -> c.copy(notes = c.notes.filterIndexed { i, _ -> i != index }) }
            },
            onSelectionChange = { selection = it },
            onAudition = { pitch -> preview(pitch) },
            // The same clamp the octave buttons had, so the window can never
            // run off either end of the keyboard.
            onScrollPitch = { delta -> lowestPitch = (lowestPitch + delta).coerceIn(0, 127 - rows) },
            onScrollTime = { ticks -> scrollTick = (scrollTick + ticks).coerceIn(0f, maxScroll) },
            // Zoom compounds in the state itself, never in `rows` or
            // `pageTicks`: those are worked out while composing, and a pinch
            // sends a dozen events before the next frame - so multiplying
            // them gives the same answer a dozen times and the roll moves one
            // row for a gesture that asked for ten.
            onZoom = { pitchScale, timeScale ->
                if (pitchScale != 1f) {
                    val was = if (zoomRows > 0f) zoomRows else defaultRows.toFloat()
                    val want = (was * pitchScale).coerceIn(MinRows.toFloat(), maxRows.toFloat())
                    // About the middle of what is on screen, so the row you
                    // were looking at stays put instead of sliding off.
                    val centre = lowestPitch + was.toInt() / 2
                    zoomRows = want
                    lowestPitch = (centre - want.toInt() / 2).coerceIn(0, 127 - want.toInt())
                }
                if (timeScale != 1f) {
                    val was = if (zoomTicks > 0f) zoomTicks else defaultTicks
                    val span = clipLen.toFloat().coerceAtLeast(PPQN.toFloat())
                    val centre = scrollTick + was / 2f
                    val want = (was * timeScale).coerceIn(PPQN.toFloat(), span)
                    zoomTicks = want
                    scrollTick = (centre - want / 2f).coerceIn(0f, (clipLen - want).coerceAtLeast(0f))
                }
            },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            onMove = { indices, dTick, dPitch ->
                editor.updateGestureClip(sceneId) { base ->
                    base.copy(notes = base.notes.mapIndexed { i, n ->
                        if (i in indices) n.copy(
                            tick = (n.tick + dTick).coerceIn(0, clipLen - 1),
                            pitch = (n.pitch + dPitch).coerceIn(0, 127),
                        ) else n
                    })
                }
            },
            onResize = { index, newLength ->
                editor.updateGestureClip(sceneId) { base ->
                    base.copy(notes = base.notes.mapIndexed { i, n -> if (i == index) n.copy(length = newLength) else n })
                }
            },
            onDraw = { tick, pitch, length ->
                editor.updateGestureClip(sceneId) { base -> base.copy(notes = base.notes + Note(tick, length, pitch, 100)) }
            },
            onGestureEnd = { editor.endGesture() },
            // The slot the rows are shared out over - see RowTarget. Measured
            // here rather than inside the roll because this is where the count
            // is worked out, and the roll is handed the answer.
            modifier = Modifier.fillMaxWidth().weight(1f)
                .onSizeChanged { rollPx = it.height },
        )
        }
        val noteLaneSlot: @Composable (Dp) -> Unit = { open ->
        NoteLane(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            firstTick = firstTick,
            visibleTicks = pageTicks.toInt(),
            prop = noteProp,
            onProp = { noteProp = it },
            // The drum grid gives every hit a whole cell; the roll draws the
            // note's own length. The lane centres its columns on whichever is
            // above it.
            cellWide = steps && kind == MachineKind.Drums,
            pitchFilter = notePitch,
            onPitchFilter = { notePitch = it },
            // A drum voice by its short name, anything else by its note name
            // spelled the way the roll's own gutter spells it.
            pitchName = { p ->
                voices.firstOrNull { it.note == p }?.short
                    ?: noteName(p, Scales.spellingFor(track))
            },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            // Absolute, not relative: the gesture is applied to the base the
            // editor captured, so a sweep that passes back over a note settles
            // on the last value rather than accumulating.
            onSet = { values ->
                editor.updateGestureClip(sceneId) { base ->
                    val notes = base.notes.toMutableList()
                    for ((i, v) in values) {
                        val n = notes.getOrNull(i) ?: continue
                        notes[i] = when (noteProp) {
                            NoteProp.Velocity -> n.copy(velocity = (v * 127f).roundToInt().coerceIn(1, 127))
                            NoteProp.Chance -> n.copy(chance = (v * 100f).roundToInt().coerceIn(0, 100))
                            NoteProp.Ratchet -> n.copy(ratchet = (v * 8f).roundToInt().coerceIn(1, 8))
                            NoteProp.Nudge ->
                                n.copy(nudge = ((v - 0.5f) * 2f * NUDGE_RANGE).roundToInt()
                                    .coerceIn(-NUDGE_RANGE, NUDGE_RANGE))
                            // The same y as everything else in the lane, so
                            // the bottom is `Always` and the top is the last
                            // of the Nth family. It used to step the list by
                            // one per eighteen pixels of drag and wrap round
                            // at the end, which Dan found unreadable: there
                            // was no way to tell where in the list you were,
                            // and dragging far enough took you back past
                            // where you started.
                            NoteProp.Cond -> {
                                val all = com.rm.acidulous.model.Trig.inOrder
                                val at = (v * (all.size - 1)).roundToInt().coerceIn(0, all.size - 1)
                                n.copy(trig = all[at])
                            }
                        }
                    }
                    base.copy(notes = notes)
                }
            },
            onGestureEnd = { editor.endGesture() },
            collapsed = noteFolded,
            onToggleCollapse = {
                if (landscape) UiPrefs.foldNoteLaneLand(!noteFolded) else UiPrefs.foldNoteLane(!noteFolded)
            },
            // The same height as the automation strip below it. It was 72 on
            // the theory that a bar needs less room than a curve, which is
            // true of the drawing and not of the reading: two lanes of the
            // same kind at two heights look like a mistake, and a bar is also
            // how far a finger has to travel to set a value.
            modifier = Modifier.fillMaxWidth().height(if (noteFolded) 24.dp else open).padding(top = 4.dp),
        )
        }

        val automationSlot: @Composable (Dp) -> Unit = { open ->
        // Automation: the parameter strip under the notes.
        AutomationStrip(
            clip = clip,
            ticksPerBar = ticksPerBar,
            playheadTick = playhead,
            firstTick = firstTick,
            visibleTicks = pageTicks.toInt(),
            laneKeys = laneKeys,
            nameOf = { com.rm.acidulous.model.laneLabel(track, it) },
            shortOf = { com.rm.acidulous.model.laneShortLabel(track, it) },
            selected = laneKey,
            onSelect = { laneKey = it },
            onGestureBegin = { editor.beginGesture(trackIndex) },
            onDraw = { key, points ->
                editor.updateGestureClip(sceneId) { base ->
                    var lane = base.automation[key] ?: com.rm.acidulous.model.Lane()
                    for ((t, v) in points) lane = lane.withPoint(t, v)
                    base.copy(automation = base.automation + (key to lane))
                }
            },
            onGestureEnd = { editor.endGesture() },
            onClear = { key -> editor.editClip(trackIndex, sceneId) { c -> c.copy(automation = c.automation - key) } },
            collapsed = autoFolded,
            onToggleCollapse = {
                if (landscape) UiPrefs.foldAutomationLand(!autoFolded) else UiPrefs.foldAutomation(!autoFolded)
            },
            modifier = Modifier.fillMaxWidth().height(if (autoFolded) 24.dp else open).padding(top = 4.dp),
        )
        }
        // [bar] and [body] are the machine panel's two halves. Upright both
        // are drawn together; sideways the header stands down the left edge
        // and the cards on the right, with the roll between them, so each is
        // placed separately. The fx slots and the mixer have headers of their
        // own and belong wholly to the body.
        val panelSlot: @Composable (bar: Boolean, body: Boolean) -> Unit = { bar, body ->
        // The machine's face: knobs go to the engine as gestures and into the document as undo steps.
        // Or, behind the fx toggle, the track's two insert slots.
        if (panel == 1) { if (body) SlotsPanel(SlotKind.Effects, track, trackIndex, editor, Modifier.fillMaxWidth().padding(top = 4.dp)) }
        else if (panel == 2) { if (body) MixerPanel(song, editor, rackPeaks, masterPeak, clickOn, onClick, Modifier.fillMaxWidth().padding(top = 4.dp)) }
        else MachinePanel(
            track, trackIndex, editor, patchNames,
            // A patch knows the notes it is for. Loading one puts the
            // keyboard's bottom C at or under the bottom of its range and
            // scrolls the roll to match, so the first key pressed is a note
            // the instrument has; saving one keeps where the keyboard was.
            onSavePatch = { name -> onSavePatch(name, 12 * (octave + 1), 12 * (octave + 3)) },
            onLoadPatch = { name ->
                onLoadPatch(name)?.also { p ->
                    if (p.low >= 0) {
                        octave = (p.low / 12 - 1).coerceIn(0, 8)
                        lowestPitch = (p.low - 2).coerceIn(0, 127 - rows)
                    }
                }
            },
            factoryPatchNames = factoryPatchNames, userPatchNames = userPatchNames, onDeletePatch = onDeletePatch,
            onImportSoundFont = { onImportSoundFont(trackIndex) },
            onPickPreset = { onPickPreset(trackIndex) },
            onImportZoneSamples = { onImportZoneSamples(trackIndex) },
            selectedPad = selectedPad,
            onImportSample = { pad -> onImportSample(trackIndex, pad) },
            onImportKit = { pad -> onImportKit(trackIndex, pad) },
            onImportSlice = { onImportSlice(trackIndex) },
            onOpenSample = onOpenSample,
            onImportOneSample = { onImportOneSample(trackIndex) },
            onClearSample = { pad -> editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), null) } },
            // The whole kit in one edit, so emptying it is one undo rather
            // than fifteen - and so a half-cleared kit cannot be autosaved.
            // Only the settings here; the pads' trim is parameters, which the
            // panel puts back itself.
            onClearKit = {
                editor.edit(trackIndex) { t ->
                    var out = t
                    for (pad in 0 until 13) out = out.withSetting("p%02d_sample".format(pad), null)
                    out.withSetting("slice_sample", null).withSetting("slice_count", null)
                }
            },
            onAssignSample = { pad, rel ->
                editor.edit(trackIndex) { t -> t.withSetting("p%02d_sample".format(pad), rel) }
            },
            onOpenPatch = onOpenPatch,
            bar = bar, body = body, vertical = landscape,
            modifier = Modifier.fillMaxWidth()
                .then(if (landscape) Modifier.fillMaxHeight() else Modifier.padding(top = 4.dp)),
        )
        }
        // The performance controls live with the keys now: a mod wheel where
        // a mod wheel goes, a bend wheel on the other side, and pressure,
        // the scale and the octave on one strip above them.
        var mod by rememberSaveable(trackIndex) { mutableStateOf(0f) }
        var pressure by remember(trackIndex) { mutableStateOf(0f) }
        var bend by remember(trackIndex) { mutableStateOf(0.5f) }
        val touchable = MachineUi.usesPerformance(track.machine.type)
        // Not a gesture: opening a track must not write a lane point.
        LaunchedEffect(trackIndex) { NativeEngine.controlChange(trackIndex, 1, (mod * 127f).toInt(), record = false) }

        // **[grip] is the drag that moves the divider above the keys, and it
        // is deliberately not a divider.** It was an eighteen dp strip with a
        // short line drawn in the middle of it, which is a row of chrome
        // spent on saying "you may drag here" on the screen with the least
        // height to spare. Dan: "have no visible line and just allow the user
        // to grab a blank space in that row". So the row that is already
        // there carries it - the wheel, the three chips and the octave
        // stepper leave two wide gaps, and the gaps are the handle. Empty
        // sideways, where there is no divider to move.
        val keysSlot: @Composable (Dp, Modifier) -> Unit = { height, grip ->
        val keysFolded = UiPrefs.keysFolded
        if (kind == MachineKind.Drums) Column(Modifier.fillMaxWidth().height(height)) {
            // The pads have no row of their own to hide a handle in, so the
            // gap above them is it: the same gap that was always there, wide
            // enough to catch a finger and still drawing nothing.
            //
            // **Its own box, not padding on the pads.** As padding it was the
            // right pixels and the wrong node - a `pointerInput` after a
            // `padding` covers what is left *inside* it, so the drag was on
            // the pads, which consume their own touches, and the gap above
            // them did nothing at all.
            //
            // It carries the fold as well, at the right-hand end, which is
            // where the keyboard's is in the row the keyboard has.
            Box(
                Modifier.fillMaxWidth().height(PadsGrip).then(grip),
                contentAlignment = Alignment.CenterEnd,
            ) { FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) } }
            if (!keysFolded) DrumPads(
                trackIndex, voices, selectedPad, { selectedPad = it },
                Modifier.fillMaxWidth().weight(1f),
                track.machine.type,
                onEmpty = { pad -> if (MachineUi.acceptsSamples(track.machine.type)) onImportSample(trackIndex, pad) },
            )
        }
        else Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            // **The whole row is the handle, not only the blank either end of
            // it.** Sideways there is width going spare beside the wheel and
            // the stepper and the blank is enough; upright there is none - the
            // three chips, the stepper's stated width and the fold mark use all
            // of it, and the wheel fills what is left. So the drag is watched
            // underneath the row, and what is *in* the row wins where it is:
            // the wheel and the stepper consume at the slop and cancel this,
            // and a chip's tap detector never sees a drag at all. See keysGrip.
            BoxWithConstraints(Modifier.fillMaxWidth().height(26.dp).then(grip)) {
            // **The words go when the controls either side start starving.**
            //
            // Three chips at their written widths - 52, 112 and 52 - plus the
            // fold mark and the gaps come to 258 dp, and everything left over
            // is shared between the pressure wheel and the octave stepper.
            // Upright at 1.0 that leaves them about 59 dp each, which is
            // already the least either is worth having; at any interface scale
            // above it the row reports fewer dp, the chips keep their stated
            // widths, and the two of them are squeezed to a sliver - Dan,
            // looking at 1.3: "the chord/scale/arp labels are squeezing
            // everything out, the octave buttons are cut off and there is no
            // space for a pressure slider on the left".
            //
            // So the chips give their words up first, the way the footer's
            // pills do, and an anchor's width each is enough for a glyph. That
            // hands about a hundred and twenty dp back to the two controls
            // that had nothing.
            val icons = maxWidth < PerfWideMin
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Equal weights either side so the scale sits on the row's
                // centre line, wherever the stepper's own width lands. The
                // chip is measured first, as the one unweighted child, so it
                // takes only what its text needs.
                // **Weighted for its place, capped for its size.** The
                // weight is what centres the scale chip on the row; it is not
                // a statement that the wheel should be as wide as the row
                // will let it. Upright the share comes to about eighty dp and
                // the two are indistinguishable, so nobody noticed until the
                // phone turned and the same weight made it four hundred dp of
                // empty ridges - Dan: "cluttered". The cap is what portrait
                // was showing all along.
                Box(
                    Modifier.weight(1f).fillMaxHeight().then(grip),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (touchable) TouchWheel(
                        value = pressure, accent = Acid.colors.pink, vertical = false,
                        // No label: the ridges say it is a wheel, its place in
                        // the row says which one, and three letters of it were
                        // the only text in the strip.
                        springBackTo = 0f, label = null,
                        modifier = Modifier.widthIn(max = PressureW).fillMaxHeight(),
                    ) { v -> pressure = v; NativeEngine.channelPressure(trackIndex, (v * 127f).toInt()) }
                }
                // The eventors sit either side of the scale chip because
                // they do the same job: they are what happens to a note
                // between playing it and hearing it. A tap says whether one
                // is running; holding opens it. They used to be a button at
                // the foot of the screen that swapped the whole lower pane,
                // which is a long way to go to find out if the arp is on.
                EventorChip(
                    "Chord", EV_CHORD, track, trackIndex, editor,
                    icon = if (icons) "\u2261" else null,
                ) { eventorSlot = EV_CHORD }
                ScaleChip(
                    label = Scales.labelFor(track),
                    onToggle = { applyScale(currentScale().let { it.copy(on = !it.on) }) },
                    onOpen = { scaleDialog = true },
                    vertical = false,
                    // A fixed width, not a minimum: the label goes from
                    // "scale" to "C Ionian (Major)" and back, and a chip
                    // that grew by half its width each time would shove the
                    // two eventor chips sideways every time it was touched.
                    modifier = Modifier.width(if (icons) PerfIconW else 112.dp).fillMaxHeight(),
                    icon = if (icons) "\u266F" else null,
                )
                EventorChip(
                    "Arp", EV_ARP, track, trackIndex, editor,
                    icon = if (icons) "\u266B" else null,
                ) { eventorSlot = EV_ARP }
                // **Stated, not shared.** The wheel at the other end is the
                // elastic one: it has a cap and reads as a wheel at any width
                // over a finger, where the stepper is three things in a row
                // that each have a size. See OctaveW. It costs the scale chip
                // its place on the exact centre line, which this row already
                // decided is the cheaper of the two prices.
                Box(
                    Modifier.width(OctaveW).fillMaxHeight().then(grip),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    OctaveStepper(octave, { octave = it }, Modifier.fillMaxHeight())
                }
                // Past the octave, at the end of the row: the last thing in a
                // row of performance controls is the one that puts the
                // instrument away.
                //
                // **Its own slot, not a passenger in the octave's.** Inside
                // that weighted box it had no width of its own, and upright
                // there is none going spare - a chip, a hundred and twelve dp
                // of scale, a chip and the stepper come to within twenty dp
                // of the row - so it overflowed and drew itself on top of the
                // screen's own margin.
                //
                // It is not balanced by a spacer at the head of the row, and
                // the scale chip is therefore about eleven dp left of the
                // true centre line. That is the cheaper of two prices: the
                // spacer put it back on the line and took the last eight dp
                // the octave stepper had, which cost it the `▶` it steps up
                // with. A chip a few dp off centre is a thing nobody can see;
                // a stepper missing an arrow is a control that does not work.
                FoldMark(keysFolded, Modifier.fillMaxHeight()) { UiPrefs.foldKeys(!keysFolded) }
            }
            }
            if (keysFolded) return@Column
            Row(
                // Thirty is the performance row above these; a `Dp` handed to
                // `height` may not be negative, and this is a share of a pane
                // that can be narrow enough to owe it - a turned phone at the
                // largest interface scale, or a split-screen window.
                Modifier.fillMaxWidth().height((height - 30.dp).coerceAtLeast(0.dp)).padding(top = 4.dp),
                // No spacing: the keyboard draws its own three dp either side
                // and owns them for touch, so a finger that misses the
                // outermost key by a hair still plays it instead of grabbing
                // the wheel beyond it. See EdgeGrab in ui/PianoKeys.kt.
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TouchWheel(
                    value = mod, accent = Acid.colors.accent, vertical = true, label = null,
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) { v -> mod = v; NativeEngine.controlChange(trackIndex, 1, (v * 127f).toInt()) }
                PianoKeys(
                    trackIndex, Scales.activeFor(track), Scales.rootFor(track), octave,
                    Modifier.weight(1f).fillMaxHeight(),
                    noteSpelling = Scales.spellingFor(track),
                )
                // Bend springs back, so it is the one wheel you can let go of
                // in a hurry and know where it landed.
                TouchWheel(
                    value = bend, accent = Acid.colors.teal, vertical = true,
                    springBackTo = 0.5f, centreMark = true, label = null,
                    modifier = Modifier.width(26.dp).fillMaxHeight(),
                ) { v ->
                    bend = v
                    val value14 = ((v * 2f - 1f) * 8192f + 8192f).toInt().coerceIn(0, 16383)
                    NativeEngine.midiEvent(trackIndex, 0xE0, value14 and 0x7f, (value14 shr 7) and 0x7f)
                }
            }
        }
        }
        val pad = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
        if (landscape) {
            // **Sideways everything that is a row upright becomes a column,
            // and the keyboard takes the whole bottom.**
            //
            // The instrument is the widest thing there is and a phone turned
            // sideways is mostly width, so the keys get all of it - two
            // octaves instead of one and a half, which `PianoKeys` picks from
            // its own measured width without being told. Above them four
            // columns: the machine's header against the left edge, the roll
            // and its lanes in the middle, the machine's cards, and the
            // transport against the right edge.
            //
            // Each of the two edge columns folds towards the edge it is
            // against, which is the one direction that reads as putting it
            // away rather than as hiding it somewhere.
            val panelFolded = UiPrefs.panelFolded
            // **How the height is divided is the thing that was wrong.**
            //
            // Every piece kept the dp it takes upright, into a window that is
            // forty-six per cent as tall: two lanes at eighty-eight left the
            // roll forty-six, and the keyboard - the thing Dan asked to have
            // "the entire bottom" - came out fifty-nine against portrait's
            // seventy-two. So nothing here states a height in dp any more.
            // The keyboard takes a share of the window, which the divider
            // under the roll drags and `UiPrefs` remembers; the lanes take a
            // share of what that leaves; the roll takes the rest.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val total = maxHeight
                // Live while the finger is down, committed to the store when
                // it lifts: a preference written per frame is a file written
                // sixty times a second.
                var frac by remember { mutableFloatStateOf(UiPrefs.keysFractionLand) }
                // Folded, it is its own control row and the share does not
                // apply - and there is nothing to drag, so the grip goes too.
                val keysFolded = UiPrefs.keysFolded
                val foldedH = if (kind == MachineKind.Drums) PADS_FOLDED_H else KEYS_FOLDED_H
                val keysH = if (keysFolded) foldedH else total * frac
                // A lane open sideways gets a quarter of what is left above
                // the keyboard rather than a stated eighty-eight, with a
                // floor at the height a finger needs to set a value in.
                val laneH = ((total - keysH) * 0.26f).coerceAtLeast(LaneMinH)
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f).then(pad),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Only a machine has a patch header; the fx slots and
                        // the mixer carry their own and are all body.
                        //
                        // It is never folded itself - it is already as thin
                        // as the marks in it - and it carries the mark that
                        // folds the cards away and brings them back.
                        if (panel == 0) Column(
                            Modifier.width(PATCH_W).fillMaxHeight(),
                        ) { panelSlot(true, false) }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            gridSlot()
                            noteLaneSlot(laneH)
                            automationSlot(laneH)
                        }
                        // The cards own their own scrolling now - see
                        // GroupRow - so there is no second scroller here.
                        // Folded, they are gone and the roll has the width;
                        // the strip on the left still has the mark.
                        if (!panelFolded || panel != 0) Column(
                            Modifier.width(CONTROL_W).fillMaxHeight(),
                        ) { panelSlot(false, true) }
                    }
                    keysSlot(
                        keysH,
                        if (keysFolded) Modifier else keysGrip(
                            onDrag = { dy -> frac = ((keysH - dy) / total).coerceIn(KeysFractionMin, KeysFractionMax) },
                            onEnd = { UiPrefs.chooseKeysFraction(frac) },
                        ),
                    )
                }
            }
        } else {
            // The bar is outside the padding, so it reaches the edges of
            // the screen as the arranger's does; everything above it keeps
            // its margins.
            Column(Modifier.fillMaxWidth().weight(1f).then(pad)) {
                gridSlot()
                noteLaneSlot(LaneH)
                automationSlot(LaneH)
                panelSlot(true, true)
                // **Upright the instrument can be dragged taller too**, by the
                // same handle the turned editor uses - the blank either side of
                // the performance row, and the gap above the drum pads. What it
                // takes, it takes from the roll, so the drag stops where the
                // roll reaches its own floor: the only thing below the keyboard
                // is the footer, and the only thing above it that can give is
                // the grid.
                //
                // A multiplier on the stated height rather than a share of the
                // window, because upright the keyboard is one of several stated
                // heights in a column rather than one of two panes. See
                // UiPrefs.keysStretch.
                //
                // **The roll has first claim on the height, so the instrument
                // is the piece that does not grow.**
                //
                // That is `PADS_H_LAND`'s rule - "the whole screen is about
                // 360dp tall here and the grid has the first claim on it" -
                // applied to the one screen where it now bites upright too. At
                // a larger interface setting every stated `dp` above the
                // keyboard grows and the roll is the `weight(1f)` that pays for
                // all of it. Dividing by the scale here leaves the keyboard the
                // physical size it has always been and hands the roll every dp
                // the setting would have spent on it - which is what the roll
                // then turns into taller rows rather than more of them.
                //
                // Nothing to prove at 1.0: the divisor is one.
                val keysBase = (if (kind == MachineKind.Drums) PADS_H else KEYS_H) / scale
                val keysH = keysBase * keysStretch
                keysSlot(
                    if (UiPrefs.keysFolded) {
                        if (kind == MachineKind.Drums) PADS_FOLDED_H else KEYS_FOLDED_H
                    } else {
                        keysH
                    },
                    if (UiPrefs.keysFolded) Modifier else keysGrip(
                        onDrag = { dy ->
                            // **From the state, not from the composed height.**
                            // A drag sends a dozen events before the next frame
                            // and `keysH` is worked out while composing, so
                            // subtracting from it gives the same answer a dozen
                            // times and the edge moves once - which is M44's
                            // rule about zoom, in a different control.
                            val live = keysBase * keysStretch
                            // What the roll can spare, and not a dp more.
                            val spare = (rollDp.dp - RollMinH).coerceAtLeast(0.dp)
                            val want = (live - dy).coerceAtMost(live + spare)
                            keysStretch = (want / keysBase).coerceIn(KeysStretchMin, KeysStretchMax)
                        },
                        onEnd = { UiPrefs.chooseKeysStretch(keysStretch) },
                    ),
                )
            }
            footerSlot()
        }
    }

    if (eventorSlot >= 0) {
        SlotDialog(
            SlotKind.Eventors, track, trackIndex, eventorSlot, editor,
            fixedType = if (eventorSlot == EV_CHORD) "Chord" else "Arp",
        ) { eventorSlot = -1 }
    }

    if (scaleDialog) {
        ScaleDialog(
            current = currentScale(),
            onDismiss = { scaleDialog = false },
            onApply = { applyScale(it); scaleDialog = false },
        )
    }
}

/**
 * The mark that folds the instrument away, and brings it back.
 *
 * The note lane's and the automation strip's, to the letter: an eleven sp
 * chevron pointing the way the thing will go, `▾` to put it away and `▴` to
 * bring it back, in textMid, in a box you can hit. It points down rather than
 * sideways because the keyboard is against the bottom edge and that is the
 * direction it leaves in - the patch column's `◂` says the same thing about
 * the left edge.
 */
/** Narrow, because upright the row it ends has about twenty dp to spare. */
/**
 * The least roll there is any point leaving.
 *
 * What stops the keyboard's drag: below about this the grid is three rows and
 * a ruler, which is not an editor, and somebody who wants the instrument
 * bigger than that wants the roll folded rather than starved.
 */
private val RollMinH = 96.dp

private val FoldMarkW = 22.dp

/**
 * What a chip in the performance row costs once it is a glyph.
 *
 * An anchor's width, which is the footer's number and the same reason for it:
 * what sets the floor is a finger, not the text.
 */
private val PerfIconW = 44.dp

/**
 * The narrowest row that can still spell "chord", "scale" and "arp" out.
 *
 * The three chips at 52, 112 and 52, the fold mark, the five gaps, the octave
 * stepper's stated width, and enough left for the pressure wheel to still read
 * as one. A 393 dp phone clears it by a single dp at 1.0 and misses it at every
 * setting above, which is the honest shape of a row that was always full: under
 * this the words go and the glyphs come, and that hands about a hundred and
 * twenty dp back to the two controls at the ends that had been starving.
 */
private val PerfWideMin = 52.dp + 112.dp + 52.dp + FoldMarkW + 20.dp + OctaveW + 32.dp

@Composable
private fun FoldMark(folded: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.width(FoldMarkW).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (folded) "\u25B4" else "\u25BE", color = Acid.colors.textMid, fontSize = 11.sp)
    }
}

/**
 * The drag that moves the edge between the roll and the keyboard, sideways.
 *
 * A Modifier rather than a composable, because there is nothing to draw: it
 * is put on the blank halves of the row the performance chips already stand
 * in, and on the gap above the drum pads. How much of a short screen belongs
 * to the notes and how much to the instrument is a thing you feel while
 * playing, not a constant - but the handle that says so does not need a row.
 *
 * **[onDrag] has to be re-read, not captured.** `pointerInput(Unit)` builds
 * its block once and keeps the values it was built with, and the value this
 * one needs - where the edge is *now* - changes on every frame of the drag.
 * Captured, the first move would be computed from the first position for the
 * whole gesture. `rememberUpdatedState` is the fix, as it was for the note
 * lane's pitch filter.
 */
@Composable
private fun keysGrip(onDrag: (Dp) -> Unit, onEnd: () -> Unit): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    return Modifier.pointerInput(Unit) {
        // The wheel and the stepper are inside this and get the touch first;
        // both consume at the slop, which cancels this one. So a finger on
        // the wheel rolls the wheel and a finger on the blank beside it moves
        // the edge, without either having to know about the other.
        detectDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { change, amount ->
            change.consume()
            drag(with(density) { amount.y.toDp() })
        }
    }
}

/**
 * The blank above the drum pads, which is their handle.
 *
 * Six dp was the gap before it had a job; eighteen is enough of one to catch
 * without it reading as a band, and the pads give it up rather than the roll.
 * The keyboard needs no equivalent: it has the performance row above it, and
 * the two wide gaps in that row are the handle there.
 */
private val PadsGrip = 18.dp

/**
 * What is left of the bottom when the instrument is folded away.
 *
 * Its own control row and nothing else: for the keyboard the performance
 * strip - the wheel, the eventors, the scale, the octave and the mark that
 * brings it back - which is six dp of top padding and a twenty-six dp row;
 * for the pads the strip above them, which is all they have. The roll takes
 * everything else, which is the point of folding it.
 */
private val KEYS_FOLDED_H = 32.dp
private val PADS_FOLDED_H = PadsGrip

/**
 * The shortest a lane may be drawn at when it is open.
 *
 * A lane is a thing you set a value in by dragging up and down inside it, so
 * under this it stops being a control and becomes a readout. Upright the
 * lanes are eighty-eight and never reach this; sideways they take a share of
 * what the keyboard leaves and can.
 */
private val LaneMinH = 56.dp

/** What a lane is upright, where there is height to spare for both. */
private val LaneH = 88.dp

/**
 * The widest the pressure wheel is drawn, whichever way the phone is held.
 *
 * What a weighted share comes to upright, stated so that it comes to the same
 * thing sideways. A wheel is a thing you roll with one thumb; past about this
 * the extra width is not reachable without moving your hand and so is not
 * part of the control.
 */
private val PressureW = 88.dp

/**
 * How many rows the roll shows at the interface's own size.
 *
 * Sixteen rather than two full octaves: tall enough that every row can carry
 * its name and be hit with a finger, which matters more on a phone than seeing
 * the whole range at once. The arrows move the window. Fewer sideways, because
 * a row shorter than about 11dp loses its name and a nameless roll is worth
 * less than a shorter one.
 *
 * **A base now rather than the answer.** The chrome around the roll is stated
 * in `dp` and grows with the interface scale; the roll is the `weight(1f)` that
 * pays for it, so sixteen rows of a shorter slot would have been sixteen
 * *smaller* rows at every setting above 1.0 - the one surface the app is for,
 * made worse by the setting meant to help. `rowsForSlot` in ui/UiScale.kt keeps
 * the share of the roll these two describe, which is exactly these numbers at
 * 1.0 and fewer, taller rows above it.
 */
private const val ROWS = 16
private const val ROWS_LAND = 12
private const val PAGE_BARS = 2

// What a pinch may do to the roll. Six rows is half an octave, which is as
// close as there is any point going; thirty-six is three octaves, at which a
// row is about four dp on a phone and a note is a line rather than a block.
private const val MinRows = 6
private const val MaxRows = 36

// Sideways the roll is about twice as wide, so it shows twice as much clip,
// and the pieces that share the screen with it move into a column of their
// own. The keyboard loses a little height there: it takes whatever the row
// gives it and scales, so the roll gets the difference.
private const val PAGE_BARS_LAND = 4
/**
 * How wide the machine's cards stand, sideways.
 *
 * Three hundred while the cards were a row turned on its side, then a
 * hundred and fifty-six, then a hundred and four as the knob itself was
 * turned and then flattened - each step narrower and each step further from
 * what the panel looks like upright, which is what Dan eventually called: the
 * portrait elements had not been faithfully ported.
 *
 * So the knob is portrait's knob again and this is what two of them cost:
 * fifty-eight apiece and six between them, twelve of `Group`'s padding,
 * twelve of the panel's own, twenty-two for the turned section tabs down the
 * left of the cards with four beside them, and the rest for the scrollbar and
 * slack. Eight dp short of this and the second knob wrapped to its own line,
 * which looks like the layout ignoring what it was told.
 */
private val CONTROL_W = 180.dp

/**
 * The left edge column, sideways.
 *
 * **Exactly a turned `BarIcon` plus the panel's own padding**, which is the
 * same forty dp the bar is *tall* upright - so the strip the machine's name
 * and patch live in is the same thickness whichever way the phone is held.
 * It was fifty-four; Dan, looking at it: "it's too fat when opened and it
 * should be slim like it is in portrait mode". The fold is still in it - it
 * is the cards to the right of the roll that it puts away, not this strip,
 * which is why the strip has no folded width of its own.
 *
 * The transport had a pair of these while it was a column against the right
 * edge and has neither now: it stands in the header, where the width was
 * already being paid for.
 */
private val PATCH_W = 40.dp
private val KEYS_H = 104.dp
// The pads used to get 72, which after padding is two rows of 28.5dp - forty
// per cent under the smallest thing a finger is meant to hit, and a third less
// than the keyboard gets *before* the keyboard spends thirty of its own on a
// control strip the pads do not have. A playing surface is not chrome; it is
// the counterpart of the keyboard and is sized like one.
private val PADS_H = 132.dp
private val KEYS_H_LAND = 92.dp
// Landscape rows come out at 44.5dp, knowingly just under: the whole screen is
// about 360dp tall here and the grid has the first claim on it.
private val PADS_H_LAND = 104.dp

@Composable
private fun KeyboardKey(note: Int, rack: Int, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) Acid.colors.teal else Acid.colors.knobPointer)
            .pointerInput(note, rack) {
                var down = false
                try {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            val now = currentEvent.changes.any { it.pressed }
                            if (now != down) {
                                down = now
                                pressed = now
                                if (now) NativeEngine.noteOn(rack, note, 100) else NativeEngine.noteOff(rack, note)
                            }
                        }
                    }
                } finally {
                    // A cancelled gesture never reports the finger lifting.
                    if (down) NativeEngine.noteOff(rack, note)
                    pressed = false
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(note.toString(), color = Acid.colors.onAccent, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/**
 * One eventor, as a chip that knows what it is.
 *
 * There are three eventors and three controls, so nothing is ever chosen
 * from a list: chord on the left, scale in the middle, arp on the right,
 * each pinned to its own slot. A tap switches it on or off, a long press
 * opens it - the grammar the scale chip between them already uses.
 *
 * Switching is a *parameter*: bypass is a pseudo-parameter on the eventor
 * unit, so a tap saves, undoes and automates like anything else, and the
 * flag goes straight to the running eventor as well as into the document.
 * Note the inversion - the field is `bypass`, so lit means `bypass == false`.
 * An empty slot is filled on the first tap rather than asking.
 */
@Composable
private fun EventorChip(
    type: String,
    slot: Int,
    track: Track,
    trackIndex: Int,
    editor: SongEditor,
    /** A glyph in place of the word, when the row cannot afford the word. */
    icon: String? = null,
    onOpen: () -> Unit,
) {
    val ev = track.eventorAt(slot)
    val loaded = ev.type == type
    SlotChip(
        text = icon ?: type.lowercase(),
        on = loaded && !ev.bypass,
        onToggle = {
            if (!loaded) {
                // Nothing there yet: the first tap is what puts it there,
                // switched on, because that is plainly what was meant.
                editor.edit(trackIndex) { t -> t.withEventor(slot, type).withEventorBypass(slot, false) }
                NativeEngine.setParam(trackIndex, eventorUnit(slot), "bypass", 0f, record = true)
            } else {
                val bypass = !ev.bypass
                editor.edit(trackIndex) { t -> t.withEventorBypass(slot, bypass) }
                NativeEngine.setParam(trackIndex, eventorUnit(slot), "bypass", if (bypass) 1f else 0f, record = true)
            }
        },
        onOpen = {
            // The selector needs something to select on, so hold fills an
            // empty slot too - bypassed, because holding is "let me look",
            // not "turn it on".
            if (!loaded) {
                editor.edit(trackIndex) { t -> t.withEventor(slot, type).withEventorBypass(slot, true) }
            }
            onOpen()
        },
        vertical = false,
        icon = icon != null,
        modifier = (if (icon != null) Modifier.width(PerfIconW) else Modifier.widthIn(min = 52.dp))
            .fillMaxHeight(),
    )
}
