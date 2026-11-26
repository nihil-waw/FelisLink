/*
 * Copyright 2025 FelisLink contributors.
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

import android.content.Context
import com.meta.usbvideo.usb.VideoFormat
import com.meta.usbvideo.usb.VideoStreamingConnection
import kotlin.math.abs
import kotlin.math.min

/** Language selection. [tag] is null when the app should follow the system language. */
enum class LanguagePref(val tag: String?) {
  SYSTEM(null),
  CHINESE("zh-CN"),
  ENGLISH("en");

  companion object {
    fun fromName(name: String?): LanguagePref = entries.firstOrNull { it.name == name } ?: SYSTEM
  }
}

/** How the captured picture is mapped into the screen area. */
enum class FillMode {
  /** Keep the source aspect ratio, letterbox with black bars. */
  ORIGINAL,
  /** Stretch to fill the screen, ignoring the source aspect ratio. */
  STRETCH,
  /** Fill the screen and crop the overflow, keeping the source aspect ratio. */
  CROP;

  companion object {
    fun fromName(name: String?): FillMode = entries.firstOrNull { it.name == name } ?: ORIGINAL
  }
}

/** Orientation requested from the system for the capture UI. */
enum class RotationPref {
  SYSTEM,
  LANDSCAPE,
  PORTRAIT;

  companion object {
    /** Landscape is the useful default for a second screen. */
    fun fromName(name: String?): RotationPref = entries.firstOrNull { it.name == name } ?: LANDSCAPE
  }
}

/** Preferred capture resolution. AUTO picks a sensible target from the screen size. */
enum class ResolutionPref {
  AUTO,
  FHD,
  HD,
  SD;

  fun resolve(context: Context): Pair<Int, Int> =
      when (this) {
        AUTO -> {
          val metrics = context.resources.displayMetrics
          val shortSide = min(metrics.widthPixels, metrics.heightPixels)
          when {
            shortSide >= 1080 -> 1920 to 1080
            shortSide >= 720 -> 1280 to 720
            else -> 640 to 480
          }
        }
        FHD -> 1920 to 1080
        HD -> 1280 to 720
        SD -> 640 to 480
      }

  companion object {
    fun fromName(name: String?): ResolutionPref = entries.firstOrNull { it.name == name } ?: AUTO
  }
}

/** Preferred UVC pixel format. [fourcc] is null when any supported format is acceptable. */
enum class ColorFormatPref(val fourcc: String?) {
  AUTO(null),
  MJPEG("MJPG"),
  YUYV("YUY2"),
  NV12("NV12"),
  HDR10("P010");

  companion object {
    fun fromName(name: String?): ColorFormatPref = entries.firstOrNull { it.name == name } ?: AUTO
  }
}

/**
 * One-tap capture presets. An explicit preset pins resolution + fps; [AUTO] lets the assessor
 * decide from the negotiated link speed, [CUSTOM] defers to the resolution / fps-cap / color rows.
 */
enum class CapturePreset(val width: Int, val height: Int, val fps: Int) {
  AUTO(0, 0, 0),
  FHD60(1920, 1080, 60),
  FHD30(1920, 1080, 30),
  HD60(1280, 720, 60),
  HD30(1280, 720, 30),
  SD30(640, 480, 30),
  CUSTOM(0, 0, 0);

  val isExplicit: Boolean
    get() = this != AUTO && this != CUSTOM

  /** Resource id of the user-facing label, kept next to the enum so callers need no mapping. */
  fun labelRes(): Int =
      when (this) {
        AUTO -> R.string.capture_preset_auto
        FHD60 -> R.string.capture_preset_fhd60
        FHD30 -> R.string.capture_preset_fhd30
        HD60 -> R.string.capture_preset_hd60
        HD30 -> R.string.capture_preset_hd30
        SD30 -> R.string.capture_preset_sd30
        CUSTOM -> R.string.capture_preset_custom
      }

  companion object {
    fun fromName(name: String?): CapturePreset = entries.firstOrNull { it.name == name } ?: AUTO
  }
}

