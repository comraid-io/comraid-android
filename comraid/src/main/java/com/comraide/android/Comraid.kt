package com.comraide.android

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.comraide.android.internal.ComraidScheduler
import com.comraide.android.internal.FcmTokenProvider
import com.comraide.android.internal.HttpTransport
import com.comraide.android.internal.MobileApiClient
import com.comraide.android.internal.ScheduleStore
import com.comraide.android.internal.SharedPreferencesSubscriptionStore
import com.comraide.android.internal.SubscriptionStore
import com.comraide.android.internal.TokenProvider
import com.comraide.android.internal.UrlConnectionHttpTransport

object Comraid {
  private const val PERMISSION_REQUEST_CODE = 24_601
  private val lock = Any()
  @Volatile private var runtime: Runtime? = null
  @Volatile private var permissionCallback: ((Boolean) -> Unit)? = null

  fun configure(context: Context, configuration: ComraidConfiguration) {
    validateConfiguration(configuration)
    val appContext = context.applicationContext
    createDefaultChannel(appContext, configuration)
    val store = SharedPreferencesSubscriptionStore(appContext)
    val transport = UrlConnectionHttpTransport(configuration.apiBaseUrl)
    val tokenProvider = FcmTokenProvider()
    synchronized(lock) {
      runtime = Runtime(
        configuration = configuration,
        scheduleStore = ScheduleStore(appContext),
        scheduler = ComraidScheduler(appContext),
        subscriptionStore = store,
        api = MobileApiClient(configuration.appId.trim(), transport),
        tokenProvider = tokenProvider,
      )
    }
  }

  /**
   * Test seam: configure with injectable HTTP transport, token provider, and subscription store.
   */
  internal fun configureForTests(
    configuration: ComraidConfiguration,
    transport: HttpTransport,
    tokenProvider: TokenProvider,
    subscriptionStore: SubscriptionStore,
  ) {
    validateConfiguration(configuration)
    synchronized(lock) {
      runtime = Runtime(
        configuration = configuration,
        scheduleStore = null,
        scheduler = null,
        subscriptionStore = subscriptionStore,
        api = MobileApiClient(configuration.appId.trim(), transport),
        tokenProvider = tokenProvider,
      )
    }
  }

  internal fun resetForTests() {
    synchronized(lock) {
      runtime = null
      permissionCallback = null
    }
  }

  fun capabilities(context: Context): ComraidCapabilities {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val exactAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return ComraidCapabilities(
      notificationsEnabled = permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled(),
      exactAlarmsAvailable = exactAvailable,
    )
  }

