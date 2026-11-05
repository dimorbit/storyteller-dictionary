# Dictionary and audio jump: known gaps

Written 2026-11-03, after the saved word jump work landed.

## What the jump does now

Tapping a word on the book details screen opens the reader at the saved place. When a word is
saved, the audio clip that covers the selection is recorded with it, so a jump can also move a
loaded audiobook to that place.

Words saved before this change carry no clip id. For those, the page still moves and the audio
stays where it was. That is deliberate: a guessed clip can land in the wrong place, and the
clip listener then saves that wrong place as your reading position.

## Scroll mode jumps

Written 2026-11-05, after the echo fix landed.

In paused scroll mode, tapping a bookmark for a place you visited a moment ago, or tapping return
to previous position, used to update the saved position without moving the page. Both now scroll.

The cause was the list of recently reported positions. It exists so the reader does not scroll
back to its own echoes while you read, but a position stayed in that list after the reader had
already sent it back, so a deliberate jump to that place was taken for an echo and skipped. A
position now counts as an echo only while it is pending, and leaves the list as soon as it comes
back.

One case is still open. Positions reported while the audiobook is playing never come back as
echoes, so they stay pending. A bookmark jump to one of those exact places, before any newer
echo arrives, is still swallowed. The fix for it needs care: the native playing flag lags the
JavaScript one at the moment of a pause, so gating on it could make the reader scroll to its own
echo, which is worse than the gap.

## Checked on the emulator

- Saving a word with a trailing full stop stores it without the punctuation, and it appears last
  in the saved words list.
- Tapping that word opens the reader at the exact copy it was saved from, with the wider dot
  spacing at that copy.
- A jump does move the audio. With the audiobook paused at 9:21, tapping the saved word put the
  player back at 0:44, the clip that covers that word.
- A bookmark made in scroll layout, then a short scroll away, then a tap on that bookmark: the
  page came back to the bookmarked place, page 8 of 10 at 12%, and then kept following the
  finger without snapping back.

## Not verified on a device

- That the same-clip jump scrolls in paused scroll mode.
