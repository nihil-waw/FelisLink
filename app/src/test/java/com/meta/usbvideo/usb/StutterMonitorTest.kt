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

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StutterMonitorTest {
  @Test
  fun firesWhenFpsStaysLow() {
    val monitor = StutterMonitor(60)
    var fired = false
    for (t in 1_000L..6_000L step 1_000L) {
      fired = monitor.onFpsSample(t, 24) || fired
    }
    assertTrue(fired, "3+ seconds under 80% of target must trigger a downgrade")
  }

  @Test
  fun staysQuietWhenFpsHealthy() {
    val monitor = StutterMonitor(30)
    var fired = false
    for (t in 1_000L..10_000L step 1_000L) {
      fired = monitor.onFpsSample(t, 28) || fired
    }
    assertFalse(fired, "28/30 fps is inside tolerance")
  }

  @Test
  fun oneShortDipIsNotEnough() {
    val monitor = StutterMonitor(60)
    assertFalse(monitor.onFpsSample(1_000L, 20))
    assertFalse(monitor.onFpsSample(2_000L, 60))
    assertFalse(monitor.onFpsSample(3_000L, 60))
    assertFalse(monitor.onFpsSample(4_000L, 60))
  }

  @Test
  fun cooldownBlocksRepeatFiring() {
    val monitor = StutterMonitor(60)
    var first = false
    for (t in 1_000L..5_000L step 1_000L) {
      first = monitor.onFpsSample(t, 20) || first
    }
    assertTrue(first)
    var second = false
    for (t in 6_000L..14_000L step 1_000L) {
      second = monitor.onFpsSample(t, 20) || second
    }
    assertFalse(second, "cooldown must absorb a sustained low stretch")
  }

  @Test
  fun targetChangeReArmsWithoutFiring() {
    val monitor = StutterMonitor(60)
    monitor.updateTargetFps(30)
    var fired = false
    for (t in 1_000L..8_000L step 1_000L) {
      fired = monitor.onFpsSample(t, 28) || fired
    }
    assertFalse(fired, "after a downgrade the new target must be the baseline")
  }
}
