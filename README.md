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

Stores App ID / API base URL, ensures default (and category) notification channels, and prepares the runtime. Call once from `Application.onCreate` before permission, register, schedule, or ACKs.

| Parameter | Type | Description |
|-----------|------|-------------|
| `context` | `Context` | Any context; the SDK keeps `applicationContext`. |
| `configuration` | `ComRaidConfiguration` | `appId` (`cr_app_…`), optional `apiBaseUrl`, channel defaults, `smallIconResourceId`. |

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
| `attributes` | `Map<String, Any?>` | Non-empty Attribute map (e.g. `plan` → `premium`). |

**Returns:** `Unit`. Throws `NotReady` if not logged in; requires non-empty map.

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

Enqueue + flush a delivered ACK.

| Parameter | Type | Description |
|-----------|------|-------------|
| `deliveryId` | `String` | Delivery id from the push payload. |
| `attributionToken` | `String` | Attribution token from the push payload. |

**Returns:** `false` when either id is blank; otherwise `true` after enqueue/flush.

#### `acknowledgeOpened(deliveryId: String, attributionToken: String, actionId: String? = null): Boolean`

Enqueue + flush an opened ACK (optional action button id).

| Parameter | Type | Description |
|-----------|------|-------------|
| `deliveryId` | `String` | Delivery id from the push payload. |
| `attributionToken` | `String` | Attribution token from the push payload. |
| `actionId` | `String?` | Optional action button id. |

**Returns:** `false` when either id is blank; otherwise `true`.

#### `flushAcks()`

Flush any durable ACK backlog (safe to call from `Application.onCreate`).

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

Convenience wrapper: schedules at `now + durationMillis`.

| Parameter | Type | Description |
|-----------|------|-------------|
| `notification` | `ComRaidNotification` | Local tray content (`id`, `title`, `body`, …). |
| `durationMillis` | `Long` | Must be positive. |
| `precision` | `SchedulePrecision` | `INEXACT` (default) or `EXACT`. |
| `fallbackPolicy` | `FallbackPolicy` | `FAIL` (default) or `USE_INEXACT` when exact is unavailable. |

**Returns:** `ComRaidScheduleResult`. Throws `InvalidSchedule` if duration ≤ 0.

#### `cancel(id: String)`

Cancel a pending local schedule by notification id and remove it from the durable store.

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

Other rich fields (`sound`, `badge`, `thread_id`, `subtitle`) are primarily provider/OS presentation; `subtitle` is iOS-oriented and ignored on Android system trays.

## Models

| Type | Role |
|------|------|
| `ComRaidConfiguration` | App ID, API base URL, default channel, small icon. |
| `ComRaidNotification` | Local schedule content (`id`, `title`, `body`, optional `channelId` / `data`). |
| `ComRaidScheduleRequest` / `ComRaidScheduleResult` | Local schedule input / outcome. |
| `SchedulePrecision` / `FallbackPolicy` | Exact vs inexact; fail vs fall back. |
| `ComRaidCapabilities` | `notificationsEnabled`, `exactAlarmsAvailable`. |
| `DeviceProperties` | Subscription Properties for register / token refresh. |

## Errors

`ComRaidException` (sealed, extends `IllegalStateException`):

| Type | When |
|------|------|
| `NotConfigured` | Public API called before `configure` (or schedule store unavailable). |
| `NotReady` | No Subscription credential, or Attributes without Login. |
| `InvalidSchedule` | Bad trigger / duration. |
| `ExactAlarmUnavailable` | Exact precision requested with `FallbackPolicy.FAIL` and no exact-alarm access. |
| `ApiError` | Mobile HTTP / FCM token failure. |

## Notes

- Wire field is `external_user_id` (never `external_id`).
- Logout keeps the stored credential so Contact-level opt-in/out still works while Inactive.
- Package namespace is `com.comraid.android`; never `com.comraide`.
