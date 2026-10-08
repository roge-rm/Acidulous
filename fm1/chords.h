/* Reading lyric chords on the FM-1's 27 keys: see fm1/CHORDS.md.
 *
 * Plain C with no allocation, for the firmware as much as the simulator. A
 * chord is a mask of the note keys held, bit n for note key n (F3 = 0 ..
 * G5 = 26), as SLOOP's input HAL numbers them. Each bank is read on its own
 * through the tables fm1/chords.py writes into chord_tables.h. */
#pragma once
#include <stdint.h>
#include <string.h>
#include "chord_tables.h"

#define CHORD_START_SHIFT 0   /* F3..B3, 7 keys: S T K P W H R */
#define CHORD_VOWEL_SHIFT 7   /* C4..E4, 5 keys: A O @ E U */
#define CHORD_END_SHIFT 12    /* F4..D#5, 11 keys: * F R P B L G S T Z D */
#define CHORD_START_MASK 0x7Fu
#define CHORD_VOWEL_MASK 0x1Fu
#define CHORD_END_MASK 0x7FFu
#define CHORD_END_R (1u << 2) /* -R, G4 */
#define CHORD_ER (1u << 5)    /* the vowel bank's ER: a schwa that takes the -R */
#define CHORD_SCHWA 0x04u     /* @ */
#define CHORD_Y_SCHWA 0x14u   /* @U */

/* The four keys above the chords, each pressed on its own. */
enum { CHORD_HOLD = 23, CHORD_REST = 24, CHORD_DELETE = 25, CHORD_PLAY = 26 };
#define CHORD_COMMANDS (0xFu << CHORD_HOLD)

static const char *chord_find(const chord_piece_t *t, int n, uint32_t keys)
{
    int i;
    for (i = 0; i < n; i++)
        if (t[i].keys == keys)
            return t[i].sounds;
    return 0;
}

/* The sounds of a chord, as Diction's phone names separated by spaces, into
 * out (at most cap bytes with the terminator). Returns 0, and out empty, for
 * a chord no table has, or one that holds a command key. */
static int chord_read(uint32_t chord, char *out, int cap)
{
    uint32_t start = (chord >> CHORD_START_SHIFT) & CHORD_START_MASK;
    uint32_t vowel = (chord >> CHORD_VOWEL_SHIFT) & CHORD_VOWEL_MASK;
    uint32_t end = (chord >> CHORD_END_SHIFT) & CHORD_END_MASK;
    const char *part[3] = {0, 0, 0};
    int i, n = 0;
    out[0] = 0;
    if (chord & CHORD_COMMANDS || !chord)
        return 0;
    /* A schwa with -R is ER, and the -R is the vowel's. */
    if ((vowel == CHORD_SCHWA || vowel == CHORD_Y_SCHWA) && end & CHORD_END_R) {
        vowel |= CHORD_ER;
        end &= ~CHORD_END_R;
    }
    if (start && !(part[0] = chord_find(CHORD_START, CHORD_START_COUNT, start)))
        return 0;
    if (vowel && !(part[1] = chord_find(CHORD_VOWEL, CHORD_VOWEL_COUNT, vowel)))
        return 0;
    if (end && !(part[2] = chord_find(CHORD_END, CHORD_END_COUNT, end)))
        return 0;
    for (i = 0; i < 3; i++) {
        int len;
        if (!part[i])
            continue;
        len = (int)strlen(part[i]);
        if (n + (n > 0) + len + 1 > cap)
            return 0;
        if (n > 0)
            out[n++] = ' ';
        memcpy(out + n, part[i], (size_t)len);
        n += len;
        out[n] = 0;
    }
    return n > 0;
}
