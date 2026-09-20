import {
  type ReadiumLink,
  type ReadiumLocator,
  type ReadiumManifest,
  type StorytellerTrack,
} from "./src/Readium.types"

/**
 * Audiobook TOC entries represent chapter/keyframe boundaries in the audio.
 * Look ahead slightly so chapter labels and track list highlights advance near
 * the boundary instead of lagging behind audible keyframe transitions.
 */
export const AUDIOBOOK_TOC_LOOKAHEAD_SECONDS = 3

function getHrefAndPosition(href: string) {
  const [hrefWithoutFragment, fragment] = href.split("#t=")
  return {
    href: hrefWithoutFragment ?? href,
    position: parseFloat(fragment ?? "0"),
  }
}

function flattenToc(toc: ReadiumLink[] | undefined): ReadiumLink[] {
  return toc?.flatMap((link) => [link, ...flattenToc(link.children)]) ?? []
}

export function buildAudiobookLocator(
  tracks: StorytellerTrack[],
  track: StorytellerTrack,
  position: number,
): ReadiumLocator {
  let prevDuration = 0
  let totalDuration = 0
  let foundCurrent = false
  for (const t of tracks) {
    if (t.relativeUri === track.relativeUri) {
      foundCurrent = true
    } else if (!foundCurrent) {
      prevDuration += t.duration
    }
    totalDuration += t.duration
  }

  return {
    href: track.relativeUri,
    title: track.title,
    type: track.mimeType,
    locations: {
      fragments: [`t=${position}`],
      progression: position / track.duration,
      totalProgression: (prevDuration + position) / totalDuration,
    },
  }
}

export function getAudiobookLocatorPosition(
  locator: ReadiumLocator,
  readingOrder: ReadiumLink[] = [],
) {
  const timeFragment = locator.locations?.fragments
    ?.find((fragment) => fragment.startsWith("t="))
    ?.replace("t=", "")
  const position = parseFloat(timeFragment ?? "")
  if (!Number.isNaN(position)) return position

  const duration = readingOrder.find(
    (link) => link.href === locator.href,
  )?.duration
  const progression = locator.locations?.progression
  if (duration !== undefined && progression !== undefined) {
    return duration * progression
  }

  return 0
}

export function getAudiobookTocItemAtPosition(
  manifest: ReadiumManifest | null | undefined,
  trackIndex: number,
  position: number,
) {
  const readingOrder = manifest?.readingOrder
  if (!readingOrder || !manifest.toc) return null

  const hrefToReadingOrderIndex = readingOrder.reduce(
    (acc, link, index) => ({ ...acc, [link.href]: index }),
    {} as Record<string, number>,
  )

  return (
    flattenToc(manifest.toc).findLast((link) => {
      const { href, position: linkPosition } = getHrefAndPosition(link.href)
      const readingOrderIndex = hrefToReadingOrderIndex[href]
      if (readingOrderIndex === undefined) return false

      return (
        readingOrderIndex < trackIndex ||
        (readingOrderIndex === trackIndex && linkPosition <= position)
      )
    }) ?? null
  )
}

export function getAudiobookLocatorDetails(
  locator: ReadiumLocator,
  manifest: ReadiumManifest | null | undefined,
) {
  const fallback = {
    title: locator.title ?? "Untitled chapter",
    timestamp: Math.max(0, getAudiobookLocatorPosition(locator)),
  }

  const readingOrder = manifest?.readingOrder
  if (!readingOrder) return fallback

  const locatorTrackIndex = readingOrder.findIndex(
    (link) => link.href === locator.href,
  )
  if (locatorTrackIndex === -1) return fallback

  const locatorPosition = getAudiobookLocatorPosition(locator, readingOrder)
  const chapter =
    getAudiobookTocItemAtPosition(
      manifest,
      locatorTrackIndex,
      locatorPosition,
    ) ?? readingOrder[locatorTrackIndex]
  if (!chapter) return fallback

  const chapterStart = getHrefAndPosition(chapter.href)
  const chapterTrackIndex = readingOrder.findIndex(
    (link) => link.href === chapterStart.href,
  )
  let timestamp = locatorPosition

  if (chapterTrackIndex !== -1) {
    timestamp = 0
    for (let index = chapterTrackIndex; index < locatorTrackIndex; index++) {
      timestamp += readingOrder[index]?.duration ?? 0
    }
    timestamp += locatorPosition - chapterStart.position
  }

  return {
    title: chapter.title ?? locator.title ?? "Untitled chapter",
    timestamp: Math.max(0, timestamp),
  }
}
