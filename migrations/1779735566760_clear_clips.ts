import { type Kysely } from "kysely"

import { type DB } from "@/database/schema"

// 2.6.0 started using clip.textResource to look up locators
// dynamically. It also fixed a bug where we were previously
// storing the wrong values for clip.textResource. But clips
// are cached! So we need to clear out all existing clips one
// time, so that Storyteller can recompute them. Luckily
// this is now much faster as of 2.6.0.
export async function up(db: Kysely<DB>): Promise<void> {
  await db.updateTable("readaloud").set({ clips: null }).execute()
}

// Not possible to undo!
export async function down(): Promise<void> {
  return
}
