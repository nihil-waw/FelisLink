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
package com.meta.usbvideo.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import com.meta.usbvideo.FillMode
import com.meta.usbvideo.SettingsStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Container view for video TextureView or SurfaceView. Implemented as a FrameLayout which can
 * enforce aspect ratio if passed from above.
 */
class VideoContainerView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

  private var videoView: TextureView? = null
  private val settings by lazy { SettingsStore.get(context) }

  fun addVideoTextureView(videoView: TextureView, width: Int, height: Int) {
    this.videoView = videoView
    addView(videoView, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    val videoView = this.videoView ?: return
    val width = videoView.width
    val height = videoView.height
    if (width > 0 && height > 0) {
      videoView.pivotX = width.toFloat() / 2f
      videoView.pivotY = height.toFloat() / 2f
      val scaleX = abs(right - left).toFloat() / width
      val scaleY = abs(bottom - top).toFloat() / height
      when (settings.fillMode) {
        FillMode.ORIGINAL -> {
          // Letterbox: one uniform factor, centred, so both bars are identical.
          val uniform = min(scaleX, scaleY)
          videoView.scaleX = uniform
          videoView.scaleY = uniform
        }
        FillMode.CROP -> {
          // Fill and crop the overflow, still uniform so nothing is distorted.
          val uniform = max(scaleX, scaleY)
          videoView.scaleX = uniform
          videoView.scaleY = uniform
        }
        FillMode.STRETCH -> {
          videoView.scaleX = scaleX
          videoView.scaleY = scaleY
        }
      }
    }
  }
}
