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
package com.meta.usbvideo

import android.app.AlertDialog
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout.LayoutParams
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meta.usbvideo.media.CaptureMediaSaver
import com.meta.usbvideo.media.CaptureVideoRecorder
import com.meta.usbvideo.media.RecordingState
import com.meta.usbvideo.ui.VideoContainerView
import com.meta.usbvideo.usb.StutterMonitor
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoFormatSelectionState
import com.meta.usbvideo.usb.loggingName
import kotlinx.coroutines.launch

private const val TAG = "StreamingViewHolder"

class StreamingViewHolder(
    private val rootView: View,
    private val streamerViewModel: StreamerViewModel,
    private val lifecycleOwner: LifecycleOwner,
    private val onOpenSettings: () -> Unit,
) : StreamerScreenViewHolder(rootView) {

  private val settings = SettingsStore.get(rootView.context)
  private var overlayMode: OverlayMode = OverlayMode.INITIAL_STATS
  val streamingStats: TextView = rootView.findViewById(R.id.streaming_stats)
  val videoFrame: VideoContainerView = rootView.findViewById(R.id.video_container)
  private val videoTextureView: TextureView = TextureView(videoFrame.context)

  private var lastUpdatedAt = 0L
  private var stateTransitionAt = 0L
  private var checkUSBSpeed = false

  // ---- frame-rate watch dog (independent of the HUD, which may be hidden) ----
  private var framesInWindow = 0
  private var windowStartAt = 0L
  private var stutterMonitor: StutterMonitor? = null
  private var boundFormat: VideoFormat? = null
  private var monitoredTargetFps = 0
  private var stutterDialogShown = false

  // ---- dead-stream recovery (ticker, because a dead stream produces no frame callbacks) ----
  private val handler = Handler(Looper.getMainLooper())
  private var lastFrameAt = 0L
  private var reconnectAttempts = 0
  private var reconnectFailedNotified = false
  private var bindAt = 0L
  private var watchdogRunning = false

  private val watchdog =
      object : Runnable {
        override fun run() {
          handler.postDelayed(this, 1_000L)
          if (!videoTextureView.isAttachedToWindow) {
            return
          }
          syncFormatIfChanged()
          syncFillModeIfChanged()
          reportRecordingError()
          val now = SystemClock.uptimeMillis()
          if (now < bindAt + 5_000L || reconnectAttempts >= 2) {
            return
          }
          if (lastFrameAt == 0L) {
            if (now - bindAt >= 12_000L) {
              handleDeadStream(now)
            }
          } else if (now - lastFrameAt >= 5_000L) {
            handleDeadStream(now)
          }
        }
      }

  // ---- pinch zoom / pan ----
  private val zoomMatrix = Matrix()
  private var zoomScale = 1f
  private var effectiveScaleX = 1f
  private var effectiveScaleY = 1f
  private var panX = 0f
  private var panY = 0f
  private var lastTouchX = 0f
  private var lastTouchY = 0f

  init {
    videoTextureView.surfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
          override fun onSurfaceTextureAvailable(
              surfaceTexture: SurfaceTexture,
              width: Int,
              height: Int,
          ) {
            Log.d(
                TAG,
                "onSurfaceTextureAvailable() called with: surface = $surfaceTexture, width = $width, height = $height",
            )
            streamerViewModel.surfaceTextureAvailable(surfaceTexture, width, height)
          }

          override fun onSurfaceTextureSizeChanged(
              surface: SurfaceTexture,
              width: Int,
              height: Int,
          ) {
            videoFrame.invalidate()
            clampPan()
            applyZoom()
            Log.d(
                TAG,
                "onSurfaceTextureSizeChanged() called with: surface = $surface, width = $width, height = $height",
            )
          }

          override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            Log.d(TAG, "onSurfaceTextureDestroyed() called with: surface = $surfaceTexture")
            streamerViewModel.surfaceTextureDestroyed(surfaceTexture)
            return true
          }

          override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
            trackFrameRate()
            maybeSubmitRecordingFrame()
            if (!settings.hudEnabled) {
              if (streamingStats.isVisible) {
                streamingStats.isVisible = false
              }
              return
            }
            if (overlayMode == OverlayMode.NONE) {
              return
            }
            if (checkUSBSpeed) {
              showDegradedUsbSpeedIfSlow()
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastUpdatedAt > 999) {
              val streamingStatsSummaryText =
                  if (overlayMode == OverlayMode.TOGGLE_TIP) {
                    rootView.context.getText(R.string.streaming_stats_toggle_tip)
                  } else {
                    streamerViewModel.getStreamingStatsSummaryString()
                  }
              streamingStats.text = streamingStatsSummaryText
              streamingStats.isVisible = streamingStatsSummaryText.isNotEmpty()
              lastUpdatedAt = now
            }

            if (stateTransitionAt == 0L) {
              stateTransitionAt = now
            } else {
              when (overlayMode) {
                OverlayMode.INITIAL_STATS -> {
                  if (now - stateTransitionAt > 10_000) {
                    updateOverlayMode(overlayMode.next())
                  }
                }
                OverlayMode.TOGGLE_TIP -> {
                  if (now - stateTransitionAt > 5_000) {
                    updateOverlayMode(overlayMode.next())
                  }
                }
                else -> Unit
              }
            }
          }
        }

    videoFrame.addVideoTextureView(
        videoTextureView,
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
    )
    // The watchdog must never outlive the view, otherwise a destroyed activity stays referenced.
    videoTextureView.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
          override fun onViewAttachedToWindow(v: View) {
            startWatchdog()
          }

          override fun onViewDetachedFromWindow(v: View) {
            watchdogRunning = false
            handler.removeCallbacks(watchdog)
          }
        }
    )
    rootView.setOnTouchListener { _, event -> handleTouch(event) }
  }

  // ---- screenshot / recording ----

  private val captureTools: View = rootView.findViewById(R.id.capture_tools)
  private val captureShotButton: TextView = rootView.findViewById(R.id.capture_shot_button)
  private val captureRecordButton: TextView = rootView.findViewById(R.id.capture_record_button)

  init {
    captureShotButton.setOnClickListener {
      if (!settings.touchLocked) {
        captureScreenshot()
      }
    }
    captureRecordButton.setOnClickListener {
      if (!settings.touchLocked) {
        toggleRecording()
      }
    }
    lifecycleOwner.lifecycleScope.launch {
      lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        RecordingState.running.collect { running -> updateRecordButton(running) }
      }
    }
    updateCaptureToolsVisibility()
  }

  private fun updateRecordButton(running: Boolean) {
    captureRecordButton.setText(
        if (running) R.string.capture_record_stop else R.string.capture_record_start
    )
  }

  private fun updateCaptureToolsVisibility() {
    captureTools.isVisible = settings.hudEnabled && overlayMode != OverlayMode.NONE
  }

  // ---- in-app recording: the capture picture, not the phone screen ----

  private var lastRecordFrameAt = 0L
  private var recordFrameIntervalMs = 33L
  private var recordingErrorShown = false

  private fun toggleRecording() {
    if (CaptureVideoRecorder.isRecording()) {
      CaptureVideoRecorder.stop()
      Toast.makeText(rootView.context, R.string.recording_saved, Toast.LENGTH_SHORT).show()
      return
    }
    val format = currentFormat() ?: run {
      Toast.makeText(rootView.context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
      return
    }
    val viewWidth = videoTextureView.width
    val viewHeight = videoTextureView.height
    if (viewWidth <= 0 || viewHeight <= 0) {
      Toast.makeText(rootView.context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
      return
    }
    recordFrameIntervalMs = (1000L / minOf(format.fps, 30)).coerceAtLeast(16L)
    recordingErrorShown = false
    lastRecordFrameAt = 0L
    if (CaptureVideoRecorder.start(rootView.context, viewWidth, viewHeight, format.fps)) {
      Toast.makeText(rootView.context, R.string.recording_started, Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(rootView.context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
    }
  }

  /** Pumps one bitmap per frame interval into the encoder; cheap no-op when not recording. */
  private fun maybeSubmitRecordingFrame() {
    if (!CaptureVideoRecorder.isRecording()) {
      return
    }
    val now = SystemClock.uptimeMillis()
    if (now - lastRecordFrameAt < recordFrameIntervalMs) {
      return
    }
    val viewWidth = videoTextureView.width
    val viewHeight = videoTextureView.height
    if (viewWidth <= 0 || viewHeight <= 0) {
      return
    }
    val (bitmapWidth, bitmapHeight) = recordingBitmapSize(viewWidth, viewHeight)
    val bitmap =
        try {
          videoTextureView.getBitmap(bitmapWidth, bitmapHeight)
        } catch (t: Throwable) {
          Log.e(TAG, "Unable to grab a frame for recording", t)
          null
        } ?: return
    if (CaptureVideoRecorder.submitFrame(bitmap)) {
      lastRecordFrameAt = now
    }
  }

  private fun recordingBitmapSize(viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
    var width = viewWidth
    var height = viewHeight
    val longest = maxOf(width, height)
    if (longest > 1920) {
      val scale = 1920f / longest
      width = (width * scale).toInt()
      height = (height * scale).toInt()
    }
    return ((width / 2) * 2).coerceAtLeast(2) to ((height / 2) * 2).coerceAtLeast(2)
  }

  private fun captureScreenshot() {
    val bitmap =
        try {
          videoTextureView.getBitmap()
        } catch (t: Throwable) {
          Log.e(TAG, "TextureView.getBitmap() failed", t)
          null
        }
    if (bitmap == null) {
      Toast.makeText(rootView.context, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
      return
    }
    val uri = CaptureMediaSaver.saveImage(rootView.context, bitmap)
    bitmap.recycle()
    Toast.makeText(
            rootView.context,
            if (uri != null) R.string.screenshot_saved else R.string.screenshot_failed,
            Toast.LENGTH_SHORT,
        )
        .show()
  }

  // ---- gestures: tap = HUD, long press = settings, pinch = zoom, drag = pan while zoomed ----

  private fun handleTouch(event: MotionEvent): Boolean {
    if (settings.touchLocked) {
      // Swallow everything so a stray touch cannot pull up the HUD or settings. Long press unlocks
      // so the user is never stuck.
      lockDetector.onTouchEvent(event)
      return true
    }
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    if (zoomScale > 1f && event.pointerCount == 1) {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          lastTouchX = event.x
          lastTouchY = event.y
        }
        MotionEvent.ACTION_MOVE -> {
          panX += event.x - lastTouchX
          panY += event.y - lastTouchY
          lastTouchX = event.x
          lastTouchY = event.y
          clampPan()
          applyZoom()
        }
      }
    }
    return true
  }

  private val lockDetector =
      GestureDetector(
          rootView.context,
          object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
              settings.touchLocked = false
              Toast.makeText(rootView.context, R.string.touch_unlocked, Toast.LENGTH_SHORT).show()
            }
          },
      )

  private val scaleDetector =
      ScaleGestureDetector(
          rootView.context,
          object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
              zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 5f)
              if (zoomScale <= 1f) {
                panX = 0f
                panY = 0f
              }
              applyZoom()
              return true
            }
          },
      )

  private val gestureDetector =
      GestureDetector(
          rootView.context,
          object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
              toggleHud()
              return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
              resetZoom()
              return true
            }

            override fun onLongPress(e: MotionEvent) {
              onOpenSettings()
            }
          },
      )

  private fun toggleHud() {
    if (!settings.hudEnabled) {
      return
    }
    if (overlayMode == OverlayMode.STREAMING_STATS) {
      updateOverlayMode(OverlayMode.NONE)
    } else {
      updateOverlayMode(overlayMode.next())
    }
  }

  /** Pinch zoom + pan on top of whatever the container's fill-mode scaling does. */
  private fun applyZoom() {
    val viewWidth = videoTextureView.width.toFloat()
    val viewHeight = videoTextureView.height.toFloat()
    zoomMatrix.reset()
    if (viewWidth <= 0f || viewHeight <= 0f) {
      return
    }
    effectiveScaleX = zoomScale
    effectiveScaleY = zoomScale
    clampPan()
    zoomMatrix.postScale(zoomScale, zoomScale, viewWidth / 2f, viewHeight / 2f)
    zoomMatrix.postTranslate(panX, panY)
    videoTextureView.setTransform(zoomMatrix)
  }

  private fun clampPan() {
    val maxX = (((effectiveScaleX - 1f) * videoTextureView.width) / 2f).coerceAtLeast(0f)
    val maxY = (((effectiveScaleY - 1f) * videoTextureView.height) / 2f).coerceAtLeast(0f)
    panX = panX.coerceIn(-maxX, maxX)
    panY = panY.coerceIn(-maxY, maxY)
  }

  private fun resetZoom() {
    zoomScale = 1f
    panX = 0f
    panY = 0f
    applyZoom()
  }

  // ---- self-healing: stutter downgrade + dead-stream restart ----

  private fun currentFormat(): VideoFormat? =
      (streamerViewModel.videoFormatSelectionStateFlow.value as? VideoFormatSelectionState.Negotiated)
          ?.videoFormat

  private fun trackFrameRate() {
    framesInWindow++
    lastFrameAt = SystemClock.uptimeMillis()
    val now = SystemClock.uptimeMillis()
    if (windowStartAt == 0L) {
      windowStartAt = now
      return
    }
    val elapsed = now - windowStartAt
    if (elapsed < 1000L) {
      return
    }
    val fps = (framesInWindow * 1000L / elapsed).toInt()
    framesInWindow = 0
    windowStartAt = now
    if (fps > 0) {
      reconnectAttempts = 0
      reconnectFailedNotified = false
    }
    maybeHandleStutter(now, fps)
  }

  private fun maybeHandleStutter(now: Long, fps: Int) {
    val monitor = stutterMonitor ?: return
    if (fps <= 0 || !monitor.onFpsSample(now, fps)) {
      return
    }
    val current = currentFormat() ?: return
    if (current.fps != monitoredTargetFps) {
      // A previous downgrade changed the target; re-arm instead of cascading further down.
      monitoredTargetFps = current.fps
      monitor.updateTargetFps(current.fps)
      return
    }
    val next = streamerViewModel.captureProfileFlow.value?.nextFallbackAfter(current)
    if (next == null) {
      Log.i(TAG, "Stutter detected but the card is already at its lowest advertised format")
      return
    }
    if (settings.autoDowngrade) {
      if (streamerViewModel.downgradeTo(next)) {
        Toast.makeText(
                rootView.context,
                rootView.context.getString(R.string.capture_auto_downgraded, next.label()),
                Toast.LENGTH_LONG,
            )
            .show()
      }
      return
    }
    if (settings.stutterPromptDisabled || stutterDialogShown) {
      return
    }
    stutterDialogShown = true
    val message =
        rootView.context.getString(
            R.string.stutter_dialog_message,
            fps,
            current.fps,
            next.label(),
        )
    rootView.post {
      AlertDialog.Builder(rootView.context, R.style.FelisLinkDialog)
          .setTitle(R.string.stutter_dialog_title)
          .setMessage(message)
          .setPositiveButton(R.string.stutter_dialog_downgrade) { _, _ ->
            streamerViewModel.downgradeTo(next)
          }
          .setNegativeButton(R.string.stutter_dialog_keep) { _, _ -> monitor.reset() }
          .setNeutralButton(R.string.stutter_dialog_never) { _, _ ->
            settings.stutterPromptDisabled = true
            monitor.reset()
          }
          .show()
    }
  }

  /**
   * The restart path reuses the same view holder, so nothing rebinds it: pick up a downgraded
   * format here and resize the surface + re-arm the monitor for the new target.
   */
  private fun syncFormatIfChanged() {
    val current = currentFormat() ?: return
    val bound = boundFormat
    if (bound != null &&
        bound.width == current.width &&
        bound.height == current.height &&
        bound.fps == current.fps &&
        bound.fourccFormat == current.fourccFormat) {
      return
    }
    Log.i(TAG, "Negotiated format changed: ${bound?.label()} -> ${current.label()}")
    boundFormat = current
    videoTextureView.layoutParams = LayoutParams(current.width, current.height, Gravity.CENTER)
    videoFrame.requestLayout()
    resetZoom()
    stutterMonitor = StutterMonitor(current.fps)
    monitoredTargetFps = current.fps
    stutterDialogShown = false
    framesInWindow = 0
    windowStartAt = 0L
    bindAt = SystemClock.uptimeMillis()
  }

  private var appliedFillMode: FillMode? = null

  /** Settings can change while the streaming screen stays alive; re-fit the picture when needed. */
  private fun syncFillModeIfChanged() {
    if (appliedFillMode == settings.fillMode) {
      return
    }
    appliedFillMode = settings.fillMode
    videoFrame.requestLayout()
  }

  private fun reportRecordingError() {
    if (recordingErrorShown) {
      return
    }
    val error = CaptureVideoRecorder.consumeLastError() ?: return
    recordingErrorShown = true
    Log.e(TAG, "Recording failed", error)
    Toast.makeText(rootView.context, R.string.recording_failed, Toast.LENGTH_SHORT).show()
  }

  private fun startWatchdog() {
    if (watchdogRunning) {
      return
    }
    watchdogRunning = true
    handler.postDelayed(watchdog, 1_000L)
  }

  /** No frame callbacks for a while: restart the pipeline, at most twice per streaming session. */
  private fun handleDeadStream(now: Long) {
    if (reconnectAttempts >= 2) {
      if (!reconnectFailedNotified) {
        reconnectFailedNotified = true
        Toast.makeText(rootView.context, R.string.reconnect_failed, Toast.LENGTH_LONG).show()
      }
      return
    }
    reconnectAttempts++
    lastFrameAt = now
    Toast.makeText(rootView.context, R.string.reconnect_in_progress, Toast.LENGTH_SHORT).show()
    streamerViewModel.requestReconnect()
  }

  private fun showDegradedUsbSpeedIfSlow() {
    val usbSpeed: UsbSpeed = UsbVideoNativeLibrary.getUsbSpeed()
    if (usbSpeed == UsbSpeed.Unknown) {
      return
    }
    if (usbSpeed != UsbSpeed.Super) {
      val label: String =
          when (usbSpeed) {
            UsbSpeed.Low -> rootView.resources.getString(R.string.usb_speed_low_speed)
            UsbSpeed.Full -> rootView.resources.getString(R.string.usb_speed_full_speed)
            UsbSpeed.High -> rootView.resources.getString(R.string.usb_speed_high_speed)
            else -> error("Unreachable usb speed $usbSpeed")
          }
      Toast.makeText(
              rootView.context,
              rootView.resources.getString(R.string.expect_degraded_performance, label),
              Toast.LENGTH_LONG,
          )
          .show()
    }
    checkUSBSpeed = false
  }

  fun bindModel() {
    if (!settings.hudEnabled) {
      overlayMode = OverlayMode.NONE
      streamingStats.isVisible = false
    }
    val videoFormatSelectionState = streamerViewModel.videoFormatSelectionStateFlow.value
    if (videoFormatSelectionState is VideoFormatSelectionState.Negotiated) {
      val videoFormat: VideoFormat = videoFormatSelectionState.videoFormat
      // Buffer stays 1:1 with the captured format; the container scales it for the fill mode.
      videoTextureView.layoutParams =
          LayoutParams(videoFormat.width, videoFormat.height, Gravity.CENTER)
      stutterMonitor = StutterMonitor(videoFormat.fps)
      monitoredTargetFps = videoFormat.fps
      boundFormat = videoFormat
    } else {
      Log.e(
          TAG,
          "Binding failure for ${videoFormatSelectionState?.loggingName()}. Expected negotiated state.",
      )
    }
    videoFrame.requestLayout()
    checkUSBSpeed = true
    resetZoom()
    videoFrame.requestLayout()
    framesInWindow = 0
    windowStartAt = 0L
    lastFrameAt = 0L
    reconnectAttempts = 0
    reconnectFailedNotified = false
    stutterDialogShown = false
    appliedFillMode = settings.fillMode
    bindAt = SystemClock.uptimeMillis()
    updateCaptureToolsVisibility()
    startWatchdog()
  }

  private enum class OverlayMode {
    INITIAL_STATS,
    TOGGLE_TIP,
    NONE,
    STREAMING_STATS,
  }

  inline fun <reified T : Enum<T>> T.next(): T {
    val values = enumValues<T>()
    val nextOrdinal = (ordinal + 1) % values.size
    return values[nextOrdinal]
  }

  private fun updateOverlayMode(overlayMode: OverlayMode) {
    this.overlayMode = overlayMode
    if (overlayMode == OverlayMode.NONE) {
      streamingStats.isVisible = false
    }
    updateCaptureToolsVisibility()
    stateTransitionAt = SystemClock.uptimeMillis()
  }
}
