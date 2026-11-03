# Dictionary and audio jump: known gaps

Written 2026-11-03, after the saved word jump work landed.

## What the jump does now

Tapping a word on the book details screen opens the reader at the saved place. When a word is
saved, the audio clip that covers the selection is recorded with it, so a jump can also move a
loaded audiobook to that place.

Words saved before this change carry no clip id. For those, the page still moves and the audio
stays where it was. That is deliberate: a guessed clip can land in the wrong place, and the
clip listener then saves that wrong place as your reading position.

## Two known gaps in scroll mode

Both only affect paused scroll mode, and only within a single chapter.

1. Tapping a bookmark for a place you visited recently does not scroll to it. The saved position
   updates, the page does not move.
2. Tapping return to previous position does not scroll, for the same reason.

The cause is the history of recently reported positions. It exists so the reader does not scroll
back to its own echoes while you read, but it also swallows a deliberate jump to a position that
is still in that history.

The fix is to treat a position as an echo only while it is actually pending, and to drop it from
the history once it has come back.

## Checked on the emulator

- Saving a word with a trailing full stop stores it without the punctuation, and it appears last
  in the saved words list.
- Tapping that word opens the reader at the exact copy it was saved from, with the wider dot
  spacing at that copy.
- A jump does move the audio. With the audiobook paused at 9:21, tapping the saved word put the
  player back at 0:44, the clip that covers that word.

## Not verified on a device

- That the same-clip jump scrolls in paused scroll mode.
