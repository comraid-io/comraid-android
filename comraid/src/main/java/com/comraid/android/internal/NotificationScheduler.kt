package com.comraid.android.internal

import com.comraid.android.ComRaidConfiguration
import com.comraid.android.ComRaidScheduleRequest
import com.comraid.android.ComRaidScheduleResult

internal interface NotificationScheduler {
  fun schedule(request: ComRaidScheduleRequest, configuration: ComRaidConfiguration): ComRaidScheduleResult

  fun cancel(id: String)
}
