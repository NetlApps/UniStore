package ru.updatehub.app.data

import org.json.JSONArray
import org.json.JSONObject

object ResultSerializer {

    fun serialize(results: Collection<AppUpdateResult>): String {
        val array = JSONArray()
        results.sortedBy { it.packageName }.forEach { result ->
            array.put(
                JSONObject().apply {
                    put("packageName", result.packageName)
                    put("installedVersion", result.installedVersion)
                    put("status", result.status.name)
                    put("checkedAt", result.checkedAt)
                    result.message?.let { put("message", it) }
                    result.best?.let { put("best", it.toJson()) }
                    put(
                        "versions",
                        JSONArray().apply {
                            result.versions.forEach { put(it.toJson()) }
                        }
                    )
                    if (result.sourceChecks.isNotEmpty()) {
                        put(
                            "sourceChecks",
                            JSONArray().apply {
                                result.sourceChecks.forEach { put(it.toJson()) }
                            }
                        )
                    }
                }
            )
        }
        return array.toString()
    }

    fun deserialize(json: String?): Map<String, AppUpdateResult> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(json)
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val packageName = item.optString("packageName")
                    if (packageName.isBlank()) continue
                    val versions = mutableListOf<StoreVersion>()
                    item.optJSONArray("versions")?.let { versionArray ->
                        for (j in 0 until versionArray.length()) {
                            versionArray.optJSONObject(j)?.toStoreVersion()?.let(versions::add)
                        }
                    }
                    val sourceChecks = mutableListOf<SourceCheck>()
                    item.optJSONArray("sourceChecks")?.let { checkArray ->
                        for (j in 0 until checkArray.length()) {
                            checkArray.optJSONObject(j)?.toSourceCheck()?.let(sourceChecks::add)
                        }
                    }
                    put(
                        packageName,
                        AppUpdateResult(
                            packageName = packageName,
                            installedVersion = item.optString("installedVersion"),
                            status = runCatching {
                                UpdateStatus.valueOf(item.optString("status"))
                            }.getOrDefault(UpdateStatus.UNKNOWN),
                            best = item.optJSONObject("best")?.toStoreVersion(),
                            versions = versions,
                            checkedAt = item.optLong("checkedAt"),
                            message = item.optString("message")
                                .takeIf { it.isNotBlank() && it != "null" },
                            sourceChecks = sourceChecks
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun SourceCheck.toJson(): JSONObject = JSONObject().apply {
        put("source", source.key)
        put("status", status.name)
        put("versionName", versionName ?: JSONObject.NULL)
        put("message", message ?: JSONObject.NULL)
        put("signatureVerified", signatureVerified ?: JSONObject.NULL)
    }

    private fun JSONObject.toSourceCheck(): SourceCheck? {
        val source = UpdateSource.fromKey(optString("source")) ?: return null
        val status = runCatching {
            SourceCheckStatus.valueOf(optString("status"))
        }.getOrDefault(SourceCheckStatus.NOT_CHECKED)
        return SourceCheck(
            source = source,
            status = status,
            versionName = optString("versionName").takeIf { it.isNotBlank() && it != "null" },
            message = optString("message").takeIf { it.isNotBlank() && it != "null" },
            signatureVerified = when {
                isNull("signatureVerified") -> null
                has("signatureVerified") -> optBoolean("signatureVerified")
                else -> null
            }
        )
    }

    private fun StoreVersion.toJson(): JSONObject = JSONObject().apply {
        put("source", source.key)
        put("versionName", versionName)
        put("versionCode", versionCode ?: JSONObject.NULL)
        put("storeUrl", storeUrl)
        put("title", title ?: JSONObject.NULL)
        put("signature", signature ?: JSONObject.NULL)
        put("signatureSha1", signatureSha1 ?: JSONObject.NULL)
    }

    private fun JSONObject.toStoreVersion(): StoreVersion? {
        val source = UpdateSource.fromKey(optString("source")) ?: return null
        val versionName = optString("versionName")
        if (versionName.isBlank()) return null
        return StoreVersion(
            source = source,
            versionName = versionName,
            versionCode = if (isNull("versionCode")) null else optLong("versionCode"),
            storeUrl = optString("storeUrl").ifBlank { source.webUrl("") },
            title = optString("title").takeIf { it.isNotBlank() && it != "null" },
            signature = optString("signature").takeIf { it.isNotBlank() && it != "null" },
            signatureSha1 = optString("signatureSha1")
                .takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
