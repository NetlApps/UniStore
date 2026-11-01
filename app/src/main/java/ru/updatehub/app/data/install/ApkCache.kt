package ru.updatehub.app.data.install

import android.content.Context
import java.io.File

/** Directory where [ApkDownloader] stores downloads before installation. */
internal fun updatesDirectory(context: Context): File =
    File(context.cacheDir, "updates")

/**
 * Removes everything downloaded for one package: a plain APK, a partial
 * `.download` file, or the directory with the base and split APKs of a bundle.
 */
internal fun deleteDownloadedPackage(context: Context, packageName: String) {
    deleteDownloadedPackage(updatesDirectory(context), packageName)
}

internal fun deleteDownloadedPackage(directory: File, packageName: String) {
    val candidates = arrayOf(
        "$packageName.apk",
        "$packageName.download",
        "$packageName.xapk"
    )
    directory.listFiles()?.forEach { entry ->
        when {
            entry.isFile && entry.name in candidates -> entry.delete()
            entry.isDirectory && entry.name == packageName -> entry.deleteRecursively()
        }
    }
}

/**
 * Removes every cached installation package. Called when the process starts:
 * a download only lives while its install flow is in memory, so anything left
 * on disk belongs to a finished, cancelled or killed installation.
 */
internal fun clearDownloadedApks(context: Context) {
    clearDownloadedApks(updatesDirectory(context))
}

internal fun clearDownloadedApks(directory: File) {
    directory.listFiles()?.forEach { entry ->
        if (entry.isFile) {
            entry.delete()
        } else {
            entry.deleteRecursively()
        }
    }
}
