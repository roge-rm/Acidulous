package com.rm.acidulous.ui

import androidx.compose.foundation.layout.fillMaxWidth
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.model.Mapping
import com.rm.acidulous.model.Mappings
import com.rm.acidulous.model.Song
import com.rm.acidulous.ui.theme.Acid
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import com.rm.acidulous.R

/**
 * Everything MIDI, in four tabs.
 *
 * It began as one page listing whatever was plugged in, and grew a direction
 * at a time until it was a scroll nobody could find anything in. The four
 * tabs are the four questions actually being asked: what is connected, what
 * comes in, what goes out, and who is keeping time. They use the shared
 * window shell, so this looks like the machine picker and the settings
 * window rather than like a third thing.
 *
 * Every tab ends in numbers. The Bluetooth bug that cost an evening was
 * invisible because a wrong service UUID looks exactly like an empty room,
 * and neither direction of MIDI can be tested from here at all - so each
 * one says out loud what it thinks is happening.
 */
@Composable
fun MidiDialog(song: Song, onDismiss: () -> Unit) {
    val trackNames = song.tracks.map { it.name }
    // Read here, in the ordinary composition, and handed down.
    //
    // The pages are subcomposed inside a SubcomposeLayout's measure block so
    // the window can size itself to the tallest of them, and a value that
    // changes on its own - which member channels are holding a note - does
    // not reliably re-measure from in there. Read at this level it is a
    // plain state read, the window recomposes, and the page is rebuilt.
    val mpeHeld = MidiHub.mpeHeld
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { MidiHub.refresh() }

    TabbedDialog(
        title = stringResource(R.string.midi_title),
        selected = tab,
        onDismiss = { MidiHub.stopScan(); onDismiss() },
        spacing = 6.dp,
        chips = { SectionChips(stringArrayResource(R.array.midi_tabs).toList(), tab) { tab = it } },
        pages = listOf(
            { DevicesTab(context) },
            { InTab(trackNames, mpeHeld) },
            { ControlTab(context, song) },
        ),
    )
}

// Three, where there were five and most of them were a card or two. Dan: "if
// that many tabs are needed, most pages feel sparse". What is plugged in,
// both ways, with the output's timing beside the outputs it applies to; then
// the notes arriving; then the two things that steer the app from outside -
// someone else's clock, and the knobs mapped onto its controls.

/**
 * The tabs are the arp window's shape, like every window with settings in it
 * (Dan, 2026-09-23): titled cards, switches and knobs for what you set, and
 * what is plugged in or arriving as rows and readings inside the same cards.
 */
