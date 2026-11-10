# Sill Host for Windows, phase 1

What this is: a Kotlin/JVM module (`clients/windows-host`, built on `clients/core`) that makes a
Windows PC a Sill host an unmodified iPhone or iPad app can find, pair with, and stream the Desktop
from, with mouse and keyboard input. It speaks the same wire as the Mac's home door
(`docs/home-pairing-plan.md`): the same TLS, the same pairing (ask, code, QR, cancel), the same
session messages, on the same default port, 7455.

## What phase 1 does

- Persists its identity (so the Mac ID and the pairing pin stay stable across restarts), a
  recognition key and the trust list of paired devices' fingerprints in `%LOCALAPPDATA%\Sill\`.
- Opens the TLS home door on all interfaces, pairing required: `sill-pair/1` for pairing (ask,
  code, QR, cancel, five tries, a 300-second window), `sill/1` for paired devices only. An `ask`
  shows the 12-digit code in an always-on-top window on the Windows desktop, and logs the code and
  its `sill://pair` link.
- Advertises `_sill._tcp.local.` over mDNS (its own small responder, not a library), the Windows PC
  named after its computer name, so the iPhone/iPad app's existing discovery finds it.
- Serves one source, the Desktop: `WindowList` with an empty window list, `HostSettings`, a signed
  `MacInfo`, pings answered, a keyframe request forces a fresh encoder start.
- Captures the chosen monitor with ffmpeg (ddagrab + hevc_nvenc on an NVIDIA GPU, falling back to
  gdigrab + hevc_mf, then libx265), and streams it as the wire expects: parameter sets before each
  keyframe, 4-byte length-prefixed access units, non-key frames dropped when a session's send backs
  up.
- Applies pointer, click, scroll and keyboard input via `java.awt.Robot`, HID usages mapped to
  Windows virtual keys, the Mac's Command mapped to Ctrl and Option to Alt (Windows has no
  separate keys for them). A scroll lands under the finger, sub-notch remainders carried
  (`WheelCarry`), in 120ths of a notch so it scrolls smoothly. The wheel is posted to the window
  under the pointer (WM_MOUSEWHEEL, WM_MOUSEHWHEEL), not injected: on GRIBPC an injected wheel
  (SendInput or Robot) never arrived while keys did, most likely eaten by Deskflow's server hook.
- Window picking: the window list is Alt+Tab's windows (Win32 through Java 22's FFM, `Win32.kt`),
  sent again when it changes; a pick brings the window forward and captures its part of the screen
  (`CaptureRegion`), followed when it moves or resizes, nothing streams once it closes or minimises.
  Whatever covers the window shows (ffmpeg has no window grabber).
- The clipboard both ways (kind 32, text, 256 KB), as the Mac's HostClipboard: only what is copied
  while a device subscribes, a device's text sent on to the other devices, never logged.
- Wakes a sleeping display and ends a screen saver before capturing (capture is refused on both).
- Runs windowless under `javaw` with a notification area icon: who is connected, Pair a Device…,
  Show Log, Quit Sill.

## What it skips (for later phases)

Audio (the device plays AAC-ELD only, which ffmpeg's
builds here cannot make, and ffmpeg has no WASAPI loopback), images on the clipboard, the Mac's
menus, trackpad gestures, a virtual display, remote (away from home) access, and a signed
installer. A lock screen stays black: Windows refuses its capture. DPAPI protection of the stored private key is a known
shortcut; see `HostState.kt`'s comment.

## How to run it

Build: `gradlew.bat :windows-host:installDist` (from `clients/`), on the PC with a JDK 22 installed (Gradle's toolchain finds it). The installed task runs the newest
Java 22 or later it finds (Temurin 25 on GRIBPC).
That produces `clients/windows-host/build/install/windows-host/`, runnable directly with
`bin\windows-host.bat` (with a console window; the installed task runs `javaw`, without one).

To install it as a logon service, run `clients/windows-host/install.ps1` from an elevated
PowerShell after building: it copies the distribution to `%LOCALAPPDATA%\Sill\app`, registers a
Scheduled Task ("Sill Host") that starts at logon in the interactive session (capture and `Robot`
need a real desktop; they do not work from session 0 or an SSH session), stops any copy already running first, starts it, restarts it within a minute if it exits
with an error (Quit Sill exits cleanly and stays quit until the next logon), and opens
the firewall for the home door's TCP port and mDNS's UDP 5353. If not run elevated, it prints the
exact command to re-run instead of partially installing.

Logs go to stdout and `%LOCALAPPDATA%\Sill\Sill.log`.

## Known risks

- UDP 5353 coexistence with Windows' own mDNS/dnscache: `MdnsResponder` binds with
  `SO_REUSEADDR`, which should let both answer, but this was only smoke-tested, not run for a long
  period alongside Windows' own responder.
- HiDPI: `Robot`'s coordinates should be physical pixels with `-Dsun.java2d.uiScale=1`, but this
  was not verified on a real scaled Windows display.
- Verified live on an RTX 4070 SUPER (ddagrab + hevc_nvenc, about 60 fps) and from an iPad:
  pairing, picture, input, scrolling, the display's wake. A scaled display (3840 px at 125 %) took
  input at the right place.
