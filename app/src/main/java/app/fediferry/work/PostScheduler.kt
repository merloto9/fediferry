package app.fediferry.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues [PostWorker] runs, one unique chain per item.
 *
 * The unique name is the item id, so re-queueing the same item replaces rather
 * than duplicates its work — and so the undo action has something to cancel by
 * name without needing to track a work id.
 */
object PostScheduler {

    private const val WORK_PREFIX = "post-"

    fun workName(itemId: String) = WORK_PREFIX + itemId

    /**
     * @param delayMillis the undo window, or a scheduled send time. The item is
     *   already persisted as QUEUED before this is called.
     */
    fun enqueue(context: Context, itemId: String, delayMillis: Long = 0) {
        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setInputData(Data.Builder().putString(PostWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply { if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) }
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(itemId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, itemId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(itemId))
    }

    const val TAG = "post"
}
