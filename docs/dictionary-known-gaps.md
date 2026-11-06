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

One case used to be open. Positions reported while the audiobook is playing never come back as
echoes, so they stayed pending, and a bookmark jump to one of those exact places was swallowed.
Pending positions now carry the time they were emitted and stop counting as echoes after two
seconds. A real echo comes back within a frame or two, so echo suppression is unchanged, and a
position reported during playback no longer blocks a later jump to it.

The remaining risk is the other way round: if the JavaScript side stalls for more than two
seconds before an echo returns, the reader could scroll once to a place it just reported. Two
seconds is a single constant, `PENDING_LOCATOR_ECHO_MAX_AGE_MS`, if that ever needs raising.

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
- A saved word tapped on the book details screen while that book was in scroll layout: the reader
  opened on the word itself, "impressively muscled." at the top of the page, chapter 1, page 1 of
  12 at 5%, with the wider dot spacing at that copy. So the saved word jump does scroll in scroll
  layout, not only in page layout.
- With the saved words filter sheet open, one Android back press closed the sheet and left the
  navigation menu on the Dictionary tab, instead of closing the menu and landing on Contents.
- After the echo expiry change: a bookmark made in scroll layout at page 4 of 12, three swipes
  away to page 6, then a tap on that bookmark, put the page back at page 4 with the same text at
  the top. Slow scrolling afterwards followed the finger and did not jump back.

## Saved word jump while audio plays

Written 2026-11-06.

A jump from a bookmark, highlight or saved word holds the reader on its place while a readaloud
plays. Player reports from before the jump settled do not move the reader or save a position.
While it holds, the reader view is told the audio is paused, so it turns no pages toward the
audio. The hold ends when the player reports the clip the jump asked for, when the audio pauses
or stops, or when another jump or a reopened book replaces it. It has no timer.

A place whose clip the audio cannot find stays put only until the audio is paused. After you
resume, the reader follows the audio again.
