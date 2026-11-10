import Foundation

/// The Apps drawer's keyboard highlight: an index into the currently filtered list of rows (open apps
/// then closed apps, in the order the drawer shows them), or none. Pure: Foundation only, checked in
/// Tests/checks/drawer-highlight.
enum DrawerHighlight {
    enum Key { case up, down }

    /// The search text changed: the one result highlighted so Return launches it at once, else none
    /// until an arrow is pressed.
    static func afterFilterChange(count: Int) -> Int? {
        count == 1 ? 0 : nil
    }

    /// An arrow key, clamped to the list. From none, both Down and Up land on the first row (there is
    /// nothing above it to move to). An empty list highlights nothing.
    static func move(_ key: Key, from current: Int?, count: Int) -> Int? {
        guard count > 0 else { return nil }
        guard let current else { return 0 }
        switch key {
        case .down: return min(current + 1, count - 1)
        case .up: return max(current - 1, 0)
        }
    }
}
