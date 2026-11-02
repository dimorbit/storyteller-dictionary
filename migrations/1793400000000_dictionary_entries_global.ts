import { type Kysely, sql } from "kysely"

import { wordKey } from "@/lib/wordKey"

// A dictionary word becomes global: one row per word instead of one row per
// place, so a word saved in one book is recognised in every book. The row keeps
// the first place the word was seen, and the book it came from may go away
// without taking the word with it.

interface DictionaryEntry {
  uuid: string
  bookUuid: string | null
  word: string
  wordKey: string
  // Kysely parses any value that looks like JSON, so the locator arrives as an
  // object rather than the stored string. Both shapes are handled.
  locator: unknown
  createdAt: string
  updatedAt: string
}

interface DB {
  dictionaryEntry: DictionaryEntry
  tmpDictionaryEntry: DictionaryEntry
}

export async function up(db: Kysely<DB>): Promise<void> {
  await sql`PRAGMA foreign_keys=0`.execute(db)
  try {
    // A previous attempt may have stopped part way through.
    await db.schema.dropTable("tmpDictionaryEntry").ifExists().execute()

    await db.schema
      .createTable("tmpDictionaryEntry")
      .addColumn("uuid", "text", (col) => col.primaryKey().notNull())
      .addColumn("book_uuid", "text", (col) =>
        col.references("book.uuid").onDelete("set null"),
      )
      .addColumn("word", "text", (col) => col.notNull())
      .addColumn("word_key", "text", (col) => col.notNull())
      .addColumn("locator", "text", (col) => col.notNull())
      .addColumn("created_at", "text", (col) =>
        col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
      )
      .addColumn("updated_at", "text", (col) =>
        col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
      )
      .execute()

    const rows = await db
      .selectFrom("dictionaryEntry")
      .selectAll()
      .orderBy("createdAt", "asc")
      .orderBy("uuid", "asc")
      .execute()

    const seen = new Set<string>()
    const kept: DictionaryEntry[] = []

    for (const row of rows) {
      const key = keyFor(row)

      // The same word may have been saved in more than one place. Keep the
      // earliest row, which is the first occurrence, and drop the rest.
      if (seen.has(key)) continue
      seen.add(key)

      kept.push({
        ...row,
        bookUuid: row.bookUuid ?? null,
        wordKey: key,
        locator: locatorText(row.locator),
      })
    }

    if (kept.length) {
      await db.insertInto("tmpDictionaryEntry").values(kept).execute()
    }

    await db.schema.dropTable("dictionaryEntry").execute()
    await db.schema
      .alterTable("tmpDictionaryEntry")
      .renameTo("dictionaryEntry")
      .execute()

    await db.schema
      .createIndex("indexDictionaryEntryBookCreatedAt")
      .on("dictionaryEntry")
      .columns(["bookUuid", "createdAt"])
      .execute()

    await db.schema
      .createIndex("indexDictionaryEntryWordKey")
      .on("dictionaryEntry")
      .columns(["wordKey"])
      .unique()
      .execute()

    await sql`CREATE TRIGGER dictionary_entry_trigger AFTER
UPDATE ON dictionary_entry FOR EACH ROW BEGIN
UPDATE dictionary_entry
SET
  updated_at = CURRENT_TIMESTAMP
WHERE
  uuid = OLD.uuid;
END;
`.execute(db)
  } finally {
    await sql`PRAGMA foreign_keys=1`.execute(db)
  }
}

// Older rows always have a word, because it was taken from the selection. A row
// without one falls back to the selected text stored in its locator.
function keyFor(row: DictionaryEntry) {
  const word = row.word?.trim()
  if (word) return wordKey(word)

  const highlight = highlightFromLocator(row.locator)
  return wordKey(highlight) || row.uuid
}

function locatorText(locator: unknown) {
  if (typeof locator === "string") return locator

  try {
    return JSON.stringify(locator) ?? ""
  } catch {
    return ""
  }
}

function highlightFromLocator(locator: unknown) {
  try {
    const parsed =
      typeof locator === "string" ? (JSON.parse(locator) as unknown) : locator
    if (!parsed || typeof parsed !== "object") return ""

    const text = (parsed as { text?: unknown }).text
    if (!text || typeof text !== "object") return ""

    const highlight = (text as { highlight?: unknown }).highlight
    return typeof highlight === "string" ? highlight : ""
  } catch {
    return ""
  }
}

export async function down(db: Kysely<DB>): Promise<void> {
  await sql`DROP TRIGGER IF EXISTS dictionary_entry_trigger`.execute(db)
  await sql`PRAGMA foreign_keys=0`.execute(db)
  try {
    await db.schema.dropTable("tmpDictionaryEntry").ifExists().execute()

    await db.schema
      .createTable("tmpDictionaryEntry")
      .addColumn("uuid", "text", (col) => col.primaryKey().notNull())
      .addColumn("book_uuid", "text", (col) =>
        col.notNull().references("book.uuid").onDelete("cascade"),
      )
      .addColumn("word", "text", (col) => col.notNull())
      .addColumn("locator", "text", (col) => col.notNull())
      .addColumn("created_at", "text", (col) =>
        col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
      )
      .addColumn("updated_at", "text", (col) =>
        col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
      )
      .execute()

    // A word whose book has been deleted has nowhere to go in the old shape.
    await sql`INSERT INTO tmp_dictionary_entry (uuid, book_uuid, word, locator, created_at, updated_at)
SELECT
  uuid,
  book_uuid,
  word,
  locator,
  created_at,
  updated_at
FROM
  dictionary_entry
WHERE
  book_uuid IS NOT NULL`.execute(db)

    await db.schema.dropTable("dictionaryEntry").execute()
    await db.schema
      .alterTable("tmpDictionaryEntry")
      .renameTo("dictionaryEntry")
      .execute()

    await db.schema
      .createIndex("indexDictionaryEntryBookCreatedAt")
      .on("dictionaryEntry")
      .columns(["bookUuid", "createdAt"])
      .execute()

    await sql`CREATE TRIGGER dictionary_entry_trigger AFTER
UPDATE ON dictionary_entry FOR EACH ROW BEGIN
UPDATE dictionary_entry
SET
  updated_at = CURRENT_TIMESTAMP
WHERE
  uuid = OLD.uuid;
END;
`.execute(db)
  } finally {
    await sql`PRAGMA foreign_keys=1`.execute(db)
  }
}
