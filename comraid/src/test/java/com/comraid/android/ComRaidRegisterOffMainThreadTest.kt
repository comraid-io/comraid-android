package com.comraid.android

import android.os.Handler
import android.os.Looper
import com.comraid.android.internal.HttpRequest
import com.comraid.android.internal.HttpResponse
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.InMemorySubscriptionStore
import com.comraid.android.internal.TokenProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Seam: public [ComRaid.register] callback API.
 * Calling from the main looper must return without waiting on token/network, and the
 * completion callback must run on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComRaidRegisterOffMainThreadTest {
  private lateinit var transport: RecordingTransport
  private lateinit var store: InMemorySubscriptionStore

  @Before
  fun setUp() {
    ComRaid.resetForTests()
    transport = RecordingTransport()
    store = InMemorySubscriptionStore()
  }

  @Test
  fun register_from_main_looper_completes_without_blocking_and_delivers_on_main() {
    val tokenStarted = CountDownLatch(1)
    val releaseToken = CountDownLatch(1)
    val tokens = TokenProvider {
      tokenStarted.countDown()
      assertTrue(
        "token fetch must not run on the main looper",
        Looper.myLooper() != Looper.getMainLooper(),
      )
      assertTrue(releaseToken.await(5, TimeUnit.SECONDS))
      "fcm-token-async"
    }
    transport.nextResponse = HttpResponse(
      statusCode = 201,
      body = """{"subscription_id":"sub-async","subscription_credential":"cred-async","channel":"push","anonymous":true,"platform":"android","provider":"fcm"}""",
    )
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(appId = "cr_app_test", smallIconResourceId = 1),
      transport = transport,
      tokenProvider = tokens,
      subscriptionStore = store,
    )

    val callbackDone = CountDownLatch(1)
    val callbackThread = AtomicReference<Thread>()
    val registerReturnedBeforeToken = AtomicBoolean(false)

    Handler(Looper.getMainLooper()).post {
      ComRaid.register { result ->
        callbackThread.set(Thread.currentThread())
        assertTrue(result.isSuccess)
        callbackDone.countDown()
      }
      // register(...) must return before the gated token provider finishes.
      registerReturnedBeforeToken.set(tokenStarted.count > 0 || releaseToken.count > 0)
    }

    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertTrue(
      "register must return on the main thread without waiting for the FCM token",
      registerReturnedBeforeToken.get(),
    )
    assertTrue(tokenStarted.await(5, TimeUnit.SECONDS))

    releaseToken.countDown()

    val deadline = System.currentTimeMillis() + 5_000
    while (callbackDone.count > 0 && System.currentTimeMillis() < deadline) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    assertTrue("callback should complete", callbackDone.await(1, TimeUnit.SECONDS))
    assertEquals(Looper.getMainLooper().thread, callbackThread.get())
    assertEquals("sub-async", store.subscriptionId)
    assertEquals("cred-async", store.credential)
    assertFalse(transport.requests.isEmpty())
  }

  @Test
  fun registerBlocking_throws_on_main_thread() {
    ComRaid.configureForTests(
      configuration = ComRaidConfiguration(appId = "cr_app_test", smallIconResourceId = 1),
      transport = transport,
      tokenProvider = TokenProvider { "fcm" },
      subscriptionStore = store,
    )
    val error = AtomicReference<Throwable>()
    Handler(Looper.getMainLooper()).post {
      try {
        ComRaid.registerBlocking()
      } catch (t: Throwable) {
        error.set(t)
      }
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    assertNotNull(error.get())
    assertTrue(error.get() is IllegalStateException)
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
