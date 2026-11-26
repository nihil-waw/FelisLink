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
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.meta.usbvideo.animation.ZoomOutPageTransformer
import com.meta.usbvideo.media.CaptureVideoRecorder
import com.meta.usbvideo.permission.getCameraPermissionState
import com.meta.usbvideo.permission.getRecordAudioPermissionState
import com.meta.usbvideo.usb.CaptureProfile
import com.meta.usbvideo.usb.ConnectedUsbDevice
import com.meta.usbvideo.usb.DetachedUsbDevice
import com.meta.usbvideo.usb.LinkSpeed
import com.meta.usbvideo.usb.ProfileReason
import com.meta.usbvideo.usb.SelectedUsbDevice
import com.meta.usbvideo.usb.StreamingStatus
import com.meta.usbvideo.usb.StreamingUsbDeviceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "StreamerActivity"

private const val STATE_PROFILE_DIALOG_KEY = "profileDialogKey"

enum class StreamerScreen {
  ConnectCaptureCardCTA,
  Status,
  Streaming,
}

class StreamerActivity : ComponentActivity() {

  private lateinit var viewPager: ViewPager2
  private lateinit var screensAdapter: StreamerScreensAdapter
  private lateinit var settingsButton: View
  private lateinit var settings: SettingsStore
  private var appliedSettingsRevision = 0
  private var recreatedForLocale = false
  /** Device whose capability dialog was already shown, so reconnect does not nag every second. */
  private var lastProfileDialogKey: String? = null
  /** Device key that already auto-started, so a re-emitted state cannot start twice. */
  private var autoConnectDeviceKey: String? = null
  private var autoConnecting = false

  /**
   * The ViewModel takes permission state snapshots, so it needs an explicit factory; the default
   * `AndroidViewModelFactory` only knows the (Application) constructor and would crash at launch.
   */
  private val streamerViewModel: StreamerViewModel by viewModels {
    viewModelFactory {
      initializer {
        StreamerViewModel(
            application,
            this@StreamerActivity.getCameraPermissionState(),
            this@StreamerActivity.getRecordAudioPermissionState(),
        )
      }
    }
  }

