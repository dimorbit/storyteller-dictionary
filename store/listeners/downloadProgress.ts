import { throttle } from "@/throttle"

export function createDownloadProgressHandler<A extends unknown[], R>(
  updateProgress: (isActive: () => boolean, ...args: A) => R,
  delay: number,
) {
  let active = true
  const isActive = () => active
  const update = throttle((...args: A) => {
    if (!active) return
    return updateProgress(isActive, ...args)
  }, delay)

  return {
    update,
    cancel() {
      active = false
      update.cancel()
    },
  }
}
