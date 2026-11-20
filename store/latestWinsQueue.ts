// Runs player commands one at a time, in the order they were sent.
//
// A "replaceable" command (an absolute seek, a clip position to save) is skipped
// when a newer replaceable command is sent before it starts: only the newest place
// matters. Other commands (next/previous track) always run. Nothing is ever
// dropped while an earlier command is still running, unlike unsubscribing a
// listener for the duration of the await.
export function createLatestWinsQueue() {
  let tail: Promise<void> = Promise.resolve()
  let latestReplaceable = 0

  function enqueue(
    task: () => Promise<void>,
    options: { replaceable: boolean },
  ): Promise<void> {
    const id = options.replaceable ? ++latestReplaceable : 0
    const run = tail.then(async () => {
      if (options.replaceable && id !== latestReplaceable) return
      await task()
    })
    // A failed command must not stop the ones queued after it.
    tail = run.catch(() => undefined)
    return run
  }

  return { enqueue }
}
