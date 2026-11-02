package ru.updatehub.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.updatehub.app.data.install.clearDownloadedApks
import ru.updatehub.app.notifications.Notifications
import ru.updatehub.app.notifications.UpdateScheduler

class UpdateHubApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Install first: a failure while the container is created must still
        // leave a readable report for the feedback screen.
        ru.updatehub.app.data.DiagnosticsLog.installCrashHandler(this)
        container = AppContainer(this)
        Notifications.createChannel(this)

        appScope.launch {
            clearDownloadedApks(this@UpdateHubApp)
            runCatching {
                val settings = container.settingsRepository.settings.first()
                if (settings.autoCheck) {
                    UpdateScheduler.schedule(this@UpdateHubApp, settings.intervalHours)
                } else {
                    UpdateScheduler.cancel(this@UpdateHubApp)
                }
            }
        }
    }
}
