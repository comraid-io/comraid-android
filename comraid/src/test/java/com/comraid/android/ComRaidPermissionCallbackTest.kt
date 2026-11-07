package com.comraid.android

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Seam: public [ComRaid.requestPermission] callback.
 * After grant (or when permission is already available), register failure must still
 * deliver [onResult] so hosts do not hang.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComRaidPermissionCallbackTest {
  private lateinit var transport: RecordingTransport
  private lateinit var store: InMemorySubscriptionStore
  private lateinit var activity: Activity

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    store = InMemorySubscriptionStore()
    activity = Robolectric.buildActivity(Activity::class.java).setup().get()
  }

  @Test
  fun requestPermission_delivers_onResult_false_when_register_fails() {
    transport.nextResponse = HttpResponse(statusCode = 500, body = """{"error":"boom"}""")
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(appId = "cr_app_test", smallIconResourceId = 1),
      transport = transport,
      tokenProvider = TokenProvider { "fcm-token" },
      subscriptionStore = store,
    )

    val callbackDone = CountDownLatch(1)
    val granted = AtomicBoolean(true)
    val callbackThread = AtomicReference<Thread>()

    Handler(Looper.getMainLooper()).post {
      ComRaid.requestPermission(activity) { result ->
        granted.set(result)
        callbackThread.set(Thread.currentThread())
        callbackDone.countDown()
      }
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()

    val deadline = System.currentTimeMillis() + 5_000
    while (callbackDone.count > 0 && System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    assertTrue(
      "onResult must run even when register fails after permission grant",
      callbackDone.await(1, TimeUnit.SECONDS),
    )
    assertEquals(false, granted.get())
    assertEquals(Looper.getMainLooper().thread, callbackThread.get())
  }

  private class RecordingTransport : HttpTransport {
    val requests: MutableList<HttpRequest> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var nextResponse: HttpResponse? = null

    override fun execute(request: HttpRequest): HttpResponse {
      requests += request
      return nextResponse ?: error("No response configured")
    }
  }
}
