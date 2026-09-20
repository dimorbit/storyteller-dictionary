package expo.modules.readium

import android.content.Intent
import android.os.Bundle
import com.facebook.react.HeadlessJsTaskService
import com.facebook.react.bridge.Arguments
import com.facebook.react.jstasks.HeadlessJsTaskConfig

class HeadlessJsTaskRunnerService : HeadlessJsTaskService() {
    override fun getTaskConfig(intent: Intent?): HeadlessJsTaskConfig? {
        val taskKey = intent?.getStringExtra(EXTRA_TASK_KEY) ?: return null
        val data = intent.getBundleExtra(EXTRA_TASK_DATA) ?: Bundle()
        return HeadlessJsTaskConfig(
            taskKey,
            Arguments.fromBundle(data),
            TASK_TIMEOUT_MS,
            true,
        )
    }

    companion object {
        const val EXTRA_TASK_KEY = "taskKey"
        const val EXTRA_TASK_DATA = "taskData"
        private const val TASK_TIMEOUT_MS = 30_000L
    }
}
