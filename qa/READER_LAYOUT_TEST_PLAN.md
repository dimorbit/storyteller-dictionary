# Scroll Layout PR Test Plan

Manual QA checklist for the scroll-layout reader changes, especially same-resource navigation, scroll-end locator updates, and active bookmark detection.

## Scope

- iOS and Android EPUB reader behavior in scroll mode.
- Regression coverage for paginated mode where the same native reader code paths are shared.
- Reader position updates, bookmark/highlight/TOC navigation, active bookmark state, and readaloud follow behavior.

## Merge Criteria

- Manual scrolling in scroll mode does not auto-center or snap after the scroll ends when audio is not playing.
- Explicit same-chapter navigation from bookmarks, highlights, and TOC anchors scrolls to the requested target.
- Cross-chapter navigation still changes resources and lands at the requested target.
- The bookmark toolbar action only treats bookmarks visible in the current viewport as active.
- Readaloud playback still follows/highlights the current fragment in scroll mode.
- In scroll layout, side taps only move to the previous/next chapter at the beginning/end of the current chapter.
- Paginated layout behavior is not regressed.

## Setup

- Use at least one EPUB with long chapters and multiple anchors/fragments.
- Add at least three bookmarks in the same chapter: one near the top, one near the middle, and one near the bottom.
- Add at least one highlight in the same chapter.
- Use a book with TOC entries that point into the same chapter if available.
- Test with audio paused/not playing first. Then repeat the relevant readaloud cases while audio is playing.

## Critical Tests

### Manual Scroll Does Not Auto-Center

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Manually scroll within a long chapter and lift your finger.
- [ ] Wait 1-2 seconds.
- [ ] Expected: the reader remains where the user left it. It should not snap, center, or perform a second automatic scroll.

### Same-Chapter Bookmark Navigation

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Open the bookmarks list.
- [ ] Tap a bookmark in the current chapter that is far above or below the current viewport.
- [ ] Expected: the reader scrolls to that bookmark once and then stays put.
- [ ] Expected: the saved reader position updates to the selected bookmark location.

### Same-Chapter Highlight Navigation

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Open the highlights list.
- [ ] Tap a highlight in the current chapter that is far above or below the current viewport.
- [ ] Expected: the reader scrolls to that highlight once and then stays put.
- [ ] Expected: the saved reader position updates to the selected highlight location.

### Same-Chapter TOC Navigation

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Open the table of contents.
- [ ] Tap a TOC entry that resolves to an anchor in the current chapter.
- [ ] Expected: the reader scrolls to that anchor once and then stays put.
- [ ] Expected: the saved reader position updates to the selected TOC location.

### Cross-Chapter Navigation

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Tap a bookmark, highlight, or TOC item in a different chapter.
- [ ] Expected: the reader changes chapter/resource and lands at the requested target.
- [ ] Expected: no extra snap or second automatic scroll happens after landing.

### Scroll Layout Side Taps Within Chapter

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Scroll to the middle of a chapter.
- [ ] Tap the left side of the reader.
- [ ] Expected: the reader does not move to the previous chapter.
- [ ] Expected on Android: the reader UI toggles the same way a middle tap does.
- [ ] Tap the right side of the reader.
- [ ] Expected: the reader does not move to the next chapter.
- [ ] Expected on Android: the reader UI toggles the same way a middle tap does.

### Scroll Layout Side Taps At Chapter Start

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Navigate to the beginning of a chapter that has a previous chapter.
- [ ] Tap the left side of the reader.
- [ ] Expected: the reader moves to the previous chapter.
- [ ] Expected: the previous chapter lands at a sensible boundary position and remains in scroll layout.
- [ ] Return to the beginning of the original chapter.
- [ ] Tap the right side of the reader.
- [ ] Expected: the reader does not move to the next chapter unless the whole chapter fits in the viewport and is also at its end.

### Scroll Layout Side Taps At Chapter End

- [ ] Enable scroll layout.
- [ ] Ensure audio is not playing.
- [ ] Navigate to the end of a chapter that has a next chapter.
- [ ] Tap the right side of the reader.
- [ ] Expected: the reader moves to the next chapter.
- [ ] Expected: the next chapter lands at a sensible boundary position and remains in scroll layout.
- [ ] Return to the end of the original chapter.
- [ ] Tap the left side of the reader.
- [ ] Expected: the reader does not move to the previous chapter unless the whole chapter fits in the viewport and is also at its beginning.

### Scroll Layout Short Chapter Side Taps

- [ ] Enable scroll layout.
- [ ] Navigate to a short chapter where the full chapter fits in the viewport.
- [ ] Tap the left side of the reader.
- [ ] Expected: the reader moves to the previous chapter if one exists.
- [ ] Return to the short chapter.
- [ ] Tap the right side of the reader.
- [ ] Expected: the reader moves to the next chapter if one exists.

### Active Bookmark Detection

- [ ] Enable scroll layout.
- [ ] Add multiple bookmarks in the same chapter.
- [ ] Scroll so only one bookmarked location is visible.
- [ ] Expected: the bookmark toolbar item shows only that visible bookmark as active.
- [ ] Tap the bookmark toolbar item.
- [ ] Expected: only the visible active bookmark is deleted.
- [ ] Expected: bookmarks elsewhere in the same chapter are not deleted.

### Multiple Visible Bookmarks

