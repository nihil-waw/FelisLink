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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.AudioFormat
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the capture picture itself (the frames the app renders), not the phone screen.
 *
 * The native engine still draws into the `TextureView`; this recorder asks that view for a bitmap
 * at the recording frame rate and feeds it through a `MediaCodec` input surface (EGL) into an H.264
 * MP4 written to `Movies/FelisLink`. No MediaProjection, no status bar, no other apps, no HUD.
 *
 * Audio, when available, is the UAC PCM that is also being played through AAudio; it is enabled
 * only while FelisLink is shown on a non-default display. Video is always recorded.
 */
object CaptureVideoRecorder {
  private const val TAG = "CaptureVideoRecorder"
  private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
  private const val FPS_CAP = 30
  private const val MAX_EDGE = 1920
  private const val I_FRAME_INTERVAL_SECONDS = 2
  private const val AUDIO_BIT_RATE = 128_000
  private const val PCM_QUEUE_CAPACITY_BYTES = 262_144

  @Volatile private var session: Session? = null
  private val starting = AtomicBoolean(false)
  @Volatile private var audioEnabled = false

  /** Set when a recording died on its own (encoder error, storage error). */
  @Volatile var lastError: Throwable? = null
    private set

  /** Consumes the last failure so the UI only reports it once. */
  fun consumeLastError(): Throwable? {
    val error = lastError
    lastError = null
    return error
  }

  fun isRecording(): Boolean = session != null

