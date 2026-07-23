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

    var isConfigValid: Boolean = false
        private set

    init {
        val url = try { BuildConfig.SUPABASE_URL } catch (e: Throwable) { "" }
        val anonKey = try { BuildConfig.SUPABASE_ANON_KEY } catch (e: Throwable) { "" }

        val urlOk = url.isNotBlank() && !url.contains("placeholder") && !url.contains("SEU-PROJETO")
        val keyOk = anonKey.isNotBlank() && !anonKey.contains("placeholder") && !anonKey.contains("SUA_CHAVE_ANON")

        if (!urlOk || !keyOk) {
            if (!urlOk) {
                Log.e(TAG, "ERRO: SUPABASE_URL não configurada.")
            }
            if (!keyOk) {
                Log.e(TAG, "ERRO: SUPABASE_ANON_KEY não configurada.")
            }
            isConfigValid = false
        } else {
            Log.d(TAG, "✓ BuildConfig carregado")
            Log.d(TAG, "✓ SUPABASE_URL encontrada")
            Log.d(TAG, "✓ SUPABASE_ANON_KEY encontrada")
            isConfigValid = true
        }
    }

    val supabaseClient: SupabaseClient by lazy {
        if (!isConfigValid) {
            val errorMsg = "Não é possível inicializar o cliente Supabase devido a configurações inválidas ou ausentes."
            Log.e(TAG, errorMsg)
            throw IllegalStateException(errorMsg)
        }

        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        Log.d(TAG, "✓ Inicializando Supabase")
        Log.d(TAG, "✓ Conectando...")
        val client = createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
        Log.d(TAG, "✓ Conexão realizada com sucesso")
        client
    }

    suspend fun getTvByToken(token: String): TvDto? {
        return try {
            val formattedToken = token.trim().uppercase()
            Log.d(TAG, "[ENTRY] getTvByToken - Token recebido: '$token', Token formatado: '$formattedToken'")
            
            Log.d(TAG, "[DEBUG] Consulta enviada ao Supabase para a tabela 'tvs' filtrando token='$formattedToken'")
            val response = supabaseClient.postgrest["tvs"].select {
                filter {
                    eq("token", formattedToken)
                }
            }
            
            Log.d(TAG, "[DEBUG] Resposta bruta recebida do Supabase: ${response.data}")
            
            val result = response.decodeSingleOrNull<TvDto>()
            
            if (result != null) {
                Log.d(TAG, "[EXIT] TV encontrada: $result")
            } else {
                Log.d(TAG, "[EXIT] TV não encontrada (decodeSingleOrNull retornou null)")
                Log.d(TAG, "[DEBUG] Motivo provável da falha: O token não existe, a resposta está vazia '[]', ou há restrições de RLS (Row Level Security) impedindo a leitura.")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] getTvByToken falhou com a seguinte exceção:", e)
            null
        }
    }

    suspend fun getTvById(id: String): TvDto? {
        return try {
            Log.d(TAG, "[ENTRY] getTvById - ID: $id")
            val response = supabaseClient.postgrest["tvs"].select {
                filter {
                    eq("id", id)
                }
            }
            Log.d(TAG, "[DEBUG] Resposta bruta (tvs): ${response.data}")
            val result = response.decodeSingleOrNull<TvDto>()
            Log.d(TAG, "[EXIT] getTvById - Result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] getTvById failed for ID: $id com a seguinte exceção:", e)
            null
        }
    }

    suspend fun getClienteById(id: String): ClienteDto? {
        return try {
            Log.d(TAG, "[ENTRY] getClienteById - ID: $id")
            val response = supabaseClient.postgrest["clientes"].select {
                filter {
                    eq("id", id)
                }
            }
            Log.d(TAG, "[DEBUG] Resposta bruta (clientes): ${response.data}")
            val result = response.decodeSingleOrNull<ClienteDto>()
            Log.d(TAG, "[EXIT] getClienteById - Result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] getClienteById failed for ID: $id com a seguinte exceção:", e)
            null
        }
    }

    suspend fun getPlaylistById(id: String): PlaylistDto? {
        return try {
            Log.d(TAG, "[ENTRY] getPlaylistById - ID: $id")
            val response = supabaseClient.postgrest["playlists"].select {
                filter {
                    eq("id", id)
                }
            }
            Log.d(TAG, "[DEBUG] Resposta bruta (playlists): ${response.data}")
            val result = response.decodeSingleOrNull<PlaylistDto>()
            Log.d(TAG, "[EXIT] getPlaylistById - Result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] getPlaylistById failed for ID: $id com a seguinte exceção:", e)
            null
        }
    }

    suspend fun getPlaylistMidias(playlistId: String): List<PlaylistMidiaDto> {
        return try {
            Log.d(TAG, "[ENTRY] getPlaylistMidias - Playlist ID: $playlistId")
            val response = supabaseClient.postgrest["playlist_midias"].select {
                filter {
                    eq("playlist_id", playlistId)
                }
            }
            Log.d(TAG, "[DEBUG] Resposta bruta (playlist_midias): ${response.data}")
            val result = response.decodeList<PlaylistMidiaDto>()
            Log.d(TAG, "[EXIT] getPlaylistMidias - Result size: ${result.size}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] getPlaylistMidias failed for Playlist ID: $playlistId com a seguinte exceção:", e)
            emptyList()
        }
    }

    suspend fun getMidiaById(id: String): MidiaDto? {
        return try {
            Log.d(TAG, "[ENTRY] getMidiaById - ID: $id")
            val response = supabaseClient.postgrest["midias"].select {
                filter {
                    eq("id", id)
                }
            }
            Log.d(TAG, "[DEBUG] Resposta bruta (midias): ${response.data}")
            val result = response.decodeSingleOrNull<MidiaDto>()
            Log.d(TAG, "[EXIT] getMidiaById - Result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] getMidiaById failed for ID: $id com a seguinte exceção:", e)
            null
        }
    }

    suspend fun updateTvStatus(tvId: String, status: String, uptime: String) {
        try {
            Log.d(TAG, "[ENTRY] updateTvStatus - TV ID: $tvId, Status: $status, Uptime: $uptime")
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
            Log.d(TAG, "[EXIT] updateTvStatus - Updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] updateTvStatus failed", e)
        }
    }

    // Listens to real-time database changes on tvs, playlists, playlist_midias, midias
    fun observeRealtimeChanges(tvId: String): Flow<String> = channelFlow {
        try {
            val channel = supabaseClient.channel("vision_realtime_channel")
            
            // Listen to updates on this specific TV
            val tvChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "tvs"
                filter = "id=eq.$tvId"
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
                            send("tv_updated:" + record.toString())
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