@Composable
private fun Line(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(Modifier.cardLine()) { content() }

/** What is plugged in, and the hunt for what is not. */
@Composable
private fun DevicesTab(context: android.content.Context) {
    val ports = MidiHub.ports
    val found = MidiHub.discovered
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) MidiHub.scanBluetooth(context)
    }

    WindowCards {
        if (!MidiHub.supported) {
            Text(stringResource(R.string.midi_unsupported), color = Acid.colors.red, fontSize = 12.sp)
        }
        WindowCard(stringResource(R.string.midi_inputs)) {
            ports.forEach { port ->
                DialogRow(
                    mark = if (port.bluetooth) "ᛒ" else "⎓",
                    name = port.name,
                    under = port.maker,
                    trailing = stringResource(if (port.open) R.string.midi_listening else R.string.midi_tap_to_open),
                    on = port.open,
                ) { MidiHub.toggle(port.id) }
            }
            if (ports.isEmpty()) Line { Readout(stringResource(R.string.midi_no_inputs)) }
            // Only with one plugged in: the app plays it, or it is itself.
            if (MidiHub.launchpadHere) {
                SwitchGrid(stringResource(R.string.midi_launchpad), stringArrayResource(R.array.midi_launchpad_choices).toList(), if (MidiHub.launchpadOn) 0 else 1) {
                    UiPrefs.chooseLaunchpad(it == 0)
                }
            }
            // Only with one plugged in: the song's key, lit on its pads.
            if (MidiHub.exquisHere) {
                SwitchGrid(stringResource(R.string.midi_exquis_pads), stringArrayResource(R.array.midi_exquis_pads_choices).toList(), if (MidiHub.padLights) 0 else 1) {
                    UiPrefs.choosePadLights(it == 0)
                }
            }
            // A cable appears by itself; a Bluetooth instrument has to be
            // looked for, which is the one thing on this tab you *do*.
            SwitchGrid(
                stringResource(if (MidiHub.bluetoothReady(context)) R.string.midi_bluetooth else R.string.midi_bluetooth_off),
                listOf(stringResource(if (MidiHub.scanning) R.string.midi_stop else R.string.midi_search)), if (MidiHub.scanning) 0 else -1,
            ) {
                if (MidiHub.scanning) {
                    MidiHub.stopScan()
                } else {
                    val missing = MidiHub.bluetoothPermissions().filter {
                        context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) MidiHub.scanBluetooth(context) else permission.launch(missing.toTypedArray())
                }
            }
            // Why the list looks the way it does. A scan that finds nothing
            // and says nothing is indistinguishable from one that is broken,
            // which is exactly how a wrong service UUID went unnoticed.
            if (MidiHub.scanStatus.isNotEmpty()) Line { Readout(MidiHub.scanStatus) }
            found.forEach { device ->
                // A device that actually advertised the MIDI service is worth
                // saying so about: in a widened scan everything else is a guess.
                DialogRow(
                    mark = if (device.midi) "ᛒ" else "·",
                    name = device.name,
                    under = if (device.midi) device.address else stringResource(R.string.midi_no_service, device.address),
                    trailing = stringResource(R.string.midi_connect),
                    monoUnder = true,
                ) { MidiHub.connectBluetooth(context, device.address) }
            }
        }
        // Each track chooses whether it sends, in the mixer; this is where to,
        // and how early.
        WindowCard(stringResource(R.string.midi_outputs)) {
            MidiHub.destinations.forEach { dest ->
                DialogRow(
                    mark = "→",
                    name = dest.name,
                    trailing = stringResource(if (dest.open) R.string.midi_sending else R.string.midi_tap_to_open),
                    on = dest.open,
                ) { MidiHub.toggleDestination(dest.id) }
            }
            if (MidiHub.destinations.isEmpty()) Line { Readout(stringResource(R.string.midi_no_outputs)) }
            // Raise it if the external part drags behind what you hear.
            CountKnob(
                stringResource(R.string.midi_send_ahead), MidiHub.outOffsetMs, -50..50, "%+d ms".format(MidiHub.outOffsetMs), PanelAmber,
                choices = (-50..50).map { "%+d ms".format(it) },
            ) { UiPrefs.chooseMidiOffset(it) }
            // The numbers to report when something sounds loose. "late" is
            // how far past its own timestamp a message was handed to the
            // framework: if that grows, the trim is not the problem.
            if (UiPrefs.showDiagnostics) Line {
                Readout(
                    "${MidiHub.produced} out · ${MidiHub.sent} sent · late %.1f ms".format(MidiHub.outLateMs) +
                        (if (MidiHub.anchored) " · timed to the audio" else " · no anchor yet"),
                    good = MidiHub.sent > 0 && MidiHub.anchored,
                )
            }
        }
    }
}

/** Notes arriving: where they land, and proof that they do. */
@Composable
private fun InTab(trackNames: List<String>, mpeHeld: Int) {
    // **The question, then its answer, then the protocol.** MPE is an *in*
    // concern and belongs on this tab; it just does not belong first.
    WindowCards {
        // Where they go, with the proof that they do in the same card.
        MidiRoutingSection(trackNames) {
            // A Launchpad's pads, played without one: a diagnostic, for the emulator.
            val tests = stringArrayResource(R.array.midi_test_notes).toList() +
                (if (UiPrefs.showDiagnostics) listOf(stringResource(R.string.midi_test_launchpad)) else emptyList())
            SwitchGrid(stringResource(R.string.midi_test), tests, -1, columns = 1) {
                when (it) { 0 -> MidiHub.testNote(); 1 -> MidiHub.testWheel(); else -> MidiHub.testLaunchpad() }
            }
            Line {
                Readout(
                    if (MidiHub.received == 0) stringResource(R.string.midi_nothing_received)
                    else pluralStringResource(R.plurals.midi_received, MidiHub.received, MidiHub.received, MidiHub.lastMessage),
                    good = MidiHub.received > 0,
                )
            }
        }
        MpeSection(mpeHeld)
    }
}

/**
 * What steers the app from outside: someone else's clock, and the knobs
 * mapped onto its controls.
 */
