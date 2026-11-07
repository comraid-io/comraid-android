package com.comraid.android

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.comraid.android.internal.AckKind
import com.comraid.android.internal.AckQueue
import com.comraid.android.internal.AckQueueStore
import com.comraid.android.internal.BackgroundAckHelpers
import com.comraid.android.internal.ComraidNotificationOpenActivity
import com.comraid.android.internal.ComraidScheduler
import com.comraid.android.internal.FcmTokenProvider
import com.comraid.android.internal.HttpTransport
import com.comraid.android.internal.MobileApiClient
import com.comraid.android.internal.NotificationChannels
import com.comraid.android.internal.RemoteMessageMapper
import com.comraid.android.internal.RemoteNotificationDisplay
import com.comraid.android.internal.ScheduleStore
import com.comraid.android.internal.SdkConfigStore
import com.comraid.android.internal.SdkExecutors
import com.comraid.android.internal.SharedPreferencesAckQueueStore
import com.comraid.android.internal.SharedPreferencesSubscriptionStore
import com.comraid.android.internal.SubscriptionStore
import com.comraid.android.internal.TokenProvider
import com.comraid.android.internal.UrlConnectionHttpTransport
import com.comraid.android.internal.WorkManagerAckFlushScheduler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

fun interface RemoteMessageListener {
  fun onRemoteMessage(event: RemoteMessageEvent)
}

fun interface TokenRefreshListener {
  fun onTokenRefresh(token: String)
}

object ComRaid {
  private const val PERMISSION_REQUEST_CODE = 24_601
  private val lock = Any()
  @Volatile private var runtime: Runtime? = null
  @Volatile private var permissionCallback: ((Boolean) -> Unit)? = null
  @Volatile private var applicationContext: Context? = null
  @Volatile private var initialNotification: RemoteMessageEvent? = null
  @Volatile private var initialNotificationConsumed = false
  @Volatile private var backgroundHandlerRegistered = false
  private val foregroundMessageListeners = CopyOnWriteArrayList<RemoteMessageListener>()
  private val backgroundMessageListeners = CopyOnWriteArrayList<RemoteMessageListener>()
  private val openedListeners = CopyOnWriteArrayList<RemoteMessageListener>()
  private val tokenRefreshListeners = CopyOnWriteArrayList<TokenRefreshListener>()

  fun configure(context: Context, configuration: ComRaidConfiguration) {
    validateConfiguration(configuration)
    val appContext = context.applicationContext
    applicationContext = appContext
    SdkConfigStore(appContext).save(configuration)
    NotificationChannels.ensureDefault(appContext, configuration)
    val store = SharedPreferencesSubscriptionStore(appContext)
    val transport = UrlConnectionHttpTransport(configuration.apiBaseUrl)
    val api = MobileApiClient(configuration.appId.trim(), transport)
    // configure runs from Application.onCreate on the main thread; the category fetch is network I/O.
    NotificationChannels.syncCategoriesAsync(appContext, api)
    val tokenProvider = FcmTokenProvider()
    val ackQueue = AckQueue(
      apiBaseUrl = configuration.apiBaseUrl,
      store = SharedPreferencesAckQueueStore(appContext),
      transport = transport,
      flushScheduler = WorkManagerAckFlushScheduler(appContext),
    )
    synchronized(lock) {
      runtime = Runtime(
        configuration = configuration,
        scheduleStore = ScheduleStore(appContext),
        scheduler = ComraidScheduler(appContext),
        subscriptionStore = store,
        api = api,
        tokenProvider = tokenProvider,
        ackQueue = ackQueue,
        deviceProperties = null,
        externalUserId = store.externalUserId,
      )
    }
    ackQueue.flushAsync()
  }

  /**
   * Test seam: configure with injectable HTTP transport, token provider, subscription store,
   * and ACK queue store.
   */
  internal fun configureForTests(
    configuration: ComRaidConfiguration,
    transport: HttpTransport,
    tokenProvider: TokenProvider,
    subscriptionStore: SubscriptionStore,
    ackQueueStore: AckQueueStore = InMemoryAckQueueStoreForTests(),
  ) {
    validateConfiguration(configuration)
    val api = MobileApiClient(configuration.appId.trim(), transport)
    val ackQueue = AckQueue(
      apiBaseUrl = configuration.apiBaseUrl,
      store = ackQueueStore,
      transport = transport,
    )
    synchronized(lock) {
      runtime = Runtime(
        configuration = configuration,
        scheduleStore = null,
        scheduler = null,
        subscriptionStore = subscriptionStore,
        api = api,
        tokenProvider = tokenProvider,
        ackQueue = ackQueue,
        deviceProperties = null,
        externalUserId = subscriptionStore.externalUserId,
      )
    }
  }

