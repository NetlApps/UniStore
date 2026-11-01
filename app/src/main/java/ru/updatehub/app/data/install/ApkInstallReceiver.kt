package ru.updatehub.app.data.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import ru.updatehub.app.UpdateHubApp
import ru.updatehub.app.notifications.Notifications

/** Result of a PackageInstaller session, consumed by the running UI. */
data class ApkInstallEvent(
    val packageName: String,
    val success: Boolean,
    val message: String? = null,
    val incompatible: Boolean = false,
    val versionName: String? = null
)

object ApkInstallEvents {
    val events = MutableSharedFlow<ApkInstallEvent>(extraBufferCapacity = 8)
}

/**
 * Deletes downloaded files after installation and forwards the result of a
 * split-APK PackageInstaller session to the UI. For a normal APK the same
 * receiver still reacts to PACKAGE_ADDED/PACKAGE_REPLACED broadcasts.
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_STATUS -> handleInstallStatus(context, intent)
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.data?.schemeSpecificPart ?: return
                deleteDownloadedPackage(context, packageName)
            }
        }
    }

    private fun handleInstallStatus(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> openConfirmation(context, intent, packageName)
            PackageInstaller.STATUS_SUCCESS -> {
                deleteDownloadedPackage(context, packageName)
                ApkInstallEvents.events.tryEmit(
                    ApkInstallEvent(packageName = packageName, success = true)
                )
            }

            else -> {
                deleteDownloadedPackage(context, packageName)
                val message = installFailureMessage(status)
                ApkInstallEvents.events.tryEmit(
                    ApkInstallEvent(
                        packageName = packageName,
                        success = false,
                        message = message,
                        incompatible =
                            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                        versionName = versionName.takeIf { it.isNotBlank() }
                    )
                )
                if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE &&
                    versionName.isNotBlank()
                ) {
                    rememberIncompatible(context, packageName, versionName)
                }
            }
        }
    }

    private fun openConfirmation(context: Context, intent: Intent, packageName: String) {
        val confirmation = statusIntent(intent) ?: return
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { context.startActivity(confirmation) }.isSuccess
        if (!started) {
            runCatching {
                Notifications.showInstallConfirmation(
                    context = context,
                    confirmation = confirmation,
                    label = packageName
                )
            }
        }
    }

    private fun rememberIncompatible(
        context: Context,
        packageName: String,
        versionName: String
    ) {
        val app = context.applicationContext as? UpdateHubApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.settingsRepository.markUpdateIncompatible(
                    packageName,
                    versionName
                )
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun statusIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS = "ru.updatehub.app.action.APK_INSTALL_STATUS"
        const val EXTRA_PACKAGE_NAME = "ru.updatehub.app.extra.PACKAGE_NAME"
        const val EXTRA_VERSION_NAME = "ru.updatehub.app.extra.VERSION_NAME"
    }
}

internal fun installFailureMessage(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Установка заблокирована системой"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт с установленной версией приложения"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Обновление несовместимо с этим телефоном"
    PackageInstaller.STATUS_FAILURE_INVALID -> "Установочный файл повреждён"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно места для установки"
    PackageInstaller.STATUS_FAILURE_TIMEOUT -> "Истекло время установки"
    else -> "Не удалось установить обновление"
}