  /**
   * Requests notification permission when needed, then registers a Subscription if granted.
   * Configure only stores settings; this is when token fetch + register run.
   */
  fun requestPermission(activity: Activity, onResult: (granted: Boolean) -> Unit) {
    requireRuntime()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      if (NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
        register()
        onResult(true)
      } else {
        onResult(false)
      }
      return
    }
    permissionCallback = onResult
    ActivityCompat.requestPermissions(
      activity,
      arrayOf(Manifest.permission.POST_NOTIFICATIONS),
      PERMISSION_REQUEST_CODE,
    )
  }

  /**
   * Forward [Activity.onRequestPermissionsResult] for [requestPermission].
   */
  fun onRequestPermissionsResult(
    requestCode: Int,
    grantResults: IntArray,
  ) {
    if (requestCode != PERMISSION_REQUEST_CODE) return
    val callback = permissionCallback ?: return
    permissionCallback = null
    val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
    if (granted) {
      register()
    }
    callback(granted)
  }

  /**
   * Fetches the FCM token and registers (or refreshes) a Subscription.
   * Call after notification permission is granted, or use [requestPermission].
   */
  fun register() {
    val current = requireRuntime()
    val token = current.tokenProvider.currentToken()
    val result = current.api.register(token)
    current.subscriptionStore.save(result.subscriptionId, result.credential)
  }

  /**
   * Handles FCM token rotation. Prefer replace-token when a credential exists; otherwise register.
   */
  fun onPushTokenRefresh() {
    val current = requireRuntime()
    val token = current.tokenProvider.currentToken()
    val subscriptionId = current.subscriptionStore.subscriptionId
    val credential = current.subscriptionStore.credential
    if (subscriptionId != null && credential != null) {
      current.api.replaceToken(subscriptionId, credential, token)
    } else {
      val result = current.api.register(token)
      current.subscriptionStore.save(result.subscriptionId, result.credential)
    }
  }

  fun login(externalUserId: String, identityToken: String? = null) {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    require(externalUserId.isNotBlank()) { "external_user_id must not be blank" }
    current.api.login(subscriptionId, credential, externalUserId.trim(), identityToken)
  }

  fun logout() {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    current.api.logout(subscriptionId, credential)
  }

  fun optOut() {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    current.api.optOut(subscriptionId, credential, channel = "push")
  }

  fun optIn() {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    current.api.optIn(subscriptionId, credential, channel = "push")
  }

  fun schedule(request: ComraidScheduleRequest): ComraidScheduleResult {
    val current = requireRuntime()
    val scheduler = current.scheduler ?: throw ComraidException.NotConfigured()
    val store = current.scheduleStore ?: throw ComraidException.NotConfigured()
    require(request.notification.id.isNotBlank()) { "Notification id must not be blank" }
    if (request.triggerAtMillis <= System.currentTimeMillis()) {
      throw ComraidException.InvalidSchedule("triggerAtMillis must be in the future")
    }
    val result = scheduler.schedule(request, current.configuration)
    store.put(request.copy(precision = result.appliedPrecision))
    return result
  }

  fun startTimer(
    notification: ComraidNotification,
    durationMillis: Long,
    precision: SchedulePrecision = SchedulePrecision.INEXACT,
    fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL,
  ): ComraidScheduleResult {
    if (durationMillis <= 0) throw ComraidException.InvalidSchedule("durationMillis must be positive")
    return schedule(
      ComraidScheduleRequest(
        notification = notification,
        triggerAtMillis = System.currentTimeMillis() + durationMillis,
        precision = precision,
        fallbackPolicy = fallbackPolicy,
      )
    )
  }

  fun cancel(id: String) {
    val current = requireRuntime()
    val scheduler = current.scheduler ?: throw ComraidException.NotConfigured()
    val store = current.scheduleStore ?: throw ComraidException.NotConfigured()
    scheduler.cancel(id)
    store.remove(id)
  }

  fun pendingSchedules(): List<ComraidScheduleRequest> {
    val current = requireRuntime()
    val store = current.scheduleStore ?: throw ComraidException.NotConfigured()
    return store.all()
  }

  internal fun restore(context: Context) {
    val current = runtime ?: return
    val store = current.scheduleStore ?: return
    val scheduler = current.scheduler ?: return
    val now = System.currentTimeMillis()
    store.all().forEach { request ->
      if (request.triggerAtMillis > now) {
        runCatching { scheduler.schedule(request, current.configuration) }
      } else {
        store.remove(request.notification.id)
      }
    }
  }

  internal fun markDelivered(id: String) {
    runtime?.scheduleStore?.remove(id)
  }

  private fun validateConfiguration(configuration: ComraidConfiguration) {
    require(configuration.appId.isNotBlank()) { "appId is required (cr_app_…)" }
  }

  private fun requireRuntime(): Runtime =
    runtime ?: throw ComraidException.NotConfigured()

  private fun requireSubscription(current: Runtime): Pair<String, String> {
    val subscriptionId = current.subscriptionStore.subscriptionId
    val credential = current.subscriptionStore.credential
    if (subscriptionId.isNullOrBlank() || credential.isNullOrBlank()) {
      throw ComraidException.NotReady()
    }
    return subscriptionId to credential
  }

  private fun createDefaultChannel(context: Context, configuration: ComraidConfiguration) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(
        NotificationChannel(
          configuration.defaultChannelId,
          configuration.defaultChannelName,
          NotificationManager.IMPORTANCE_DEFAULT,
        )
      )
    }
  }

  private data class Runtime(
    val configuration: ComraidConfiguration,
    val scheduleStore: ScheduleStore?,
    val scheduler: ComraidScheduler?,
    val subscriptionStore: SubscriptionStore,
    val api: MobileApiClient,
    val tokenProvider: TokenProvider,
  )
}
