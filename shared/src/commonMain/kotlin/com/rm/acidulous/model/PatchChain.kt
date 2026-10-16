package com.rm.acidulous.model

/**
 * The effects a machine patch brings: its inserts and send levels, put on the
 * track when the patch loads.
 *
 * A track's slots the current patch filled are marked [UnitSlot.fromPatch].
 * Loading another patch clears only those, then puts the new patch's inserts
 * in free slots, its old slots first, in slot order so the chain keeps its
 * order. Inserts the player added stay, and an insert with no free slot is
 * skipped, and one naming an effect this build doesn't have is left out
 * before any slot is chosen. A patch with send levels sets both and keeps the
 * player's levels on the track ([Track.patchSends]); one without puts them
 * back. Moving a send by hand makes the sends the player's again. Saving a
 * patch with effects makes the track's inserts its own.
 *
 * The same rule as engine/core/PatchChain.h, which a host that drives the
 * engine directly uses through EngineHost::applyPatchChain. Keep them alike.
 */
object PatchChain {
    /** Where each of [wanted] inserts goes (-1 skipped), which slots to empty, and which the patch then owns. */
    class Plan(val slotOf: List<Int>, val clear: Set<Int>, val owned: Set<Int>, val skipped: Int)

    fun plan(occupied: List<Boolean>, owned: Set<Int>, wanted: Int): Plan {
        val clear = (0 until EFFECT_SLOTS).filter { it in owned }
        val chosen = (clear + (0 until EFFECT_SLOTS).filter { it !in owned && !occupied.getOrElse(it) { false } })
            .take(wanted.coerceAtLeast(0))
            .sorted()
        val slotOf = List(wanted.coerceAtLeast(0)) { chosen.getOrElse(it) { -1 } }
        return Plan(slotOf, clear.toSet(), chosen.toSet(), wanted.coerceAtLeast(0) - chosen.size)
    }

    /**
     * What [apply] made: the slots it put inserts in (to mount fresh, so
     * nothing rings on from the last patch's), the types of the inserts that
     * found no slot, and the types this build doesn't have, which are left out.
     */
    class Applied(val track: Track, val skipped: List<String>, val placed: Set<Int> = emptySet(), val unknown: List<String> = emptyList())

    /**
     * [track] with [patch]'s effects on it. [defaults] gives an effect type's
     * parameters at their defaults, normalised, so an insert's knobs a patch
     * leaves out don't keep the last effect's values. [known] says whether
     * this build has an effect, since a patch can come from a newer one.
     */
    fun apply(track: Track, patch: Patch, known: (String) -> Boolean, defaults: (String) -> Map<String, Float>): Applied {
        val unknown = patch.inserts.filter { !it.isEmpty && !known(it.type) }.map { it.type }
        val inserts = patch.inserts.filter { !it.isEmpty && known(it.type) }
        val owned = (0 until EFFECT_SLOTS).filter { track.effectAt(it).fromPatch }.toSet()
        val plan = plan(List(EFFECT_SLOTS) { !track.effectAt(it).isEmpty }, owned, inserts.size)
        val slots = MutableList(EFFECT_SLOTS) { track.effectAt(it) }
        for (s in plan.clear) slots[s] = UnitSlot()
        val skipped = mutableListOf<String>()
        inserts.forEachIndexed { i, fx ->
            val s = plan.slotOf[i]
            if (s < 0) skipped += fx.type
            else slots[s] = fx.copy(params = defaults(fx.type) + fx.params, fromPatch = true)
        }
        var next = if (slots == List(EFFECT_SLOTS) { track.effectAt(it) }) track else track.copy(effects = slots)
        next = withSends(next, patch)
        return Applied(next, skipped, plan.owned, unknown)
    }

    /**
     * The track's sends while a patch owns them: [PatchSends] holds the
     * player's levels from before. Null once the player has moved a send,
     * since the sends are theirs again. Checked here rather than at every
     * place a send can move (the strip, a controller, a mapping).
     */
    fun heldSends(track: Track): PatchSends? = track.patchSends?.takeIf {
        track.mixer.sendReverb == it.patchReverb && track.mixer.sendDelay == it.patchDelay
    }

    /**
     * A patch that names sends sets them, keeping the player's levels the
     * first time; one that names none puts the player's back. The same rule
     * as planPatchSends in engine/core/PatchChain.h.
     */
    private fun withSends(track: Track, patch: Patch): Track {
        val held = heldSends(track)
        if (patch.sendReverb != null || patch.sendDelay != null) {
            val reverb = patch.sendReverb ?: 0f
            val delay = patch.sendDelay ?: 0f
            val kept = held ?: PatchSends(track.mixer.sendReverb, track.mixer.sendDelay)
            return track.copy(
                mixer = track.mixer.copy(sendReverb = reverb, sendDelay = delay),
                patchSends = kept.copy(patchReverb = reverb, patchDelay = delay),
            )
        }
        if (held != null) {
            return track.copy(mixer = track.mixer.copy(sendReverb = held.reverb, sendDelay = held.delay), patchSends = null)
        }
        return if (track.patchSends == null) track else track.copy(patchSends = null)
    }

    /**
     * [patch] carrying [track]'s inserts, the patch's and the player's, and
     * its send levels when either is up or a patch owns them, for saving with
     * effects. The player's sends at zero aren't saved, so loading the patch
     * leaves another track's alone. The track keeps its held levels.
     */
    fun withEffectsOf(patch: Patch, track: Track): Patch {
        val sends = heldSends(track) != null || track.mixer.sendReverb > 0f || track.mixer.sendDelay > 0f
        return patch.copy(
            inserts = (0 until EFFECT_SLOTS).map { track.effectAt(it) }.filter { !it.isEmpty }.map { it.copy(fromPatch = false) },
            sendReverb = if (sends) track.mixer.sendReverb else null,
            sendDelay = if (sends) track.mixer.sendDelay else null,
        )
    }

    /**
     * [track] after saving its patch with effects: its inserts are now the
     * patch's, so loading that patch again replaces them instead of adding a
     * second set.
     */
    fun ownedBySaved(track: Track): Track {
        if ((0 until EFFECT_SLOTS).all { track.effectAt(it).isEmpty || track.effectAt(it).fromPatch }) return track
        return track.copy(effects = List(EFFECT_SLOTS) { track.effectAt(it).let { fx -> if (fx.isEmpty) fx else fx.copy(fromPatch = true) } })
    }
}
