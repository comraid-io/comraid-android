package com.comraid.android.internal

import android.app.ActivityManager
import android.content.Context
import android.os.Process

internal object AppForeground {
  fun isInForeground(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    val pid = Process.myPid()
    return am.runningAppProcesses.orEmpty().any { process ->
      process.pid == pid && process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
  }
}
