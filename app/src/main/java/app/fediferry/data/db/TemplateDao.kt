package app.fediferry.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.fediferry.data.model.Template
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: Template)

    @Query("SELECT * FROM templates ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Template>>

    @Query("SELECT * FROM templates ORDER BY sortOrder, name")
    suspend fun all(): List<Template>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun byId(id: String): Template?

    @Query("SELECT * FROM templates WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultTemplate(): Template?

    @Query("UPDATE templates SET isDefault = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("DELETE FROM templates WHERE id = :id AND isDefault = 0")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int
}
