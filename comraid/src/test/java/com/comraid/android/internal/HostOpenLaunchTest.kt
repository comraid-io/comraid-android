package com.comraid.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostOpenLaunchTest {
  @Test
  fun with_deep_link_targets_package_launcher_and_keeps_deep_link_extra() {
    val data = mapOf(
      "cr_delivery_id" to "d1",
      "cr_attribution_token" to "tok",
      "cr_deep_link" to "myapp://orders/42",
      "cr_title" to "Order ready",
    )

    val target = HostOpenLaunch.resolve(data)

    assertTrue(target is HostOpenLaunch.Target.PackageLauncher)
    val launcher = target as HostOpenLaunch.Target.PackageLauncher
    assertEquals("myapp://orders/42", launcher.extras["cr_deep_link"])
    assertEquals("d1", launcher.extras["cr_delivery_id"])
  }

  @Test
  fun without_deep_link_still_targets_package_launcher() {
    val data = mapOf(
      "cr_delivery_id" to "d2",
      "cr_attribution_token" to "tok2",
    )

    val target = HostOpenLaunch.resolve(data)

    assertTrue(target is HostOpenLaunch.Target.PackageLauncher)
  }
}
