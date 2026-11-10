#!/bin/bash
# RaisePolicy (Sources/SillHost/RaisePolicy.swift): the pure part of StreamCoordinator's
# raiseIfInteracting, shared by the regular (shared-stream) path and a lane's own window
# (StreamCoordinator.raiseLaneIfInteracting): which input events ever call for activating an app
# and raising its window, whether they need the app active and the window key (a click, a key
# down, text) or only the window uncovered (a scroll), and the per-key throttle (one check per
# 0.5 s, one activation attempt per 2 s) that must not let the shared stream's window and a lane's
# own, or two lanes, share a clock.
#   Tests/checks/raise-policy/run.sh             compile and run the check
#   Tests/checks/raise-policy/run.sh --mutants   one-line mutants of RaisePolicy.swift; each must fail it
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/../common.sh"
if [ "${1:-}" = "--mutants" ]; then run_mutants python3 "$here/mutants.py" "$root"; exit; fi
rm -f "$out/check"
"$here/build.sh" "$root" "$out/check"
[ -x "$out/check" ] || { echo "$name: did not compile" >&2; exit 1; }
"$out/check"
