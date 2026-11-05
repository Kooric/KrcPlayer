package com.kooric.krcplayer.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.kooric.krcplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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

    suspend fun moveToVault(sourceUri: Uri, originalTitle: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeName = "secret_${System.currentTimeMillis()}_$originalTitle"
            val targetFile = File(vaultDir, safeName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getSecretVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val files = vaultDir.listFiles() ?: return@withContext emptyList()
        files.filter { it.isFile }.mapIndexed { index, file ->
            val displayTitle = file.name.substringAfter("_").substringAfter("_")
            VideoItem(
                id = -index.toLong() - 1,
                title = displayTitle,
                displayName = displayTitle,
                path = file.absolutePath,
                uri = Uri.fromFile(file),
                duration = 0,
                size = file.length(),
                dateModified = file.lastModified() / 1000,
                folderName = "シークレット"
            )
        }
    }

    suspend fun deleteFromVault(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists() && file.parentFile == vaultDir) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
