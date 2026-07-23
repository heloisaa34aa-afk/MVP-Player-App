package com.example.data.download

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.MidiaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MediaDownloadManager(private val context: Context) {
    private val client = OkHttpClient()
    private val mediaDir: File by lazy {
        File(context.getExternalFilesDir("media"), "").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    companion object {
        private const val TAG = "MediaDownloadManager"
        private const val MIN_FREE_SPACE_BYTES = 50 * 1024 * 1024 // 50MB safety buffer
    }

    /**
     * Downloads a media item and returns the local file path if successful.
     * Always plays/exhibits from the downloaded local file.
     */
    suspend fun downloadMedia(media: MidiaEntity): String? = withContext(Dispatchers.IO) {
        val url = resolveMediaUrl(media) ?: return@withContext null
        val extension = getFileExtension(url, media.tipo)
        val destinationFile = File(mediaDir, "${media.id}$extension")

        // If file already exists and is not empty, reuse it
        if (destinationFile.exists() && destinationFile.length() > 0) {
            Log.d(TAG, "Arquivo encontrado em cache\nNome: ${media.nome}")
            return@withContext destinationFile.absolutePath
        }

        Log.d(TAG, "Arquivo inexistente\nIniciando download\nNome: ${media.nome}")

        // Check usable space before downloading
        val usableSpace = mediaDir.usableSpace
        if (usableSpace < MIN_FREE_SPACE_BYTES) {
            Log.e(TAG, "Insufficient disk space for download. Free space: $usableSpace bytes. Required buffer: $MIN_FREE_SPACE_BYTES")
            return@withContext null
        }

        val request = Request.Builder().url(url).build()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download media ${media.id}: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body
                if (body == null) {
                    Log.e(TAG, "Empty body response for media ${media.id}")
                    return@withContext null
                }

                // Double check space if Content-Length is provided
                val contentLength = body.contentLength()
                if (contentLength > 0 && usableSpace < contentLength + MIN_FREE_SPACE_BYTES) {
                    Log.e(TAG, "Insufficient disk space for Content-Length $contentLength bytes.")
                    return@withContext null
                }

                FileOutputStream(destinationFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                Log.d(TAG, "Download complete for ${media.id}: ${destinationFile.absolutePath}")
                destinationFile.absolutePath
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException downloading media ${media.id}", e)
            if (destinationFile.exists()) {
                destinationFile.delete() // Clean up partial downloads
            }
            null
        }
    }

    /**
     * Resolves the storage or external URL for a given media.
     */
    private fun resolveMediaUrl(media: MidiaEntity): String? {
        return if (media.origem == "storage") {
            val path = media.url_storage ?: return null
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                // Construct the public storage URL
                "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/midias/$path"
            }
        } else {
            media.url_externa
        }
    }

    private fun getFileExtension(url: String, type: String): String {
        val lastSegment = url.substringAfterLast('/')
        val ext = lastSegment.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext.length <= 4) {
            return ".$ext"
        }
        return if (type == "video") ".mp4" else ".jpg"
    }

    /**
     * Purges files from disk that are no longer referenced in the active playlist.
     */
    fun cleanupUnusedMedia(activeMediaIds: Set<String>) {
        try {
            val files = mediaDir.listFiles() ?: return
            for (file in files) {
                val mediaId = file.nameWithoutExtension
                if (!activeMediaIds.contains(mediaId)) {
                    val deleted = file.delete()
                    Log.d(TAG, "Cleaned up unused media file: ${file.name}, Deleted: $deleted")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    fun getCacheSize(): Long {
        return try {
            mediaDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun getCachedMediaCount(): Int {
        return try {
            mediaDir.listFiles()?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun clearAll() {
        try {
            mediaDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing media cache", e)
        }
    }
}
