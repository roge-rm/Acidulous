package com.rm.acidulous.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo song is the first thing anybody sees, and every name in it is a
 * string looked up at build time.
 *
 * A misspelt patch name does not fail: `PatchStore.factory(...).firstOrNull`
 * returns null and the machine is built on its defaults, so the song still
 * plays and merely sounds wrong - which is exactly the kind of fault nobody
 * finds by reading the file. Same for a machine or effect type that no registry
 * knows: the track is silent and the song looks fine.
 *
 * So this checks the names resolve, not that the music is any good.
 */
class DemoSongTest {

    private val song = DemoSong.build()

    @Test
    fun everyMachineAndEffectNameIsReal() {
        val machines = MachineUi.machineGroups.flatMap { it.machines }.toSet()
        for (track in song.tracks) {
            assertTrue("unknown machine '${track.machine.type}'", track.machine.type in machines)
        }
        // Effects are named in the engine's registry, which a unit test cannot
        // ask, so they are checked against the factory banks instead - which is
        // the stronger check anyway: a bank exists only for a real effect.
        for (track in song.tracks) {
            for (slot in track.effects) {
                if (slot.type.isEmpty()) continue
                assertTrue(
                    "no factory bank for effect '${slot.type}'",
                    PatchStore.factory(PatchStore.effectKey(slot.type)).isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun everyPatchNameResolved() {
        // A machine given a patch has more than nothing in its params; one that
        // came back empty means the name was not found.
        for (track in song.tracks) {
            assertTrue(
                "patch for ${track.machine.type} on '${track.name}' resolved to nothing",
                track.machine.params.isNotEmpty(),
            )
        }
        for (track in song.tracks) {
            for (slot in track.effects) {
                if (slot.type.isEmpty()) continue
                // fx.Bitcrusher's Init sets nothing on purpose, so an empty map
                // is legal for that one and only that one.
                if (slot.params.isEmpty()) {
                    assertTrue(
                        "effect ${slot.type} on '${track.name}' resolved to nothing",
                        slot.type == "Bitcrusher",
                    )
                }
            }
        }
        for (send in song.master.sends) {
            assertTrue("send ${send.type} resolved to nothing", send.params.isNotEmpty())
        }
    }

    @Test
    fun everyClipBelongsToAScene() {
        val ids = song.scenes.map { it.id }.toSet()
        for (track in song.tracks) {
            for (sceneId in track.clips.keys) {
                assertTrue("'${track.name}' has a clip in no scene: $sceneId", sceneId in ids)
            }
        }
        // And every scene has something in it, or it is a silent bar nobody
        // asked for.
        for (scene in song.scenes) {
            assertTrue(
                "scene '${scene.name}' is empty",
                song.tracks.any { it.clips[scene.id]?.notes?.isNotEmpty() == true },
            )
        }
    }

    @Test
    fun everyNoteIsPlayable() {
        for (track in song.tracks) {
            for ((sceneId, clip) in track.clips) {
                val len = song.clipLengthTicks(sceneId, clip)
                for (n in clip.notes) {
                    assertTrue("pitch ${n.pitch} out of range on '${track.name}'", n.pitch in 0..127)
                    assertTrue("velocity ${n.velocity} on '${track.name}'", n.velocity in 1..127)
                    assertTrue("length ${n.length} on '${track.name}'", n.length > 0)
                    assertTrue("note at ${n.tick} past the clip on '${track.name}'", n.tick < len)
                    assertTrue("chance ${n.chance} on '${track.name}'", n.chance in 0..100)
                    assertTrue("ratchet ${n.ratchet} on '${track.name}'", n.ratchet in 1..8)
                }
            }
        }
    }

    @Test
    fun itShowsTheThingsItIsThereToShow() {
        // Each of these is a feature the demo exists to demonstrate. If one
        // disappears the demo has quietly stopped earning its place.
        assertTrue("no scene repeats", song.scenes.any { it.repeat > 1 })
        assertTrue("no scene has its own tempo", song.scenes.any { it.tempo != null })
        assertTrue("clips are all one length", song.tracks.flatMap { it.clips.values }.map { it.bars }.distinct().size > 1)
        assertTrue("nothing swings", song.swing > SWING_STRAIGHT)
        assertNotNull("no song key", song.key)
        assertTrue(
            "no track disagrees with the song's swing",
            song.tracks.any { it.swing != null && it.swing != song.swing },
        )
        assertTrue("no insert effects", song.tracks.any { it.effects.any { e -> e.type.isNotEmpty() } })
        assertEquals("both sends should be filled", 2, song.master.sends.count { it.type.isNotEmpty() })
        assertTrue(
            "no automation",
            song.tracks.flatMap { it.clips.values }.any { it.automation.isNotEmpty() },
        )
        assertTrue(
            "no trig conditions",
            song.tracks.flatMap { it.clips.values }.flatMap { it.notes }.any { it.hasTrig },
        )
        assertTrue("nothing rolls free", song.tracks.flatMap { it.clips.values }.any { it.freeRoll })
        assertTrue(
            "no one-shot clip",
            song.tracks.flatMap { it.clips.values }.any { it.playMode == PlayMode.OneShot },
        )
        assertTrue(
            "no note expression",
            song.tracks.flatMap { it.clips.values }.flatMap { it.notes }.any { it.hasExpression },
        )
    }

    @Test
    fun itNeedsNothingFromDisk() {
        // The demo has to play on a phone that has never recorded anything, so
        // no machine in it may be one that holds audio and no clip may carry a
        // take. These five are the sample-holding machines.
        val needsMedia = setOf("Forage", "Mosaic", "Pollen", "Dice", "Molt", "Bias")
        for (track in song.tracks) {
            assertFalse("'${track.name}' needs a sample", track.machine.type in needsMedia)
            assertTrue("'${track.name}' names a file", track.machine.settings.isEmpty())
        }
        for (clip in song.tracks.flatMap { it.clips.values }) {
            assertTrue("a clip carries audio", clip.audio == null)
            assertTrue("a clip is frozen", clip.frozen == null)
        }
    }

    @Test
    fun itSurvivesBeingSavedAndOpened() {
        assertEquals(song, SongStore.decode(SongStore.encode(song)))
    }
}
