package ru.updatehub.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A manufacturer store together with whether its app is installed on this
 * phone. The settings screen shows the full catalog so a supported store is
 * discoverable even when it is not preinstalled.
 */
data class ManualStoreAvailability(
    val store: ManualStore,
    val installed: Boolean
)

object StoreLauncher {

    /**
     * Tries to open the update in the native store app first, then falls back
     * to the store's web page in a browser. Returns false when nothing can
     * handle the request.
     */
    fun open(context: Context, packageName: String, source: UpdateSource): Boolean {
        val packageManager = context.packageManager

        for (uri in source.launchUris(packageName)) {
            for (storePackage in source.storePackages) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setPackage(storePackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    if (runCatching { context.startActivity(intent) }.isSuccess) return true
                }
            }

            val generic = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (generic.resolveActivity(packageManager) != null) {
                if (runCatching { context.startActivity(generic) }.isSuccess) return true
            }
        }
        return false
    }

    /**
     * Opens the store's own update-check section instead of a single app page.
     *
     * Android does not allow one app to click buttons inside another app, so
     * this lands on the right screen and the store itself starts checking for
     * updates. When a deep link is not supported the store app is opened.
     */
    fun openUpdateCenter(context: Context, source: UpdateSource): Boolean {
        val packageManager = context.packageManager

        for (uri in source.updateCenterUris()) {
            for (storePackage in source.storePackages) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setPackage(storePackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    if (runCatching { context.startActivity(intent) }.isSuccess) return true
                }
            }

            val generic = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (generic.resolveActivity(packageManager) != null) {
                if (runCatching { context.startActivity(generic) }.isSuccess) return true
            }
        }

        return openStoreApp(context, source)
    }

    /**
     * The complete catalog of manufacturer stores. Installed stores are put
     * first so the useful entries stay at the top. The settings screen uses
     * the [ManualStoreAvailability.installed] flag to choose between opening
     * the store app and opening the manufacturer's website.
     */
    fun manualStores(context: Context): List<ManualStoreAvailability> {
        val packageManager = context.packageManager
        return ManualStore.all
            .map { store ->
                ManualStoreAvailability(
                    store = store,
                    installed = store.storePackages.any { packageName ->
                        runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
                    }
                )
            }
            .sortedWith(
                compareByDescending<ManualStoreAvailability> { it.installed }
                    .thenBy { it.store.title.lowercase() }
            )
    }

    /**
     * Opens the closest available update screen in a manufacturer store.
     *
     * Huawei AppGallery exposes [ManualStore.updateAction] for third-party
     * update checks. Other stores are opened normally; their update section is
     * usually the first screen or one tap away. Android does not let UniStore
     * press buttons inside another app.
     */
    fun openUpdateCenter(context: Context, store: ManualStore): Boolean {
        val packageManager = context.packageManager

        store.updateAction?.let { action ->
            for (storePackage in store.storePackages) {
                val intent = Intent(action)
                    .setPackage(storePackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    if (runCatching { context.startActivity(intent) }.isSuccess) return true
                }
            }
        }

        for (uri in store.launchUris) {
            for (storePackage in store.storePackages) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .setPackage(storePackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    if (runCatching { context.startActivity(intent) }.isSuccess) return true
                }
            }
        }

        for (storePackage in store.storePackages) {
            val intent = packageManager.getLaunchIntentForPackage(storePackage) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }

        val web = Intent(Intent.ACTION_VIEW, Uri.parse(store.webUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (web.resolveActivity(packageManager) != null) {
            return runCatching { context.startActivity(web) }.isSuccess
        }
        return false
    }

    private fun openStoreApp(context: Context, source: UpdateSource): Boolean {
        val packageManager = context.packageManager
        for (storePackage in source.storePackages) {
            val intent = packageManager.getLaunchIntentForPackage(storePackage) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }

        val web = Intent(Intent.ACTION_VIEW, Uri.parse(source.webUrl("")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (web.resolveActivity(packageManager) != null) {
            return runCatching { context.startActivity(web) }.isSuccess
        }
        return false
    }
}
