package com.example.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.data.download.MediaDownloadManager
import com.example.data.local.AppDatabase
import com.example.data.local.CacheDao
import com.example.data.local.ClienteEntity
import com.example.data.local.MidiaEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistMidiaEntity
import com.example.data.local.TvEntity
import com.example.data.remote.SupabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.time.OffsetDateTime

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vision_settings")

class AppRepository(private val context: Context) {
    private val database: AppDatabase by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "vision_central_cache.db"
        ).fallbackToDestructiveMigration().build()
    }

    val cacheDao: CacheDao by lazy { database.cacheDao() }
    private val downloadManager = MediaDownloadManager(context)

    companion object {
        private const val TAG = "AppRepository"
        val DEVICE_TOKEN_KEY = stringPreferencesKey("device_token")
        val TV_ID_KEY = stringPreferencesKey("tv_id")
    }

    // --- Preference flows ---

    val deviceTokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_TOKEN_KEY]
    }

    val tvIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[TV_ID_KEY]
    }

    suspend fun saveDeviceToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_TOKEN_KEY] = token
        }
    }

    suspend fun saveTvId(tvId: String) {
        context.dataStore.edit { prefs ->
            prefs[TV_ID_KEY] = tvId
        }
    }

    suspend fun clearDevicePairing() {
        context.dataStore.edit { prefs ->
            prefs.remove(DEVICE_TOKEN_KEY)
            prefs.remove(TV_ID_KEY)
        }
    }

    // --- Pairing logic ---

    /**
     * Normalizes a token using exact rules: trim, uppercase, remove anything that is not alphanumeric.
     */
    fun normalizeToken(token: String): String {
        return token.trim().uppercase().filter { it.isLetterOrDigit() }
    }

    /**
     * Attempts to pair the TV by checking if the token exists on Supabase.
     */
    suspend fun tryPairing(token: String): Boolean {
        val normalized = normalizeToken(token)
        Log.d(TAG, "Trying to pair token: $token (normalized: $normalized)")
        val remoteTv = SupabaseManager.getTvByToken(normalized)
        if (remoteTv != null) {
            saveTvId(remoteTv.id)
            saveDeviceToken(normalized)
            Log.d(TAG, "Successfully paired with TV ID: ${remoteTv.id}")
            
            // Perform immediate sync
            syncData(remoteTv.id)
            return true
        }
        return false
    }

    // --- Sync logic ---

    suspend fun syncData(tvId: String): Boolean {
        Log.d(TAG, "Starting sync for TV: $tvId")
        return try {
            // 1. Fetch TV from remote
            val remoteTv = SupabaseManager.getTvById(tvId)
            if (remoteTv == null) {
                Log.w(TAG, "Could not find TV $tvId on remote. Sync fallback to local.")
                return false
            }

            // Save to Local DB
            val tvEntity = TvEntity(
                id = remoteTv.id,
                cliente_id = remoteTv.cliente_id,
                nome = remoteTv.nome,
                token = remoteTv.token,
                playlist_id = remoteTv.playlist_id,
                status = "Online",
                uptime = getSystemUptime(),
                rotacao = remoteTv.rotacao,
                texto_superior = remoteTv.texto_superior,
                texto_superior_cor = remoteTv.texto_superior_cor,
                texto_superior_tamanho = remoteTv.texto_superior_tamanho,
                texto_superior_visivel = remoteTv.texto_superior_visivel,
                texto_inferior = remoteTv.texto_inferior,
                texto_inferior_cor = remoteTv.texto_inferior_cor,
                texto_inferior_tamanho = remoteTv.texto_inferior_tamanho,
                texto_inferior_visivel = remoteTv.texto_inferior_visivel,
                volume = remoteTv.volume,
                tempo_transicao = remoteTv.tempo_transicao
            )
            cacheDao.insertTv(tvEntity)

            // 2. Resolve Cliente
            val clienteId = remoteTv.cliente_id
            var remoteCliente: com.example.data.remote.ClienteDto? = null
            if (clienteId != null) {
                remoteCliente = SupabaseManager.getClienteById(clienteId)
                if (remoteCliente != null) {
                    val clienteEntity = ClienteEntity(
                        id = remoteCliente.id,
                        nome = remoteCliente.nome,
                        playlist_id = remoteCliente.playlist_id,
                        ticker_text = remoteCliente.ticker_text
                    )
                    cacheDao.insertCliente(clienteEntity)
                }
            }

            // 3. Resolve Playlist ID (via TV or Cliente)
            val playlistId = remoteTv.playlist_id ?: remoteCliente?.playlist_id
            if (playlistId != null) {
                // Fetch and cache Playlist
                val remotePlaylist = SupabaseManager.getPlaylistById(playlistId)
                if (remotePlaylist != null) {
                    cacheDao.insertPlaylist(PlaylistEntity(id = remotePlaylist.id, nome = remotePlaylist.nome))
                }

                // Fetch Playlist Midias
                val remotePlaylistMidias = SupabaseManager.getPlaylistMidias(playlistId)
                Log.d(TAG, "Found ${remotePlaylistMidias.size} playlist medias on remote.")

                // Delete local mappings for this playlist and write new ones
                cacheDao.deletePlaylistMidias(playlistId)
                val playlistMidiaEntities = remotePlaylistMidias.map { dto ->
                    PlaylistMidiaEntity(
                        id = dto.id,
                        playlist_id = dto.playlist_id,
                        midia_id = dto.midia_id,
                        ordem = dto.ordem,
                        duracao = dto.duracao
                    )
                }
                cacheDao.insertPlaylistMidias(playlistMidiaEntities)

                // 4. Resolve and Download individual medias in background
                val activeMediaIds = mutableSetOf<String>()
                val mediaEntities = mutableListOf<MidiaEntity>()

                for (pm in remotePlaylistMidias) {
                    activeMediaIds.add(pm.midia_id)
                    val remoteMedia = SupabaseManager.getMidiaById(pm.midia_id)
                    if (remoteMedia != null) {
                        val mediaEntity = MidiaEntity(
                            id = remoteMedia.id,
                            nome = remoteMedia.nome,
                            tipo = remoteMedia.tipo,
                            origem = remoteMedia.origem,
                            url_storage = remoteMedia.url_storage,
                            url_externa = remoteMedia.url_externa,
                            duracao = remoteMedia.duracao
                        )

                        // If media source is storage, download and save locally
                        if (remoteMedia.origem == "storage" && (remoteMedia.tipo == "image" || remoteMedia.tipo == "video")) {
                            val localPath = downloadManager.downloadMedia(mediaEntity)
                            if (localPath != null) {
                                mediaEntity.local_file_path = localPath
                            }
                        }
                        mediaEntities.add(mediaEntity)
                    }
                }

                if (mediaEntities.isNotEmpty()) {
                    cacheDao.insertMidias(mediaEntities)
                }

                // Cleanup unused files from media directory
                downloadManager.cleanupUnusedMedia(activeMediaIds)
            }

            // Update heartbeat status to remote
            SupabaseManager.updateTvStatus(tvId, "Online", getSystemUptime())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed due to exception. Falling back to cache.", e)
            false
        }
    }

    /**
     * Resolves the media list for the current playlist from the Room local cache.
     */
    suspend fun getPlaylistMediasFromCache(tvId: String): List<Pair<PlaylistMidiaEntity, MidiaEntity>> {
        val tv = cacheDao.getTv(tvId) ?: return emptyList()
        val cliente = tv.cliente_id?.let { cacheDao.getCliente(it) }
        val playlistId = tv.playlist_id ?: cliente?.playlist_id ?: return emptyList()

        val mappings = cacheDao.getPlaylistMidias(playlistId)
        val result = mutableListOf<Pair<PlaylistMidiaEntity, MidiaEntity>>()
        for (map in mappings) {
            val media = cacheDao.getMidia(map.midia_id)
            if (media != null) {
                result.add(Pair(map, media))
            }
        }
        return result
    }

    /**
     * Updates the heartbeat online status on Supabase.
     */
    suspend fun updateHeartbeat(tvId: String) {
        try {
            SupabaseManager.updateTvStatus(tvId, "Online", getSystemUptime())
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
        }
    }

    fun getSystemUptime(): String {
        val uptimeMs = SystemClock.elapsedRealtime()
        val seconds = (uptimeMs / 1000) % 60
        val minutes = (uptimeMs / (1000 * 60)) % 60
        val hours = (uptimeMs / (1000 * 60 * 60)) % 24
        val days = uptimeMs / (1000 * 60 * 60 * 24)
        return "${days}d ${hours}h ${minutes}m ${seconds}s"
    }
}