  internal fun resetForTests() {
    synchronized(lock) {
      runtime = null
      permissionCallback = null
      applicationContext = null
      initialNotification = null
      initialNotificationConsumed = false
      backgroundHandlerRegistered = false
      foregroundMessageListeners.clear()
      backgroundMessageListeners.clear()
      openedListeners.clear()
      tokenRefreshListeners.clear()
    }
  }

  /**
   * Provider push token (FCM). Not a ComRaid Endpoint / Subscription id.
   */
  fun getToken(): String = requireRuntime().tokenProvider.currentToken()

  fun addForegroundMessageListener(listener: RemoteMessageListener) {
    foregroundMessageListeners.addIfAbsent(listener)
  }

  fun removeForegroundMessageListener(listener: RemoteMessageListener) {
    foregroundMessageListeners.remove(listener)
  }

  fun addBackgroundMessageListener(listener: RemoteMessageListener) {
    backgroundMessageListeners.addIfAbsent(listener)
  }

  fun removeBackgroundMessageListener(listener: RemoteMessageListener) {
    backgroundMessageListeners.remove(listener)
  }

  fun addNotificationOpenedListener(listener: RemoteMessageListener) {
    openedListeners.addIfAbsent(listener)
  }

  fun removeNotificationOpenedListener(listener: RemoteMessageListener) {
    openedListeners.remove(listener)
  }

  fun addTokenRefreshListener(listener: TokenRefreshListener) {
    tokenRefreshListeners.addIfAbsent(listener)
  }

  fun removeTokenRefreshListener(listener: TokenRefreshListener) {
    tokenRefreshListeners.remove(listener)
  }

  /**
   * Cold-start tap payload, if any. Cleared after the first successful read.
   */
  fun getInitialNotification(): RemoteMessageEvent? {
    synchronized(lock) {
      if (initialNotificationConsumed) return null
      val value = initialNotification ?: return null
      initialNotificationConsumed = true
      initialNotification = null
      return value
    }
  }

  /**
   * Consume a host Activity launch [Intent] (system-tray tap with `cr_*` extras).
   * Records opened when attribution is present and surfaces
   * [getInitialNotification] / opened listeners.
   */
  fun consumeLaunchIntent(intent: Intent?, coldStart: Boolean = false): RemoteMessageEvent? {
    if (intent == null) return null
    val data = ComraidNotificationOpenActivity.dataFromIntent(intent) ?: return null
    val actionId = intent.getStringExtra(ComraidNotificationOpenActivity.EXTRA_ACTION_ID)
      ?: intent.getStringExtra(com.comraid.android.internal.ComraidNotificationActionReceiver.EXTRA_ACTION_ID)
    return handleNotificationOpen(data, actionId, coldStart)
  }

  /**
   * Process a notification open (body tap or action button). Enqueues opened ACK and
   * notifies opened listeners / cold-start [getInitialNotification].
   *
   * @param ackContext optional Context for the SharedPreferences ACK queue when the
   *   runtime is not configured yet (notification trampoline before [configure]).
   */
  internal fun handleNotificationOpen(
    data: Map<String, String>,
    actionId: String? = null,
    coldStart: Boolean = false,
    ackContext: Context? = null,
  ): RemoteMessageEvent {
    val trimmedAction = actionId?.takeIf { it.isNotBlank() }
    val event = RemoteMessageMapper.toEvent(data, trimmedAction)
    val queue = runtime?.ackQueue
      ?: (ackContext ?: applicationContext)?.let { BackgroundAckHelpers.from(it)?.queue }
    if (queue != null) {
      queue.enqueueFromPayload(AckKind.OPENED, data, trimmedAction)
      queue.flushAsync()
    }
    if (coldStart) {
      synchronized(lock) {
        if (!initialNotificationConsumed && initialNotification == null) {
          initialNotification = event
        }
      }
    } else {
      synchronized(lock) {
        if (!initialNotificationConsumed && initialNotification == null && openedListeners.isEmpty()) {
          initialNotification = event
        }
      }
    }
    openedListeners.forEach { runCatching { it.onRemoteMessage(event) } }
    return event
  }

