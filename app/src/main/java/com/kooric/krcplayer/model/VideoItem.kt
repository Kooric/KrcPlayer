package com.kooric.krcplayer.model

import android.net.Uri

data class VideoItem(
    val id: Long,
    val title: String,
    val displayName: String,
    val path: String,
    val uri: Uri,
    val duration: Long, // ミリ秒
    val size: Long,     // バイト
    val dateModified: Long,
    val folderName: String,
    val width: Int = 0,
    val height: Int = 0
) {
    fun getFormattedDuration(): String {
        val totalSeconds = duration / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun getFormattedSize(): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }
}

data class FolderItem(
    val name: String,
    val path: String,
    val videoCount: Int,
    val thumbnailUri: Uri?
)
