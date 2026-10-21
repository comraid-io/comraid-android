package com.comraid.android

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
import com.comraid.android.internal.ComraidScheduler
import com.comraid.android.internal.FcmTokenProvider
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.MobileApiClient
import com.comraid.android.internal.ScheduleStore
import com.comraid.android.internal.SharedPreferencesSubscriptionStore
import com.comraid.android.internal.SubscriptionStore
import com.comraid.android.internal.TokenProvider
import com.comraid.android.internal.UrlConnectionHttpTransport

object ComRaid {
  private const val PERMISSION_REQUEST_CODE = 24_601
  private val lock = Any()
  @Volatile private var runtime: Runtime? = null
  @Volatile private var permissionCallback: ((Boolean) -> Unit)? = null

  fun configure(context: Context, configuration: ComRaidConfiguration) {
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
    configuration: ComRaidConfiguration,
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

  fun capabilities(context: Context): ComRaidCapabilities {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val exactAvailable = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return ComRaidCapabilities(
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

  fun login(externalUserId: String, jwt: String? = null) {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    require(externalUserId.isNotBlank()) { "external_user_id must not be blank" }
    current.api.login(subscriptionId, credential, externalUserId.trim(), jwt)
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

  fun schedule(request: ComRaidScheduleRequest): ComRaidScheduleResult {
    val current = requireRuntime()
    val scheduler = current.scheduler ?: throw ComRaidException.NotConfigured()
    val store = current.scheduleStore ?: throw ComRaidException.NotConfigured()
    require(request.notification.id.isNotBlank()) { "Notification id must not be blank" }
    if (request.triggerAtMillis <= System.currentTimeMillis()) {
      throw ComRaidException.InvalidSchedule("triggerAtMillis must be in the future")
    }
    val result = scheduler.schedule(request, current.configuration)
    store.put(request.copy(precision = result.appliedPrecision))
    return result
  }

  fun startTimer(
    notification: ComRaidNotification,
    durationMillis: Long,
    precision: SchedulePrecision = SchedulePrecision.INEXACT,
    fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL,
  ): ComRaidScheduleResult {
    if (durationMillis <= 0) throw ComRaidException.InvalidSchedule("durationMillis must be positive")
    return schedule(
      ComRaidScheduleRequest(
        notification = notification,
        triggerAtMillis = System.currentTimeMillis() + durationMillis,
        precision = precision,
        fallbackPolicy = fallbackPolicy,
      )
    )
  }

  fun cancel(id: String) {
    val current = requireRuntime()
    val scheduler = current.scheduler ?: throw ComRaidException.NotConfigured()
    val store = current.scheduleStore ?: throw ComRaidException.NotConfigured()
    scheduler.cancel(id)
    store.remove(id)
  }

  fun pendingSchedules(): List<ComRaidScheduleRequest> {
    val current = requireRuntime()
    val store = current.scheduleStore ?: throw ComRaidException.NotConfigured()
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

  private fun validateConfiguration(configuration: ComRaidConfiguration) {
    require(configuration.appId.isNotBlank()) { "appId is required (cr_app_…)" }
  }

  private fun requireRuntime(): Runtime =
    runtime ?: throw ComRaidException.NotConfigured()

  private fun requireSubscription(current: Runtime): Pair<String, String> {
    val subscriptionId = current.subscriptionStore.subscriptionId
    val credential = current.subscriptionStore.credential
    if (subscriptionId.isNullOrBlank() || credential.isNullOrBlank()) {
      throw ComRaidException.NotReady()
    }
    return subscriptionId to credential
  }

  private fun createDefaultChannel(context: Context, configuration: ComRaidConfiguration) {
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
    val configuration: ComRaidConfiguration,
    val scheduleStore: ScheduleStore?,
    val scheduler: ComraidScheduler?,
    val subscriptionStore: SubscriptionStore,
    val api: MobileApiClient,
    val tokenProvider: TokenProvider,
  )
}
