#pragma once
#include <cstdint>
#include <fcntl.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

// A file the audio thread may read as if it were memory.
//
// **This is the answer to "what if the take is longer than RAM wants to hold".**
// The obvious answer is a prefetch ring per lane with a filler thread behind
// it, and it is the wrong one: it buys a ring, a worker, an underrun policy
// and seek latency at every scene change, all to reimplement the page cache
// badly. A take converted once to the engine's own flat format and mapped
// read-only gets all of that from the kernel, which is better at it.
//
// What it costs is virtual address space, which on 64-bit is free, and a
// *major* page fault the first time a region is touched. `willNeed` is the
// answer to that: a hint, asked for from a worker ahead of the cell that is
// about to play, which costs nothing when it is wrong.
namespace acidulous::audio {

class Mapping {
  public:
    Mapping() = default;
    ~Mapping() { close(); }
    Mapping(const Mapping &) = delete;
    Mapping &operator=(const Mapping &) = delete;

    bool open(const std::string &path) {
        close();
        const int f = ::open(path.c_str(), O_RDONLY);
        if (f < 0) return false;
        struct stat st {};
        if (::fstat(f, &st) != 0 || st.st_size <= 0) {
            ::close(f);
            return false;
        }
        void *p = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, f, 0);
        // The descriptor's job ends at the mapping: the pages outlive it, and
        // holding it open would spend one of a process's few thousand for
        // nothing.
        ::close(f);
        if (p == MAP_FAILED) return false;
        base = static_cast<const unsigned char *>(p);
        length = static_cast<size_t>(st.st_size);
        // Read straight through and do not keep it: a take is played once per
        // pass and the pages behind the playhead are worth less than the
        // pages of everything else in the app.
        ::madvise(const_cast<void *>(static_cast<const void *>(base)), length, MADV_SEQUENTIAL);
        return true;
    }

    void close() {
        if (base != nullptr) ::munmap(const_cast<void *>(static_cast<const void *>(base)), length);
        base = nullptr;
        length = 0;
    }

    /**
     * Ask for [bytes] from [offset] to be paged in, without waiting.
     *
     * A hint and nothing more. Called from a worker before a cell plays, so
     * that the first block of a region is not the block that takes a major
     * fault on the audio thread.
     */
    void willNeed(size_t offset, size_t bytes) const {
        if (base == nullptr || offset >= length) return;
        const size_t n = offset + bytes > length ? length - offset : bytes;
        ::madvise(const_cast<void *>(static_cast<const void *>(base + offset)), n, MADV_WILLNEED);
    }

    bool valid() const { return base != nullptr; }
    const unsigned char *data() const { return base; }
    size_t size() const { return length; }

  private:
    const unsigned char *base = nullptr;
    size_t length = 0;
};

} // namespace acidulous::audio
