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
package com.meta.usbvideo.usb

import android.hardware.usb.UsbDevice
import com.meta.usbvideo.SettingsStore
import com.meta.usbvideo.UsbSpeed
import com.meta.usbvideo.UsbVideoNativeLibrary
import kotlin.math.abs

/**
 * Physical link speed of the USB port the card is plugged into. The port is what actually limits
 * throughput, so this wins over whatever the card claims in its device descriptor.
 */
enum class LinkSpeed {
  UNKNOWN,
  USB_2,
  USB_3;

  val isUsb3: Boolean
    get() = this == USB_3

  companion object {
    /** Reads the speed negotiated by the native stack (ioctl), not the descriptor's bcdUSB. */
    fun current(): LinkSpeed =
        when (UsbVideoNativeLibrary.getUsbSpeed()) {
          UsbSpeed.Super -> USB_3
          UsbSpeed.High,
          UsbSpeed.Full,
          UsbSpeed.Low -> USB_2
          UsbSpeed.Unknown -> UNKNOWN
        }
  }
}

/**
 * Small VID:PID lookup table. **Cosmetic only**: it feeds a label in the connect dialog and a
 * conservative "assume USB 2.0" fallback when the port speed cannot be read. Parsed descriptors
 * always decide what we actually stream.
 */
object ChipsetRegistry {
  private data class Entry(
      val vendorId: Int,
      val productId: Int,
      val label: String,
      val usb2Only: Boolean,
  )

  private val entries =
      listOf(
          Entry(0x534D, 0x2109, "MS2109", usb2Only = true),
          Entry(0x345F, 0x2130, "MS2130", usb2Only = false),
          Entry(0x1D1F, 0xF115, "AM8352", usb2Only = true),
      )

  private fun entryFor(device: UsbDevice?): Entry? =
      device?.let { d -> entries.firstOrNull { it.vendorId == d.vendorId && it.productId == d.productId } }

  fun labelFor(device: UsbDevice?): String? = entryFor(device)?.label

  fun isUsb2Only(device: UsbDevice?): Boolean = entryFor(device)?.usb2Only ?: false
}

/** Why the assessor ended up with this format; mapped to a sentence in the connect dialog. */
enum class ProfileReason {
  /** USB 3.0 link, uncompressed (YUYV/NV12) is affordable, so prefer it over MJPEG. */
  AUTO_USB3_UNCOMPRESSED,
  /** USB 2.0 link: stay on MJPEG and start at 30 fps to stay inside the 480 Mbps budget. */
  AUTO_USB2_COMPRESSED,
  /** The user pinned a pixel format in settings and the card supports it. */
  USER_PREFERENCE,
  /** A one-tap preset matched exactly (resolution + fps available). */
  PRESET_MATCHED,
  /** A preset was requested but the card cannot do it, so the nearest advertised format is used. */
  PRESET_FALLBACK,
  /** `libuvc` exposes no UVC Compression Control, so the backend MJPEG quality is not tunable. */
  NO_COMPRESSION_CONTROL,
}

/**
 * Result of the capability assessment: the preferred format plus a safe degradation chain.
 *
 * [fallbackChain] is ordered from best to worst (same format first, then lower fps, then smaller
 * frames, then other pixel formats). **Reserved**: the stutter-triggered auto downgrade will walk
 * this chain one step at a time; this build only displays it in the connect dialog.
 */
data class CaptureProfile(
    val deviceKey: String,
    val productName: String,
    val chipsetLabel: String?,
    val linkSpeed: LinkSpeed,
    val audioSupported: Boolean,
    val preferred: VideoFormat,
    val fallbackChain: List<VideoFormat>,
    val reason: ProfileReason,
  ) {
  /** Next step down the chain, or null when already at the worst advertised format. */
  fun nextFallbackAfter(format: VideoFormat): VideoFormat? {
    val index =
        fallbackChain.indexOfFirst {
          it.fourccFormat == format.fourccFormat &&
              it.width == format.width &&
              it.height == format.height &&
              it.fps == format.fps
        }
    return fallbackChain.getOrNull(index + 1)
  }
}

/**
 * Turns (link speed × UVC descriptors × user preference) into a capture profile.
 *
 * Rules of thumb: uncompressed formats (YUYV/NV12) only make sense on USB 3.0; on USB 2.0 stay on
 * MJPEG and start at 30 fps because 1080p60 MJPEG already sits at the edge of the 480 Mbps budget.
 */
