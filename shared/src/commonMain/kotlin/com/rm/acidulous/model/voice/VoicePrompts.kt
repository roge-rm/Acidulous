package com.rm.acidulous.model.voice

/**
 * What a voice is recorded from: a list of short things to sing, in stages.
 *
 * Every prompt is sung on one note, so the voice's own pitch marks can move it
 * to any other. The first stage is enough to sing with: every vowel held, and
 * every consonant between two ahs, which gives both the way into it and the
 * way out. Later stages sing the consonants between other vowels, so the
 * joins sound like the vowel around them.
 *
 * A prompt's [id] names its file and must never change once people have
 * recorded it; add new prompts rather than renaming old ones.
 */
data class Prompt(
    val id: String,
    val stage: Int,
    /** What to sing, written to be read at a glance: "ah-sah". */
    val sung: String,
    /** A word with the sound in it, for a vowel that's hard to name: "see". */
    val like: String,
    /** The sounds, in the singer's phone names, for cutting it up: ["AA", "S", "AA"]. */
    val sounds: List<String>,
    /** How a consonant prompt's consonant is written: "h", "th", "ng". Empty for a vowel. */
    val letters: String = "",
) {
    val held: Boolean get() = sounds.size == 1

    /**
     * A diphthong ("eye"): sung on its first vowel, moving to the second at
     * the end, and cut that way.
     */
    val glides: Boolean get() = held && sounds[0] in VoicePrompts.DIPHTHONGS
}

object VoicePrompts {
    /** The vowels that move from one to another. */
    val DIPHTHONGS = setOf("EY", "AY", "AW", "OY", "OW")

    /** The vowels, each held. */
    private val VOWELS = listOf(
        Triple("IY", "ee", "see"), Triple("IH", "ih", "sit"), Triple("EH", "eh", "set"),
        Triple("AE", "a", "sat"), Triple("AA", "ah", "father"), Triple("AO", "aw", "saw"),
        Triple("AH", "uh", "sun"), Triple("UH", "oo", "book"), Triple("UW", "oo", "soon"),
        Triple("ER", "er", "sir"), Triple("EY", "ay", "say"), Triple("AY", "eye", "sigh"),
        Triple("AW", "ow", "south"), Triple("OY", "oy", "soy"), Triple("OW", "oh", "so"),
    )

    /** The consonants, and how each is written between vowels. */
    private val CONSONANTS = listOf(
        Triple("P", "p", "pie"), Triple("B", "b", "buy"), Triple("T", "t", "tie"), Triple("D", "d", "die"),
        Triple("K", "k", "kite"), Triple("G", "g", "guy"), Triple("CH", "ch", "chai"), Triple("JH", "j", "jar"),
        Triple("F", "f", "fine"), Triple("V", "v", "vine"), Triple("TH", "th", "thin"), Triple("DH", "th", "this"),
        Triple("S", "s", "sun"), Triple("Z", "z", "zoo"), Triple("SH", "sh", "shy"), Triple("ZH", "zh", "measure"),
        Triple("HH", "h", "high"), Triple("M", "m", "my"), Triple("N", "n", "nine"), Triple("NG", "ng", "sing"),
        Triple("L", "l", "lie"), Triple("R", "r", "rye"), Triple("W", "w", "why"), Triple("Y", "y", "yes"),
    )

    /** Between which vowels each stage sings the consonants. Stage 1 is ah; later ones front and round the mouth. */
    private val CARRIERS = listOf(Triple("AA", "ah", 1), Triple("IY", "ee", 2), Triple("UW", "oo", 3))

    val all: List<Prompt> = buildList {
        for ((sound, sung, like) in VOWELS) add(Prompt("v-${sound.lowercase()}", 1, sung, like, listOf(sound)))
        for ((vowel, v, stage) in CARRIERS) {
            for ((sound, c, like) in CONSONANTS) {
                // NG can't start a syllable, so it closes the first vowel instead.
                val sung = if (sound == "NG") "$v$c-$v" else "$v-$c$v"
                add(Prompt("${vowel.lowercase()}-${sound.lowercase()}", stage, sung, like, listOf(vowel, sound, vowel), c))
            }
        }
    }

    /** How a vowel is written to be sung: "AA" is "ah". */
    fun sungOf(sound: String): String = VOWELS.firstOrNull { it.first == sound }?.second ?: sound.lowercase()

    /** The two vowels a diphthong moves between, as sung: "eye" is ah to ee. */
    val DIPHTHONG_PARTS = mapOf(
        "EY" to ("eh" to "ee"), "AY" to ("ah" to "ee"), "AW" to ("ah" to "oo"),
        "OY" to ("oh" to "ee"), "OW" to ("oh" to "oo"),
    )

    val stages: Int get() = all.maxOf { it.stage }

    fun inStage(stage: Int): List<Prompt> = all.filter { it.stage == stage }
    fun byId(id: String): Prompt? = all.firstOrNull { it.id == id }
}