@Composable
private fun ControlTab(context: android.content.Context, song: Song) {
    val link = com.rm.acidulous.engine.LinkHub.enabled
    val follow = if (link) MidiHub.Follow.Off else MidiHub.follow
    fun choose(mode: MidiHub.Follow) {
        // One master at a time, on screen as well as in the engine.
        if (mode != MidiHub.Follow.Off && link) {
            UiPrefs.chooseLink(false)
            com.rm.acidulous.engine.LinkHub.setEnabled(context, false)
        }
        UiPrefs.chooseFollow(mode)
    }
    WindowCards {
        WindowCard(stringResource(R.string.midi_clock)) {
            // Sent to every open output, with start, stop and song position.
            SwitchGrid(stringResource(R.string.midi_clock_send), stringArrayResource(R.array.off_on).toList(), if (MidiHub.clockOut) 1 else 0) { UiPrefs.chooseClockOut(it == 1) }
            SwitchGrid(
                stringResource(R.string.midi_clock_follow), stringArrayResource(R.array.midi_clock_follow_choices).toList(),
                when (follow) { MidiHub.Follow.Off -> 0; MidiHub.Follow.Auto -> 1; MidiHub.Follow.On -> 2 },
                columns = 1,
            ) { choose(listOf(MidiHub.Follow.Off, MidiHub.Follow.Auto, MidiHub.Follow.On)[it]) }
            // Ten seconds of a perfect 120, from inside the app.
            SwitchGrid(stringResource(R.string.midi_test), listOf(stringResource(R.string.midi_test_clock)), -1, enabled = listOf(follow != MidiHub.Follow.Off)) { MidiHub.testClock() }
            // A tempo can look right while the phase wanders, so the second
            // number is the one that says whether this is really working: it
            // is the last pulse's distance from where the loop expected it.
            Line {
                Readout(
                    when {
                        link -> stringResource(R.string.midi_follow_link)
                        follow == MidiHub.Follow.Off -> stringResource(R.string.midi_follow_off)
                        !MidiHub.clockIn -> stringResource(R.string.midi_follow_nothing)
                        MidiHub.followBpm <= 0f -> stringResource(R.string.midi_follow_waiting)
                        else -> stringResource(
                            R.string.midi_following,
                            MidiHub.followBpm,
                            stringResource(if (MidiHub.followLocked) R.string.midi_follow_locked else R.string.midi_follow_settling),
                            MidiHub.followErrorMs,
                        )
                    },
                    good = MidiHub.followLocked,
                )
            }
        }
        MappingCard(song)
    }
}

/**
 * What the hardware has been pointed at.
 *
 * Mapping is learned by touching things, not by filling in a table, so this
 * is a receipt rather than an editor: it says what is bound, which notes are
 * no longer free to play, and offers the one destructive thing that has no
 * home on a knob - forget the lot.
 *
 * The song's mappings and the device's are listed apart because they behave
 * differently: the device's travel with the controller and the song's travel
 * with the music, and when both claim a controller the song wins.
 */