  override fun attachBaseContext(newBase: android.content.Context) {
    super.attachBaseContext(LocaleSupport.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settings = SettingsStore.get(this)
    lastProfileDialogKey = savedInstanceState?.getString(STATE_PROFILE_DIALOG_KEY)
    appliedSettingsRevision = SettingsStore.revision
    applyRotation()
    applyWindowSettings()
    streamerViewModel.prepareCameraPermissionLaunchers(this)
    streamerViewModel.prepareUsbBroadcastReceivers(this)
    setContentView(R.layout.activity_streamer)
    viewPager = findViewById(R.id.view_pager)
    viewPager.offscreenPageLimit = 1
    viewPager.setPageTransformer(ZoomOutPageTransformer())
    settingsButton = findViewById(R.id.settings_button)
    settingsButton.setOnClickListener { openSettings() }
    screensAdapter =
        StreamerScreensAdapter(
            this,
            streamerViewModel,
            ::openSettings,
            buildInitialScreens(),
        )
    viewPager.adapter = screensAdapter
    viewPager.registerOnPageChangeCallback(
        object : ViewPager2.OnPageChangeCallback() {
          override fun onPageSelected(position: Int) {
            updateSettingsButtonVisibility()
          }
        }
    )
    updateSettingsButtonVisibility()
    // One-time "here is what this card can do" card, shown once per attach.
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        streamerViewModel.captureProfileFlow.collect { profile ->
          // In auto-connect mode the capture starts right away, so the card would otherwise pop
          // over the live picture; the format is announced with a toast instead.
          if (!settings.autoConnect &&
              profile != null &&
              profile.deviceKey != lastProfileDialogKey) {
            lastProfileDialogKey = profile.deviceKey
            showCaptureProfileDialog(profile)
          }
        }
      }
    }
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        usbmon.usbDeviceState.collect {
          when (it) {
            null -> {
              autoConnectDeviceKey = null
              presentConnectCaptureCardCTA()
            }
            is DetachedUsbDevice -> {
              autoConnectDeviceKey = null
              presentConnectCaptureCardCTA()
            }
            is ConnectedUsbDevice -> {
              presentStatusScreen()
              maybeAutoConnect(it)
            }
            is SelectedUsbDevice -> presentStatusScreen()
            is StreamingUsbDeviceState -> {
              when (it.streamingStatus) {
                StreamingStatus.Start,
                StreamingStatus.Restart -> {
                  Log.i(TAG, "doOnCreate: presentStreamingScreen ${it.streamingStatus}")
                  presentStreamingScreen()
                  streamerViewModel.startStreaming(it)
                }
                is StreamingStatus.Started -> Unit
                is StreamingStatus.Stopping -> Unit
                StreamingStatus.Stopped -> {
                  streamerViewModel.restartStreaming()
                }
              }
            }
          }
        }
      }
    }

    // for testing splash screen
    val splashScreenDurationMs: Int = intent.getIntExtra("_splash_screen_millis", 0)
    if (splashScreenDurationMs > 0) {
      viewPager.viewTreeObserver.addOnPreDrawListener(
          object : ViewTreeObserver.OnPreDrawListener {
            // wait for the splash screen duration to pass before showing the app content
            override fun onPreDraw(): Boolean =
                SystemClock.uptimeMillis() - Process.getStartUptimeMillis() > splashScreenDurationMs
          }
      )
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString(STATE_PROFILE_DIALOG_KEY, lastProfileDialogKey)
  }

  /**
   * Shows the assessed profile (link speed, chipset label, audio capability, chosen format and the
   * next fallback step). Display only: the format is already applied by [StreamerViewModel], and
   * the status screen still lets the user override it.
   */
  private fun showCaptureProfileDialog(profile: CaptureProfile) {
    val lines = mutableListOf<String>()
    if (profile.productName.isNotBlank()) {
      lines += getString(R.string.capture_profile_device, profile.productName)
    }
    profile.chipsetLabel?.let { lines += getString(R.string.capture_profile_chipset, it) }
    lines +=
        getString(
            when (profile.linkSpeed) {
              LinkSpeed.USB_3 -> R.string.capture_profile_link_usb3
              LinkSpeed.USB_2 -> R.string.capture_profile_link_usb2
              LinkSpeed.UNKNOWN -> R.string.capture_profile_link_unknown
            }
        )
    lines +=
        getString(
            if (profile.audioSupported) R.string.capture_profile_audio_yes
            else R.string.capture_profile_audio_no
        )
    lines += getString(R.string.capture_profile_selected, profile.preferred.label())
    lines +=
        getString(
            when (profile.reason) {
              ProfileReason.AUTO_USB3_UNCOMPRESSED -> R.string.capture_profile_reason_usb3
              ProfileReason.AUTO_USB2_COMPRESSED -> R.string.capture_profile_reason_usb2
              ProfileReason.USER_PREFERENCE -> R.string.capture_profile_reason_user
              ProfileReason.PRESET_MATCHED -> R.string.capture_profile_reason_preset_matched
              ProfileReason.PRESET_FALLBACK -> R.string.capture_profile_reason_preset_fallback
              ProfileReason.NO_COMPRESSION_CONTROL -> R.string.capture_profile_reason_usb2
              ProfileReason.HDR10_PREFERRED -> R.string.capture_profile_reason_hdr10
            }
        )
    profile.nextFallbackAfter(profile.preferred)?.let {
      lines += getString(R.string.capture_profile_fallbacks, it.label())
    }
    lines += getString(R.string.capture_profile_note)
    AlertDialog.Builder(this, R.style.FelisLinkDialog)
        .setTitle(R.string.capture_profile_dialog_title)
        .setMessage(lines.joinToString("\n"))
        .setPositiveButton(R.string.capture_profile_apply) { _, _ ->
          streamerViewModel.autoConnectIfPossible()
        }
        .setNegativeButton(R.string.capture_profile_manual, null)
        .show()
  }

  /** Auto-connect toggle: once per attach, start with the assessed format without a tap. */
  private fun maybeAutoConnect(state: ConnectedUsbDevice) {
    if (!settings.autoConnect) {
      return
    }
    val device = state.usbDevice
    val key = "${device.deviceId}-${device.vendorId}:${device.productId}"
    if (autoConnectDeviceKey == key || autoConnecting) {
      return
    }
    autoConnecting = true
    lifecycleScope.launch {
      try {
        delay(700)
        val preferred = streamerViewModel.captureProfileFlow.value?.preferred
        if (streamerViewModel.autoConnectIfPossible()) {
          // Only remember the key after a real start: until the USB permission is granted the
          // same device re-emits the connected state, and that retry is exactly what we want.
          autoConnectDeviceKey = key
          preferred?.let {
            Toast.makeText(
                    this@StreamerActivity,
                    getString(R.string.auto_connect_started, it.label()),
                    Toast.LENGTH_SHORT,
                )
                .show()
          }
        }
      } finally {
        autoConnecting = false
      }
    }
  }

  private fun presentConnectCaptureCardCTA() {
    val oldScreens: List<StreamerScreen> = screensAdapter.screens
    val newScreens: List<StreamerScreen> = listOf(StreamerScreen.ConnectCaptureCardCTA)
    if (oldScreens != newScreens) {
      screensAdapter.screens = newScreens
      if (oldScreens.isNotEmpty()) {
        screensAdapter.notifyItemRangeRemoved(0, oldScreens.size)
      }
      screensAdapter.notifyItemInserted(0)
      viewPager.setCurrentItem(0, true)
    }
    updateSettingsButtonVisibility()
  }

  private fun presentStatusScreen() {
    val oldScreens: List<StreamerScreen> = screensAdapter.screens
    val newScreens: List<StreamerScreen> = listOf(StreamerScreen.Status)
    if (oldScreens != newScreens) {
      screensAdapter.screens = newScreens
      if (oldScreens.isNotEmpty()) {
        screensAdapter.notifyItemRangeRemoved(0, oldScreens.size)
      }
      screensAdapter.notifyItemInserted(0)
      viewPager.setCurrentItem(0, true)
    }
    updateSettingsButtonVisibility()
  }

  private fun presentStreamingScreen() {
    val oldScreens: List<StreamerScreen> = screensAdapter.screens
    if (!screensAdapter.screens.contains(StreamerScreen.Streaming)) {
      screensAdapter.screens =
          listOf(
              StreamerScreen.Status,
              StreamerScreen.Streaming,
          )
      if (oldScreens.firstOrNull() == StreamerScreen.Status) {
        screensAdapter.notifyItemInserted(1)
        viewPager.setCurrentItem(1, true)
      } else {
        screensAdapter.notifyItemRangeRemoved(0, oldScreens.size)
        screensAdapter.notifyItemRangeInserted(0, 2)
      }
    }
    updateSettingsButtonVisibility()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Log.i(TAG, "onNewIntent: ${intent.action}")
    if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
      val usbDevice: UsbDevice =
          IntentCompat.getParcelableExtra(
              intent,
              UsbManager.EXTRA_DEVICE,
              UsbDevice::class.java,
          ) ?: return
      usbmon.onUsbDeviceAttached(usbDevice, "onNewIntent")
    }
  }

  override fun onResume() {
    super.onResume()
    applyRotation()
    applyWindowSettings()
    if (SettingsStore.revision != appliedSettingsRevision) {
      appliedSettingsRevision = SettingsStore.revision
      streamerViewModel.refreshFormatSelectionFromSettings()
    }
    maybeRecreateForLocale()
    updateSettingsButtonVisibility()
  }

  override fun onStop() {
    super.onStop()
    // Leaving the app stops the capture pipeline, so a running recording has no frames left.
    if (CaptureVideoRecorder.isRecording()) {
      CaptureVideoRecorder.stop()
    }
    streamerViewModel.stopStreaming()
  }

  private fun openSettings() {
    startActivity(SettingsActivity.intent(this))
  }

  private fun updateSettingsButtonVisibility() {
    val current = screensAdapter.screens.getOrNull(viewPager.currentItem)
    settingsButton.isVisible = current != StreamerScreen.Streaming
  }

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

  private fun maybeRecreateForLocale() {
    val tag = settings.language.tag ?: return
    if (recreatedForLocale) {
      return
    }
    val current = resources.configuration.locales[0].toLanguageTag()
    if (!current.equals(tag, ignoreCase = true) && !recreatedForLocale) {
      recreatedForLocale = true
      LocaleRecreateGuard.tryRecreate(this)
    }
  }

  private fun buildInitialScreens(): List<StreamerScreen> {
    return when (usbmon.usbDeviceState.value) {
      is ConnectedUsbDevice -> listOf(StreamerScreen.Status)
      is DetachedUsbDevice -> listOf(StreamerScreen.ConnectCaptureCardCTA)
      is SelectedUsbDevice -> listOf(StreamerScreen.Status)
      is StreamingUsbDeviceState -> {
        Log.e(TAG, "Initial state as streaming is not expected.")
        listOf(StreamerScreen.Status)
      }
      null -> listOf(StreamerScreen.ConnectCaptureCardCTA)
    }
  }
}