object CaptureProfileAssessor {
  private val FORMAT_PRIORITY_USB3 = listOf("YUY2", "MJPG", "NV12")
  private val FORMAT_PRIORITY_USB2 = listOf("MJPG", "NV12", "YUY2")

  fun assess(
      connection: VideoStreamingConnection,
      usbDevice: UsbDevice,
      audioSupported: Boolean,
      settings: SettingsStore,
  ): CaptureProfile? {
    val formats = connection.supportedVideoFormats()
    if (formats.isEmpty()) {
      return null
    }

    var link = LinkSpeed.current()
    if (link == LinkSpeed.UNKNOWN && ChipsetRegistry.isUsb2Only(usbDevice)) {
      link = LinkSpeed.USB_2
    }
    val priority = if (link.isUsb3) FORMAT_PRIORITY_USB3 else FORMAT_PRIORITY_USB2

    val preset = settings.capturePreset
    val (targetWidth, targetHeight) = settings.resolvedTarget()
    val presetFps = settings.resolvedFpsCap()
    val targetFps = if (presetFps > 0) presetFps else Int.MAX_VALUE

    val cappedPool = formats.filter { it.fps <= targetFps }.ifEmpty { formats }
    val preferredFourcc = settings.colorFormat.fourcc
    val fourccPool =
        if (preferredFourcc != null) {
          cappedPool.filter { it.fourccFormat.equals(preferredFourcc, ignoreCase = true) }
        } else {
          emptyList()
        }
    val pool = fourccPool.ifEmpty { cappedPool }
    val chosen = pickFrom(pool, targetWidth, targetHeight, priority) ?: return null

    val reason =
        when {
          preset.isExplicit ->
              if (chosen.width == preset.width &&
                  chosen.height == preset.height &&
                  chosen.fps == preset.fps) {
                ProfileReason.PRESET_MATCHED
              } else {
                ProfileReason.PRESET_FALLBACK
              }
          preferredFourcc != null && chosen.fourccFormat.equals(preferredFourcc, true) ->
              ProfileReason.USER_PREFERENCE
          link.isUsb3 && !chosen.fourccFormat.equals("MJPG", ignoreCase = true) ->
              ProfileReason.AUTO_USB3_UNCOMPRESSED
          else -> ProfileReason.AUTO_USB2_COMPRESSED
        }

    val chain =
        (listOf(chosen) +
                formats
                    .filter { it != chosen }
                    .sortedWith(
                        compareBy(
                            { it.fourccFormat != chosen.fourccFormat },
                            { it.width != chosen.width || it.height != chosen.height },
                            { -it.fps },
                            { -it.area },
                        )
                    ))
            .distinct()

    return CaptureProfile(
        deviceKey = "${usbDevice.deviceId}-${usbDevice.vendorId}:${usbDevice.productId}",
        productName = usbDevice.productName.orEmpty(),
        chipsetLabel = ChipsetRegistry.labelFor(usbDevice),
        linkSpeed = link,
        audioSupported = audioSupported,
        preferred = chosen,
        fallbackChain = chain,
        reason = reason,
    )
  }

  /** Exact target size first, then nearest area; within a size prefer the format priority order. */
  private fun pickFrom(
      pool: List<VideoFormat>,
      targetWidth: Int,
      targetHeight: Int,
      priority: List<String>,
  ): VideoFormat? {
    if (pool.isEmpty()) {
      return null
    }
    fun formatRank(f: VideoFormat): Int = priority.indexOf(f.fourccFormat).coerceAtLeast(priority.size)
    val exact = pool.filter { it.width == targetWidth && it.height == targetHeight }
    if (exact.isNotEmpty()) {
      return exact.sortedWith(compareBy({ formatRank(it) }, { -it.fps })).first()
    }
    val targetAspect = targetWidth.toFloat() / targetHeight
    val sameAspect = pool.filter { abs(it.aspectRatioFloat - targetAspect) < 0.02f }
    val candidates = sameAspect.ifEmpty { pool }
    return candidates
        .sortedWith(
            compareBy(
                { abs(it.area - targetWidth * targetHeight) },
                { formatRank(it) },
                { -it.fps },
            )
        )
        .first()
  }
}
