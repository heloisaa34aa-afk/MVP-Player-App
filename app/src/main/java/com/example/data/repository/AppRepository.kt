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
import kotlinx.coroutines.flow.first
import com.example.data.download.MediaDownloadManager
import com.example.data.local.AppDatabase
import com.example.data.local.CacheDao
import com.example.data.local.ClienteEntity
import com.example.data.local.MidiaEntity
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistMidiaEntity
import com.example.data.local.TvEntity
import com.example.data.remote.SupabaseManager
import com.example.data.remote.TvDto
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import com.example.BuildConfig

data class DeviceInfo(
    val tvName: String,
    val token: String,
    val clienteName: String,
    val playlistName: String,
    val playlistId: String,
    val status: String,
    val lastSync: Instant?,
    val cacheSize: String,
    val downloadedMediaCount: Int,
    val appVersion: String
)

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
        val LAST_SYNC_TIME_KEY = stringPreferencesKey("last_sync_time")
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

    suspend fun saveLastSyncTime(timeStr: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_SYNC_TIME_KEY] = timeStr
        }
    }

    suspend fun getLastSyncTime(): String? {
        val prefs = context.dataStore.data.first()
        return prefs[LAST_SYNC_TIME_KEY]
    }

    suspend fun clearDevicePairing() {
        context.dataStore.edit { prefs ->
            prefs.remove(DEVICE_TOKEN_KEY)
            prefs.remove(TV_ID_KEY)
            prefs.remove(LAST_SYNC_TIME_KEY)
        }
    }

    // --- Pairing logic ---

    /**
     * Normalizes a token using exact rules: trim and uppercase (keeps hyphens).
     */
    fun normalizeToken(token: String): String {
        return token.trim().uppercase()
    }

    /**
     * Attempts to pair the TV by checking if the token exists on Supabase.
     */
    suspend fun tryPairing(token: String): Boolean {
        val normalized = normalizeToken(token)
        Log.d(TAG, "[ENTRY] tryPairing - token: $token (normalized: $normalized)")
        val remoteTv = SupabaseManager.getTvByToken(normalized)
        if (remoteTv != null) {
            saveTvId(remoteTv.id)
            saveDeviceToken(normalized)
            Log.d(TAG, "[EXIT] tryPairing - Successfully paired with TV ID: ${remoteTv.id}")
            
            // Perform immediate sync
            val syncSuccess = syncData(remoteTv.id)
            Log.d(TAG, "Sync result after pairing: $syncSuccess")
            return true
        }
        Log.d(TAG, "[EXIT] tryPairing - TV not found for token")
        return false
    }

    // --- Sync logic ---

    suspend fun syncData(tvId: String): Boolean {
        Log.d(TAG, "[ENTRY] syncData - Starting sync for TV: $tvId")
        return try {
            // 1. Fetch TV from remote
            val remoteTv = SupabaseManager.getTvById(tvId)
            if (remoteTv == null) {
                Log.w(TAG, "[EXIT] syncData - Could not find TV $tvId on remote. Sync fallback to local.")
                return false
            }

            Log.d(TAG, "TV fetched successfully. Status: ${remoteTv.status}, Playlist: ${remoteTv.playlist_id}")

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
                tempo_transicao = remoteTv.tempo_transicao,
                ultima_sincronizacao = remoteTv.ultima_sincronizacao ?: OffsetDateTime.now().toString()
            )
            cacheDao.insertTv(tvEntity)
            Log.d(TAG, "TV saved to local DB.")

            // 2. Resolve Cliente
            val clienteId = remoteTv.cliente_id
            var remoteCliente: com.example.data.remote.ClienteDto? = null
            if (clienteId != null) {
                Log.d(TAG, "Fetching cliente for ID: $clienteId")
                remoteCliente = SupabaseManager.getClienteById(clienteId)
                if (remoteCliente != null) {
                    val clienteEntity = ClienteEntity(
                        id = remoteCliente.id,
                        nome = remoteCliente.nome,
                        playlist_id = remoteCliente.playlist_id,
                        ticker_text = remoteCliente.ticker_text
                    )
                    cacheDao.insertCliente(clienteEntity)
                    Log.d(TAG, "Cliente saved to local DB.")
                }
            } else {
                Log.d(TAG, "No cliente_id associated with TV.")
            }

            // 3. Resolve Playlist ID (via TV or Cliente)
            val playlistId = remoteTv.playlist_id ?: remoteCliente?.playlist_id
            if (playlistId != null) {
                Log.d(TAG, "Fetching playlist for ID: $playlistId")
                // Fetch and cache Playlist
                val remotePlaylist = SupabaseManager.getPlaylistById(playlistId)
                if (remotePlaylist != null) {
                    cacheDao.insertPlaylist(PlaylistEntity(id = remotePlaylist.id, nome = remotePlaylist.nome))
                    Log.d(TAG, "Playlist saved to local DB.")
                } else {
                    Log.w(TAG, "Playlist $playlistId not found.")
                }

                // Fetch Playlist Midias
                Log.d(TAG, "Fetching playlist medias for playlist ID: $playlistId")
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

                Log.d(TAG, "Fetching individual media details and downloading if necessary...")
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
                        // Preserve existing local_file_path if it exists
                        val existing = cacheDao.getMidia(mediaEntity.id)
                        if (existing != null) {
                            mediaEntity.local_file_path = existing.local_file_path
                        }
                        mediaEntities.add(mediaEntity)
                    } else {
                        Log.w(TAG, "Media ${pm.midia_id} not found.")
                    }
                }

                if (mediaEntities.isNotEmpty()) {
                    cacheDao.insertMidias(mediaEntities)
                    Log.d(TAG, "Saved ${mediaEntities.size} medias to local DB.")
                }

                // Launch downloads in background so it doesn't block playback
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    Log.d(TAG, "Background download started...")
                    for (mediaEntity in mediaEntities) {
                        if (mediaEntity.origem == "storage" && (mediaEntity.tipo == "image" || mediaEntity.tipo == "video")) {
                            Log.d(TAG, "[ENTRY] Downloading media ${mediaEntity.id}...")
                            val localPath = downloadManager.downloadMedia(mediaEntity)
                            if (localPath != null) {
                                Log.d(TAG, "[EXIT] Downloaded media ${mediaEntity.id} to $localPath")
                                mediaEntity.local_file_path = localPath
                                cacheDao.insertMidias(listOf(mediaEntity)) // Update DB with local path
                            } else {
                                Log.w(TAG, "[EXIT] Download failed for media ${mediaEntity.id}")
                            }
                        }
                    }
                    // Cleanup unused files from media directory
                    Log.d(TAG, "Cleaning up unused media...")
                    downloadManager.cleanupUnusedMedia(activeMediaIds)
                }
            } else {
                Log.w(TAG, "No playlist_id resolved for TV or Cliente.")
            }

            // Update heartbeat status to remote
            Log.d(TAG, "Updating TV status to Online...")
            val nowStr = OffsetDateTime.now().toString()
            saveLastSyncTime(nowStr)
            SupabaseManager.updateTvStatus(tvId, "Online", getSystemUptime(), nowStr)
            Log.d(TAG, "[EXIT] syncData - Sync complete successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] syncData - Sync failed due to exception. Falling back to cache.", e)
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
    suspend fun updateHeartbeat(tvId: String, status: String = "Online"): Boolean {
        return try {
            val lastSync = getLastSyncTime()
            SupabaseManager.updateTvStatus(tvId, status, getSystemUptime(), lastSync)
            
            val tv = cacheDao.getTv(tvId)
            if (tv != null) {
                cacheDao.insertTv(tv.copy(status = status, uptime = getSystemUptime()))
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed for status $status", e)
            val tv = cacheDao.getTv(tvId)
            if (tv != null) {
                cacheDao.insertTv(tv.copy(status = "Offline", uptime = getSystemUptime()))
            }
            false
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

    suspend fun getDeviceInfo(): DeviceInfo {
        val tvId = tvIdFlow.firstOrNull()
        val tv = tvId?.let { cacheDao.getTv(it) }
        val cliente = tv?.cliente_id?.let { cacheDao.getCliente(it) }
        val playlist = tv?.playlist_id?.let { cacheDao.getPlaylist(it) }

        val lastSyncStr = tv?.ultima_sincronizacao
        val lastSyncInstant = try {
            if (lastSyncStr != null) OffsetDateTime.parse(lastSyncStr).toInstant() else null
        } catch (e: Exception) {
            null
        }

        return DeviceInfo(
            tvName = tv?.nome ?: "",
            token = tv?.token ?: "",
            clienteName = cliente?.nome ?: "",
            playlistName = playlist?.nome ?: "",
            playlistId = playlist?.id ?: "",
            status = tv?.status ?: "Offline",
            lastSync = lastSyncInstant,
            cacheSize = String.format("%.1f MB", getCacheSizeMB()),
            downloadedMediaCount = getCachedMediaCount(),
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    suspend fun checkConfigurationChanges(tvId: String): Boolean {
        return try {
            val remoteTv = SupabaseManager.getTvById(tvId) ?: return false
            val localTv = cacheDao.getTv(tvId) ?: return true
            
            remoteTv.playlist_id != localTv.playlist_id ||
            remoteTv.nome != localTv.nome ||
            remoteTv.rotacao != localTv.rotacao ||
            remoteTv.texto_superior != localTv.texto_superior ||
            remoteTv.texto_superior_visivel != localTv.texto_superior_visivel ||
            remoteTv.texto_inferior != localTv.texto_inferior ||
            remoteTv.texto_inferior_visivel != localTv.texto_inferior_visivel ||
            remoteTv.volume != localTv.volume ||
            remoteTv.tempo_transicao != localTv.tempo_transicao
        } catch (e: Exception) {
            false
        }
    }

    suspend fun hasTvContentChanges(tvId: String, recordJson: String): Boolean {
        return try {
            val localTv = cacheDao.getTv(tvId) ?: return true
            
            val json = Json { ignoreUnknownKeys = true }
            val remoteTv = json.decodeFromString<TvDto>(recordJson)
            
            val playlistChanged = remoteTv.playlist_id != localTv.playlist_id
            val nameChanged = remoteTv.nome != localTv.nome
            val rotationChanged = remoteTv.rotacao != localTv.rotacao
            val textSupChanged = remoteTv.texto_superior != localTv.texto_superior ||
                    remoteTv.texto_superior_cor != localTv.texto_superior_cor ||
                    remoteTv.texto_superior_tamanho != localTv.texto_superior_tamanho ||
                    remoteTv.texto_superior_visivel != localTv.texto_superior_visivel
            val textInfChanged = remoteTv.texto_inferior != localTv.texto_inferior ||
                    remoteTv.texto_inferior_cor != localTv.texto_inferior_cor ||
                    remoteTv.texto_inferior_tamanho != localTv.texto_inferior_tamanho ||
                    remoteTv.texto_inferior_visivel != localTv.texto_inferior_visivel
            val volumeChanged = remoteTv.volume != localTv.volume
            val transitionChanged = remoteTv.tempo_transicao != localTv.tempo_transicao

            val hasChanges = playlistChanged || nameChanged || rotationChanged || textSupChanged || textInfChanged || volumeChanged || transitionChanged
            
            Log.d(TAG, "Comparing TV content fields. " +
                    "playlistChanged: $playlistChanged, nameChanged: $nameChanged, rotationChanged: $rotationChanged, " +
                    "textSup: $textSupChanged, textInf: $textInfChanged, volume: $volumeChanged, transition: $transitionChanged. " +
                    "Result: $hasChanges")
            
            hasChanges
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing TV content changes, defaulting to true", e)
            true
        }
    }

    suspend fun clearAllData() {
        Log.d(TAG, "Clearing all local data and unlinking device.")
        clearDevicePairing()
        cacheDao.clearAll()
        downloadManager.clearAll()
    }

    fun getCacheSizeMB(): Double {
        return downloadManager.getCacheSize().toDouble() / (1024 * 1024)
    }

    fun getCachedMediaCount(): Int {
        return downloadManager.getCachedMediaCount()
    }
}
