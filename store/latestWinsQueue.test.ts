import assert from "node:assert/strict"
import { test } from "vitest"

import { createLatestWinsQueue } from "./latestWinsQueue"

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

test("runs commands one at a time and keeps only the newest pending seek", async () => {
  const queue = createLatestWinsQueue()
  const ran: string[] = []
  const first = deferred()

  const a = queue.enqueue(
    async () => {
      ran.push("seek 10")
      await first.promise
    },
    { replaceable: true },
  )
  // Let the first seek start; one that has not started yet would be replaced.
  await new Promise<void>((resolve) => setTimeout(resolve, 0))
  const b = queue.enqueue(async () => void ran.push("seek 20"), {
    replaceable: true,
  })
  const c = queue.enqueue(async () => void ran.push("next"), {
    replaceable: false,
  })
  const d = queue.enqueue(async () => void ran.push("seek 30"), {
    replaceable: true,
  })

  assert.deepEqual(ran, ["seek 10"])

  first.resolve()
  await Promise.all([a, b, c, d])
  assert.deepEqual(ran, ["seek 10", "next", "seek 30"])
})

test("a failing command does not stop the queue", async () => {
  const queue = createLatestWinsQueue()
  const ran: string[] = []
  const failed = queue.enqueue(
    async () => {
      throw new Error("player gone")
    },
    { replaceable: false },
  )
  const next = queue.enqueue(async () => void ran.push("after"), {
    replaceable: true,
  })
  await assert.rejects(failed)
  await next
  assert.deepEqual(ran, ["after"])
})

test("a seek replaced before it starts never runs", async () => {
  const queue = createLatestWinsQueue()
  const ran: number[] = []
  await Promise.all(
    [1, 2, 3].map((n) =>
      queue.enqueue(async () => void ran.push(n), { replaceable: true }),
    ),
  )
  assert.deepEqual(ran, [3])
})
