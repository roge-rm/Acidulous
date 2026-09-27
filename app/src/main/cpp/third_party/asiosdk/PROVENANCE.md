# ASIO SDK 2.3.4, vendored in part

Steinberg's ASIO SDK, for playing and recording through an interface's ASIO
driver on Windows (platform/desktop/drivers/Asio.cpp). Only the host side is
here: the interface definitions (`common/asio.h`, `asiosys.h`, `iasiodrv.h`),
the C API over the loaded driver (`common/asio.cpp`), and the helpers that list
and load the installed drivers (`host/asiodrivers.*`, `host/ginclude.h`,
`host/pc/asiolist.*`). The sample driver, the documentation PDFs and the logo
artwork are left behind. Unchanged.

    https://download.steinberg.net/sdk_downloads/ASIO-SDK_2.3.4_2025-10-15.zip
    sha256 d5ebf0c20dd2c5f43771fd0c1418f4b361bf52434ee670097cfa6b3a335e2eca

## Licence

`LICENSE.txt` is Steinberg's: the SDK is dual-licensed, under the Steinberg
ASIO License or the GNU GPL version 3. Acidulous takes it under the **GPL v3**,
which it is itself released under. The files under `host/` carry a BSD-style
licence of their own in their headers.

The GPL option does not require using the ASIO name or logo. Where the app
does use them, Steinberg's usage guidelines apply (in the SDK, not vendored
here).
