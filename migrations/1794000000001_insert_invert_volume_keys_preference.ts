import { type Kysely } from "kysely"

import { type DB } from "@/database/schema"
import { randomUUID } from "@/uuid"

// getPreferences() builds its result by reducing the stored rows and does not merge
// defaults, and updatePreference() is an UPDATE rather than an upsert. Without a row
// here the setting would read as undefined and toggling it would silently write
// nothing, so existing installs need it inserted.
export async function up(db: Kysely<DB>): Promise<void> {
  await db
    .insertInto("preferences")
    .values({ uuid: randomUUID(), name: "invertVolumeKeys", value: "false" })
    .execute()
}

export async function down(db: Kysely<DB>): Promise<void> {
  await db
    .deleteFrom("preferences")
    .where("name", "=", "invertVolumeKeys")
    .execute()
}