  /**
   * Post a local tray item from a received message's title/body/actions (optional promotion).
   * Body/action taps auto ACK opened (with actionId for actions).
   */
  fun presentNotification(event: RemoteMessageEvent) {
    val ctx = applicationContext ?: throw ComRaidException.NotConfigured()
    val configuration = runtime?.configuration
      ?: SdkConfigStore(ctx).load()
      ?: throw ComRaidException.NotConfigured()
    event.title?.takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("presentNotification requires a non-blank title")
    val payload = RemoteMessageMapper.toPayload(event)
    NotificationChannels.ensureDefault(ctx, configuration)
    RemoteNotificationDisplay.show(ctx, configuration, payload)
  }

  /**
   * Marks that a JS background message handler is registered (RN Headless path).
   * Receive still never auto-opens; this only gates background event delivery to JS.
   */
  fun setBackgroundHandlerRegistered(registered: Boolean) {
    backgroundHandlerRegistered = registered
  }

  fun isBackgroundHandlerRegistered(): Boolean = backgroundHandlerRegistered

  /** Present from raw FCM-shaped data map (title via `cr_title`). */
  fun presentNotification(data: Map<String, String>) {
    presentNotification(RemoteMessageMapper.toEvent(data))
  }

  internal fun notifyMessageReceived(data: Map<String, String>, appInForeground: Boolean) {
    val event = RemoteMessageMapper.toEvent(data)
    val targets = if (appInForeground) foregroundMessageListeners else backgroundMessageListeners
    targets.forEach { runCatching { it.onRemoteMessage(event) } }
  }

  internal fun notifyNotificationOpened(data: Map<String, String>, actionId: String?) {
    // Activity trampoline already enqueued the opened ACK; only surface listeners / cold stash.
    val event = RemoteMessageMapper.toEvent(data, actionId)
    synchronized(lock) {
      if (!initialNotificationConsumed && initialNotification == null && openedListeners.isEmpty()) {
        // Cold path before JS listeners attach: stash for getInitialNotification.
        initialNotification = event
      }
    }
    openedListeners.forEach { runCatching { it.onRemoteMessage(event) } }
  }

  internal fun notifyTokenRefresh(token: String) {
    if (token.isBlank()) return
    tokenRefreshListeners.forEach { runCatching { it.onTokenRefresh(token) } }
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
   * Configure only stores settings; this is when token fetch + register run (on the SDK
   * executor). [onResult] is invoked on the main thread after register completes when
   * permission is granted; register failures mean [onResult] is not called.
   * Channels are created in [configure] before this runs (Android 13+ prerequisite).
   */
  fun requestPermission(activity: Activity, onResult: (granted: Boolean) -> Unit) {
    requireRuntime()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      if (NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
        registerAfterPermissionGrant(onResult)
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
   * On grant, register runs on the SDK executor; [permissionCallback] is invoked on the
   * main thread after register completes (or immediately with false when denied).
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
      registerAfterPermissionGrant(callback)
    } else {
      callback(false)
    }
  }

  /**
   * Fetches the FCM token and registers (or refreshes) a Subscription on the SDK executor.
   * Returns immediately; [onComplete] is delivered on the main thread.
   * Call after notification permission is granted, or use [requestPermission].
   */
  fun register(onComplete: (Result<Unit>) -> Unit) {
    requireRuntime()
    SdkExecutors.worker.execute {
      val result = runCatching { registerBlockingInternal() }
      SdkExecutors.postMain { onComplete(result) }
    }
  }

  /**
   * Suspends until [register] completes. Safe from any thread; token fetch and network I/O
   * run on the SDK executor. Resumes on the main thread when started from the main thread
   * (callback delivery), or on the thread that runs the completion callback.
   */
  suspend fun register() {
    suspendCoroutine { continuation ->
      register { result ->
        result.fold(
          onSuccess = { continuation.resume(Unit) },
          onFailure = { continuation.resumeWithException(it) },
        )
      }
    }
  }

  /**
   * Blocking register for worker threads (for example a host-owned executor).
   * Prefer [register] with a callback or the suspend overload. Throws if called on the
   * main thread.
   */
  @WorkerThread
  fun registerBlocking() {
    if (SdkExecutors.isMainThread()) {
      throw IllegalStateException(
        "ComRaid.registerBlocking must not be called on the main thread; use register { } or suspend register()",
      )
    }
    registerBlockingInternal()
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
      current.api.replaceToken(subscriptionId, credential, token, current.deviceProperties)
    } else {
      val result = current.api.register(
        token = token,
        properties = current.deviceProperties,
        subscriptionCredential = null,
      )
      current.subscriptionStore.save(result.subscriptionId, result.credential)
    }
    notifyTokenRefresh(token)
  }

