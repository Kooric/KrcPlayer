package com.kooric.krcplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.kooric.krcplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class SecretVaultManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("krc_secret_vault", Context.MODE_PRIVATE)
    private val vaultDir = File(context.filesDir, "secret_vault").apply {
        if (!exists()) mkdirs()
    }

    fun isPinSet(): Boolean {
        return prefs.contains("vault_pin_hash")
    }

    fun setPin(pin: String): Boolean {
        val hash = hashPin(pin)
        return prefs.edit().putString("vault_pin_hash", hash).commit()
    }

    fun verifyPin(pin: String): Boolean {
        val savedHash = prefs.getString("vault_pin_hash", null) ?: return false
        return savedHash == hashPin(pin)
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // シークレット移動済みまたは削除済みの動画ID管理（通常一覧から隠す）
    fun getHiddenVideoIds(): Set<Long> {
        val set = prefs.getStringSet("hidden_video_ids", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun addHiddenVideoIds(ids: Collection<Long>) {
        val current = prefs.getStringSet("hidden_video_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        ids.forEach { current.add(it.toString()) }
        prefs.edit().putStringSet("hidden_video_ids", current).apply()
    }

    fun removeHiddenVideoId(id: Long) {
        val current = prefs.getStringSet("hidden_video_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(id.toString())
        prefs.edit().putStringSet("hidden_video_ids", current).apply()
    }

    suspend fun moveVideosToVault(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        var successCount = 0
        val movedIds = mutableListOf<Long>()

        for (video in videos) {
            try {
                // ファイル名に元のIDとタイトルを保持: secret_{id}_{timestamp}_{title}
                val cleanTitle = video.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val safeName = "secret_${video.id}_${System.currentTimeMillis()}_$cleanTitle"
                val targetFile = File(vaultDir, safeName)

                context.contentResolver.openInputStream(video.uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                movedIds.add(video.id)
                successCount++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (movedIds.isNotEmpty()) {
            addHiddenVideoIds(movedIds)
        }
        successCount
    }

    suspend fun restoreFromVault(secretVideos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        var restoredCount = 0
        for (video in secretVideos) {
            try {
                val file = File(video.path)
                val parts = file.name.split("_", limit = 4)
                if (parts.size >= 2) {
                    val originalId = parts[1].toLongOrNull()
                    if (originalId != null) {
                        removeHiddenVideoId(originalId)
                    }
                }
                if (file.exists() && file.parentFile == vaultDir) {
                    file.delete()
                }
                restoredCount++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        restoredCount
    }

    suspend fun getSecretVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val files = vaultDir.listFiles() ?: return@withContext emptyList()
        files.filter { it.isFile }.sortedByDescending { it.lastModified() }.mapIndexed { index, file ->
            val parts = file.name.split("_", limit = 4)
            val displayTitle = if (parts.size >= 4) parts[3] else file.name

            var duration = 0L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }

            VideoItem(
                id = -index.toLong() - 1000L,
                title = displayTitle,
                displayName = displayTitle,
                path = file.absolutePath,
                uri = Uri.fromFile(file),
                duration = duration,
                size = file.length(),
                dateModified = file.lastModified() / 1000,
                folderName = "シークレット"
            )
        }
    }

    suspend fun deleteFromVault(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        for (video in videos) {
            try {
                val file = File(video.path)
                if (file.exists() && file.parentFile == vaultDir && file.delete()) {
                    deletedCount++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        deletedCount
    }
}