  /**
   * @param sourceWidth/sourceHeight view size of the picture being recorded.
   * @param sourceFps negotiated capture fps (clamped to [FPS_CAP]).
   */
  fun start(
    context: Context,
    sourceWidth: Int,
    sourceHeight: Int,
    sourceFps: Int,
    enableAudio: Boolean,
  ): Boolean {
    lastError = null
    if (session != null || !starting.compareAndSet(false, true)) {
      return false
    }
    return try {
      val (width, height) = fitSize(sourceWidth, sourceHeight)
      val fps = sourceFps.coerceIn(1, FPS_CAP)
      audioEnabled = enableAudio
      val candidate =
          Session(context.applicationContext, width, height, fps, enableAudio, ::onSessionFailed)
      if (!candidate.start()) {
        candidate.abandon()
        return false
      }
      session = candidate
      RecordingState.setRunning(true)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "Unable to start recording", t)
      lastError = t
      false
    } finally {
      starting.set(false)
    }
  }

  /** Queues [bitmap] for encoding; the recorder recycles it on its own thread. */
  fun submitFrame(bitmap: Bitmap): Boolean {
    val current = session
    if (current == null) {
      bitmap.recycle()
      return false
    }
    return current.submit(bitmap)
  }

  /** Called from native UAC playback callback on the AAudio thread. */
  fun onPcmData(data: ByteArray, androidAudioFormat: Int, channelCount: Int, sampleRate: Int) {
    if (!audioEnabled) {
      return
    }
    session?.submitPcm(data, androidAudioFormat, channelCount, sampleRate)
  }

  fun stop() {
    val current = session ?: return
    session = null
    audioEnabled = false
    RecordingState.setRunning(false)
    current.stop()
  }

  private fun onSessionFailed(error: Throwable) {
    Log.e(TAG, "Recording stopped unexpectedly", error)
    lastError = error
    session = null
    RecordingState.setRunning(false)
  }

  private fun fitSize(width: Int, height: Int): Pair<Int, Int> {
    var w = width.coerceAtLeast(2)
    var h = height.coerceAtLeast(2)
    val longest = maxOf(w, h)
    if (longest > MAX_EDGE) {
      val scale = MAX_EDGE.toFloat() / longest
      w = (w * scale).toInt()
      h = (h * scale).toInt()
    }
    return ((w / 2) * 2) to ((h / 2) * 2)
  }

  private class Session(
      private val context: Context,
      private val width: Int,
      private val height: Int,
      private val fps: Int,
      private val recordAudio: Boolean,
      private val onFailed: (Throwable) -> Unit,
  ) {
    private val thread = HandlerThread("felislink-recorder").apply { start() }
    private val handler = Handler(thread.looper)
    private val ready = CountDownLatch(1)
    private val frameInFlight = AtomicBoolean(false)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val audioBufferInfo = MediaCodec.BufferInfo()
    private val pcmQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var audioSourceFormat = AudioFormat.ENCODING_PCM_16BIT
    private var audioChannelCount = 2
    private var audioSampleRate = 48_000
    private var audioCodec: MediaCodec? = null
    private var audioTrackIndex = -1
    private var audioPtsUs = 0L

    @Volatile private var setupError: Throwable? = null
    @Volatile private var closed = false

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var positionSlot = 0
    private var texCoordSlot = 0
    private var samplerSlot = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null

    fun start(): Boolean {
      handler.post {
        try {
          setup()
        } catch (t: Throwable) {
          setupError = t
        } finally {
          ready.countDown()
        }
      }
      ready.await(6, TimeUnit.SECONDS)
      return setupError == null && !closed
    }

    private fun setup() {
      val resolver = context.contentResolver
      val values =
          ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FelisLink_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FelisLink")
            put(MediaStore.Video.Media.IS_PENDING, 1)
          }
      val target =
          resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
              ?: throw IllegalStateException("MediaStore rejected the recording entry")
      uri = target
      descriptor = resolver.openFileDescriptor(target, "rw")
          ?: throw IllegalStateException("Unable to open the recording file")

      val encoder =
          MediaCodec.createEncoderByType(MIME_TYPE).apply {
            val format =
                MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                  setInteger(
                      MediaFormat.KEY_COLOR_FORMAT,
                      MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                  )
                  setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, fps))
                  setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                  setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
          }
      codec = encoder
      val surface = encoder.createInputSurface()
      inputSurface = surface
      muxer =
          MediaMuxer(
              descriptor!!.fileDescriptor,
              MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
          )
      encoder.start()
      setupEgl(surface)
      if (recordAudio) {
        ensureAudioEncoder()
      }
      Log.i(TAG, "Recording " + width + " x " + height + " @" + fps + "fps, audio=" + recordAudio)
    }

    private fun ensureAudioEncoder() {
      if (audioCodec != null) {
        return
      }
      val format =
          MediaFormat.createAudioFormat(
              MediaFormat.MIMETYPE_AUDIO_AAC,
              audioSampleRate,
              audioChannelCount,
          )
      format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
      format.setInteger(
          MediaFormat.KEY_AAC_PROFILE,
          MediaCodecInfo.CodecProfileLevel.AACObjectLC,
      )
      val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
      encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      encoder.start()
      audioCodec = encoder
      // The output format is only known after configure; add the track here so the muxer
      // accepts audio samples even before the first AAC buffer is drained.
      val mux = muxer ?: return
      audioTrackIndex = mux.addTrack(encoder.outputFormat)
      if (trackIndex >= 0 && !muxerStarted) {
        mux.start()
        muxerStarted = true
      }
    }

    fun submitPcm(data: ByteArray, androidAudioFormat: Int, channels: Int, sampleRate: Int) {
      if (!recordAudio || closed) {
        return
      }
      audioSourceFormat = androidAudioFormat
      audioChannelCount = channels.coerceIn(1, 2)
      audioSampleRate = sampleRate.coerceIn(8_000, 192_000)
      try {
        ensureAudioEncoder()
      } catch (t: Throwable) {
        fail(t)
        return
      }
      if (pcmQueue.size >= PCM_QUEUE_CAPACITY_BYTES / data.size.coerceAtLeast(1)) {
        pcmQueue.poll()
      }
      pcmQueue.offer(data.copyOf())
      handler.post { drainAudio(endOfStream = false) }
    }

    private fun drainAudio(endOfStream: Boolean) {
      val encoder = audioCodec ?: return
      val chunk = pcmQueue.poll() ?: run {
        if (endOfStream) {
          try {
            encoder.signalEndOfInputStream()
          } catch (t: Throwable) {
            Log.w(TAG, "AAC EOS failed", t)
          }
        }
        drainAudioOutput(encoder)
        return
      }
      val pcm = convertPcm(chunk, audioSourceFormat, audioChannelCount)
      val inputIndex = encoder.dequeueInputBuffer(0)
      if (inputIndex < 0) {
        pcmQueue.offer(chunk)
        drainAudioOutput(encoder)
        return
      }
      val input = encoder.getInputBuffer(inputIndex) ?: run {
        encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0)
        drainAudioOutput(encoder)
        return
      }
      if (input.remaining() < pcm.size) {
        drainAudioOutput(encoder)
        return
      }
      input.put(pcm)
      encoder.queueInputBuffer(inputIndex, 0, pcm.size, audioPtsUs, 0)
      audioPtsUs += (pcm.size / 2L / audioChannelCount.coerceAtLeast(1)) * 1_000_000L / audioSampleRate
      drainAudioOutput(encoder)
    }

    private fun convertPcm(data: ByteArray, audioFormat: Int, channels: Int): ByteArray {
      return when (audioFormat) {
        AudioFormat.ENCODING_PCM_16BIT -> data
        AudioFormat.ENCODING_PCM_8BIT -> {
          val out = ByteArray(data.size * 2)
          for (i in data.indices) {
            val sample = (data[i].toInt() - 128) shl 8
            out[i * 2] = (sample and 0xff).toByte()
            out[i * 2 + 1] = (sample shr 8).toByte()
          }
          out
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
          val source = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).asFloatBuffer()
          val out = ByteArray(source.remaining() * 2)
          for (i in 0 until source.remaining()) {
            val value = source.get().coerceIn(-1f, 1f)
            val pcm = (value * 32767).toInt()
            out[i * 2] = (pcm and 0xff).toByte()
            out[i * 2 + 1] = (pcm shr 8).toByte()
          }
          out
        }
        else -> data
      }
    }

    private fun drainAudioOutput(encoder: MediaCodec) {
      while (true) {
        val index = encoder.dequeueOutputBuffer(audioBufferInfo, 0)
        when {
          index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val mux = muxer ?: return
            if (audioTrackIndex < 0) {
              audioTrackIndex = mux.addTrack(encoder.outputFormat)
            }
            if (trackIndex >= 0 && !muxerStarted) {
              mux.start()
              muxerStarted = true
            }
          }
          index >= 0 -> {
            val buffer = encoder.getOutputBuffer(index) ?: continue
            if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
              audioBufferInfo.size = 0
            }
            if (audioBufferInfo.size > 0 && muxerStarted && audioTrackIndex >= 0) {
              buffer.position(audioBufferInfo.offset)
              buffer.limit(audioBufferInfo.offset + audioBufferInfo.size)
              muxer?.writeSampleData(audioTrackIndex, buffer, audioBufferInfo)
            }
            encoder.releaseOutputBuffer(index, false)
            if (audioBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              return
            }
          }
        }
      }
    }

    fun submit(bitmap: Bitmap): Boolean {
      if (closed || !frameInFlight.compareAndSet(false, true)) {
        bitmap.recycle()
        return false
      }
      handler.post {
        try {
          if (!closed) {
            drawFrame(bitmap)
            drain(endOfStream = false)
          }
        } catch (t: Throwable) {
          fail(t)
        } finally {
          bitmap.recycle()
          frameInFlight.set(false)
        }
      }
      return true
    }

    fun stop() {
      closed = true
      handler.post {
        try {
          finish()
        } catch (t: Throwable) {
          Log.e(TAG, "Failed to finish the recording cleanly", t)
          releaseQuietly()
        }
      }
      thread.quitSafely()
    }

    fun abandon() {
      closed = true
      releaseQuietly()
      thread.quitSafely()
    }

    // ---- EGL / GL ----

    private fun setupEgl(target: Surface) {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
      val version = IntArray(2)
      check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
      val attributes =
          intArrayOf(
              EGL14.EGL_RED_SIZE,
              8,
              EGL14.EGL_GREEN_SIZE,
              8,
              EGL14.EGL_BLUE_SIZE,
              8,
              EGL14.EGL_ALPHA_SIZE,
              8,
              EGL14.EGL_RENDERABLE_TYPE,
              EGL14.EGL_OPENGL_ES2_BIT,
              EGL_RECORDABLE_ANDROID,
              1,
              EGL14.EGL_NONE,
          )
      val configs = arrayOfNulls<EGLConfig>(1)
      val configCount = IntArray(1)
      check(
          EGL14.eglChooseConfig(
              eglDisplay,
              attributes,
              0,
              configs,
              0,
              configs.size,
              configCount,
              0,
          ) && configCount[0] > 0
      ) { "No EGL config" }
      eglContext =
          EGL14.eglCreateContext(
              eglDisplay,
              configs[0],
              EGL14.EGL_NO_CONTEXT,
              intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
              0,
          )
      check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
      eglSurface =
          EGL14.eglCreateWindowSurface(
              eglDisplay,
              configs[0],
              target,
              intArrayOf(EGL14.EGL_NONE),
              0,
          )
      check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
      check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        "eglMakeCurrent failed"
      }

      program = buildProgram()
      positionSlot = GLES20.glGetAttribLocation(program, "aPosition")
      texCoordSlot = GLES20.glGetAttribLocation(program, "aTexCoord")
      samplerSlot = GLES20.glGetUniformLocation(program, "uTexture")
      val texture = IntArray(1)
      GLES20.glGenTextures(1, texture, 0)
      textureId = texture[0]
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
      GLES20.glTexParameteri(
          GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_MIN_FILTER,
          GLES20.GL_LINEAR,
      )
      GLES20.glTexParameteri(
          GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_MAG_FILTER,
          GLES20.GL_LINEAR,
      )
      GLES20.glTexParameteri(
          GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_WRAP_S,
          GLES20.GL_CLAMP_TO_EDGE,
      )
      GLES20.glTexParameteri(
          GLES20.GL_TEXTURE_2D,
          GLES20.GL_TEXTURE_WRAP_T,
          GLES20.GL_CLAMP_TO_EDGE,
      )
      vertexBuffer =
          floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
              .toFloatBuffer()
      // Bitmaps are top-left origin, GL textures bottom-left: flip V.
      texBuffer =
          floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
              .toFloatBuffer()
    }

    private fun drawFrame(bitmap: Bitmap) {
      EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
      GLES20.glViewport(0, 0, width, height)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
      GLES20.glUseProgram(program)
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
      GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
      GLES20.glUniform1i(samplerSlot, 0)
      GLES20.glEnableVertexAttribArray(positionSlot)
      GLES20.glVertexAttribPointer(positionSlot, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
      GLES20.glEnableVertexAttribArray(texCoordSlot)
      GLES20.glVertexAttribPointer(texCoordSlot, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GLES20.glDisableVertexAttribArray(positionSlot)
      GLES20.glDisableVertexAttribArray(texCoordSlot)
      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, frameTimeNs())
      check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
    }

    private fun frameTimeNs(): Long = System.nanoTime()

    // ---- encode / mux ----

    private fun drain(endOfStream: Boolean) {
      val encoder = codec ?: return
      val info = bufferInfo
      val deadline = SystemClock.uptimeMillis() + if (endOfStream) 3_000L else 0L
      while (true) {
        val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
        when {
          index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
            if (!endOfStream || SystemClock.uptimeMillis() >= deadline) {
              return
            }
          }
          index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            val mux = muxer ?: return
            trackIndex = mux.addTrack(encoder.outputFormat)
            if (trackIndex >= 0 && !muxerStarted) {
              mux.start()
              muxerStarted = true
            }
          }
          index >= 0 -> {
            val buffer: ByteBuffer = encoder.getOutputBuffer(index) ?: continue
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
              info.size = 0
            }
            if (info.size > 0 && muxerStarted) {
              buffer.position(info.offset)
              buffer.limit(info.offset + info.size)
              muxer?.writeSampleData(trackIndex, buffer, info)
            }
            encoder.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
              return
            }
          }
        }
      }
    }

    private fun finish() {
      val encoder = codec
      if (encoder != null) {
        try {
          if (!closed) {
            // Recording stopped by the user: flush what is still queued.
          }
          encoder.signalEndOfInputStream()
          drain(endOfStream = true)
          audioCodec?.let { drainAudio(endOfStream = true) }
        } catch (t: Throwable) {
          Log.w(TAG, "Encoder flush failed", t)
        }
      }
      releaseQuietly()
      Log.i(TAG, "Recording finished")
    }

    private fun releaseQuietly() {
      try {
        codec?.stop()
      } catch (t: Throwable) {
        Log.w(TAG, "codec.stop failed", t)
      }
      try {
        codec?.release()
      } catch (t: Throwable) {
        Log.w(TAG, "codec.release failed", t)
      }
      codec = null
      try {
        audioCodec?.stop()
      } catch (t: Throwable) {
        Log.w(TAG, "audioCodec.stop failed", t)
      }
      try {
        audioCodec?.release()
      } catch (t: Throwable) {
        Log.w(TAG, "audioCodec.release failed", t)
      }
      audioCodec = null
      if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
          EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
          EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
      }
      inputSurface?.release()
      inputSurface = null
      val mux = muxer
      muxer = null
      if (mux != null) {
        try {
          if (muxerStarted) {
            mux.stop()
          }
        } catch (t: Throwable) {
          Log.w(TAG, "muxer.stop failed", t)
        }
        try {
          mux.release()
        } catch (t: Throwable) {
          Log.w(TAG, "muxer.release failed", t)
        }
      }
      try {
        descriptor?.close()
      } catch (t: Throwable) {
        Log.w(TAG, "descriptor close failed", t)
      }
      descriptor = null
      val target = uri
      uri = null
      if (target != null) {
        try {
          val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
          }
          context.contentResolver.update(target, values, null, null)
        } catch (t: Throwable) {
          Log.w(TAG, "Unable to publish the recording", t)
        }
      }
    }

    private fun fail(t: Throwable) {
      Log.e(TAG, "Encoder failure", t)
      if (!closed) {
        closed = true
        releaseQuietly()
        onFailed(t)
      }
    }

    private fun buildProgram(): Int {
      val vertexSource =
          """
          attribute vec4 aPosition;
          attribute vec2 aTexCoord;
          varying vec2 vTexCoord;
          void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
          }
          """
              .trimIndent()
      val fragmentSource =
          """
          precision mediump float;
          uniform sampler2D uTexture;
          varying vec2 vTexCoord;
          void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
          }
          """
              .trimIndent()
      val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
      val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
      val result = GLES20.glCreateProgram()
      GLES20.glAttachShader(result, vertexShader)
      GLES20.glAttachShader(result, fragmentShader)
      GLES20.glLinkProgram(result)
      val status = IntArray(1)
      GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "Program link failed" }
      GLES20.glDeleteShader(vertexShader)
      GLES20.glDeleteShader(fragmentShader)
      return result
    }

    private fun compileShader(type: Int, source: String): Int {
      val shader = GLES20.glCreateShader(type)
      GLES20.glShaderSource(shader, source)
      GLES20.glCompileShader(shader)
      val status = IntArray(1)
      GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
      check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
      return shader
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
              put(this@toFloatBuffer)
              position(0)
            }

    private fun bitrateFor(width: Int, height: Int, fps: Int): Int {
      val bitsPerSecond = (width.toLong() * height * fps * 0.12).toInt()
      return bitsPerSecond.coerceIn(2_000_000, 24_000_000)
    }

    private companion object {
      const val EGL_RECORDABLE_ANDROID = 0x3142
    }
  }
}