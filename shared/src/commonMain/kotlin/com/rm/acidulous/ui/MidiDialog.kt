package com.rm.acidulous.ui

import com.rm.acidulous.util.format

import androidx.compose.foundation.layout.fillMaxWidth
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rm.acidulous.engine.NativeEngine
import com.rm.acidulous.midi.MidiHub
import com.rm.acidulous.midi.VelocityCurve
import com.rm.acidulous.model.Mapping
import com.rm.acidulous.model.Mappings
import com.rm.acidulous.model.Song
import com.rm.acidulous.ui.theme.Acid
import com.rm.acidulous.res.*
import com.rm.acidulous.res.*

/**
 * Everything MIDI, in tabs, using the shared window shell.
 *
 * Every tab ends in numbers showing what the app thinks is happening, since a
 * lot of MIDI problems can't be seen otherwise (a Bluetooth scan with the wrong
 * service UUID looks exactly like no devices nearby).
 */
@Composable
fun MidiDialog(song: Song, onDismiss: () -> Unit) {
    val trackNames = song.tracks.map { it.name }
    // Read here and passed down. The pages are subcomposed inside the window's
    // measure block (so it can size to the tallest), and a value that changes
    // by itself doesn't reliably re-measure from in there. Read here it's a
    // plain state read and the page is rebuilt.
    val mpeHeld = MidiHub.mpeHeld
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { MidiHub.refresh() }

    TabbedDialog(
        title = stringResource(Res.string.midi_title),
        selected = tab,
        onDismiss = { MidiHub.stopScan(); onDismiss() },
        spacing = 6.dp,
        pageNames = stringArrayResource(Res.array.midi_tabs).toList(),
        onSelectPage = { tab = it },
        pages = listOf(
            { DevicesTab() },
            { InTab(trackNames, mpeHeld) },
            { ControlTab(song) },
        ),
    )
}

// Three tabs: what's plugged in both ways, with output timing next to the
// outputs; the notes arriving; and what steers the app from outside, which is
// an external clock and the mapped controls.

/**
 * Titled cards with switches and knobs for settings, and devices and readings
 * as rows inside the same cards, like every settings window.
 */
