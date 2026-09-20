import { type Kysely, sql } from "kysely"

export async function up(db: Kysely<unknown>): Promise<void> {
  await db.schema
    .createTable("bookToCreatorNew")
    .addColumn("uuid", "text", (col) => col.primaryKey().notNull())
    .addColumn("book_uuid", "text", (col) =>
      col.notNull().references("book.uuid").onDelete("cascade"),
    )
    .addColumn("creator_uuid", "text", (col) =>
      col.notNull().references("creator.uuid").onDelete("cascade"),
    )
    .addColumn("role", "text")
    .addColumn("created_at", "text", (col) =>
      col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
    )
    .addColumn("updated_at", "text", (col) =>
      col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
    )
    .addUniqueConstraint("book_to_creator_book_uuid_creator_uuid_role", [
      "book_uuid",
      "creator_uuid",
      "role",
    ])
    .execute()

  await db
    .insertInto("bookToCreatorNew" as never)
    .expression((eb) => eb.selectFrom("bookToCreator" as never).selectAll())
    .execute()

  await db.schema.dropTable("bookToCreator").execute()
  await db.schema
    .alterTable("bookToCreatorNew")
    .renameTo("bookToCreator")
    .execute()

  await sql`CREATE TRIGGER book_to_creator_trigger AFTER
UPDATE ON book_to_creator FOR EACH ROW BEGIN
UPDATE book_to_creator
SET
  updated_at = CURRENT_TIMESTAMP
WHERE
  uuid = OLD.uuid;
END;`.execute(db)
}

export async function down(db: Kysely<unknown>): Promise<void> {
  await db.schema
    .createTable("bookToCreatorOld")
    .addColumn("uuid", "text", (col) => col.primaryKey().notNull())
    .addColumn("book_uuid", "text", (col) =>
      col.notNull().references("book.uuid").onDelete("cascade"),
    )
    .addColumn("creator_uuid", "text", (col) =>
      col.notNull().references("creator.uuid").onDelete("cascade"),
    )
    .addColumn("role", "text")
    .addColumn("created_at", "text", (col) =>
      col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
    )
    .addColumn("updated_at", "text", (col) =>
      col.notNull().defaultTo(sql`CURRENT_TIMESTAMP`),
    )
    .addUniqueConstraint("book_to_creator_book_uuid_creator_uuid", [
      "book_uuid",
      "creator_uuid",
    ])
    .execute()

  // if the same person has both aut and nrt rows, keep whichever comes first
  await sql`INSERT OR IGNORE INTO book_to_creator_old SELECT * FROM book_to_creator`.execute(
    db,
  )

  await db.schema.dropTable("bookToCreator").execute()
  await db.schema
    .alterTable("bookToCreatorOld")
    .renameTo("bookToCreator")
    .execute()

  await sql`CREATE TRIGGER book_to_creator_trigger AFTER
UPDATE ON book_to_creator FOR EACH ROW BEGIN
UPDATE book_to_creator
SET
  updated_at = CURRENT_TIMESTAMP
WHERE
  uuid = OLD.uuid;
END;`.execute(db)
}
