package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo song is the first thing anybody hears, and every name in it is a
 * string looked up at build time.
 *
 * A misspelt patch name does not fail: `PatchStore.factory(...).firstOrNull`
 * returns null and the machine is built on its defaults, so the song still
 * plays and merely sounds wrong - which is exactly the kind of fault nobody
 * finds by reading the file. Same for a machine or effect type that no registry
 * knows, or a lane on a parameter that does not exist: the song looks fine.
 *
 * So this checks the names resolve, not that the music is any good, and then
 * that the demo shows what it is there to show.
 */
class DemoSongTest {

    private val demos = listOf(Demo("Squelch") to DemoSong.build())
    private class Demo(val name: String)
    private fun Song.clips() = tracks.flatMap { it.clips.values }
    private fun Song.notes() = clips().flatMap { it.notes }

    @Test
    fun everyMachineAndEffectNameIsReal() {
        val machines = MachineUi.machineGroups.flatMap { it.machines }.toSet()
        for ((demo, song) in demos) {
            for (track in song.tracks) {
                assertTrue("${demo.name}: unknown machine '${track.machine.type}'", track.machine.type in machines)
                // Effects are named in the engine's registry, which a unit test
                // cannot ask, so they are checked against the factory banks -
                // a bank exists only for a real effect.
                for (slot in track.effects) {
                    if (slot.type.isEmpty()) continue
                    assertTrue(
                        "${demo.name}: no factory bank for effect '${slot.type}'",
                        PatchStore.factory(PatchStore.effectKey(slot.type)).isNotEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun everyPatchNameResolved() {
        for ((demo, song) in demos) {
            // A machine given a patch has more than nothing in its params; one
            // that came back empty means the name was not found.
            for (track in song.tracks) {
                assertTrue(
                    "${demo.name}: patch for ${track.machine.type} on '${track.name}' resolved to nothing",
                    track.machine.params.isNotEmpty(),
                )
                for (slot in track.effects) {
                    if (slot.type.isEmpty() || slot.params.containsKey(SIDECHAIN_PARAM)) continue
                    assertTrue(
                        "${demo.name}: effect ${slot.type} on '${track.name}' resolved to nothing",
                        slot.params.isNotEmpty() || slot.type == "Bitcrusher", // its Init sets nothing on purpose
                    )
                }
            }
            for (slot in song.master.sends + song.master.inserts) {
                assertTrue("${demo.name}: ${slot.type} on the master resolved to nothing", slot.params.isNotEmpty())
            }
        }
    }

    @Test
    fun everyLaneIsOnARealParameter() {
        val perform = setOf("repeat", "stop", "x", "y", "reverse", "gate", "killlow", "killmid", "killhigh", "riser")
        val channel = setOf("gain", "pan", "mute", "solo", "sendreverb", "senddelay")
        for ((demo, song) in demos) {
            for (track in song.tracks) {
                // A machine's parameters, as far as its own factory bank names them.
                val known = PatchStore.factory(track.machine.type).flatMap { it.params.keys }.toSet()
                for (clip in track.clips.values) {
                    for (key in clip.automation.keys) {
                        val unit = laneUnit(key)
                        val name = laneParam(key)
                        val ok = when (unit) {
                            "machine" -> name in known
                            "perform" -> name in perform
                            "channel" -> name in channel
                            else -> false
                        }
                        assertTrue("${demo.name}: lane '$key' on '${track.name}' names nothing", ok)
                    }
                }
            }
        }
    }

    @Test
    fun theHeldEffectsAreLetGoOf() {
        // A perform lane holds its last value past the end of its clip, so
        // one that ends held leaves the effect on into the next scene.
        val rest = mapOf("x" to 0.5f)
        for ((demo, song) in demos) {
            for (clip in song.clips()) {
                for ((key, lane) in clip.automation) {
                    if (laneUnit(key) != "perform") continue
                    val atRest = rest[laneParam(key)] ?: 0f
                    assertEquals("${demo.name}: '$key' starts held", atRest, lane.points.first().value)
                    assertEquals("${demo.name}: '$key' ends held", atRest, lane.points.last().value)
                }
            }
        }
    }

    @Test
    fun everyClipBelongsToAScene() {
        for ((demo, song) in demos) {
            val ids = song.scenes.map { it.id }.toSet()
            for (track in song.tracks) {
                for (sceneId in track.clips.keys) {
                    assertTrue("${demo.name}: '${track.name}' has a clip in no scene: $sceneId", sceneId in ids)
                }
            }
            // And every scene has something in it, or it is a silent bar nobody
            // asked for.
            for (scene in song.scenes) {
                assertTrue(
                    "${demo.name}: scene '${scene.name}' is empty",
                    song.tracks.any { it.clips[scene.id]?.notes?.isNotEmpty() == true },
                )
            }
        }
    }

    @Test
    fun everyNoteIsPlayable() {
        for ((demo, song) in demos) {
            for (track in song.tracks) {
                for ((sceneId, clip) in track.clips) {
                    val len = song.clipLengthTicks(sceneId, clip)
                    for (n in clip.notes) {
                        val where = "${demo.name}, '${track.name}'"
                        assertTrue("pitch ${n.pitch} out of range on $where", n.pitch in 0..127)
                        assertTrue("velocity ${n.velocity} on $where", n.velocity in 1..127)
                        assertTrue("length ${n.length} on $where", n.length > 0)
                        assertTrue("note at ${n.tick} past the clip on $where", n.tick < len)
                        assertTrue("chance ${n.chance} on $where", n.chance in 0..100)
                        assertTrue("ratchet ${n.ratchet} on $where", n.ratchet in 1..8)
                    }
                }
            }
        }
    }

    @Test
    fun itNeedsNothingFromDisk() {
        // The demo has to play on a phone that has never recorded anything, so
        // no machine in it may hold audio and no clip may carry a take. A
        // Nexus graph is a setting, but it is text, not a file.
        val needsMedia = setOf("Forage", "Mosaic", "Pollen", "Dice", "Molt", "Bias")
        for ((demo, song) in demos) {
            for (track in song.tracks) {
                assertFalse("${demo.name}: '${track.name}' needs a sample", track.machine.type in needsMedia)
                assertTrue("${demo.name}: '${track.name}' names a file", (track.machine.settings.keys - "nexus").isEmpty())
            }
            for (clip in song.clips()) {
                assertTrue("${demo.name}: a clip carries audio", clip.audio == null)
                assertTrue("${demo.name}: a clip is frozen", clip.frozen == null)
            }
        }
    }

    @Test
    fun itSurvivesBeingSavedAndOpened() {
        for ((demo, song) in demos) assertEquals(demo.name, song, SongStore.decode(SongStore.encode(song)))
    }

    // --- what it is there to show -----------------------------------------------------

    @Test
    fun theFirstRunOpensIt() {
        assertEquals("Squelch", DemoSong.build().name)
    }

    @Test
    fun itShowsTheAcid() {
        val song = DemoSong.build()
        val lines = song.tracks.filter { it.machine.type == "Reflux" }
        assertTrue("not two acid lines", lines.size >= 2)
        val acid = lines.first().clips.values.flatMap { it.notes }.sortedBy { it.tick }
        assertTrue("no slides: nothing overlaps the next note", acid.zipWithNext().any { (a, b) -> a.tick + a.length > b.tick })
        assertTrue("no accents", acid.map { it.velocity }.distinct().size > 1)
        assertTrue("no filter sweep", song.clips().any { laneKey("machine", "cutoff") in it.automation })
        assertTrue("no step locks", song.clips().any { c -> c.automation.values.any { Locks.isLocks(it) } })
    }

    @Test
    fun itShowsThePerformPages() {
        val song = DemoSong.build()
        val performed = song.clips().flatMap { it.automation.keys }.filter { laneUnit(it) == "perform" }.map { laneParam(it) }.toSet()
        assertTrue("the performance lanes are missing: $performed", performed.containsAll(listOf("repeat", "riser", "gate", "y")))
        assertTrue("no fill-only notes", song.notes().any { it.trig == Trig.Fill })
    }

    @Test
    fun itShowsTheSong() {
        val song = DemoSong.build()
        assertTrue("no scene repeats", song.scenes.any { it.repeat > 1 })
        assertTrue("clips are all one length", song.clips().map { it.bars }.distinct().size > 1)
        assertTrue("nothing swings", song.swing > SWING_STRAIGHT)
        assertNotNull("no song key", song.key)
        assertEquals("both sends should be filled", 2, song.master.sends.count { it.type.isNotEmpty() })
        assertTrue("no trig conditions", song.notes().any { it.trig != Trig.Always && it.trig != Trig.Fill })
        assertTrue("no chances", song.notes().any { it.chance < 100 })
        assertTrue("no ratchets", song.notes().any { it.ratchet > 1 })
        assertTrue("nothing rolls free", song.clips().any { it.freeRoll })
        assertTrue("no one-shot clip", song.clips().any { it.playMode == PlayMode.OneShot })
        assertTrue("no note expression", song.notes().any { it.hasExpression })
        assertTrue("no modifier", song.tracks.any { t -> t.modifiers.any { it.type.isNotEmpty() } })
    }

    @Test
    fun itShowsTheMixer() {
        val song = DemoSong.build()
        assertTrue("no sidechain", song.tracks.flatMap { it.effects }.any { (it.params[SIDECHAIN_PARAM] ?: 0f) > 0f })
        assertTrue("no group", song.master.groups.isNotEmpty())
        assertTrue("no track routed into a group", song.tracks.any { it.mixer.output in 1..song.master.groups.size })
        assertTrue("no master inserts", song.master.inserts.any { it.type.isNotEmpty() })
    }

    @Test
    fun itShowsTheModular() {
        val nexus = DemoSong.build().tracks.firstOrNull { it.machine.type == "Nexus" }
        assertNotNull("no Nexus track", nexus)
        assertTrue("the Nexus patch has no graph", NexusPatch.decode(nexus!!.machine.settings["nexus"]).modules.isNotEmpty())
    }
}
