import { type Insertable, type Selectable } from "kysely"

import { wordKey } from "@/lib/wordKey"
import { type UUID } from "@/uuid"

import { db } from "./db"
import { type DB } from "./schema"

export type DictionaryEntry = Selectable<DB["dictionaryEntry"]>
export type NewDictionaryEntry = Omit<
  Insertable<DB["dictionaryEntry"]>,
  "locator" | "wordKey"
> & {
  locator: string
}

export async function getDictionaryEntries() {
  return await db
    .selectFrom("dictionaryEntry")
    .selectAll()
    .orderBy("createdAt", "desc")
    .execute()
}

export async function getBookDictionaryEntries(bookUuid: UUID) {
  return await db
    .selectFrom("dictionaryEntry")
    .selectAll()
    .where("bookUuid", "=", bookUuid)
    .orderBy("createdAt", "desc")
    .execute()
}

export async function createDictionaryEntry(entry: NewDictionaryEntry) {
  // The key is derived here so that no caller can forget it, and a word that is
  // already saved, from this book or any other, is left as it is.
  await db
    .insertInto("dictionaryEntry")
    .values({
      ...entry,
      wordKey: wordKey(entry.word),
    } as unknown as Insertable<DB["dictionaryEntry"]>)
    .onConflict((oc) => oc.column("wordKey").doNothing())
    .execute()
}

export async function deleteDictionaryEntry(uuid: UUID) {
  await db.deleteFrom("dictionaryEntry").where("uuid", "=", uuid).execute()
}