- [ ] Enable scroll layout.
- [ ] Create two bookmarks close enough that both are genuinely visible in the viewport.
- [ ] Tap the bookmark toolbar item.
- [ ] Expected: verify whether the current product behavior is acceptable.
- [ ] Note: the toolbar currently receives a list of active bookmarks, so if two bookmarked anchors are actually visible, deleting both may still be expected by the current implementation. If that is not desired, fix `BookmarkItem` semantics separately.

### Readaloud Follow Behavior

- [ ] Enable scroll layout.
- [ ] Start readaloud playback.
- [ ] Let playback advance across several fragments.
- [ ] Expected: the active readaloud highlight follows playback.
- [ ] Expected: the reader may auto-scroll to keep the playing fragment visible/centered.
- [ ] Pause playback.
- [ ] Manually scroll.
- [ ] Expected: auto-centering stops while paused/not playing.

## Regression Tests

### Paginated Layout Bookmark Navigation

- [ ] Switch to paginated layout.
- [ ] Tap same-chapter and cross-chapter bookmarks.
- [ ] Expected: navigation works as it did before the scroll-layout changes.

### Paginated Layout Side Taps

- [ ] Switch to paginated layout.
- [ ] Tap the left side of the reader.
- [ ] Expected: the reader moves backward by page/chapter according to existing paginated behavior.
- [ ] Tap the right side of the reader.
- [ ] Expected: the reader moves forward by page/chapter according to existing paginated behavior.
- [ ] Expected: paginated side taps are not restricted to chapter boundaries.

### Paginated Layout Active Bookmark State

- [ ] Switch to paginated layout.
- [ ] Add multiple bookmarks in the same chapter on different pages.
- [ ] Navigate page by page.
- [ ] Expected: only bookmarks on the current page are active.
- [ ] Expected: deleting an active bookmark does not delete bookmarks on other pages.

### Layout Switching

- [ ] Start in paginated layout at a saved position.
- [ ] Switch to scroll layout.
- [ ] Expected: the reader lands near the saved position.
- [ ] Scroll manually and wait.
- [ ] Expected: no unexpected auto-centering while not playing.
- [ ] Switch back to paginated layout.
- [ ] Expected: the reader remains usable and navigates to a sensible position/page.

### Reopen Book

- [ ] In scroll layout, manually scroll to a new position.
- [ ] Leave the reader and reopen the same book.
- [ ] Expected: the reader restores to the saved position.
- [ ] Expected: after restore, it does not keep auto-scrolling while not playing.

### Selection And Highlight Creation

- [ ] Enable scroll layout.
- [ ] Select text and create a highlight.
- [ ] Navigate away, then tap the new highlight from the highlights list.
- [ ] Expected: the reader scrolls to the highlight.
- [ ] Expected: selection/highlight UI still appears in the correct place.

### Chapter Boundaries

- [ ] Test bookmarks/highlights near the very top of a chapter.
- [ ] Test bookmarks/highlights near the very bottom of a chapter.
- [ ] Expected: scroll targets clamp naturally and do not overscroll or bounce into a bad resting position.
- [ ] In scroll layout, verify side taps at the top and bottom boundary follow the dedicated side-tap cases above.

### Font And Theme Changes

- [ ] Enable scroll layout.
- [ ] Change font size, line height, alignment, font family, and color theme.
- [ ] Expected: the reader remains in scroll layout.
- [ ] Expected: bookmark/highlight/TOC navigation still scrolls to the requested location after reflow.
- [ ] Expected: no unexpected auto-centering while not playing.

### Orientation Or Size Class Changes

- [ ] Enable scroll layout.
- [ ] Rotate the device or test a different iPad split size if available.
- [ ] Manually scroll, then tap same-chapter and cross-chapter bookmarks.
- [ ] Expected: viewport-based active bookmark detection remains correct.
- [ ] Expected: no unexpected auto-centering while not playing.

### Rapid Navigation

- [ ] Enable scroll layout.
- [ ] Quickly tap several bookmarks/highlights/TOC items in sequence.
- [ ] Expected: the final tapped item wins.
- [ ] Expected: the reader does not get stuck in a repeated scroll loop.

## Android Parity Checks

Run the same critical tests on Android before merging if Android is in scope for the PR. Pay special attention to:

- [ ] Same-chapter bookmark/highlight/TOC navigation in scroll layout.
- [ ] Manual scroll end not auto-centering while not playing.
- [ ] Active bookmark detection with multiple bookmarks in the same chapter.
- [ ] Left/right side taps in scroll layout only navigate at chapter boundaries.
- [ ] Android side taps in scroll layout still toggle reader UI when they do not navigate.
- [ ] Readaloud follow behavior while playing.
- [ ] Paginated layout regression behavior.

## Known Risk Areas

- Same-resource navigation and normal scroll-position persistence both update the `locator` prop. If unexpected auto-scroll returns, verify that native code can distinguish explicit bookmark/highlight/TOC navigation from a prop echo of a native `onLocatorChange`.
- Anchor-only locators and progression-only locators use different fallback paths. Test both if possible.
- Zero-height or empty anchor elements may rely on progression fallback rather than element-rect visibility.
- Multiple active bookmarks in a single viewport may still be deleted together by design unless `BookmarkItem` is changed.
- Scroll boundary detection uses the active document scroll position. Re-test after typography, theme, and orientation changes because reflow changes the effective top/end thresholds.
