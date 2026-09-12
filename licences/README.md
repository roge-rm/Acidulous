# Licence texts the app ships

The About window shows these, and a build stages them into the APK's assets -
see the `stageLicences` task in `app/build.gradle.kts`. They are staged rather
than copied in, so a licence on screen cannot drift from the one in the tree.

| Shown as | Comes from | For |
|---|---|---|
| GNU GPL v3 | `/LICENSE` | the app itself, GPLv3-or-later |
| GNU LGPL v2 | `/app/src/main/cpp/third_party/lame/COPYING` | LAME, the MP3 encoder |
| Apache 2.0 | `Apache-2.0.txt` here | Oboe, which comes from Maven and ships no licence text in its AAR |

`Apache-2.0.txt` is the verbatim text as published by the Apache Software
Foundation. Nothing here is ours to edit.
