import { type Kysely } from "kysely"

import { type DB } from "@/database/schema"
import { randomUUID } from "@/uuid"

export async function up(db: Kysely<DB>): Promise<void> {
  await db
    .insertInto("preferences")
    .values({ uuid: randomUUID(), name: "floatingToolbar", value: "false" })
    .execute()
}

export async function down(db: Kysely<DB>): Promise<void> {
  await db
    .deleteFrom("preferences")
    .where("name", "=", "floatingToolbar")
    .execute()
}
