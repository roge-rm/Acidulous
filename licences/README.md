# Licence texts the app ships

The About window shows these, and a build stages them into the APK's assets -
see the `stageLicences` task in `app/build.gradle.kts`. They are staged rather
than copied in, so a licence on screen cannot drift from the one in the tree.

| Shown as | Comes from | For |
|---|---|---|
| GNU GPL v3 | `/LICENSE` | the app itself, GPLv3-or-later |
| GNU LGPL v2 | `/app/src/main/cpp/third_party/lame/COPYING` | LAME, the MP3 encoder |
| Apache 2.0 | `Apache-2.0.txt` here | Oboe, which comes from Maven and ships no licence text in its AAR |
| GNU GPL v2 | `GPL-2.0.txt` here | Ableton Link, taken under its GPLv2-or-later option. Its own copy (`third_party/link/GNU-GPL-v2.0.md`) is the same licence written in markdown, which reads badly as plain text |
| Boost 1.0 | `/app/src/main/cpp/third_party/asio/LICENSE_1_0.txt` | asio, which Link uses to reach the network |

`Apache-2.0.txt` and `GPL-2.0.txt` are the verbatim texts as published by the
Apache Software Foundation and the Free Software Foundation. Nothing here is
ours to edit.
