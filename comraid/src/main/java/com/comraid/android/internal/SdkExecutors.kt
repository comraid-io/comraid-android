package com.comraid.android.internal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Shared SDK background work and main-thread delivery.
 * Register / token fetch / mobile network I/O run on [worker], never on the caller's thread
 * when using the async public APIs.
 */
internal object SdkExecutors {
  val worker: Executor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "comraid-sdk").apply { isDaemon = true }
    }

  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

  fun postMain(block: () -> Unit) {
    if (isMainThread()) {
      block()
    } else {
      mainHandler.post(block)
    }
  }

  /** True when running on the process main looper. False when Looper is unavailable (JVM unit tests). */
  fun isMainThread(): Boolean =
    try {
      val main = Looper.getMainLooper() ?: return false
      Looper.myLooper() == main
    } catch (_: Throwable) {
      false
    }
}
