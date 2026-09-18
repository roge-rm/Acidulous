#pragma once
#include <engine/core/Sample.h>
#include <engine/format/AudioSink.h>
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
 * Decode any of the four. [targetRate] behaves as in WavReader::read.
 *
 * Null on failure with [error] set - and the error says the format it decided
 * on, because "not a RIFF/WAVE file" is an unhelpful thing to be told about
 * an mp3 that turned out to be truncated.
 */
std::unique_ptr<SampleData> decodeAudio(const std::string &path, int32_t targetRate, std::string &error);

} // namespace acidulous
