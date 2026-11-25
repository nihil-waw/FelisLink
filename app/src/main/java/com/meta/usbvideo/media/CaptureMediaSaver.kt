/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.meta.usbvideo.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes app-generated media into the shared gallery through MediaStore (no storage permission). */
object CaptureMediaSaver {
  private const val TAG = "CaptureMediaSaver"
  private const val IMAGE_FOLDER = "Pictures/FelisLink"
  const val VIDEO_FOLDER = "Movies/FelisLink"

  fun saveImage(context: Context, bitmap: Bitmap): Uri? {
    val name = fileNameStamp() + ".png"
    val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, name)
          put(MediaStore.Images.Media.MIME_TYPE, "image/png")
          put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FelisLink")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val resolver = context.contentResolver
    val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
      resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
          ?: run {
            resolver.delete(uri, null, null)
            return null
          }
      finishPending(resolver, uri)
      uri
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to save screenshot", t)
      resolver.delete(uri, null, null)
      null
    }
  }

  fun newVideoValues(): ContentValues =
      ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileNameStamp() + ".mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FelisLink")
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }

  fun finishPendingVideo(context: Context, uri: Uri) {
    finishPending(context.contentResolver, uri)
  }

  private fun finishPending(
      resolver: android.content.ContentResolver,
      uri: Uri,
  ) {
    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
    resolver.update(uri, values, null, null)
  }

  private fun fileNameStamp(): String =
      "FelisLink_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
