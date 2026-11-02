import { type Kysely, sql } from "kysely"

export async function up(db: Kysely<unknown>): Promise<void> {
  await db.schema
    .createTable("dictionaryEntry")
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

  await sql`CREATE TRIGGER dictionary_entry_trigger AFTER
UPDATE ON dictionary_entry FOR EACH ROW BEGIN
UPDATE dictionary_entry
SET
  updated_at = CURRENT_TIMESTAMP
WHERE
  uuid = OLD.uuid;
END;
`.execute(db)

  await db.schema
    .createIndex("indexDictionaryEntryBookCreatedAt")
    .on("dictionaryEntry")
    .columns(["bookUuid", "createdAt"])
    .execute()
}

export async function down(db: Kysely<unknown>): Promise<void> {
  await sql`DROP TRIGGER dictionary_entry_trigger`.execute(db)
  await db.schema.dropTable("dictionaryEntry").execute()
}
