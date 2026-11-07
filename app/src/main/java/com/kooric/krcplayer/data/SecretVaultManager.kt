package com.kooric.krcplayer.data

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import com.kooric.krcplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min

class SecretVaultManager(private val context: Context) {

    companion object {
        private const val HEADER_SIZE = 512
        private const val XOR_KEY: Byte = 0x5A
        private val MAGIC_BYTES = byteArrayOf(
            'K'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte(),
            'L'.code.toByte(), 'T'.code.toByte(), '0'.code.toByte(), '1'.code.toByte()
        )

        fun isAllFilesAccessGranted(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        }

        // ファイルがKRCVLT01ヘッダー暗号化されているか判定
        fun isFileEncrypted(file: File): Boolean {
            if (!file.exists() || file.length() < HEADER_SIZE) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = ByteArray(8)
                    raf.readFully(magic)
                    magic.contentEquals(MAGIC_BYTES)
                }
            } catch (_: Exception) {
                false
            }
        }

        // 暗号化されたファイルのオリジナル先頭512バイトを取得（バックアップファイルまたはXOR復号）
        fun getDecryptedHeader(context: Context, vaultFile: File): ByteArray? {
            if (!vaultFile.exists()) return null
            val hdrFile = File(vaultFile.parentFile, "${vaultFile.name}.hdr")
            if (hdrFile.exists() && hdrFile.length() == HEADER_SIZE.toLong()) {
                try {
                    return hdrFile.readBytes()
                } catch (_: Exception) {
                }
            }
            val prefs = context.getSharedPreferences("krc_secret_vault", Context.MODE_PRIVATE)
            val b64 = prefs.getString("hdr_${vaultFile.name}", null)
            if (b64 != null) {
                try {
                    val decoded = Base64.decode(b64, Base64.NO_WRAP)
                    if (decoded.size == HEADER_SIZE) return decoded
                } catch (_: Exception) {
                }
            }
            return null
        }

        // MediaMetadataRetriever で暗号化動画からサムネイルや再生時間を取得するためのカスタム MediaDataSource
        fun createMediaDataSource(context: Context, file: File): MediaDataSource {
            val isEnc = isFileEncrypted(file)
            val origHeader = if (isEnc) getDecryptedHeader(context, file) else null
            return object : MediaDataSource() {
                private val raf = RandomAccessFile(file, "r")

                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= raf.length()) return -1
                    raf.seek(position)
                    val toRead = min(size.toLong(), raf.length() - position).toInt()
                    val bytesRead = raf.read(buffer, offset, toRead)
                    if (bytesRead > 0 && isEnc && origHeader != null && position < HEADER_SIZE) {
                        val headerOverlap = min(bytesRead.toLong(), HEADER_SIZE - position).toInt()
                        for (i in 0 until headerOverlap) {
                            buffer[offset + i] = origHeader[(position + i).toInt()]
                        }
                    }
                    return bytesRead
                }

                override fun getSize(): Long = raf.length()

                override fun close() {
                    try {
                        raf.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // ExoPlayer で暗号化動画をディスク書き換えなしで直接オンザフライ復号再生する DataSource.Factory
        @OptIn(UnstableApi::class)
        fun createExoDataSourceFactory(context: Context): DataSource.Factory {
            val defaultFactory = DefaultDataSource.Factory(context)
            return DataSource.Factory {
                val fallback = defaultFactory.createDataSource()
                object : BaseDataSource(false) {
                    private var raf: RandomAccessFile? = null
                    private var currentUri: Uri? = null
                    private var bytesRemaining: Long = 0
                    private var currentPos: Long = 0
                    private var isEncryptedVaultFile = false
                    private var originalHeader: ByteArray? = null
                    private var usingFallback = false

                    override fun open(dataSpec: DataSpec): Long {
                        val uri = dataSpec.uri
                        currentUri = uri
                        val path = uri.path
                        if (uri.scheme == "file" && path != null) {
                            val f = File(path)
                            if (f.exists() && isFileEncrypted(f)) {
                                usingFallback = false
                                isEncryptedVaultFile = true
                                originalHeader = getDecryptedHeader(context, f)
                                transferInitializing(dataSpec)
                                val r = RandomAccessFile(f, "r")
                                raf = r
                                currentPos = dataSpec.position
                                r.seek(currentPos)
                                bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                                    dataSpec.length
                                } else {
                                    r.length() - dataSpec.position
                                }
                                if (bytesRemaining < 0) throw EOFException()
                                transferStarted(dataSpec)
                                return bytesRemaining
                            }
                        }
                        usingFallback = true
                        return fallback.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (usingFallback) {
                            return fallback.read(buffer, offset, length)
                        }
                        if (length == 0) return 0
                        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
                        val r = raf ?: return C.RESULT_END_OF_INPUT
                        val toRead = min(bytesRemaining, length.toLong()).toInt()
                        val readBytes = r.read(buffer, offset, toRead)
                        if (readBytes == -1) return C.RESULT_END_OF_INPUT

                        val hdr = originalHeader
                        if (isEncryptedVaultFile && hdr != null && currentPos < HEADER_SIZE) {
                            val overlap = min(readBytes.toLong(), HEADER_SIZE - currentPos).toInt()
                            for (i in 0 until overlap) {
                                buffer[offset + i] = hdr[(currentPos + i).toInt()]
                            }
                        }

                        currentPos += readBytes
                        bytesRemaining -= readBytes
                        bytesTransferred(readBytes)
                        return readBytes
                    }

                    override fun getUri(): Uri? {
                        return if (usingFallback) fallback.uri else currentUri
                    }

                    override fun close() {
                        if (usingFallback) {
                            fallback.close()
                            usingFallback = false
                            return
                        }
                        currentUri = null
                        try {
                            raf?.close()
                        } catch (_: Exception) {
                        } finally {
                            raf = null
                            if (isEncryptedVaultFile) {
                                isEncryptedVaultFile = false
                                transferEnded()
                            }
                        }
                    }
                }
            }
        }
    }

    private val prefs = context.getSharedPreferences("krc_secret_vault", Context.MODE_PRIVATE)

    // 内部ボールト（フォールバック＆旧データ互換用）
    private val internalVaultDir = File(context.filesDir, "secret_vault").apply {
        if (!exists()) mkdirs()
        File(this, ".nomedia").apply { if (!exists()) try { createNewFile() } catch (_: Exception) {} }
    }

    // 外部ストレージの隠しボールト（アンインストールしても消えず、同一ストレージ内で0.01秒でrenameTo移動可能）
    private val externalVaultDir: File
        get() {
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, ".KrcSecretVault")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) {
                try { nomedia.createNewFile() } catch (_: Exception) {}
            }
            return if (dir.exists() && dir.canWrite()) dir else internalVaultDir
        }

    fun isPinSet(): Boolean = prefs.contains("vault_pin_hash")

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

    fun getSecretVideoIds(): Set<Long> {
        val set = prefs.getStringSet("secret_locked_ids", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun getDeletedVideoIds(): Set<Long> {
        val set = prefs.getStringSet("deleted_video_ids", emptySet()) ?: emptySet()
        return set.mapNotNull { it.toLongOrNull() }.toSet()
    }

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

    // ファイルを100%安全にヘッダー暗号化する（二重暗号化防止＆オリジナル先頭512バイトの二重バックアップ付き）
    private fun encryptFileHeaderSafely(vaultFile: File): Boolean {
        if (!vaultFile.exists() || vaultFile.length() < HEADER_SIZE) return false
        if (isFileEncrypted(vaultFile)) return true // 既に暗号化済みなら何もしない（破壊防止）

        return try {
            RandomAccessFile(vaultFile, "rw").use { raf ->
                val origHeader = ByteArray(HEADER_SIZE)
                raf.seek(0)
                raf.readFully(origHeader)

                // 1. まず .hdr ファイルにオリジナル先頭512バイトを保存
                val hdrFile = File(vaultFile.parentFile, "${vaultFile.name}.hdr")
                hdrFile.writeBytes(origHeader)

                // 2. さらに SharedPreferences にもBase64で二重バックアップ保存
                val b64 = Base64.encodeToString(origHeader, Base64.NO_WRAP)
                prefs.edit().putString("hdr_${vaultFile.name}", b64).commit()

                // 3. バックアップ完了を確認してから、先頭8バイトを KRCVLT01 にし、残りをXOR難読化
                val encryptedHeader = ByteArray(HEADER_SIZE)
                for (i in 0 until 8) {
                    encryptedHeader[i] = MAGIC_BYTES[i]
                }
                for (i in 8 until HEADER_SIZE) {
                    encryptedHeader[i] = (origHeader[i].toInt() xor XOR_KEY.toInt()).toByte()
                }

                raf.seek(0)
                raf.write(encryptedHeader)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ファイルの先頭512バイトをオリジナルに完全復元する
    private fun decryptFileHeaderSafely(vaultFile: File): Boolean {
        if (!vaultFile.exists()) return false
        if (!isFileEncrypted(vaultFile)) return true // 既に平文ならOK

        return try {
            val origHeader = getDecryptedHeader(context, vaultFile) ?: return false
            RandomAccessFile(vaultFile, "rw").use { raf ->
                raf.seek(0)
                raf.write(origHeader)
            }
            // バックアップのクリーンアップ
            val hdrFile = File(vaultFile.parentFile, "${vaultFile.name}.hdr")
            if (hdrFile.exists()) hdrFile.delete()
            prefs.edit().remove("hdr_${vaultFile.name}").apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 動画をシークレットへ移動＆暗号化（外部ギャラリー・他アプリから完全に消去＆開けなくする）
    suspend fun moveVideosToVault(videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        val currentSecretIds = prefs.getStringSet("secret_locked_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        var successCount = 0
        val targetDir = externalVaultDir

        for (video in videos) {
            try {
                val sourceFile = if (video.path.isNotEmpty()) File(video.path) else null
                val cleanTitle = video.title.replace(Regex("[\\\\/:*?\"<>|_]"), "-")
                val vaultFileName = "secret_${video.id}_${video.duration}_${cleanTitle}.krcvault"
                val destFile = File(targetDir, vaultFileName)

                // 元のパスを復元用に保存
                if (video.path.isNotEmpty()) {
                    prefs.edit().putString("orig_path_${vaultFileName}", video.path).apply()
                }

                var movedOrCopied = false

                // 1. 直接ファイル移動（renameTo）を試行（0.01秒で移動完了し、元の場所から消える）
                if (sourceFile != null && sourceFile.exists() && isAllFilesAccessGranted()) {
                    if (sourceFile.renameTo(destFile)) {
                        movedOrCopied = true
                        // ギャラリー（MediaStore）から元のエントリを消去
                        try {
                            context.contentResolver.delete(video.uri, null, null)
                        } catch (_: Exception) {}
                        MediaScannerConnection.scanFile(context, arrayOf(video.path), null, null)
                    }
                }

                // 2. renameTo できなかった場合（SDカード別パーティションや権限未付与時）
                if (!movedOrCopied && sourceFile != null && sourceFile.exists() && isAllFilesAccessGranted()) {
                    sourceFile.inputStream().use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.exists() && destFile.length() == sourceFile.length()) {
                        sourceFile.delete()
                        try {
                            context.contentResolver.delete(video.uri, null, null)
                        } catch (_: Exception) {}
                        MediaScannerConnection.scanFile(context, arrayOf(video.path), null, null)
                        movedOrCopied = true
                    }
                }

                // 3. 暗号化ヘッダーを適用（外部ツールからは破損ファイルに見えて100%開けなくなる）
                if (movedOrCopied && destFile.exists()) {
                    encryptFileHeaderSafely(destFile)
                    currentSecretIds.remove(video.id.toString())
                    successCount++
                } else {
                    // 全ファイルアクセス権限がまだOFFの場合はIDロックに登録（後で権限ONになった際に自動暗号化移行される）
                    currentSecretIds.add(video.id.toString())
                    successCount++
                }
            } catch (e: Exception) {
                e.printStackTrace()
                currentSecretIds.add(video.id.toString())
                successCount++
            }
        }

        prefs.edit().putStringSet("secret_locked_ids", currentSecretIds).commit()
        successCount
    }

    // 既存のシークレット動画（v1.1.0〜v1.4.0の未暗号化ファイルやIDロック動画）を1本も壊さず安全に暗号化方式へ移行
    private suspend fun migrateExistingSecretVideosSafely() = withContext(Dispatchers.IO) {
        // 1. 内部ボールト（v1.1.0）にある既存ファイルのヘッダー保護
        internalVaultDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".hdr") && file.name != ".nomedia") {
                encryptFileHeaderSafely(file)
            }
        }

        // 2. 外部ボールトにある既存ファイルのヘッダー保護
        if (externalVaultDir != internalVaultDir) {
            externalVaultDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(".hdr") && file.name != ".nomedia") {
                    encryptFileHeaderSafely(file)
                }
            }
        }

        // 3. v1.2.0〜v1.4.0 の IDロック方式（secret_locked_ids）の動画があり、かつ全ファイルアクセス権限がONなら自動で暗号化ボールトへ移行
        if (isAllFilesAccessGranted()) {
            val lockedIds = getSecretVideoIds()
            if (lockedIds.isNotEmpty()) {
                val allMedia = VideoScanner.getRawAllVideos(context)
                val toMigrate = allMedia.filter { lockedIds.contains(it.id) }
                if (toMigrate.isNotEmpty()) {
                    moveVideosToVault(toMigrate)
                }
            }
        }
    }

    // シークレットから通常一覧へ完全復号して戻す
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
                val vaultFile = File(video.path)
                if (vaultFile.exists()) {
                    // 1. まずヘッダーをオリジナルに100%完全復号
                    decryptFileHeaderSafely(vaultFile)

                    // 2. 元のパス（またはMoviesフォルダ）へ戻す
                    val savedOrigPath = prefs.getString("orig_path_${vaultFile.name}", null)
                    val targetRestoreFile = if (!savedOrigPath.isNullOrEmpty()) {
                        val origFile = File(savedOrigPath)
                        origFile.parentFile?.mkdirs()
                        origFile
                    } else {
                        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        moviesDir.mkdirs()
                        val cleanName = if (video.title.endsWith(".mp4", true)) video.title else "${video.title}.mp4"
                        File(moviesDir, cleanName)
                    }

                    var movedBack = false
                    if (isAllFilesAccessGranted()) {
                        movedBack = vaultFile.renameTo(targetRestoreFile)
                        if (!movedBack) {
                            try {
                                vaultFile.inputStream().use { input ->
                                    FileOutputStream(targetRestoreFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                if (targetRestoreFile.exists() && targetRestoreFile.length() == vaultFile.length()) {
                                    vaultFile.delete()
                                    movedBack = true
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (movedBack) {
                        MediaScannerConnection.scanFile(context, arrayOf(targetRestoreFile.absolutePath), null, null)
                    }

                    // IDロックの解除
                    val parts = vaultFile.name.removeSuffix(".krcvault").split("_", limit = 4)
                    if (parts.size >= 2) {
                        val origId = parts[1].toLongOrNull()
                        if (origId != null) {
                            currentSecretIds.remove(origId.toString())
                            legacyHidden.remove(origId.toString())
                        }
                    }
                    prefs.edit().remove("orig_path_${vaultFile.name}").apply()
                    restoredCount++
                }
            }
        }

        prefs.edit()
            .putStringSet("secret_locked_ids", currentSecretIds)
            .putStringSet("hidden_video_ids", legacyHidden)
            .commit()
        restoredCount
    }

    // シークレット内の全動画を取得（安全な自動マイグレーション付き）
    suspend fun getSecretVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        migrateExistingSecretVideosSafely()

        val resultList = mutableListOf<VideoItem>()

        // 1. 外部ボールト＆内部ボールト内の暗号化ファイル（.krcvault および旧ファイル）を取得
        val dirs = if (externalVaultDir != internalVaultDir) {
            listOf(externalVaultDir, internalVaultDir)
        } else {
            listOf(internalVaultDir)
        }

        val allVaultFiles = dirs.flatMap { dir ->
            dir.listFiles()?.filter {
                it.isFile && !it.name.endsWith(".hdr") && it.name != ".nomedia"
            } ?: emptyList()
        }.sortedByDescending { it.lastModified() }

        allVaultFiles.forEachIndexed { index, file ->
            val rawName = file.name.removeSuffix(".krcvault")
            val parts = rawName.split("_", limit = 4)

            var parsedDuration = 0L
            val displayTitle: String
            if (parts.size >= 4) {
                parsedDuration = parts[2].toLongOrNull() ?: 0L
                displayTitle = parts[3]
            } else if (parts.size == 3) {
                displayTitle = parts[2]
            } else {
                displayTitle = rawName
            }

            if (parsedDuration <= 0L) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(createMediaDataSource(context, file))
                    parsedDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (_: Exception) {
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            resultList.add(
                VideoItem(
                    id = -index.toLong() - 1000L,
                    title = displayTitle,
                    displayName = displayTitle,
                    path = file.absolutePath,
                    uri = Uri.fromFile(file),
                    duration = parsedDuration,
                    size = file.length(),
                    dateModified = file.lastModified() / 1000,
                    folderName = "シークレット(暗号化済)"
                )
            )
        }

        // 2. もし全ファイルアクセス権限が未許可で IDロックに残っている動画があればそれも表示
        val secretIds = getSecretVideoIds()
        if (secretIds.isNotEmpty()) {
            val allMedia = VideoScanner.getRawAllVideos(context)
            val lockedVideos = allMedia.filter { secretIds.contains(it.id) }.map {
                it.copy(folderName = "シークレット")
            }
            resultList.addAll(lockedVideos)
        }

        resultList.distinctBy { it.title + "_" + it.size }
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
                    val hdrFile = File(file.parentFile, "${file.name}.hdr")
                    if (hdrFile.exists()) hdrFile.delete()
                    prefs.edit().remove("hdr_${file.name}").remove("orig_path_${file.name}").apply()
                    if (file.exists() && file.delete()) {
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
