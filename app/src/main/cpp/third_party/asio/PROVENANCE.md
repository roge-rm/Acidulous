# asio 1.36.0, vendored

Not a dependency of this app so much as one of Ableton Link's: Link is
header-only and reaches the network through asio's UDP sockets and timers.
It is here because Link is.

    https://github.com/chriskohlhoff/asio  tag asio-1-36-0
    the submodule Link-4.0 pins; taken from that clone by tools/vendor_link.sh

## What is here

`include/` whole and unedited, which is all asio is - it is used
header-only, with `ASIO_STANDALONE` set so it never looks for Boost. Not the
subset Link happens to reach today: asio's headers include each other freely,
the set differs between platforms, and a header missing from a future build
is a worse problem than four megabytes of text that never compiles.

## Licence

The **Boost Software License 1.0** - see `LICENSE_1_0.txt`. Permissive, and
compatible with the GPL.
