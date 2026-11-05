package com.kooric.krcplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.kooric.krcplayer.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VideoScanner {

    suspend fun getAllVideos(context: Context): List<VideoItem> = withContext(Dispatchers.IO) {
        val vaultManager = SecretVaultManager(context)
        val hiddenIds = vaultManager.getHiddenVideoIds()

        val videoList = mutableListOf<VideoItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    if (hiddenIds.contains(id)) continue

                    val title = cursor.getString(titleColumn) ?: ""
                    val displayName = cursor.getString(displayNameColumn) ?: ""
                    val path = cursor.getString(dataColumn) ?: ""
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)

                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val parentFolder = if (path.isNotEmpty()) {
                        File(path).parentFile?.name ?: "Videos"
                    } else {
                        "Videos"
                    }

                    videoList.add(
                        VideoItem(
                            id = id,
                            title = if (title.isNotEmpty()) title else displayName,
                            displayName = displayName,
                            path = path,
                            uri = contentUri,
                            duration = duration,
                            size = size,
                            dateModified = dateModified,
                            folderName = parentFolder,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        videoList
    }

    suspend fun deleteVideos(context: Context, videos: List<VideoItem>): Int = withContext(Dispatchers.IO) {
        val vaultManager = SecretVaultManager(context)
        val deletedIds = mutableListOf<Long>()

        for (video in videos) {
            try {
                context.contentResolver.delete(video.uri, null, null)
            } catch (_: Exception) {
                // Scoped storage で直接削除できない場合もアプリリストから隠す
            }
            deletedIds.add(video.id)
        }

        vaultManager.addHiddenVideoIds(deletedIds)
        deletedIds.size
    }
}
