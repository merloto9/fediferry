package app.fediferry.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.fediferry.data.model.Item
import app.fediferry.data.model.Status
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Item)

    @Update
    suspend fun update(item: Item)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: String): Item?

    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: String): Flow<Item?>

    /** The inbox: everything not yet successfully posted, newest first. */
    @Query("SELECT * FROM items WHERE status != 'POSTED' ORDER BY createdAt DESC")
    fun observeInbox(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE status = 'POSTED' ORDER BY postedAt DESC")
    fun observeHistory(): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE mediaHash = :hash AND status != 'FAILED' LIMIT 1")
    suspend fun byMediaHash(hash: String): Item?

    @Query("UPDATE items SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: Status)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM items WHERE status = 'POSTED' AND postedAt < :before")
    suspend fun purgePostedBefore(before: Long): Int

    /**
     * Re-arms items the process died on. POSTING is only ever a transient state
     * held by a running worker, so any survivor of a crash is stale.
     */
    @Query("UPDATE items SET status = 'QUEUED' WHERE status = 'POSTING'")
    suspend fun requeueStalePosting()
}