@Composable
private fun Line(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(Modifier.cardLine()) { content() }

/** What's plugged in, and the Bluetooth scan for what isn't. */
@Composable
private fun DevicesTab() {
    val ports = MidiHub.ports
    val found = MidiHub.discovered
    val permissions = rememberPermissions { ok -> if (ok) MidiHub.scanBluetooth() }

    WindowCards {
        if (!MidiHub.supported) {
            Text(stringResource(Res.string.midi_unsupported), color = Acid.colors.red, fontSize = 12.sp)
        }
        WindowCard(stringResource(if (MidiHub.canFindBluetooth) Res.string.midi_inputs else Res.string.midi_inputs_cable)) {
            // At the top, above the devices: the Bluetooth search, and how far
            // ahead MIDI is sent.
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                if (MidiHub.canFindBluetooth) {
                    // A cable shows up by itself, a Bluetooth instrument has to be
                    // scanned for.
                    SwitchGrid(
                        stringResource(if (MidiHub.bluetoothReady()) Res.string.midi_bluetooth else Res.string.midi_bluetooth_off),
                        listOf(stringResource(if (MidiHub.scanning) Res.string.midi_stop else Res.string.midi_search)), if (MidiHub.scanning) 0 else -1,
                    ) {
                        if (MidiHub.scanning) {
                            MidiHub.stopScan()
                        } else {
                            val missing = MidiHub.bluetoothPermissions().filter { !permissions.has(it) }
                            if (missing.isEmpty()) MidiHub.scanBluetooth() else permissions.ask(*missing.toTypedArray())
                        }
                    }
                }
                // Raise it if the external part drags behind what you hear.
                CountKnob(
                    stringResource(Res.string.midi_send_ahead), MidiHub.outOffsetMs, -50..50, "%+d ms".format(MidiHub.outOffsetMs), PanelAmber,
                    choices = (-50..50).map { "%+d ms".format(it) },
                ) { UiPrefs.chooseMidiOffset(it) }
            }
            // One column. In a wide window a card lays its controls side by
            // side, and full width rows after the first got no room at all.
            if (ports.isNotEmpty()) Line {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    ports.forEach { port ->
                        DialogRow(
                            mark = if (port.bluetooth) "ᛒ" else "⎓",
                            name = port.name,
                            under = port.maker,
                            trailing = if (port.open) stringResource(Res.string.midi_listening) else stringResource(Res.string.midi_tap_to_open, Res.string.midi_tap_to_open_mouse),
                            on = port.open,
                        ) { MidiHub.toggle(port.id) }
                    }
                }
            }
            if (ports.isEmpty()) Line { Readout(stringResource(if (MidiHub.canFindBluetooth) Res.string.midi_no_inputs else Res.string.midi_no_inputs_cable)) }
            // Only with a Launchpad plugged in: the app drives it, or it runs
            // itself.
            if (MidiHub.launchpadHere) {
                SwitchGrid(stringResource(Res.string.midi_launchpad), stringArrayResource(Res.array.midi_launchpad_choices).toList(), if (MidiHub.launchpadOn) 0 else 1) {
                    UiPrefs.chooseLaunchpad(it == 0)
                }
                if (MidiHub.launchpadOn) {
                    SwitchGrid(stringResource(Res.string.midi_launchpad_pages), stringArrayResource(Res.array.midi_pages_choices).toList(), if (UiPrefs.launchpadNotesOnly) 1 else 0) {
                        UiPrefs.chooseLaunchpadNotesOnly(it == 1)
                    }
                }
            }
            // Only with an Exquis plugged in: the played track's scale on its
            // pads.
            if (MidiHub.exquisHere) {
                SwitchGrid(stringResource(Res.string.midi_exquis_pads), stringArrayResource(Res.array.midi_exquis_pads_choices).toList(), MidiHub.padMode.ordinal, columns = 1) {
                    UiPrefs.choosePadMode(MidiHub.PadMode.entries[it])
                }
                // Played by the app as a controller, or left as the Exquis's own.
                SwitchGrid(stringResource(Res.string.midi_exquis_buttons), stringArrayResource(Res.array.midi_launchpad_choices).toList(), if (MidiHub.exquisButtons) 0 else 1) {
                    UiPrefs.chooseExquisButtons(it == 0)
                }
                if (MidiHub.exquisButtons) {
                    SwitchGrid(stringResource(Res.string.midi_exquis_pages), stringArrayResource(Res.array.midi_pages_choices).toList(), if (UiPrefs.exquisNotesOnly) 1 else 0) {
                        UiPrefs.chooseExquisNotesOnly(it == 1)
                    }
                    SwitchGrid(stringResource(Res.string.midi_exquis_held), stringArrayResource(Res.array.midi_exquis_held_choices).toList(), UiPrefs.exquisHold.ordinal, columns = 1) {
                        UiPrefs.chooseExquisHold(com.rm.acidulous.midi.exquis.XqHold.entries[it])
                    }
                }
            }
            // Only where the app finds Bluetooth instruments itself (on a
            // phone). Desktops pair them in the system settings.
            if (MidiHub.canFindBluetooth) {
                // The scan status, so a scan that finds nothing doesn't look
                // like a broken one.
                if (MidiHub.scanStatus.isNotEmpty()) Line { Readout(MidiHub.scanStatus) }
                found.forEach { device ->
                    // Mark devices that advertised the MIDI service. In a widened
                    // scan everything else is a guess.
                    DialogRow(
                        mark = if (device.midi) "ᛒ" else "·",
                        name = device.name,
                        under = if (device.midi) device.address else stringResource(Res.string.midi_no_service, device.address),
                        trailing = stringResource(Res.string.midi_connect),
                        monoUnder = true,
                    ) { MidiHub.connectBluetooth(device.address) }
                }
            }
        }
        // Each track chooses whether it sends in the mixer. This sets where to,
        // and how early.
        WindowCard(stringResource(Res.string.midi_outputs)) {
            // One column, like the inputs.
            if (MidiHub.destinations.isNotEmpty()) Line {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    MidiHub.destinations.forEach { dest ->
                        DialogRow(
                            mark = "→",
                            name = dest.name,
                            trailing = if (dest.open) stringResource(Res.string.midi_sending) else stringResource(Res.string.midi_tap_to_open, Res.string.midi_tap_to_open_mouse),
                            on = dest.open,
                        ) { MidiHub.toggleDestination(dest.id) }
                    }
                }
            }
            if (MidiHub.destinations.isEmpty()) Line { Readout(stringResource(Res.string.midi_no_outputs)) }
            // Numbers to check when timing sounds loose. "late" is how far past
            // its own timestamp a message was handed to the system. If that
            // grows, the trim isn't the problem.
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

/** Notes arriving: where they go, and a readout showing they arrive. */
@Composable
private fun InTab(trackNames: List<String>, mpeHeld: Int) {
    // Routing first, then the readout, then MPE.
    WindowCards {
        // Where they go, with the readout in the same card.
        MidiRoutingSection(trackNames) {
            // Play notes without a controller, for testing on the emulator.
            // Diagnostics only.
            if (UiPrefs.showDiagnostics) {
                val tests = stringArrayResource(Res.array.midi_test_notes).toList() + stringResource(Res.string.midi_test_launchpad)
                SwitchGrid(stringResource(Res.string.midi_test), tests, -1, columns = 1) {
                    when (it) { 0 -> MidiHub.testNote(); 1 -> MidiHub.testWheel(); else -> MidiHub.testLaunchpad() }
                }
            }
            // Next to the readout, which shows each note's velocity after the
            // curve.
            val curveNames = (-VelocityCurve.STEPS..VelocityCurve.STEPS).map {
                when {
                    it < 0 -> stringResource(Res.string.midi_velocity_softer, -it)
                    it > 0 -> stringResource(Res.string.midi_velocity_harder, it)
                    else -> stringResource(Res.string.midi_velocity_as_sent)
                }
            }
            CountKnob(
                stringResource(Res.string.midi_velocity), MidiHub.velocityCurve, -VelocityCurve.STEPS..VelocityCurve.STEPS,
                curveNames[MidiHub.velocityCurve + VelocityCurve.STEPS], PanelAmber, width = 84.dp,
                choices = curveNames,
            ) { UiPrefs.chooseVelocityCurve(it) }
            Line {
                Readout(
                    if (MidiHub.received == 0) stringResource(Res.string.midi_nothing_received)
                    else pluralStringResource(Res.plurals.midi_received, MidiHub.received, MidiHub.received, MidiHub.lastMessage),
                    good = MidiHub.received > 0,
                )
            }
        }
        MpeSection(mpeHeld)
    }
}

/**
 * What steers the app from outside: an external clock, and the mapped controls.
 */
@Composable
private fun ControlTab(song: Song) {
    val link = com.rm.acidulous.engine.LinkHub.enabled
    val follow = if (link) MidiHub.Follow.Off else MidiHub.follow
    fun choose(mode: MidiHub.Follow) {
        // Only one clock master at a time, on screen as well as in the engine.
        if (mode != MidiHub.Follow.Off && link) {
            UiPrefs.chooseLink(false)
            com.rm.acidulous.engine.LinkHub.chooseEnabled(false)
        }
        UiPrefs.chooseFollow(mode)
    }
    WindowCards {
        WindowCard(stringResource(Res.string.midi_clock)) {
            // Sent to every open output, with start, stop and song position.
            SwitchGrid(stringResource(Res.string.midi_clock_send), stringArrayResource(Res.array.off_on).toList(), if (MidiHub.clockOut) 1 else 0) { UiPrefs.chooseClockOut(it == 1) }
            SwitchGrid(
                stringResource(Res.string.midi_clock_follow), stringArrayResource(Res.array.midi_clock_follow_choices).toList(),
                when (follow) { MidiHub.Follow.Off -> 0; MidiHub.Follow.Auto -> 1; MidiHub.Follow.On -> 2 },
                columns = 1,
            ) { choose(listOf(MidiHub.Follow.Off, MidiHub.Follow.Auto, MidiHub.Follow.On)[it]) }
            // Ten seconds of a perfect 120 BPM clock, generated inside the app.
            SwitchGrid(stringResource(Res.string.midi_test), listOf(stringResource(Res.string.midi_test_clock)), -1, enabled = listOf(follow != MidiHub.Follow.Off)) { MidiHub.testClock() }
            // The tempo can look right while the phase drifts, so the second
            // number is the last pulse's distance from where the loop expected
            // it.
            Line {
                Readout(
                    when {
                        link -> stringResource(Res.string.midi_follow_link)
                        follow == MidiHub.Follow.Off -> stringResource(Res.string.midi_follow_off)
                        !MidiHub.clockIn -> stringResource(Res.string.midi_follow_nothing)
                        MidiHub.followBpm <= 0f -> stringResource(Res.string.midi_follow_waiting)
                        else -> stringResource(
                            Res.string.midi_following,
                            MidiHub.followBpm,
                            stringResource(if (MidiHub.followLocked) Res.string.midi_follow_locked else Res.string.midi_follow_settling),
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
 * What the hardware is mapped to.
 *
 * Mappings are learned by touching controls, so this only lists them: what's
 * bound, which notes are taken, and a button to clear everything.
 *
 * The song's mappings and the device's are listed separately because they
 * behave differently: the device's go with the controller and the song's go
 * with the music. When both claim a controller the song wins.
 */
@Composable
private fun MappingCard(song: Song) {
    val device = UiPrefs.mappings
    val resources = AppStrings
    val laneWord: (String) -> String = { resources.panelWord(it) }
    // The title says where the mapping gesture is, the one thing the screen
    // can't show.
    WindowCard(stringResource(Res.string.midi_mapping)) {
        SwitchGrid(stringResource(Res.string.midi_mapping_mode), stringArrayResource(Res.array.off_on).toList(), if (UiPrefs.mapMode) 1 else 0) { UiPrefs.chooseMapMode(it == 1) }
        SwitchGrid(
            stringResource(Res.string.midi_device_mappings), listOf(stringResource(Res.string.midi_forget)), -1, enabled = listOf(device.isNotEmpty()),
        ) { UiPrefs.chooseMappings(emptyList()); UiPrefs.chooseMapWaiting(null) }
        // One list, each marked with whose it is. See the note above.
        val songs = stringResource(Res.string.midi_mapping_song)
        val devices = stringResource(Res.string.midi_mapping_device)
        val all = song.mappings.map { it to songs } + device.map { it to devices }
        if (all.isEmpty()) Line { Readout(stringResource(Res.string.midi_nothing_mapped)) }
        for ((m, whose) in all.sortedWith(compareBy({ it.first.cc ?: 1000 }, { it.first.note ?: 1000 }))) {
            val track = m.rack?.let { song.tracks.getOrNull(it) } ?: song.tracks.firstOrNull()
            Row(Modifier.cardLine(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.sourceLabel().padEnd(10),
                    color = Acid.colors.accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text(
                    m.targetLabel(track, laneWord).let { m.rack?.let { r -> stringResource(Res.string.midi_mapping_track, it, r + 1) } ?: it },
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
                        Res.string.midi_claimed_notes,
                        claimed.joinToString(stringResource(Res.string.list_separator)) { Mapping(note = it).sourceLabel().removePrefix("note ") },
                    ),
                )
            }
        }
    }
}

/**
 * MPE: one instrument played on many channels, a finger per channel.
 *
 * A zone belongs to the connected controller, not the music, so it lives here
 * with the routing and not in the song. While a zone is on, the member channels
 * are fingers and can't be tracks. Routing still picks which track the zone
 * plays, but by-channel routing doesn't apply.
 */
@Composable
private fun MpeSection(mpeHeld: Int) {
    val zone = MidiHub.mpeZone
    val setting = MidiHub.mpeSetting
    val auto = setting == com.rm.acidulous.midi.MpeZone.AUTO
    // Auto first, it's the default and what almost everyone wants.
    val order = listOf(com.rm.acidulous.midi.MpeZone.AUTO, 0, 1, 2)
    WindowCard(stringResource(Res.string.midi_mpe)) {
        // Lower: channel 1 is the zone and the ones above it are fingers.
        // Upper: channel 16 and the ones below. Auto: whatever the controller
        // says or does.
        SwitchGrid(stringResource(Res.string.midi_mpe_zone), stringArrayResource(Res.array.midi_mpe_zone_choices).toList(), order.indexOf(setting), columns = 2) {
            UiPrefs.chooseMpe(zone = order[it])
        }
        if (auto) {
            // What auto has found and how, so you can see when a controller
            // isn't recognised.
            Line {
                Readout(
                    when {
                        zone == 0 -> stringResource(Res.string.midi_mpe_auto_waiting)
                        else -> stringResource(
                            if (MidiHub.mpeHeard == MidiHub.MpeHeard.Config) Res.string.midi_mpe_auto_config else Res.string.midi_mpe_auto_fingers,
                            stringArrayResource(Res.array.midi_mpe_zone_choices)[order.indexOf(zone)],
                            MidiHub.mpeMembers, MidiHub.mpeBendSemis.roundToInt(),
                        )
                    },
                    good = zone != 0,
                )
            }
        }
        if (!auto && zone != 0) {
            // 15 unless the controller says otherwise.
            CountKnob(stringResource(Res.string.midi_mpe_fingers), MidiHub.mpeMembers, 1..15, choices = (1..15).map { "$it" }) { UiPrefs.chooseMpe(members = it) }
            // Pitch bend range per finger. The standard says 48, many
            // controllers use 24.
            CountKnob(
                stringResource(Res.string.midi_mpe_bend), MidiHub.mpeBendSemis.roundToInt(), 1..96,
                stringResource(Res.string.midi_mpe_bend_st, MidiHub.mpeBendSemis.roundToInt()), PanelAmber,
                choices = (1..96).map { stringResource(Res.string.midi_mpe_bend_st, it) },
            ) { UiPrefs.chooseMpe(bendSemis = it.toFloat()) }
        }
        if (zone != 0) {
            // Timbre: CC 74 drives each machine's slide knob. Plain: it's a
            // normal controller, free to map.
            SwitchGrid(stringResource(Res.string.midi_mpe_cc74), stringArrayResource(Res.array.midi_mpe_cc74_choices).toList(), if (MidiHub.mpeTimbre) 0 else 1, columns = 1) {
                UiPrefs.chooseMpe(timbre = it == 0)
            }
            // Is expression reaching the voices? Plays two notes, then bends,
            // presses and slides only the first. If both change, per-note
            // expression isn't working.
            val held = (0 until 16).filter { (mpeHeld shr it) and 1 == 1 }.map { it + 1 }
            Line {
                Readout(
                    if (held.isEmpty()) stringResource(Res.string.midi_mpe_none_held)
                    else stringResource(Res.string.midi_mpe_held, held.joinToString(stringResource(Res.string.list_separator))),
                    good = held.isNotEmpty(),
                )
            }
        }
        // Also shown under auto, where the test plays like a controller would
        // and auto should recognise it.
        if (UiPrefs.showDiagnostics && (zone != 0 || auto)) {
            SwitchGrid(stringResource(Res.string.midi_test), listOf(stringResource(Res.string.midi_test_mpe)), -1) { MidiHub.testMpe() }
        }
    }
}
