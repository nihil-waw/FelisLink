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

/**
 * Frame-rate watch dog. Fires once when the measured fps stays under 80% of the target for at
 * least 3 seconds inside a 5 second window, then stays quiet for 15 seconds so one bad patch of
 * frames cannot cause a downgrade storm.
 *
 * Pure logic: the caller feeds one fps sample per second and reacts to a `true` return.
 */
class StutterMonitor(targetFps: Int) {
  private val windowMs = 5_000L
  private val triggerMs = 3_000L
  private val cooldownMs = 15_000L

  private val samples = ArrayDeque<Pair<Long, Int>>()
  private var targetFps = targetFps.coerceAtLeast(0)
  private var lowSinceAt = 0L
  private var lastFiredAt = -cooldownMs

  fun updateTargetFps(fps: Int) {
    targetFps = fps.coerceAtLeast(0)
    reset()
  }

  fun reset() {
    samples.clear()
    lowSinceAt = 0L
  }

  /** @return true when the caller should degrade the format now. */
  fun onFpsSample(now: Long, fps: Int): Boolean {
    if (targetFps <= 0 || fps < 0) {
      return false
    }
    samples.addLast(now to fps)
    while (samples.isNotEmpty() && now - samples.first().first > windowMs) {
      samples.removeFirst()
    }
    val average = samples.sumOf { it.second }.toDouble() / samples.size
    if (average >= targetFps * 0.8) {
      lowSinceAt = 0L
      return false
    }
    if (lowSinceAt == 0L) {
      lowSinceAt = now
    }
    if (now - lowSinceAt < triggerMs || now - lastFiredAt < cooldownMs) {
      return false
    }
    lastFiredAt = now
    reset()
    return true
  }
}