/**
 * Rate limits "recreate to pick up a new app locale" so a platform that silently refuses the
 * requested locale cannot put an activity into a recreate loop.
 */
object LocaleRecreateGuard {
  private const val MIN_INTERVAL_MS = 2500L
  private var lastRecreateAt = 0L

  fun tryRecreate(activity: android.app.Activity): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastRecreateAt < MIN_INTERVAL_MS) {
      return false
    }
    lastRecreateAt = now
    activity.recreate()
    return true
  }
}

/**
 * Persisted FelisLink preferences. One instance per process is enough; [revision] is bumped on every
 * write so that screens can tell whether any preference changed while they were in the background.
 */
class SettingsStore private constructor(private val context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  var language: LanguagePref
    get() = LanguagePref.fromName(prefs.getString(KEY_LANGUAGE, null))
    set(value) = putString(KEY_LANGUAGE, value.name)

  var rotation: RotationPref
    get() = RotationPref.fromName(prefs.getString(KEY_ROTATION, null))
    set(value) = putString(KEY_ROTATION, value.name)

  var resolution: ResolutionPref
    get() = ResolutionPref.fromName(prefs.getString(KEY_RESOLUTION, null))
    set(value) = putString(KEY_RESOLUTION, value.name)

  var colorFormat: ColorFormatPref
    get() = ColorFormatPref.fromName(prefs.getString(KEY_COLOR_FORMAT, null))
    set(value) = putString(KEY_COLOR_FORMAT, value.name)

  var hudEnabled: Boolean
    get() = prefs.getBoolean(KEY_HUD_ENABLED, true)
    set(value) {
      prefs.edit().putBoolean(KEY_HUD_ENABLED, value).apply()
      bumpRevision()
    }

  var keepScreenOn: Boolean
    get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
    set(value) {
      prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
      bumpRevision()
    }

  var immersive: Boolean
    get() = prefs.getBoolean(KEY_IMMERSIVE, true)
    set(value) {
      prefs.edit().putBoolean(KEY_IMMERSIVE, value).apply()
      bumpRevision()
    }

  /** Upper bound for the capture fps; 0 means "whatever the card offers". */
  var fpsCap: Int
    get() = prefs.getInt(KEY_FPS_CAP, 0)
    set(value) {
      prefs.edit().putInt(KEY_FPS_CAP, value).apply()
      bumpRevision()
    }

  /** Automatically step down one format when the measured fps stays too low. */
  var autoDowngrade: Boolean
    get() = prefs.getBoolean(KEY_AUTO_DOWNGRADE, true)
    set(value) {
      prefs.edit().putBoolean(KEY_AUTO_DOWNGRADE, value).apply()
      bumpRevision()
    }

  /** How the picture is fitted into the screen (original / stretch / crop). */
  var fillMode: FillMode
    get() = FillMode.fromName(prefs.getString(KEY_FILL_MODE, null))
    set(value) = putString(KEY_FILL_MODE, value.name)

  /** One-tap format preset; explicit presets override the resolution row. */
  var capturePreset: CapturePreset
    get() = CapturePreset.fromName(prefs.getString(KEY_CAPTURE_PRESET, null))
    set(value) = putString(KEY_CAPTURE_PRESET, value.name)

  /** Start streaming as soon as a supported card is attached, without waiting for a tap. */
  var autoConnect: Boolean
    get() = prefs.getBoolean(KEY_AUTO_CONNECT, false)
    set(value) {
      prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
      bumpRevision()
    }

  /** Swallow HUD/gesture input so an accidental touch cannot disturb a second screen. */
  var touchLocked: Boolean
    get() = prefs.getBoolean(KEY_TOUCH_LOCKED, false)
    set(value) {
      prefs.edit().putBoolean(KEY_TOUCH_LOCKED, value).apply()
      bumpRevision()
    }

  /** After the user answers "don't ask again", never show the stutter prompt. */
  var stutterPromptDisabled: Boolean
    get() = prefs.getBoolean(KEY_STUTTER_PROMPT_DISABLED, false)
    set(value) {
      prefs.edit().putBoolean(KEY_STUTTER_PROMPT_DISABLED, value).apply()
      bumpRevision()
    }

  /** Last format that actually streamed, purely informational (and a fallback hint). */
  var lastGoodFormat: String?
    get() = prefs.getString(KEY_LAST_GOOD_FORMAT, null)
    set(value) {
      prefs.edit().putString(KEY_LAST_GOOD_FORMAT, value).apply()
      bumpRevision()
    }

  val appContext: Context
    get() = context

  /** Resolution the capture format picker should aim for (explicit preset wins). */
  fun resolvedTarget(): Pair<Int, Int> {
    val preset = capturePreset
    return if (preset.isExplicit) preset.width to preset.height else resolution.resolve(context)
  }

  /** Fps the picker should respect: preset fps, else the manual cap, else no cap (0). */
  fun resolvedFpsCap(): Int {
    val preset = capturePreset
    return if (preset.isExplicit) preset.fps else fpsCap.coerceAtLeast(0)
  }

  private fun putString(key: String, value: String) {
    prefs.edit().putString(key, value).apply()
    bumpRevision()
  }

  /**
   * Picks the video format to pre-select once a capture card connects, honoring the preferred
   * resolution and pixel format. Never returns a format the card does not advertise.
   */
  fun pickInitialVideoFormat(connection: VideoStreamingConnection): VideoFormat? {
    val supported = connection.supportedVideoFormats()
    if (supported.isEmpty()) {
      return null
    }
    val (width, height) = resolution.resolve(context)
    val fourcc = colorFormat.fourcc
    if (fourcc != null) {
      val preferred = supported.filter { it.fourccFormat.equals(fourcc, ignoreCase = true) }
      if (preferred.isNotEmpty()) {
        return pickClosest(preferred, width, height)
      }
    }
    return connection.findBestVideoFormat(width, height) ?: supported.first()
  }

  private fun pickClosest(candidates: List<VideoFormat>, width: Int, height: Int): VideoFormat {
    val exact = candidates.filter { it.width == width && it.height == height }
    if (exact.isNotEmpty()) {
      return exact.maxByOrNull { it.fps }!!
    }
    val targetAspect = width.toFloat() / height.toFloat()
    val sameAspect = candidates.filter { abs(it.aspectRatioFloat - targetAspect) < 0.02f }
    val pool = sameAspect.ifEmpty { candidates }
    return pool.minByOrNull { abs(it.area - width * height) }!!
  }

  fun pickInitialVideoFormatOrNull(connection: VideoStreamingConnection?): VideoFormat? =
      connection?.let { pickInitialVideoFormat(it) }

  companion object {
    private const val PREFS_NAME = "felislink.settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ROTATION = "rotation"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_COLOR_FORMAT = "colorFormat"
    private const val KEY_HUD_ENABLED = "hudEnabled"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    private const val KEY_IMMERSIVE = "immersive"
    private const val KEY_FPS_CAP = "fpsCap"
    private const val KEY_AUTO_DOWNGRADE = "autoDowngrade"
    private const val KEY_LAST_GOOD_FORMAT = "lastGoodFormat"
    private const val KEY_CAPTURE_PRESET = "capturePreset"
    private const val KEY_AUTO_CONNECT = "autoConnect"
    private const val KEY_TOUCH_LOCKED = "touchLocked"
    private const val KEY_STUTTER_PROMPT_DISABLED = "stutterPromptDisabled"
    private const val KEY_FILL_MODE = "fillMode"

    @Volatile private var instance: SettingsStore? = null

    /** Bumped whenever any preference is written. Screens compare it across lifecycle changes. */
    @Volatile var revision: Int = 0
      private set

    fun get(context: Context): SettingsStore =
        instance
            ?: synchronized(this) {
              instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }

    private fun bumpRevision() {
      revision++
    }
  }
}
