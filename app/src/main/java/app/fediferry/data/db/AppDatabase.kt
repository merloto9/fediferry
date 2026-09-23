/*
 * FediFerry — share a meme screenshot straight to Mastodon.
 * Copyright (C) 2026 Jasper Ramthun
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.fediferry.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.fediferry.data.model.Account
import app.fediferry.data.model.AiModel
import app.fediferry.data.model.InstanceApp
import app.fediferry.data.model.Item
import app.fediferry.data.model.CleanupProfile
import app.fediferry.data.model.Hashtag
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.data.model.ProfileRule
import app.fediferry.data.model.Source
import app.fediferry.data.model.Template

@Database(
    entities = [
        Item::class,
        Account::class,
        InstanceApp::class,
        Template::class,
        CleanupProfile::class,
        ProfileRule::class,
        Source::class,
        PlaceholderKey::class,
        Hashtag::class,
        AiModel::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun accounts(): AccountDao
    abstract fun templates(): TemplateDao
    abstract fun cleanup(): CleanupDao
    abstract fun sources(): SourceDao
    abstract fun placeholderKeys(): PlaceholderKeyDao
    abstract fun hashtags(): HashtagDao
    abstract fun aiModels(): AiModelDao

    companion object {
        /**
         * Adds [app.fediferry.data.model.Item.originalMediaPath]. Additive and
         * nullable, so existing drafts survive untouched — destructive migration
         * would throw away saved memes on an app update.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN originalMediaPath TEXT")
            }
        }

        /** Adds the cleanup profiles and their rules. Purely additive. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cleanup_profiles` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cleanup_rules` (
                        `id` TEXT NOT NULL,
                        `profileId` TEXT NOT NULL,
                        `left` REAL NOT NULL,
                        `top` REAL NOT NULL,
                        `right` REAL NOT NULL,
                        `bottom` REAL NOT NULL,
                        `treatment` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cleanup_rules_profileId` " +
                        "ON `cleanup_rules` (`profileId`)",
                )
            }
        }

        /** Adds the followed sources. Purely additive; posts are never stored. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sources` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `handle` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds user-defined placeholders, the sources a template reads, and the
         * raw source data an item keeps. Additive: bodies already written are
         * left exactly as they are.
         *
         * `{caption}` becomes the first user-defined placeholder, carrying the
         * mappings it used to have built in — except Pinterest's, which filled
         * posts with the service's own filler text.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `placeholder_keys` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `mappings` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE templates ADD COLUMN excludedSources TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN origin TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN sourceFields TEXT NOT NULL DEFAULT '{}'")
                seedPlaceholders(db)
            }
        }

        /**
         * Adds the hashtag list, the reserved `{tags}` placeholder, the
         * hashtags each item picked, and whether templates and items take the
         * source's own hashtags — on, as `{tags}` recipes start empty anyway.
         *
         * The list starts with every hashtag the templates already used, so no
         * template loses its tags. Existing items keep a null selection: their
         * text was written with the tags already in it.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hashtags` (
                        `tag` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`tag`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("ALTER TABLE items ADD COLUMN hashtags TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN addSourceHashtags INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE templates ADD COLUMN addSourceHashtags INTEGER NOT NULL DEFAULT 1")

                val used = mutableListOf<String>()
                db.query("SELECT tags FROM templates ORDER BY sortOrder, name").use { c ->
                    while (c.moveToNext()) used += Hashtags.parse(c.getString(0))
                }
                seedHashtags(db, Hashtags.union(used, emptyList()))
                seedPlaceholder(db, PlaceholderKey.tagsSeed())
            }
        }

        /**
         * Adds the list of AI models. Purely additive: the one model each kind
         * had lived in settings, which the database cannot read, so it moves
         * across at first use instead — see [app.fediferry.data.AiModels].
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_models` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `endpoint` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `wireFormat` TEXT NOT NULL,
                        `maskPolarity` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_models_kind` ON `ai_models` (`kind`)")
            }
        }

        private fun seedPlaceholder(db: SupportSQLiteDatabase, key: PlaceholderKey) {
            db.execSQL(
                "INSERT OR IGNORE INTO placeholder_keys (id, name, mappings, sortOrder) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(key.id, key.name, Converters().stringMapToString(key.mappings), key.sortOrder),
            )
        }

        private fun seedPlaceholders(db: SupportSQLiteDatabase) = seedPlaceholder(db, PlaceholderKey.seed())

        private fun seedHashtags(db: SupportSQLiteDatabase, tags: List<String>) {
            tags.forEachIndexed { i, tag ->
                db.execSQL("INSERT OR IGNORE INTO hashtags (tag, sortOrder) VALUES (?, ?)", arrayOf<Any>(tag, i))
            }
        }

        /**
         * A fresh install gets `{caption}`, `{tags}` and the seed template's
         * hashtag. Seeded here rather than whenever a table is empty, so
         * deleting them stays deleted — except `{tags}`, which cannot be.
         */
        private val SEED = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedPlaceholders(db)
                seedPlaceholder(db, PlaceholderKey.tagsSeed())
                seedHashtags(db, Template.seed().hashtagList)
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fediferry.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .addCallback(SEED)
                .build()
    }
}
