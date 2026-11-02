package expo.modules.readium

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.facebook.react.HeadlessJsTaskService

object HeadlessJsBridge {
    fun run(context: Context, taskKey: String, data: Bundle) {
        val intent =
            Intent(context, HeadlessJsTaskRunnerService::class.java).apply {
                putExtra(HeadlessJsTaskRunnerService.EXTRA_TASK_KEY, taskKey)
                putExtra(HeadlessJsTaskRunnerService.EXTRA_TASK_DATA, data)
            }
        context.startService(intent)
        HeadlessJsTaskService.acquireWakeLockNow(context)
    }
}
