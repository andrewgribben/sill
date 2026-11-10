import Foundation

// RaisePolicy (Sources/SillHost/RaisePolicy.swift): the pure part of StreamCoordinator's
// raiseIfInteracting. Asserts, not just prints, so a compile that silently lost the logic still
// fails loudly.

var failures = 0, checks = 0
func check(_ ok: Bool, _ what: @autoclosure () -> String, line: Int = #line) {
    checks += 1
    if !ok { failures += 1; print("FAIL (line \(line)): \(what())") }
}

func pointer(_ action: PointerAction) -> InputEvent { .pointer(action, x: 0.5, y: 0.5) }
func key(_ down: Bool) -> InputEvent { .key(hidUsage: 0x04, down: down, modifiers: 0) }
let scroll = InputEvent.scroll(x: 0.5, y: 0.5, dx: 0, dy: 1)
func scrollGesture(_ phase: ScrollPhase) -> InputEvent { .scrollGesture(phase, x: 0.5, y: 0.5) }
let text = InputEvent.text("a")

// needsFocus: a click needs the app active and the window key; a scroll (routed to the window
// under the cursor whatever is active) and a scroll gesture's `began` only need it uncovered; a
// key or button up, and the rest of a scroll gesture's phases, are never a reason to raise or
// activate anything.
check(RaisePolicy.needsFocus(for: pointer(.leftDown)) == true, "a left-down needs focus")
check(RaisePolicy.needsFocus(for: pointer(.rightDown)) == true, "a right-down needs focus")
check(RaisePolicy.needsFocus(for: pointer(.leftUp)) == nil, "a left-up is not a reason to raise")
check(RaisePolicy.needsFocus(for: pointer(.move)) == nil, "a move is not a reason to raise")
check(RaisePolicy.needsFocus(for: scroll) == false, "a scroll only needs uncovering")
check(RaisePolicy.needsFocus(for: scrollGesture(.began)) == false, "a scroll gesture's began only needs uncovering")
check(RaisePolicy.needsFocus(for: scrollGesture(.ended)) == nil, "a scroll gesture's ended is not a reason to raise")
check(RaisePolicy.needsFocus(for: scrollGesture(.momentumBegan)) == nil, "momentumBegan is not a reason to raise")
check(RaisePolicy.needsFocus(for: scrollGesture(.momentumEnded)) == nil, "momentumEnded is not a reason to raise")
check(RaisePolicy.needsFocus(for: key(true)) == true, "a key down needs focus")
check(RaisePolicy.needsFocus(for: key(false)) == nil, "a key up is not a reason to raise")
check(RaisePolicy.needsFocus(for: text) == true, "text needs focus")

// Throttle: per key, independently. The regression this guards (#lane-input-focus): the shared
// stream's window (`.shared`) and a lane's own (keyed by its connection) must never share a
// clock, or switching quickly between a device's shared window and a lane would make one skip a
// needed raise because the other just checked.
enum Key: Hashable { case shared, laneA, laneB }

// A base far from 0: `shouldCheck`/`shouldActivate` compare against a stored 0 for a key never
// seen before, so a real first call (CFAbsoluteTimeGetCurrent() is always far from the epoch)
// always runs; starting the test at 0 would make that look throttled by accident.
let base: CFAbsoluteTime = 1_000
var t = RaisePolicy.Throttle()
check(t.shouldCheck(Key.shared, now: base) == true, "the first check for a key always runs")
check(t.shouldCheck(Key.shared, now: base + 0.1) == false, "a second check inside 0.5 s is throttled")
check(t.shouldCheck(Key.shared, now: base + 0.5) == false, "exactly at the boundary is still throttled (strict >)")
check(t.shouldCheck(Key.shared, now: base + 0.51) == true, "a check past 0.5 s runs again")

// A lane's own key is judged on its own clock: it is not throttled by `.shared`'s check just now,
// and `.shared` is not throttled by the lane's.
check(t.shouldCheck(Key.laneA, now: base + 0.51) == true, "a different key's first check is never throttled by another's")
check(t.shouldCheck(Key.shared, now: base + 0.52) == false, "`.shared`'s own throttle still holds")
check(t.shouldCheck(Key.laneB, now: base + 0.52) == true, "a second lane's own clock is independent of the first lane's too")

// Activation attempts: one per 2 s, per key, same independence.
var a = RaisePolicy.Throttle()
check(a.shouldActivate(Key.shared, now: 10) == true, "the first activation attempt always runs")
check(a.shouldActivate(Key.shared, now: 11) == false, "a second attempt inside 2 s is throttled")
check(a.shouldActivate(Key.laneA, now: 11) == true, "a lane's activation attempt is not throttled by `.shared`'s")
check(a.shouldActivate(Key.shared, now: 12.01) == true, "`.shared`'s own attempt runs again past 2 s")

// `forget` drops a key's clocks (a lane that is gone): its next check and activation attempt run
// at once, as a brand new key's would, with no residue from before it was forgotten.
var f = RaisePolicy.Throttle()
_ = f.shouldCheck(Key.laneA, now: base)
_ = f.shouldActivate(Key.laneA, now: base)
f.forget(Key.laneA)
check(f.shouldCheck(Key.laneA, now: base + 0.01) == true, "forgetting a key resets its check clock")
check(f.shouldActivate(Key.laneA, now: base + 0.01) == true, "forgetting a key resets its activation clock")

print(checks - failures, "of", checks, "checks passed")
if failures > 0 { exit(1) }
