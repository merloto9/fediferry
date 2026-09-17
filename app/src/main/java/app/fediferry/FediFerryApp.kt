package app.fediferry

import android.app.Application
import app.fediferry.di.ServiceLocator
import app.fediferry.share.ShortcutPublisher
import app.fediferry.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FediFerryApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        ShortcutPublisher.publish(this)

        scope.launch {
            val repo = ServiceLocator.items(this@FediFerryApp)
            repo.seedIfEmpty()
            // Anything left POSTING is a survivor of a killed process, not a
            // running send; put it back in the queue.
            repo.recoverStalePosting()
            val days = ServiceLocator.settings(this@FediFerryApp).current().purgePostedAfterDays
            repo.purgePosted(days)
        }
    }
}
