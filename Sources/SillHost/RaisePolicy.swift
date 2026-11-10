import Foundation
import StreamProtocol

/// The pure part of `StreamCoordinator.raiseIfInteracting`: whether an input event is ever a
/// reason to activate an app and raise its window, and if so whether it needs the app active and
/// the window key (a click, a key down, text) or only the window uncovered (a scroll) — and the
/// per-subject throttle (one check per 0.5 s, one activation attempt per 2 s) that keeps the
/// shared stream's window and each lane's own from skipping a needed raise because the other
/// checked recently. Kept free of AppKit/Accessibility and of `StreamCoordinator`'s own state, so
/// it can be tested on its own (Tests/checks/raise-policy); `StreamCoordinator` is the only caller.
enum RaisePolicy {
    /// nil: `event` is never a reason to raise or activate anything here (a key or button up, a
    /// scroll gesture not yet begun). Otherwise whether it needs the app active and the window
    /// key, as opposed to only uncovered.
    static func needsFocus(for event: InputEvent) -> Bool? {
        switch event {
        case .pointer(let action, _, _):
            return (action == .leftDown || action == .rightDown) ? true : nil
        case .scroll:
            return false
        case .scrollGesture(let phase, _, _):
            return phase == .began ? false : nil
        case .key(_, let down, _):
            return down ? true : nil
        case .text:
            return true
        }
    }

    /// One raise check per 0.5 s and one activation attempt per 2 s, each judged per key on its
    /// own: `StreamCoordinator`'s `RaiseKey` keeps the shared stream's `active` window and each
    /// lane's own window (keyed by its connection) from sharing a clock, so switching quickly
    /// between a device's shared window and a lane never makes one skip a needed raise because
    /// the other just checked. A struct, not a class: each `StreamCoordinator` owns one, mutated
    /// in place like its other state.
    struct Throttle {
        static let checkInterval: TimeInterval = 0.5
        static let activationInterval: TimeInterval = 2
        private var lastCheck: [AnyHashable: CFAbsoluteTime] = [:]
        private var lastActivation: [AnyHashable: CFAbsoluteTime] = [:]

        /// True at most once per `checkInterval` for a given key; marks it as checked now.
        mutating func shouldCheck<Key: Hashable>(_ key: Key, now: CFAbsoluteTime) -> Bool {
            guard now - (lastCheck[key] ?? 0) > Self.checkInterval else { return false }
            lastCheck[key] = now
            return true
        }

        /// True at most once per `activationInterval` for a given key; marks it as attempted now.
        mutating func shouldActivate<Key: Hashable>(_ key: Key, now: CFAbsoluteTime) -> Bool {
            guard now - (lastActivation[key] ?? 0) > Self.activationInterval else { return false }
            lastActivation[key] = now
            return true
        }

        /// A lane (or any other subject) that is gone: nothing left to raise or activate it, so
        /// its clocks are dropped rather than left to linger forever in these dictionaries.
        mutating func forget<Key: Hashable>(_ key: Key) {
            lastCheck[key] = nil
            lastActivation[key] = nil
        }
    }
}
