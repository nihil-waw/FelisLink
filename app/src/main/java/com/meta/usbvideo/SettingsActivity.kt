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

import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.app.LocaleManager
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * FelisLink settings page. Every preference is persisted immediately; display preferences are
 * applied on resume so the capture screen picks them up when the user goes back.
 */
class SettingsActivity : ComponentActivity() {

  private lateinit var settings: SettingsStore
  private lateinit var rows: LinearLayout
  private var recreatedForLocale = false

  override fun attachBaseContext(newBase: android.content.Context) {
    super.attachBaseContext(LocaleSupport.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settings = SettingsStore.get(this)
    applyRotation()
    setContentView(R.layout.activity_settings)
    rows = findViewById(R.id.settings_rows)
    findViewById<View>(R.id.back_button).setOnClickListener { finish() }
    rebuildRows()
  }

  override fun onResume() {
    super.onResume()
    applyRotation()
    applyWindowSettings()
    val desired = settings.language
    if (desired.tag != null && !recreatedForLocale) {
      val current = resources.configuration.locales[0].toLanguageTag()
      if (!current.equals(desired.tag, ignoreCase = true) && !recreatedForLocale) {
        recreatedForLocale = true
        LocaleRecreateGuard.tryRecreate(this)
      }
    }
  }

  // ---- display preferences ----

  private fun applyRotation() {
    requestedOrientation =
        when (settings.rotation) {
          RotationPref.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
          RotationPref.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
          RotationPref.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
  }

  private fun applyWindowSettings() {
    if (settings.keepScreenOn) {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (settings.immersive) {
      controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
      controller.show(WindowInsetsCompat.Type.systemBars())
    }
  }

  // ---- rows ----

  private fun rebuildRows() {
    rows.removeAllViews()

    addSection(R.string.settings_section_general)
    addChoiceRow(
        R.string.settings_language,
        R.string.settings_language_summary,
        languageLabel(settings.language),
    ) {
      showChoices(
          R.string.settings_language,
          listOf(LanguagePref.SYSTEM, LanguagePref.CHINESE, LanguagePref.ENGLISH),
          ::languageLabel,
          settings.language,
      ) { pref ->
        settings.language = pref
        applyLanguage(pref)
      }
    }

    addSection(R.string.settings_section_display)
    addChoiceRow(
        R.string.settings_rotation,
        R.string.settings_rotation_summary,
        rotationLabel(settings.rotation),
    ) {
      showChoices(
          R.string.settings_rotation,
          listOf(RotationPref.SYSTEM, RotationPref.LANDSCAPE, RotationPref.PORTRAIT),
          ::rotationLabel,
          settings.rotation,
      ) { pref ->
        settings.rotation = pref
        applyRotation()
      }
    }
    addChoiceRow(
        R.string.settings_fill_mode,
        R.string.settings_fill_mode_summary,
        fillModeLabel(settings.fillMode),
    ) {
      showChoices(
          R.string.settings_fill_mode,
          listOf(FillMode.ORIGINAL, FillMode.STRETCH, FillMode.CROP),
          ::fillModeLabel,
          settings.fillMode,
      ) { mode -> settings.fillMode = mode }
    }
    addChoiceRow(
        R.string.settings_resolution,
        R.string.settings_resolution_summary,
        resolutionLabel(settings.resolution),
    ) {
      showChoices(
          R.string.settings_resolution,
          listOf(ResolutionPref.AUTO, ResolutionPref.FHD, ResolutionPref.HD, ResolutionPref.SD),
          ::resolutionLabel,
          settings.resolution,
      ) { pref ->
        materializePreset()
        settings.resolution = pref
      }
    }
    addChoiceRow(
        R.string.settings_color_format,
        R.string.settings_color_format_summary,
        colorFormatLabel(settings.colorFormat),
    ) {
      showChoices(
          R.string.settings_color_format,
          listOf(ColorFormatPref.AUTO, ColorFormatPref.MJPEG, ColorFormatPref.YUYV, ColorFormatPref.NV12),
          ::colorFormatLabel,
          settings.colorFormat,
      ) { pref ->
        materializePreset()
        settings.colorFormat = pref
      }
    }
    addToggleRow(
        R.string.settings_hud,
        R.string.settings_hud_summary,
        settings.hudEnabled,
    ) { enabled -> settings.hudEnabled = enabled }
    addToggleRow(
        R.string.settings_keep_screen_on,
        R.string.settings_keep_screen_on_summary,
        settings.keepScreenOn,
    ) { enabled ->
      settings.keepScreenOn = enabled
      applyWindowSettings()
    }
    addToggleRow(
        R.string.settings_immersive,
        R.string.settings_immersive_summary,
        settings.immersive,
    ) { enabled ->
      settings.immersive = enabled
      applyWindowSettings()
    }

    addSection(R.string.settings_section_advanced)
    addChoiceRow(
        R.string.settings_preset,
        R.string.settings_preset_summary,
        presetLabel(settings.capturePreset),
    ) {
      showChoices(
          R.string.settings_preset,
          CAPTURE_PRESETS,
          ::presetLabel,
          settings.capturePreset,
      ) { preset -> settings.capturePreset = preset }
    }
    addChoiceRow(
        R.string.settings_fps_cap,
        R.string.settings_fps_cap_summary,
        fpsCapLabel(settings.fpsCap),
    ) {
      showChoices(
          R.string.settings_fps_cap,
          FPS_CAP_CHOICES,
          ::fpsCapLabel,
          settings.fpsCap,
      ) { cap ->
        materializePreset()
        settings.fpsCap = cap
      }
    }
    addTextRow(
        getString(R.string.settings_compression_quality),
        getString(R.string.settings_compression_quality_note),
    )
    addTextRow(
        getString(R.string.settings_audio_route),
        getString(R.string.settings_audio_route_note),
    )
    addToggleRow(
        R.string.settings_auto_downgrade,
        R.string.settings_auto_downgrade_summary,
        settings.autoDowngrade,
    ) { enabled -> settings.autoDowngrade = enabled }
    addToggleRow(
        R.string.settings_auto_connect,
        R.string.settings_auto_connect_summary,
        settings.autoConnect,
    ) { enabled -> settings.autoConnect = enabled }
    addToggleRow(
        R.string.settings_touch_lock,
        R.string.settings_touch_lock_summary,
        settings.touchLocked,
    ) { locked -> settings.touchLocked = locked }
    addTextRow(
        getString(R.string.settings_apply_timing),
        getString(R.string.settings_apply_timing_summary),
    )

    addSection(R.string.settings_section_about)
    addTextRow(versionLabel(), getString(R.string.settings_about_note))
  }

  private fun addSection(titleRes: Int) {
    val title = TextView(this)
    title.setText(titleRes)
    title.setTextColor(primaryColor())
    title.setTypeface(null, android.graphics.Typeface.BOLD)
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    val params =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    params.topMargin = if (rows.childCount == 0) dp(8) else dp(20)
    params.bottomMargin = dp(6)
    params.leftMargin = dp(4)
    rows.addView(title, params)
  }

  private fun inflateRow(titleRes: Int, summaryRes: Int): View {
    val row = layoutInflater.inflate(R.layout.item_settings_row, rows, false)
    row.findViewById<TextView>(R.id.row_title).setText(titleRes)
    row.findViewById<TextView>(R.id.row_summary).setText(summaryRes)
    rows.addView(row)
    return row
  }

  private fun addChoiceRow(
      titleRes: Int,
      summaryRes: Int,
      valueLabel: String,
      onClick: () -> Unit,
  ) {
    val row = inflateRow(titleRes, summaryRes)
    val value = TextView(this)
    value.text = getString(R.string.settings_value_format, valueLabel)
    value.setTextColor(primaryColor())
    value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    row.findViewById<FrameLayout>(R.id.row_action).addView(value)
    row.setOnClickListener { onClick() }
  }

  private fun addToggleRow(
      titleRes: Int,
      summaryRes: Int,
      checked: Boolean,
      onChange: (Boolean) -> Unit,
  ) {
    val row = inflateRow(titleRes, summaryRes)
    val pill = TextView(this)
    pill.text = getString(if (checked) R.string.settings_on else R.string.settings_off)
    pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    pill.setTextColor(
        if (checked) ContextCompat.getColor(this, R.color.felislink_on_accent)
        else ContextCompat.getColor(this, R.color.felislink_text_secondary)
    )
    pill.setPadding(dp(12), dp(4), dp(12), dp(4))
    val background = GradientDrawable()
    background.cornerRadius = dp(14).toFloat()
    background.setColor(
        if (checked) primaryColor() else ContextCompat.getColor(this, R.color.felislink_chip_off)
    )
    pill.background = background
    row.findViewById<FrameLayout>(R.id.row_action).addView(pill)
    row.setOnClickListener {
      onChange(!checked)
      applyWindowSettings()
      rebuildRows()
    }
  }

  private fun addTextRow(title: String, summary: String) {
    val row = layoutInflater.inflate(R.layout.item_settings_row, rows, false)
    row.findViewById<TextView>(R.id.row_title).text = title
    row.findViewById<TextView>(R.id.row_summary).text = summary
    row.isClickable = false
    row.isFocusable = false
    rows.addView(row)
  }

  private fun <T> showChoices(
      titleRes: Int,
      values: List<T>,
      label: (T) -> String,
      current: T,
      onPick: (T) -> Unit,
  ) {
    val labels = values.map(label).toTypedArray()
    val checked = values.indexOf(current).coerceAtLeast(0)
    AlertDialog.Builder(this, R.style.FelisLinkDialog)
        .setTitle(titleRes)
        .setSingleChoiceItems(labels, checked) { dialog, which ->
          dialog.dismiss()
          onPick(values[which])
          rebuildRows()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /** Editing a manual parameter while a preset is active freezes the preset into the manual rows. */
  private fun materializePreset() {
    val preset = settings.capturePreset
    if (!preset.isExplicit) {
      return
    }
    settings.resolution =
        when {
          preset.width >= 1920 -> ResolutionPref.FHD
          preset.width >= 1280 -> ResolutionPref.HD
          else -> ResolutionPref.SD
        }
    settings.fpsCap = preset.fps
    settings.capturePreset = CapturePreset.CUSTOM
  }

  private fun presetLabel(preset: CapturePreset): String = getString(preset.labelRes())

  // ---- labels ----

  private fun languageLabel(pref: LanguagePref): String =
      when (pref) {
        LanguagePref.SYSTEM -> getString(R.string.language_system)
        LanguagePref.CHINESE -> getString(R.string.language_chinese)
        LanguagePref.ENGLISH -> getString(R.string.language_english)
      }

  private fun rotationLabel(pref: RotationPref): String =
      when (pref) {
        RotationPref.SYSTEM -> getString(R.string.rotation_system)
        RotationPref.LANDSCAPE -> getString(R.string.rotation_landscape)
        RotationPref.PORTRAIT -> getString(R.string.rotation_portrait)
      }

  private fun fillModeLabel(mode: FillMode): String =
      when (mode) {
        FillMode.ORIGINAL -> getString(R.string.fill_mode_original)
        FillMode.STRETCH -> getString(R.string.fill_mode_stretch)
        FillMode.CROP -> getString(R.string.fill_mode_crop)
      }

  private fun resolutionLabel(pref: ResolutionPref): String =
      when (pref) {
        ResolutionPref.AUTO -> getString(R.string.resolution_auto)
        ResolutionPref.FHD -> getString(R.string.resolution_fhd)
        ResolutionPref.HD -> getString(R.string.resolution_hd)
        ResolutionPref.SD -> getString(R.string.resolution_sd)
      }

  private fun fpsCapLabel(cap: Int): String =
      if (cap <= 0) getString(R.string.settings_fps_cap_unlimited)
      else getString(R.string.settings_fps_cap_value, cap)

  private fun colorFormatLabel(pref: ColorFormatPref): String =
      when (pref) {
        ColorFormatPref.AUTO -> getString(R.string.color_format_auto)
        ColorFormatPref.MJPEG -> getString(R.string.color_format_mjpeg)
        ColorFormatPref.YUYV -> getString(R.string.color_format_yuyv)
        ColorFormatPref.NV12 -> getString(R.string.color_format_nv12)
      }

  private fun versionLabel(): String {
    val versionName =
        try {
          packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
          "?"
        }
    return getString(R.string.settings_about_version, versionName)
  }

  // ---- language ----

  private fun applyLanguage(pref: LanguagePref) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      // Android 12/12L has no per-app locale API: LocaleSupport.wrap() reads the stored preference
      // in attachBaseContext, so a recreate is what applies the new language.
      LocaleRecreateGuard.tryRecreate(this)
      return
    }
    val localeManager = getSystemService(LocaleManager::class.java) ?: return
    try {
      localeManager.applicationLocales =
          if (pref.tag == null) {
            LocaleList.getEmptyLocaleList()
          } else {
            LocaleList.forLanguageTags(pref.tag)
          }
    } catch (e: Exception) {
      // The platform refused the locale (e.g. it is not declared in locales_config).
      android.util.Log.e("FelisLink", "Unable to set app locale", e)
    }
  }

  // ---- helpers ----

  private fun primaryColor(): Int = ContextCompat.getColor(this, R.color.felislink_accent)

  private fun dp(value: Int): Int =
      (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

  companion object {
    /** 0 = no cap; the rest are the frame rates UVC cards actually advertise. */
    private val FPS_CAP_CHOICES = listOf(0, 120, 60, 30, 25, 24, 15)

    private val CAPTURE_PRESETS =
        listOf(
            CapturePreset.AUTO,
            CapturePreset.FHD60,
            CapturePreset.FHD30,
            CapturePreset.HD60,
            CapturePreset.HD30,
            CapturePreset.SD30,
            CapturePreset.CUSTOM,
        )

    /** Convenience launcher used by the capture screen. */
    fun intent(context: android.content.Context): android.content.Intent =
        android.content.Intent(context, SettingsActivity::class.java)
  }
}