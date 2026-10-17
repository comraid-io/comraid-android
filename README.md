# Comraid Android SDK

Native Android foundation for local notifications and timers. Remote push and React Native integration are intentionally outside this first slice.

```kotlin
Comraid.configure(
  applicationContext,
  ComraidConfiguration(smallIconResourceId = R.drawable.ic_notification),
)

val result = Comraid.startTimer(
  notification = ComraidNotification(
    id = "focus-1",
    title = "Focus complete",
    body = "Your timer has finished.",
  ),
  durationMillis = 25 * 60 * 1000L,
)
```

The host app requests `POST_NOTIFICATIONS` at an appropriate UI moment. Exact alarms are opt-in: the host must declare the applicable exact-alarm permission, satisfy store policy, and obtain system access. Comraid defaults to inexact alarms and never adds an exact-alarm permission to the library manifest.

Current limitation: boot restoration needs the host app to call `Comraid.configure` from `Application.onCreate` before the boot receiver can restore schedules. A future initializer component can remove that integration requirement.
