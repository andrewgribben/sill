#!/bin/bash
# DrawerHighlight (iOSClient/DrawerHighlight.swift) on its own: the Apps drawer's keyboard-highlighted
# row after a search's result count changes, and after Up or Down, then 2,000 random walks against
# the bounds.
#   Tests/checks/drawer-highlight/run.sh             compile and run the check
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/../common.sh"
swiftc -O iOSClient/DrawerHighlight.swift "$here/main.swift" -o "$out/check"
"$out/check"