@Composable
private fun MappingCard(song: Song) {
    val device = UiPrefs.mappings
    val resources = androidx.compose.ui.platform.LocalResources.current
    val laneWord: (String) -> String = { resources.panelWord(it) }
    // What survives of the explanation is the half nothing on screen can
    // tell you: that the mode has a gesture of its own, and where - the
    // title says it.
    WindowCard(stringResource(R.string.midi_mapping)) {
        SwitchGrid(stringResource(R.string.midi_mapping_mode), stringArrayResource(R.array.off_on).toList(), if (UiPrefs.mapMode) 1 else 0) { UiPrefs.chooseMapMode(it == 1) }
        SwitchGrid(
            stringResource(R.string.midi_device_mappings), listOf(stringResource(R.string.midi_forget)), -1, enabled = listOf(device.isNotEmpty()),
        ) { UiPrefs.chooseMappings(emptyList()); UiPrefs.chooseMapWaiting(null) }
        // One list, each marked with whose it is: the device's travel with
        // the controller and the song's with the music, and when both claim
        // a controller the song wins.
        val songs = stringResource(R.string.midi_mapping_song)
        val devices = stringResource(R.string.midi_mapping_device)
        val all = song.mappings.map { it to songs } + device.map { it to devices }
        if (all.isEmpty()) Line { Readout(stringResource(R.string.midi_nothing_mapped)) }
        for ((m, whose) in all.sortedWith(compareBy({ it.first.cc ?: 1000 }, { it.first.note ?: 1000 }))) {
            val track = m.rack?.let { song.tracks.getOrNull(it) } ?: song.tracks.firstOrNull()
            Row(Modifier.cardLine(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.sourceLabel().padEnd(10),
                    color = Acid.colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text(
                    m.targetLabel(track, laneWord).let { if (m.rack != null) stringResource(R.string.midi_mapping_track, it, m.rack + 1) else it },
                    color = Acid.colors.text, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
                Text(whose, color = Acid.colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        val claimed = Mappings.claimedNotes(song, device)
        if (claimed.isNotEmpty()) {
            Line {
                Readout(
                    stringResource(
                        R.string.midi_claimed_notes,
                        claimed.joinToString(stringResource(R.string.list_separator)) { Mapping(note = it).sourceLabel().removePrefix("note ") },
                    ),
                )
            }
        }
    }
}

/**
 * MPE: one instrument played on many channels, a finger to each.
 *
 * A zone is a property of what is plugged in rather than of the music, so
 * it lives here with the routing and not in the song. While one is on, the
 * member channels are fingers and cannot also be tracks - routing still
 * chooses *which* track the zone plays, but by-channel has nothing left to
 * mean.
 */
@Composable
private fun MpeSection(mpeHeld: Int) {
    val zone = MidiHub.mpeZone
    val setting = MidiHub.mpeSetting
    val auto = setting == com.rm.acidulous.midi.MpeZone.AUTO
    // Auto first: it is the default and what almost everyone wants.
    val order = listOf(com.rm.acidulous.midi.MpeZone.AUTO, 0, 1, 2)
    WindowCard(stringResource(R.string.midi_mpe)) {
        // Lower: channel 1 is the zone and the ones above it are fingers;
        // upper: channel 16, and the ones below. Auto: whatever the
        // controller says, or does.
        SwitchGrid(stringResource(R.string.midi_mpe_zone), stringArrayResource(R.array.midi_mpe_zone_choices).toList(), order.indexOf(setting), columns = 2) {
            UiPrefs.chooseMpe(zone = order[it])
        }
        if (auto) {
            // What auto has found, and how - so a controller that is not
            // being recognised can be seen not to be.
            Line {
                Readout(
                    when {
                        zone == 0 -> stringResource(R.string.midi_mpe_auto_waiting)
                        else -> stringResource(
                            if (MidiHub.mpeHeard == MidiHub.MpeHeard.Config) R.string.midi_mpe_auto_config else R.string.midi_mpe_auto_fingers,
                            stringArrayResource(R.array.midi_mpe_zone_choices)[order.indexOf(zone)],
                            MidiHub.mpeMembers, MidiHub.mpeBendSemis.roundToInt(),
                        )
                    },
                    good = zone != 0,
                )
            }
        }
        if (!auto && zone != 0) {
            // 15 unless the controller says otherwise.
            CountKnob(stringResource(R.string.midi_mpe_fingers), MidiHub.mpeMembers, 1..15, choices = (1..15).map { "$it" }) { UiPrefs.chooseMpe(members = it) }
            // Per finger. The standard says 48; many controllers use 24.
            CountKnob(
                stringResource(R.string.midi_mpe_bend), MidiHub.mpeBendSemis.roundToInt(), 1..96,
                stringResource(R.string.midi_mpe_bend_st, MidiHub.mpeBendSemis.roundToInt()), PanelAmber,
                choices = (1..96).map { stringResource(R.string.midi_mpe_bend_st, it) },
            ) { UiPrefs.chooseMpe(bendSemis = it.toFloat()) }
        }
        if (zone != 0) {
            // Timbre: CC 74 drives each machine's slide knob. Plain: it is
            // a controller like any other, free to map.
            SwitchGrid(stringResource(R.string.midi_mpe_cc74), stringArrayResource(R.array.midi_mpe_cc74_choices).toList(), if (MidiHub.mpeTimbre) 0 else 1, columns = 1) {
                UiPrefs.chooseMpe(timbre = it == 0)
            }
            // Is it reaching the voices? Two notes, then the first alone is
            // bent, pressed and slid: if both change, per-note expression is
            // not working.
            val held = (0 until 16).filter { (mpeHeld shr it) and 1 == 1 }.map { it + 1 }
            Line {
                Readout(
                    if (held.isEmpty()) stringResource(R.string.midi_mpe_none_held)
                    else stringResource(R.string.midi_mpe_held, held.joinToString(stringResource(R.string.list_separator))),
                    good = held.isNotEmpty(),
                )
            }
        }
        // Under auto too, before anything is heard: the test plays as a
        // controller would, and auto should recognise it.
        if (zone != 0 || auto) {
            SwitchGrid(stringResource(R.string.midi_test), listOf(stringResource(R.string.midi_test_mpe)), -1) { MidiHub.testMpe() }
        }
    }
}
