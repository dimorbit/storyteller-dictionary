import { type Kysely, sql } from "kysely"

export async function up(db: Kysely<unknown>): Promise<void> {
  const tables = await db.introspection.getTables()
  const bookTable = tables.find((table) => table.name === "book")
  if (!bookTable) {
    return
  }

  // expand/contract the rating column to a real
  await db.schema.alterTable("book").addColumn("rating_new", "real").execute()

  await sql`update "book" set "rating_new" = cast("rating" as real)`.execute(db)

  await db.schema.alterTable("book").dropColumn("rating").execute()

  await db.schema
    .alterTable("book")
    .renameColumn("rating_new", "rating")
    .execute()
}

export async function down(db: Kysely<unknown>): Promise<void> {
  const tables = await db.introspection.getTables()
  const bookTable = tables.find((table) => table.name === "book")
  if (!bookTable) {
    return
  }

  const ratingColumn = bookTable.columns.find(
    (column) => column.name === "rating",
  )
  const ratingType = ratingColumn?.dataType?.toLowerCase()

  const isAlreadyRolledBack = ratingType === "integer"
  if (isAlreadyRolledBack) {
    return
  }

  await db.schema
    .alterTable("book")
    .addColumn("rating_old", "integer")
    .execute()

  await sql`update "book" set "rating_old" = cast("rating" as integer)`.execute(
    db,
  )

  await db.schema.alterTable("book").dropColumn("rating").execute()

  await db.schema
    .alterTable("book")
    .renameColumn("rating_old", "rating")
    .execute()
}
