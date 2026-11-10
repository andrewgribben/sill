// DrawerHighlight (iOSClient/DrawerHighlight.swift): the Apps drawer's keyboard-highlighted row, an
// index into the filtered list (open apps then closed apps) or none. What a search's result count
// gives it, and what Up/Down do to it, clamped. Pure: Foundation only.
//   swiftc -O iOSClient/DrawerHighlight.swift Tests/checks/drawer-highlight/main.swift -o check && ./check
import Foundation

var failures = 0, checks = 0
func check(_ ok: Bool, _ what: @autoclosure () -> String) {
    checks += 1
    if ok { print("ok   \(what())") } else { failures += 1; print("FAIL \(what())") }
}

// MARK: - afterFilterChange

check(DrawerHighlight.afterFilterChange(count: 0) == nil, "no results: none highlighted")
check(DrawerHighlight.afterFilterChange(count: 1) == 0, "exactly one result: it is highlighted")
check(DrawerHighlight.afterFilterChange(count: 2) == nil, "several results: none highlighted")
check(DrawerHighlight.afterFilterChange(count: 50) == nil, "many results: none highlighted")

// MARK: - move

// From none, both Down and Up land on the first row.
check(DrawerHighlight.move(.down, from: nil, count: 5) == 0, "down from none goes to the first row")
check(DrawerHighlight.move(.up, from: nil, count: 5) == 0, "up from none goes to the first row")
// An empty list highlights nothing, whatever the key or the starting point.
check(DrawerHighlight.move(.down, from: nil, count: 0) == nil, "down with nothing to highlight stays none")
check(DrawerHighlight.move(.down, from: 2, count: 0) == nil, "down on an emptied list falls back to none")
// Clamped at both ends.
check(DrawerHighlight.move(.down, from: 3, count: 5) == 4, "down moves one row")
check(DrawerHighlight.move(.down, from: 4, count: 5) == 4, "down at the last row stays put")
check(DrawerHighlight.move(.up, from: 1, count: 5) == 0, "up moves one row")
check(DrawerHighlight.move(.up, from: 0, count: 5) == 0, "up at the first row stays put")

// 2,000 random walks of up/down against a shrinking and growing count stay in bounds.
var seed: UInt64 = 0xA17
func next() -> UInt64 { seed = seed &* 6364136223846793005 &+ 1; return seed }
for _ in 0..<2_000 {
    var current: Int? = nil
    var count = Int(next() % 12)
    for _ in 0..<40 {
        let key: DrawerHighlight.Key = next() % 2 == 0 ? .down : .up
        current = DrawerHighlight.move(key, from: current, count: count)
        if let current {
            check(current >= 0 && current < count, "in bounds (count \(count), got \(current))")
        } else {
            check(count == 0, "none only with an empty list (count \(count))")
        }
        count = Int(next() % 12)   // the search text changed the result count before the next key
        if let c = current, c >= count { current = nil } // AppDrawer resets on a filter change; model that
    }
}

print("\(checks - failures)/\(checks) passed")
exit(failures == 0 ? 0 : 1)
