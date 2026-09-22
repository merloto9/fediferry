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
import app.fediferry.data.model.InstanceApp
import app.fediferry.data.model.Item
import app.fediferry.data.model.CleanupProfile
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
    ],
    version = 5,
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

        private fun seedPlaceholders(db: SupportSQLiteDatabase) {
            val key = PlaceholderKey.seed()
            db.execSQL(
                "INSERT OR IGNORE INTO placeholder_keys (id, name, mappings, sortOrder) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(key.id, key.name, Converters().stringMapToString(key.mappings), key.sortOrder),
            )
        }

        /**
         * A fresh install gets `{caption}` too. Seeded here rather than whenever
         * the table is empty, so deleting every placeholder stays deleted.
         */
        private val SEED = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedPlaceholders(db)
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fediferry.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(SEED)
                .build()
    }
}
