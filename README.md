# ComRaid Android SDK

Native Android SDK for push Subscriptions, remote notification display, delivery ACKs, and local timers. Vocabulary: **Endpoint** / **Subscription**, `external_user_id`, Identity verification **JWT** (`login(..., jwt)`), Contact **Attributes**, Subscription **Properties**. Maven coordinate: `com.comraid:comraid`.

Canonical docs (install, getting started, method reference): [docs.comraid.io/sdks/android](https://docs.comraid.io/sdks/android).

**Not wrapped:** `mobileSubscriptionSession` exists in the mobile OpenAPI but has **no SDK method** — call it over HTTP directly until a wrapper lands.

## Setup

1. Add the library and **Firebase Messaging** (the host app owns the Firebase project).
2. Configure early in `Application.onCreate` with a publishable App ID (`cr_app_…`). Channels are created here **before** permission / token acquisition (Android 13+).

```kotlin
ComRaid.configure(
  applicationContext,
  ComRaidConfiguration(
    appId = "cr_app_your_id",
    // apiBaseUrl = "http://10.0.2.2:8080", // optional; default https://api.comraid.io
    smallIconResourceId = R.drawable.ic_notification,
  ),
)

val caps = ComRaid.capabilities(applicationContext)
// caps.notificationsEnabled, caps.exactAlarmsAvailable
```

Declare `com.comraid.android.internal.ComraidMessagingService` (or a subclass) in the host manifest for FCM token rotation (`onPushTokenRefresh`), remote display (including action buttons), and durable delivered/opened ACKs.

## Method reference

Grouped by lifecycle. Depth B: signature, purpose, parameters, returns. Examples are clustered per group (not every method).

### Configure

#### `configure(context: Context, configuration: ComRaidConfiguration)`

Stores App ID / API base URL, creates the default channel, starts a background sync of category channels, and prepares the runtime. Call once from `Application.onCreate` before permission, register, schedule, or ACKs. No network I/O runs on the calling thread.

| Parameter | Type | Description |
|-----------|------|-------------|
| `context` | `Context` | Any context; the SDK keeps `applicationContext`. |
| `configuration` | `ComRaidConfiguration` | `appId` (`cr_app_…`), optional `apiBaseUrl`, channel defaults, `smallIconResourceId`, optional `accentColor`. |

**Returns:** `Unit`. Throws if `appId` is blank.

#### `capabilities(context: Context): ComRaidCapabilities`

Local capability probe (no network): whether notifications are enabled and whether exact alarms are available on this device / OS version.

| Parameter | Type | Description |
|-----------|------|-------------|
| `context` | `Context` | Used for `AlarmManager` and notification permission / settings. |

**Returns:** `ComRaidCapabilities(notificationsEnabled, exactAlarmsAvailable)`.

### Identity

#### `requestPermission(activity: Activity, onResult: (granted: Boolean) -> Unit)`

Requests `POST_NOTIFICATIONS` when needed (Android 13+), then registers a Subscription if granted. Configure only stores settings; this is when token fetch + register run.

| Parameter | Type | Description |
|-----------|------|-------------|
| `activity` | `Activity` | Host activity for the system permission dialog. |
| `onResult` | `(Boolean) -> Unit` | Invoked with whether permission (and notifications) are granted. |

**Returns:** `Unit`. Requires prior `configure`.

#### `onRequestPermissionsResult(requestCode: Int, grantResults: IntArray)`

Forward `Activity.onRequestPermissionsResult` for `requestPermission`. Registers on grant.

| Parameter | Type | Description |
|-----------|------|-------------|
| `requestCode` | `Int` | Must match the SDK’s permission request. |
| `grantResults` | `IntArray` | System grant results. |

**Returns:** `Unit`.

#### `register()`

Fetches the FCM token and registers (or refreshes) a Subscription. Call after notification permission is granted, or use `requestPermission`.

**Returns:** `Unit`. Throws `ComRaidException.NotConfigured` / `ApiError`.

#### `onPushTokenRefresh()`

Handles FCM token rotation. Prefer replace-token when a Subscription credential exists; otherwise register. Prefer letting `ComraidMessagingService` call this; invoke manually only if you own the messaging service.

**Returns:** `Unit`. Requires prior `configure`.

#### `getToken(): String`

Returns the Provider push token (FCM). Not a ComRaid Endpoint / Subscription id.

**Returns:** FCM registration token. Requires prior `configure`.

#### `getInitialNotification(): RemoteMessageEvent?`

Returns the notification tap that cold-started the app, if any. Cleared after the first read.

**Returns:** `RemoteMessageEvent?`.

#### `consumeLaunchIntent(intent: Intent?, coldStart: Boolean = false): RemoteMessageEvent?`

Reads `cr_*` extras from a system-tray / launcher launch intent, records opened, and surfaces open listeners / initial notification.

| Parameter | Type | Description |
|-----------|------|-------------|
| `intent` | `Intent?` | Host Activity launch intent. |
| `coldStart` | `Boolean` | When true, stash for [getInitialNotification]. |

**Returns:** Parsed event, or null when no ComRaid payload is present.

#### `presentNotification(event: RemoteMessageEvent)` / `presentNotification(data: Map<String, String>)`

Posts a local tray item from title/body/actions (optional Data-only promotion). Body/action taps auto-ACK opened (with `actionId` for actions). Data-only Notifications are never auto-drawn on receive — call this when the app wants shade UI. Presentation fields travel on the event (`imageUrl`, `subtitle`, `sound`, `badge`, `threadId`, `androidChannelId`, `priority`), so either form keeps them. Android trays never draw `subtitle`; on Android 8+ the category channel decides sound and importance (ADR 0012).

**Returns:** `Unit`. Requires prior `configure` (or persisted SdkConfigStore).

`ComRaidConfiguration.displayForegroundNotifications` (default `true`) controls whether non–data-only alerts are shown by the SDK in the foreground; set `false` to handle alerts only via receive listeners.

#### `setBackgroundHandlerRegistered(registered: Boolean)`

Records that a JS background message handler is registered (React Native). Does not change ACK behavior; receive never auto-opens.

**Returns:** `Unit`.

#### `login(externalUserId: String, jwt: String? = null)`

Binds the current Subscription to a known Contact (`external_user_id`). Optional Identity verification JWT from your backend.

| Parameter | Type | Description |
|-----------|------|-------------|
| `externalUserId` | `String` | Non-blank External user ID. |
| `jwt` | `String?` | Optional verification token (`jwt` field). |

**Returns:** `Unit`. Throws `NotConfigured`, `NotReady`, or `ApiError`.

#### `logout()`

Clears the Contact binding on the Subscription. Stored credential remains so Contact-level opt-in/out still works while Inactive.

**Returns:** `Unit`.

#### `optOut()`

Opt the Subscription out of the push Channel (`channel: push`).

**Returns:** `Unit`. Requires a registered Subscription.

#### `optIn()`

Opt the Subscription back into the push Channel (`channel: push`).

**Returns:** `Unit`. Requires a registered Subscription.

```kotlin
ComRaid.requestPermission(activity) { granted -> /* … */ }
// or ComRaid.register() when permission is already granted

ComRaid.login("user_123")
ComRaid.login("user_123", jwt = jwtFromYourBackend)
ComRaid.logout()
ComRaid.optOut()
ComRaid.optIn()
```

### Properties and Attributes

#### `setDeviceProperties(properties: DeviceProperties)`

Persist Subscription device Properties for the next register / token refresh (`app_version` + `device` object).

| Parameter | Type | Description |
|-----------|------|-------------|
| `properties` | `DeviceProperties` | Optional `appVersion`, `model`, `osVersion`, `extras`. |

**Returns:** `Unit`.

#### `setAttributes(attributes: Map<String, Any?>)`

Upsert Contact Attributes for the logged-in Contact (`PUT /mobile/subscriptions/{id}/attributes`).

| Parameter | Type | Description |
|-----------|------|-------------|
| `attributes` | `Map<String, Any?>` | Attribute map (e.g. `plan` → `premium`). Empty map is a successful empty update. |

**Returns:** `Unit`. Throws `NotReady` if not logged in.

```kotlin
ComRaid.setDeviceProperties(
  DeviceProperties(appVersion = "1.2.0", model = "Pixel 8", osVersion = "14"),
)
// included on the next register / token refresh

ComRaid.login("user_123")
ComRaid.setAttributes(mapOf("plan" to "premium"))
```

### Delivery ACKs

Remote messages enqueue durable delivered/opened ACKs (persisted SharedPreferences queue with Retry-After / backoff). Flush works from the messaging service without a foreground `ComRaid` singleton as long as `configure` has run at least once on the device.

#### `acknowledgeDelivered(deliveryId: String, attributionToken: String): Boolean`

Enqueue a delivered ACK and flush it on the SDK's background thread (safe from any thread).

| Parameter | Type | Description |
|-----------|------|-------------|
| `deliveryId` | `String` | Delivery id from the push payload. |
| `attributionToken` | `String` | Attribution token from the push payload. |

**Returns:** `false` when either id is blank; otherwise `true` once the ACK is queued.

#### `acknowledgeOpened(deliveryId: String, attributionToken: String, actionId: String? = null): Boolean`

Enqueue an opened ACK (optional action button id) and flush it on the SDK's background thread.

| Parameter | Type | Description |
|-----------|------|-------------|
| `deliveryId` | `String` | Delivery id from the push payload. |
| `attributionToken` | `String` | Attribution token from the push payload. |
| `actionId` | `String?` | Optional action button id. |

**Returns:** `false` when either id is blank; otherwise `true` once the ACK is queued.

#### `flushAcks()`

Flush any durable ACK backlog on the SDK's background thread (safe to call from `Application.onCreate`). Notification taps and actions flush the same way.

**Returns:** `Unit`. No-op if not configured.

```kotlin
ComRaid.acknowledgeDelivered(deliveryId, attributionToken)
ComRaid.acknowledgeOpened(deliveryId, attributionToken, actionId = "accept")
ComRaid.flushAcks()
```

### Local schedule and timers

Exact alarms are opt-in: the host must declare the applicable exact-alarm permission, satisfy store policy, and obtain system access. ComRaid defaults to inexact alarms and never adds an exact-alarm permission to the library manifest. Boot restoration needs `ComRaid.configure` from `Application.onCreate` before the boot receiver can restore schedules.

#### `schedule(request: ComRaidScheduleRequest): ComRaidScheduleResult`

Schedule a local notification at an absolute trigger time.

| Parameter | Type | Description |
|-----------|------|-------------|
| `request` | `ComRaidScheduleRequest` | `notification`, `triggerAtMillis` (must be in the future), `precision`, `fallbackPolicy`. |

**Returns:** `ComRaidScheduleResult` (`id`, requested/applied precision, trigger, optional `fallbackReason`). Throws `NotConfigured`, `InvalidSchedule`, or `ExactAlarmUnavailable`.

#### `startTimer(notification: ComRaidNotification, durationMillis: Long, precision: SchedulePrecision = INEXACT, fallbackPolicy: FallbackPolicy = FAIL): ComRaidScheduleResult`

Starts a countdown timer that fires a Local notification when complete. Persists timer state
(`RUNNING`) so pause/resume/list and reboot restore work like iOS.

| Parameter | Type | Description |
|-----------|------|-------------|
| `notification` | `ComRaidNotification` | Local tray content (`id`, `title`, `body`, …). |
| `durationMillis` | `Long` | Must be positive. |
| `precision` | `SchedulePrecision` | `INEXACT` (default) or `EXACT`. |
| `fallbackPolicy` | `FallbackPolicy` | `FAIL` (default) or `USE_INEXACT` when exact is unavailable. |

**Returns:** `ComRaidScheduleResult`. Throws `InvalidSchedule` if duration ≤ 0.

#### `pauseTimer(id: String): ComRaidTimer`

Pauses a running timer, clears its pending system alarm, and stores remaining duration.

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | `String` | Timer / notification id. |

**Returns:** `ComRaidTimer` (`PAUSED`). Throws `TimerNotFound` / `TimerNotPaused`.

#### `resumeTimer(id: String): ComRaidTimer`

Resumes a paused timer from remaining duration (re-arms the alarm).

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | `String` | Timer / notification id. |

**Returns:** `ComRaidTimer` (`RUNNING`). Throws `TimerNotFound` / `TimerNotPaused`.

#### `cancelTimer(id: String)`

Cancels a timer and removes its pending schedule.

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | `String` | Timer / notification id. |

**Returns:** `Unit`.

#### `timers(): List<ComRaidTimer>`

Lists persisted timers (`RUNNING`, `PAUSED`, `CANCELLED`, `COMPLETED`).

**Returns:** `List<ComRaidTimer>`. Throws `NotConfigured` if timer store is unavailable.

#### `cancel(id: String)`

Cancel a pending local schedule by notification id and remove it from the durable store.
If a matching timer exists, marks it `CANCELLED` (same as iOS `cancel`).

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | `String` | `ComRaidNotification.id` / schedule id. |

**Returns:** `Unit`.

#### `pendingSchedules(): List<ComRaidScheduleRequest>`

List durable pending local schedules.

**Returns:** `List<ComRaidScheduleRequest>`. Throws `NotConfigured` if schedule store is unavailable.

```kotlin
val result = ComRaid.startTimer(
  notification = ComRaidNotification(
    id = "focus-1",
    title = "Focus complete",
    body = "Your timer has finished.",
  ),
  durationMillis = 25 * 60 * 1000L,
)
ComRaid.pauseTimer("focus-1")
ComRaid.resumeTimer("focus-1")
val allTimers = ComRaid.timers()
ComRaid.cancelTimer("focus-1")

ComRaid.schedule(
  ComRaidScheduleRequest(
    notification = ComRaidNotification(id = "promo-1", title = "Reminder", body = "Tonight"),
    triggerAtMillis = System.currentTimeMillis() + 3_600_000L,
  ),
)
ComRaid.cancel("promo-1")
val pending = ComRaid.pendingSchedules()
```

## Deep links and rich fields

API `content.push.deep_link` arrives in FCM data as `cr_deep_link`. On notification open, read it from the payload map passed through `EXTRA_DATA_JSON` (tap / action handlers) or from `RemoteMessage.data` when you own presentation.

- When FCM includes a system `notification` block, Android applies sound / tag / notification count from the provider; the host still reads `cr_deep_link` on open for in-app navigation.
- When the SDK builds the tray notification (data-first payloads or action buttons), the content intent uses `cr_deep_link` when it is a valid URI and launches it after the opened ACK. Host apps still own intent-filter / routing setup.

When the SDK draws the notification (action buttons, or the app is in the foreground) it applies:

| Field | Android behaviour |
|-------|-------------------|
| `image_url` | Downloaded (≤ 5 MB, 10 s timeout) and shown as an expanded picture and large icon. |
| `body` | Expandable long text when there is no image. |
| `actions[].icon` | Name of a drawable in your app (`res/drawable`); unknown names give a text-only button. |
| `thread_id` | Notification tag: a newer push in the same thread replaces the older one. |
| `badge` | Notification count (`setNumber`). |
| `sound` / category `android_sound` | Name of a sound in your app's `res/raw` (`chime` or `chime.wav`). On Android 8+ the channel's sound plays, so set sounds on the category; Android fixes a channel's sound when the channel is first created. |
| `priority` | Android 7 and older only; on 8+ the category's importance decides. |
| `ComRaidConfiguration.accentColor` | Tint for the small icon and action buttons. |

If a push names a category channel this device hasn't created yet, the SDK syncs categories once more and otherwise posts on the default channel, so the notification is never dropped. `subtitle` is iOS-only and ignored on Android.

## Models

| Type | Role |
|------|------|
| `ComRaidConfiguration` | App ID, API base URL, default channel, small icon, accent colour. |
| `RemoteMessageEvent` | Receive / open event: `messageId`, `deliveryId`, `attributionToken`, `title`, `body`, sender-only `data`, `deepLink`, `actions`, `actionId`, plus presentation fields `imageUrl`, `subtitle`, `sound`, `badge`, `threadId`, `androidChannelId`, and always-present `priority` (`"high"` \| `"normal"`). `subtitle` is exposed for parity and never drawn on Android trays. |
| `RemoteMessageAction` | Action button (`id`, `text`, optional `icon` drawable name). |
| `ComRaidNotification` | Local schedule content (`id`, `title`, `body`, optional `channelId` / `data`). |
| `ComRaidScheduleRequest` / `ComRaidScheduleResult` | Local schedule input / outcome. |
| `SchedulePrecision` / `FallbackPolicy` | Exact vs inexact; fail vs fall back. |
| `ComRaidCapabilities` | `notificationsEnabled`, `exactAlarmsAvailable`. |
| `DeviceProperties` | Subscription Properties for register / token refresh. |

## Errors

`ComRaidException` (sealed, extends `IllegalStateException`):

| Type | When |
|------|------|
| `NotConfigured` | Public API called before `configure` (or schedule/timer store unavailable). |
| `NotReady` | No Subscription credential, or Attributes without Login. |
| `InvalidSchedule` | Bad trigger / duration. |
| `ExactAlarmUnavailable` | Exact precision requested with `FallbackPolicy.FAIL` and no exact-alarm access. |
| `TimerNotFound` | Unknown timer id. |
| `TimerNotPaused` | Pause/resume state mismatch. |
| `ApiError` | Mobile HTTP / FCM token failure. |

## Notes

- Wire field is `external_user_id` (never `external_id`).
- Logout keeps the stored credential so Contact-level opt-in/out still works while Inactive.
- Package namespace is `com.comraid.android`; never `com.comraide`.
