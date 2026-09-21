package com.rm.acidulous.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A freeze bakes in the machine and both insert effects, so changing either
 * has to invalidate it.
 *
 * It only ever compared the tempo, which meant turning a filter knob on a
 * frozen track changed nothing you could hear and said nothing about why.
 */
class FreezeVoiceTest {

    private val song = Fixtures.song()
    private val sceneId = song.scenes[0].id

    private fun frozen(track: Track): Song {
        val f = Frozen("f.wav", song.tempo, 960, 48000, 0.5f, Freeze.voiceOf(track))
        val withClip = track.copy(clips = track.clips + (sceneId to track.clips[sceneId]!!.copy(frozen = f)))
        return song.copy(tracks = song.tracks.toMutableList().also { it[0] = withClip })
    }

    @Test
    fun aFreshFreezeIsNotStale() {
        val s = frozen(song.tracks[0])
        assertFalse(Freeze.stale(s, sceneId, s.tracks[0].clips[sceneId]!!))
    }

    @Test
    fun turningAMachineKnobMakesItStale() {
        val s = frozen(song.tracks[0])
        val moved = s.tracks[0].let { t ->
            t.copy(machine = t.machine.copy(params = t.machine.params + ("cutoff" to 0.9f)))
        }
        val after = s.copy(tracks = s.tracks.toMutableList().also { it[0] = moved })
        assertTrue(Freeze.stale(after, sceneId, after.tracks[0].clips[sceneId]!!))
    }

    @Test
    fun addingOrChangingAnInsertMakesItStale() {
        val s = frozen(song.tracks[0])
        val withFx = s.tracks[0].withEffect(0, "Delay")
        val after = s.copy(tracks = s.tracks.toMutableList().also { it[0] = withFx })
        assertTrue(Freeze.stale(after, sceneId, after.tracks[0].clips[sceneId]!!))
        // And bypassing it is a different sound again.
        val bypassed = withFx.withEffectBypass(0, true)
        val after2 = s.copy(tracks = s.tracks.toMutableList().also { it[0] = bypassed })
        assertNotEquals(Freeze.voiceOf(withFx), Freeze.voiceOf(bypassed))
        assertTrue(Freeze.stale(after2, sceneId, after2.tracks[0].clips[sceneId]!!))
    }

    @Test
    fun theMixerStaysLiveOverAFrozenTrack() {
        // The fader, pan, sends and mute are deliberately not baked in - that
        // is the line between a freeze and a bounce - so moving them must not
        // mark it stale.
        val s = frozen(song.tracks[0])
        val mixed = s.tracks[0].let { it.copy(mixer = it.mixer.copy(volume = 0.3f, pan = -0.5f, sendReverb = 0.9f)) }
        val after = s.copy(tracks = s.tracks.toMutableList().also { it[0] = mixed })
        assertFalse(Freeze.stale(after, sceneId, after.tracks[0].clips[sceneId]!!))
    }

    @Test
    fun aFreezeFromBeforeThisFieldIsLeftAlone() {
        // voice = 0 means "written by a build that did not record it", and an
        // old song must not open with every frozen clip claiming to be wrong.
        val f = Frozen("f.wav", song.tempo, 960, 48000, 0.5f)
        val t = song.tracks[0]
        val withClip = t.copy(clips = t.clips + (sceneId to t.clips[sceneId]!!.copy(frozen = f)))
        val s = song.copy(tracks = song.tracks.toMutableList().also { it[0] = withClip })
        assertFalse(Freeze.stale(s, sceneId, s.tracks[0].clips[sceneId]!!))
    }
}
