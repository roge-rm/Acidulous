#pragma once
#include <engine/core/Sample.h>
#include <engine/format/AudioSink.h>
#include <engine/format/Decoded.h>
#include <memory>
#include <string>

// One door for every audio file that comes in.
//
// The app writes four formats and used to read one, which is a thing it did
// to people rather than for them: a stem exported yesterday could not be
// loaded back today. This reads all four, by looking at what the file
// actually is rather than at what it is called - an extension is a claim and
// a magic number is evidence, and a break downloaded from the internet is as
// likely as not to be named wrongly.
//
// **Conversion happens at the door.** Everything past this point still sees a
// SampleData that came from a WAV, because import decodes once and writes a
// WAV into the sample folder. Machines and harnesses never learn a second
// format, and nothing is decoded twice.
namespace acidulous {

/** What this file really is, from its first bytes. */
AudioFormat sniff(const std::string &path);

/** The name to show a player: "WAV", "AIFF", "FLAC", "MP3". */
const char *formatName(AudioFormat f);

/**
 * A name for a file we can recognise but cannot read, or null.
 *
 * Worth having because the alternative is a lie. An m4a's payload is full of
 * byte pairs that look like an MPEG frame sync, so a scan for one finds it,
 * and the player is then told their file is a broken mp3 rather than an m4a
 * this app does not read. Checked before the mp3 scan for that reason.
 */
const char *foreignKind(const std::string &path);

/**
 * Decode any of the four. [targetRate] behaves as in WavReader::read.
 *
 * Null on failure with [error] set - and the error says the format it decided
 * on, because "not a RIFF/WAVE file" is an unhelpful thing to be told about
 * an mp3 that turned out to be truncated.
 *
 * [maxSeconds] is how much of a long file to take - kMaxDecodeSeconds for a
 * pad sample, kMaxSliceSeconds for the one file a whole machine slices.
 */
std::unique_ptr<SampleData> decodeAudio(const std::string &path, int32_t targetRate, std::string &error,
                                        int32_t maxSeconds = kMaxDecodeSeconds);

} // namespace acidulous