  fun login(externalUserId: String, jwt: String? = null) {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    require(externalUserId.isNotBlank()) { "external_user_id must not be blank" }
    val trimmed = externalUserId.trim()
    current.api.login(subscriptionId, credential, trimmed, jwt)
    current.subscriptionStore.setExternalUserId(trimmed)
    synchronized(lock) {
      runtime = current.copy(externalUserId = trimmed)
    }
  }

  fun logout() {
    val current = requireRuntime()
    val (subscriptionId, credential) = requireSubscription(current)
    current.api.logout(subscriptionId, credential)
    current.subscriptionStore.clear()
    synchronized(lock) {
      runtime = current.copy(externalUserId = null)
    }
    // Fresh Subscription after Logout; keep sync semantics of logout().
    registerBlockingInternal()
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

  /** Persist Subscription device Properties for the next register / token refresh. */
  fun setDeviceProperties(properties: DeviceProperties) {
    val current = requireRuntime()
    synchronized(lock) {
      runtime = current.copy(deviceProperties = properties)
    }
  }

  /**
   * Upsert Contact Attributes for the logged-in Contact
   * (`PUT /mobile/subscriptions/{id}/attributes`).
   * A `null` value removes that Attribute.
   */
  fun setAttributes(attributes: Map<String, Any?>) {
    val current = requireRuntime()
    current.externalUserId ?: throw ComRaidException.NotReady()
    val (subscriptionId, credential) = requireSubscription(current)
    current.api.upsertAttributes(subscriptionId, credential, attributes)
  }

  /**
   * Enqueue a delivered ACK and flush it on the SDK's background thread, so any thread may call it.
   * Returns false when ids are blank.
   */
  fun acknowledgeDelivered(deliveryId: String, attributionToken: String): Boolean {
    val current = requireRuntime()
    if (deliveryId.isBlank() || attributionToken.isBlank()) return false
    current.ackQueue.enqueueFromPayload(
      AckKind.DELIVERED,
      mapOf(
        "cr_delivery_id" to deliveryId.trim(),
        "cr_attribution_token" to attributionToken.trim(),
      ),
    )
    current.ackQueue.flushAsync()
    return true
  }

  /** Enqueue an opened ACK (optional action button id) and flush it on the SDK's background thread. */
  fun acknowledgeOpened(
    deliveryId: String,
    attributionToken: String,
    actionId: String? = null,
  ): Boolean {
    val current = requireRuntime()
    if (deliveryId.isBlank() || attributionToken.isBlank()) return false
    current.ackQueue.enqueueFromPayload(
      AckKind.OPENED,
      mapOf(
        "cr_delivery_id" to deliveryId.trim(),
        "cr_attribution_token" to attributionToken.trim(),
      ),
      actionId = actionId,
    )
    current.ackQueue.flushAsync()
    return true
  }

  /** Flush any durable ACK backlog (safe to call from Application.onCreate). */
  fun flushAcks() {
    runtime?.ackQueue?.flushAsync()
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
      ),
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

  private fun registerAfterPermissionGrant(onResult: (granted: Boolean) -> Unit) {
    register { result ->
      result.onSuccess { onResult(true) }
      // Match prior sync behaviour: permission callback is skipped when register throws.
    }
  }

  private fun registerBlockingInternal() {
    val current = requireRuntime()
    val token = current.tokenProvider.currentToken()
    val result = current.api.register(
      token = token,
      properties = current.deviceProperties,
      subscriptionCredential = current.subscriptionStore.credential,
    )
    current.subscriptionStore.save(result.subscriptionId, result.credential)
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

  private data class Runtime(
    val configuration: ComRaidConfiguration,
    val scheduleStore: ScheduleStore?,
    val scheduler: ComraidScheduler?,
    val subscriptionStore: SubscriptionStore,
    val api: MobileApiClient,
    val tokenProvider: TokenProvider,
    val ackQueue: AckQueue,
    val deviceProperties: DeviceProperties?,
    val externalUserId: String?,
  )

  private class InMemoryAckQueueStoreForTests : AckQueueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) {
      map[key] = value
    }
  }
}
