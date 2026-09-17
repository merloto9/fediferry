package app.fediferry.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.fediferry.data.model.Account
import app.fediferry.data.model.InstanceApp
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: Account)

    @Query("SELECT * FROM accounts ORDER BY acct")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY acct")
    suspend fun all(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): Account?

    @Query("SELECT * FROM accounts WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultAccount(): Account?

    @Query("UPDATE accounts SET isDefault = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApp(app: InstanceApp)

    @Query("SELECT * FROM instance_apps WHERE instance = :instance")
    suspend fun appFor(instance: String): InstanceApp?
}
