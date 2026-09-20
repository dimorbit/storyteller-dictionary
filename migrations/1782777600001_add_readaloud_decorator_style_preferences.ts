import { type Kysely } from "kysely"

import { type DB } from "@/database/schema"
import { randomUUID } from "@/uuid"

const preferenceNames = ["readaloudDecoratorStyle"] as const

export async function up(db: Kysely<DB>): Promise<void> {
  await db
    .insertInto("preferences")
    .values({
      uuid: randomUUID(),
      name: "readaloudDecoratorStyle",
      value: JSON.stringify("highlight"),
    })
    .execute()
}

export async function down(db: Kysely<DB>): Promise<void> {
  await db
    .deleteFrom("preferences")
    .where("name", "in", preferenceNames)
    .execute()
}
