package com.example.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clientes")
data class ClienteEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val playlist_id: String?,
    val ticker_text: String?
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val nome: String
)

@Entity(tableName = "tvs")
data class TvEntity(
    @PrimaryKey val id: String,
    val cliente_id: String?,
    val nome: String?,
    val token: String?,
    val playlist_id: String?,
    val status: String?,
    val uptime: String?,
    val rotacao: Int,
    val texto_superior: String?,
    val texto_superior_cor: String?,
    val texto_superior_tamanho: String?,
    val texto_superior_visivel: Boolean,
    val texto_inferior: String?,
    val texto_inferior_cor: String?,
    val texto_inferior_tamanho: String?,
    val texto_inferior_visivel: Boolean,
    val volume: Int?,
    val tempo_transicao: Int?,
    val ultima_sincronizacao: String?
)

@Entity(tableName = "midias")
data class MidiaEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val tipo: String,
    val origem: String,
    val url_storage: String?,
    val url_externa: String?,
    val duracao: Int,
    var local_file_path: String? = null // Path to downloaded file
)

@Entity(tableName = "playlist_midias")
data class PlaylistMidiaEntity(
    @PrimaryKey val id: String,
    val playlist_id: String,
    val midia_id: String,
    val ordem: Int,
    val duracao: Int?
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM tvs WHERE id = :id LIMIT 1")
    fun getTvFlow(id: String): Flow<TvEntity?>

    @Query("SELECT * FROM tvs WHERE id = :id LIMIT 1")
    suspend fun getTv(id: String): TvEntity?

    @Query("SELECT * FROM clientes WHERE id = :id LIMIT 1")
    suspend fun getCliente(id: String): ClienteEntity?

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylist(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_midias WHERE playlist_id = :playlistId ORDER BY ordem ASC")
    fun getPlaylistMidiasFlow(playlistId: String): Flow<List<PlaylistMidiaEntity>>

    @Query("SELECT * FROM playlist_midias WHERE playlist_id = :playlistId ORDER BY ordem ASC")
    suspend fun getPlaylistMidias(playlistId: String): List<PlaylistMidiaEntity>

    @Query("SELECT * FROM midias WHERE id = :id LIMIT 1")
    suspend fun getMidia(id: String): MidiaEntity?

    @Query("SELECT * FROM midias")
    suspend fun getAllMidias(): List<MidiaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTv(tv: TvEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCliente(cliente: ClienteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistMidias(playlistMidias: List<PlaylistMidiaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMidias(midias: List<MidiaEntity>)

    @Query("DELETE FROM playlist_midias WHERE playlist_id = :playlistId")
    suspend fun deletePlaylistMidias(playlistId: String)

    @Query("DELETE FROM midias WHERE id = :id")
    suspend fun deleteMidia(id: String)

    @Query("DELETE FROM tvs")
    suspend fun clearTvs()

    @Query("DELETE FROM clientes")
    suspend fun clearClientes()

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM playlist_midias")
    suspend fun clearPlaylistMidias()

    @Query("DELETE FROM midias")
    suspend fun clearMidias()

    suspend fun clearAll() {
        clearTvs()
        clearClientes()
        clearPlaylists()
        clearPlaylistMidias()
        clearMidias()
    }
}

@Database(
    entities = [
        ClienteEntity::class,
        PlaylistEntity::class,
        TvEntity::class,
        MidiaEntity::class,
        PlaylistMidiaEntity::class
    ],
    // INCREMENTE A VERSÃO sempre que houver alteração nas Entities
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}
