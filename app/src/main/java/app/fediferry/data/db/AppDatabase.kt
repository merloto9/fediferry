package app.fediferry.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.fediferry.data.model.Account
import app.fediferry.data.model.InstanceApp
import app.fediferry.data.model.Item
import app.fediferry.data.model.Template

@Database(
    entities = [Item::class, Account::class, InstanceApp::class, Template::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun accounts(): AccountDao
    abstract fun templates(): TemplateDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "fediferry.db")
                .build()
    }
}
