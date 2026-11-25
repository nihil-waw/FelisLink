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

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * Per-app language on Android 12/12L.
 *
 * `LocaleManager` only exists on Android 13+, so the Activities wrap their base context in a
 * locale-specific configuration instead. On 13+ the platform applies the stored locale itself and
 * this is a no-op.
 */
object LocaleSupport {
  // Kept in sync with SettingsStore; read directly so attachBaseContext never builds the singleton.
  private const val PREFS_NAME = "felislink.settings"
  private const val KEY_LANGUAGE = "language"

  fun wrap(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return base
    }
    val tag = languageTag(base) ?: return base
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocales(LocaleList.forLanguageTags(tag))
    return base.createConfigurationContext(configuration)
  }

  private fun languageTag(context: Context): String? {
    val stored =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null) ?: return null
    return LanguagePref.fromName(stored).tag
  }
}
