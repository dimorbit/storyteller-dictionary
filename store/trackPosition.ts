import { type StorytellerTrack } from "@/modules/readium/src/Readium.types"

// Maps a position in the whole book (seconds from the start of the first track)
// to a track and a position inside that track. Positions past the end land at the
// end of the last track; negative positions land at the start of the first.
export function trackPositionFromTotal(
  tracks: readonly StorytellerTrack[],
  totalPosition: number,
): { track: StorytellerTrack; index: number; position: number } | null {
  if (!tracks.length) return null

  const target = Math.max(0, totalPosition)
  let trackStart = 0
  for (let index = 0; index < tracks.length; index++) {
    const track = tracks[index]!
    const duration = track.duration ?? 0
    if (target < trackStart + duration) {
      return { track, index, position: target - trackStart }
    }
    trackStart += duration
  }

  const index = tracks.length - 1
  const track = tracks[index]!
  return { track, index, position: track.duration ?? 0 }
}
