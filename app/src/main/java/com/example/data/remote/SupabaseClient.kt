package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class ClienteDto(
    val id: String,
    val nome: String,
    val playlist_id: String? = null,
    val ticker_text: String? = null
)

@Serializable
data class PlaylistDto(
    val id: String,
    val cliente_id: String? = null,
    val nome: String
)

@Serializable
data class TvDto(
    val id: String,
    val cliente_id: String? = null,
    val nome: String? = null,
    val token: String? = null,
    val playlist_id: String? = null,
    val status: String? = null,
    val uptime: String? = null,
    val ultima_sincronizacao: String? = null,
    val ultima_conexao: String? = null,
    val orientacao: String? = null,
    val resolucao: String? = null,
    val rotacao: Int = 0,
    val texto_superior: String? = null,
    val texto_superior_cor: String? = null,
    val texto_superior_tamanho: String? = null,
    val texto_superior_visivel: Boolean = false,
    val texto_inferior: String? = null,
    val texto_inferior_cor: String? = null,
    val texto_inferior_tamanho: String? = null,
    val texto_inferior_visivel: Boolean = false,
    val volume: Int? = null,
    val tempo_transicao: Int? = null
)

@Serializable
data class MidiaDto(
    val id: String,
    val cliente_id: String? = null,
    val nome: String,
    val tipo: String, // 'image' | 'video' | 'website' | 'instagram' | 'youtube' | 'google_maps' | 'canva'
    val origem: String, // 'storage' | 'url'
    val url_storage: String? = null,
    val url_externa: String? = null,
    val duracao: Int = 10
)

@Serializable
data class PlaylistMidiaDto(
    val id: String,
    val playlist_id: String,
    val midia_id: String,
    val ordem: Int,
    val duracao: Int? = null
)

@Serializable
data class TvStatusUpdate(
    val status: String,
    val uptime: String,
    val ultima_conexao: String,
    val ultima_sincronizacao: String
)

object SupabaseManager {
    private const val TAG = "SupabaseManager"

    val supabaseClient: SupabaseClient by lazy {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_KEY
        Log.d(TAG, "Initializing Supabase with URL: $url")
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }

    suspend fun getTvByToken(token: String): TvDto? {
        return try {
            val normalized = token.trim().uppercase().filter { it.isLetterOrDigit() }
            Log.d(TAG, "Searching TV with normalized token: $normalized")
            supabaseClient.postgrest["tvs"].select {
                filter {
                    eq("token", normalized)
                }
            }.decodeSingleOrNull<TvDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TV by token", e)
            null
        }
    }

    suspend fun getTvById(id: String): TvDto? {
        return try {
            supabaseClient.postgrest["tvs"].select {
                filter {
                    eq("id", id)
                }
            }.decodeSingleOrNull<TvDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TV by ID: $id", e)
            null
        }
    }

    suspend fun getClienteById(id: String): ClienteDto? {
        return try {
            supabaseClient.postgrest["clientes"].select {
                filter {
                    eq("id", id)
                }
            }.decodeSingleOrNull<ClienteDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Cliente: $id", e)
            null
        }
    }

    suspend fun getPlaylistById(id: String): PlaylistDto? {
        return try {
            supabaseClient.postgrest["playlists"].select {
                filter {
                    eq("id", id)
                }
            }.decodeSingleOrNull<PlaylistDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Playlist: $id", e)
            null
        }
    }

    suspend fun getPlaylistMidias(playlistId: String): List<PlaylistMidiaDto> {
        return try {
            supabaseClient.postgrest["playlist_midias"].select {
                filter {
                    eq("playlist_id", playlistId)
                }
            }.decodeList<PlaylistMidiaDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching PlaylistMidias for: $playlistId", e)
            emptyList()
        }
    }

    suspend fun getMidiaById(id: String): MidiaDto? {
        return try {
            supabaseClient.postgrest["midias"].select {
                filter {
                    eq("id", id)
                }
            }.decodeSingleOrNull<MidiaDto>()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Midia: $id", e)
            null
        }
    }

    suspend fun updateTvStatus(tvId: String, status: String, uptime: String) {
        try {
            val nowStr = OffsetDateTime.now().toString()
            val update = TvStatusUpdate(
                status = status,
                uptime = uptime,
                ultima_conexao = nowStr,
                ultima_sincronizacao = nowStr
            )
            supabaseClient.postgrest["tvs"].update(update) {
                filter {
                    eq("id", tvId)
                }
            }
            Log.d(TAG, "Updated TV $tvId status to $status")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating TV status", e)
        }
    }

    // Listens to real-time database changes on tvs, playlists, playlist_midias, midias
    fun observeRealtimeChanges(tvId: String): Flow<String> = channelFlow {
        try {
            val channel = supabaseClient.channel("vision_realtime_channel")
            
            // Listen to updates on this specific TV
            val tvChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "tvs"
            }
            
            // Listen to changes in playlist mappings
            val playlistMediaChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "playlist_midias"
            }

            channel.subscribe()

            val job1 = launch {
                tvChanges.collect { action ->
                    if (action is PostgresAction.Update) {
                        val record = action.record
                        val recordId = record["id"]?.toString()?.removeSurrounding("\"")
                        if (recordId == tvId) {
                            Log.d(TAG, "Realtime TV update detected for $tvId")
                            send("tv_updated")
                        }
                    }
                }
            }
            
            val job2 = launch {
                playlistMediaChanges.collect {
                    Log.d(TAG, "Realtime playlist_midias update detected")
                    send("playlist_updated")
                }
            }

            // Keep channel active until closed
            awaitClose {
                job1.cancel()
                job2.cancel()
                kotlinx.coroutines.GlobalScope.launch {
                    try {
                        channel.unsubscribe()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during unsubscribe", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Realtime", e)
        }
    }
}
