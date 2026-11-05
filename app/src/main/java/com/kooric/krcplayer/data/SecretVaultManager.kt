package com.kooric.krcplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.kooric.krcplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    // シークレットロック中の動画ID一覧 (Setで管理するため重複登録は100%発生しない)
    fun getSecretVideoIds(): Set<Long> {
        val set = prefs.getStringSet("secret_locked_ids", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    // 削除済み（非表示）の動画ID一覧
    fun getDeletedVideoIds(): Set<Long> {
        val set = prefs.getStringSet("deleted_video_ids", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    // 通常一覧から隠すべき全ID（シークレット移動分 ＋ 削除分 ＋ 旧hidden_video_ids）
    fun getHiddenVideoIds(): Set<Long> {
        val legacy = (prefs.getStringSet("hidden_video_ids", emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }.toSet()
        return getSecretVideoIds() + getDeletedVideoIds() + legacy
    }

    fun addDeletedVideoIds(ids: Collection<Long>) {
        val current = prefs.getStringSet("deleted_video_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        ids.forEach { current.add(it.toString()) }
        prefs.edit().putStringSet("deleted_video_ids", current).apply()
    }

    // 高速・瞬時シークレット移動（ファイル丸ごとコピーを行わず一瞬でロック登録＆重複完全防止）
    suspend fun moveVideosToVault(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        val currentSecretIds = prefs.getStringSet("secret_locked_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        var addedCount = 0
        for (video in videos) {
            if (currentSecretIds.add(video.id.toString())) {
                addedCount++
            }
        }
        prefs.edit().putStringSet("secret_locked_ids", currentSecretIds).commit()
        addedCount
    }

    // シークレットから通常一覧へ瞬時に戻す
    suspend fun restoreFromVault(secretVideos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        val currentSecretIds = prefs.getStringSet("secret_locked_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val legacyHidden = prefs.getStringSet("hidden_video_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        var restoredCount = 0

        for (video in secretVideos) {
            if (video.id > 0) {
                currentSecretIds.remove(video.id.toString())
                legacyHidden.remove(video.id.toString())
                restoredCount++
            } else {
                // 旧バージョンでファイルコピーされていた場合の復元
                val file = File(video.path)
                val parts = file.name.split("_", limit = 4)
                if (parts.size >= 2) {
                    val originalId = parts[1].toLongOrNull()
                    if (originalId != null) {
                        currentSecretIds.remove(originalId.toString())
                        legacyHidden.remove(originalId.toString())
                    }
                }
                if (file.exists() && file.parentFile == vaultDir) {
                    file.delete()
                }
                restoredCount++
            }
        }

        prefs.edit()
            .putStringSet("secret_locked_ids", currentSecretIds)
            .putStringSet("hidden_video_ids", legacyHidden)
            .commit()
        restoredCount
    }

    // シークレット内の動画一覧を取得（重複排除済み）
    suspend fun getSecretVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val secretIds = getSecretVideoIds()
        val allMediaVideos = VideoScanner.getRawAllVideos(context)
        val lockedVideos = allMediaVideos.filter { secretIds.contains(it.id) }.map {
            it.copy(folderName = "シークレット")
        }

        // 旧方式でvaultDir内にコピーされたファイルがあれば重複しないものだけ統合
        val existingTitles = lockedVideos.map { it.title }.toSet()
        val files = vaultDir.listFiles()?.filter { it.isFile } ?: emptyList()
        val legacyFiles = files.mapIndexedNotNull { index, file ->
            val parts = file.name.split("_", limit = 4)
            val displayTitle = if (parts.size >= 4) parts[3] else file.name
            if (existingTitles.contains(displayTitle)) {
                // 既に重複している旧コピーファイルは自動クリーンアップ
                try { file.delete() } catch (_: Exception) {}
                null
            } else {
                var duration = 0L
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
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

        (lockedVideos + legacyFiles).distinctBy { it.title + "_" + it.size }
    }

    suspend fun deleteFromVault(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        val currentSecretIds = prefs.getStringSet("secret_locked_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val deletedIds = mutableListOf<Long>()
        var deletedCount = 0

        for (video in videos) {
            if (video.id > 0) {
                currentSecretIds.remove(video.id.toString())
                deletedIds.add(video.id)
                try {
                    context.contentResolver.delete(video.uri, null, null)
                } catch (_: Exception) {}
                deletedCount++
            } else {
                try {
                    val file = File(video.path)
                    if (file.exists() && file.parentFile == vaultDir && file.delete()) {
                        deletedCount++
                    }
                } catch (_: Exception) {}
            }
        }

        prefs.edit().putStringSet("secret_locked_ids", currentSecretIds).commit()
        if (deletedIds.isNotEmpty()) {
            addDeletedVideoIds(deletedIds)
        }
        deletedCount
    }
}
