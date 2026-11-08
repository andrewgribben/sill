## Learned User Preferences

- Run several Sill windows on the iPad, each showing a different Mac app edge to edge, as if that app were native on the iPad.
- Magic Keyboard two-finger scrolling must move the streamed Mac window the way a finger scroll does.
- Pinch open fills only the window under the fingers: that window's bar hides and the Mac window sizes to the picture. Pinch closed brings that window's bar back. The Desktop keeps the bar.
- Pinch to Fill the Screen, in Settings › This iPad and in Settings › This phone on Android, only enables the gesture. It must not itself switch every window between the bar and edge to edge.
- The Mac window should match the iPad window from the first picture, including after a pinch to the edges, without first moving the font-size control.
- Two-finger double-tap on the picture may show or hide the on-screen keyboard when no hardware keyboard is attached, and should do nothing while a Magic Keyboard is attached.

## Learned Workspace Facts

- A Magic Keyboard trackpad pan reports each movement on its own. Posting only the change since the last reading sends almost nothing while `in.scroll` still climbs. Each reading is taken whole, then cleared. The scroll recognizers do not require a touch.
- The host's one-second line records what was posted: `in.scroll` (events), `in.scrollY` (vertical points), `in.scroll0` (events whose wheels rounded to 0), and `in.scrollMiss` only when a point was posted outside the streamed window. A finger scroll can land away from the Mac pointer and still move the window.
- Each iPad window has its own connection. A second window can ask for its own Mac window; the first stays on the shared stream, and an older Mac keeps that shared stream. A second window is opened with ⌘N or Settings › This iPad › New Window. Virtual Display keeps a covered Mac window painting.
- The first viewport is sent at 1×. Leaving the scale unset leaves the Mac window alone until the font-size control is touched.
- Pinch to Fill is per window. `StreamClient.pinchToFillKey` only allows the gesture; turning it off restores the bars. Two-finger scrolling stays a scroll until the fingers have clearly spread or closed. A three-finger pinch stays the Mac gesture.
- Double-Tap for the Keyboard (`StreamClient.keyboardDoubleTapKey`, `Sill.keyboardDoubleTap`) is off until turned on. It is fingers only, and a hardware keyboard is left alone.
- The Android client follows those same pinch and keyboard rules. `Sill.pinchToFill` is on unless it is "0"; `Sill.keyboardDoubleTap` is off until it is "1". Pinch open is 1.28 and pinch closed is 0.78, one decision per gesture, and only while a window streams.
