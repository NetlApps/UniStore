package ru.updatehub.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.updateHubDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "updatehub_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.updateHubDataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            val storedPalette = preferences[Keys.palette]
            val palette = when {
                // Older builds stored "default" for what is now a fixed
                // standard scheme. Treat it as the new adaptive default once,
                // until the user explicitly picks a palette.
                preferences[Keys.paletteInitialized] == true ->
                    Palette.fromKey(storedPalette)

                storedPalette == null || storedPalette == Palette.DEFAULT.key ->
                    Palette.ADAPTIVE

                else -> Palette.fromKey(storedPalette)
            }
            AppSettings(
                trackedPackages = preferences[Keys.trackedPackages]
                    ?.minus(UNISTORE_PACKAGE)
                    .orEmpty(),
                enabledSources = preferences[Keys.enabledSources]?.toSet()
                    ?: UpdateSource.defaultEnabledKeys,
                themeMode = ThemeMode.fromKey(preferences[Keys.themeMode]),
                palette = palette,
                weakDevice = preferences[Keys.weakDevice]
                    ?: (preferences[Keys.legacyPerformanceMode] == "economy"),
                seasonalTheme = SeasonalTheme.fromKey(preferences[Keys.seasonalTheme]),
                onboarded = preferences[Keys.onboarded] ?: false,
                autoCheck = preferences[Keys.autoCheck] ?: false,
                intervalHours = (preferences[Keys.intervalHours] ?: 12).coerceIn(6, 48),
                lastCheckAt = preferences[Keys.lastCheckAt] ?: 0L,
                resultsJson = preferences[Keys.resultsJson].orEmpty(),
                checkSystemApps = preferences[Keys.checkSystemApps] ?: false,
                incompatibleUpdates = preferences[Keys.incompatibleUpdates].orEmpty(),
                storeFilterKey = preferences[Keys.storeFilterKey].orEmpty()
            )
        }

    suspend fun setTrackedPackages(packages: Set<String>) {
        context.updateHubDataStore.edit { it[Keys.trackedPackages] = packages }
    }

    /**
     * Removes hidden system packages from the latest stored set instead of
     * writing a set calculated from an older snapshot. That keeps a package
     * the user added while a scan was running from being lost.
     */
    suspend fun pruneTrackedPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        context.updateHubDataStore.edit { preferences ->
            if (preferences[Keys.checkSystemApps] == true) return@edit
            val current = preferences[Keys.trackedPackages].orEmpty()
            if (current.none { it in packages }) return@edit
            preferences[Keys.trackedPackages] = current - packages
        }
    }

    /** Same read-merge-write cleanup for the cached check results. */
    suspend fun pruneResultPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        context.updateHubDataStore.edit { preferences ->
            if (preferences[Keys.checkSystemApps] == true) return@edit
            val current = ResultSerializer.deserialize(
                preferences[Keys.resultsJson].orEmpty()
            )
            if (current.keys.none { it in packages }) return@edit
            preferences[Keys.resultsJson] = ResultSerializer.serialize(
                current.filterKeys { it !in packages }.values
            )
        }
    }

    /**
     * Drops packages that are no longer installed, together with their cached
     * results. Unlike the system-app cleanup, this runs even when the user
     * enabled system app checks: an app removed from the phone must disappear
     * from the update list automatically.
     */
    suspend fun pruneMissingPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        context.updateHubDataStore.edit { preferences ->
            val tracked = preferences[Keys.trackedPackages].orEmpty()
            val removed = tracked intersect packages
            if (removed.isNotEmpty()) {
                preferences[Keys.trackedPackages] = tracked - removed
            }

            // Results are cleaned even when the package was not tracked: a
            // stale cached row for UniStore itself or an uninstalled app must
            // not survive in the serialized history either.
            val results = ResultSerializer.deserialize(
                preferences[Keys.resultsJson].orEmpty()
            )
            if (results.keys.any { it in packages }) {
                preferences[Keys.resultsJson] = ResultSerializer.serialize(
                    results.filterKeys { it !in packages }.values
                )
            }
        }
    }

    suspend fun setEnabledSources(sources: Set<String>) {
        context.updateHubDataStore.edit { it[Keys.enabledSources] = sources }
    }

    /**
     * Applies device-specific defaults exactly once. On upgrade the existing
     * non-empty selection wins and is left untouched.
     */
    suspend fun initializeDefaultSources(defaults: Set<String>) {
        context.updateHubDataStore.edit { preferences ->
            if (preferences[Keys.sourcesInitialized] == true) return@edit
            val existing = preferences[Keys.enabledSources]
            if (existing.isNullOrEmpty() && defaults.isNotEmpty()) {
                preferences[Keys.enabledSources] = defaults
            }
            preferences[Keys.sourcesInitialized] = true
        }
    }

    suspend fun setTheme(mode: ThemeMode, palette: Palette) {
        context.updateHubDataStore.edit {
            it[Keys.themeMode] = mode.key
            it[Keys.palette] = palette.key
            it[Keys.paletteInitialized] = true
        }
    }

    suspend fun setSeasonalTheme(theme: SeasonalTheme) {
        context.updateHubDataStore.edit { it[Keys.seasonalTheme] = theme.key }
    }

    suspend fun setWeakDevice(enabled: Boolean) {
        context.updateHubDataStore.edit { it[Keys.weakDevice] = enabled }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.updateHubDataStore.edit { it[Keys.onboarded] = value }
    }

    suspend fun setAutoCheck(value: Boolean) {
        context.updateHubDataStore.edit { it[Keys.autoCheck] = value }
    }

    suspend fun setIntervalHours(hours: Int) {
        context.updateHubDataStore.edit { it[Keys.intervalHours] = hours.coerceIn(6, 48) }
    }

    suspend fun setCheckSystemApps(value: Boolean) {
        context.updateHubDataStore.edit { it[Keys.checkSystemApps] = value }
    }

    suspend fun setStoreFilterKey(key: String) {
        context.updateHubDataStore.edit { it[Keys.storeFilterKey] = key }
    }

    suspend fun setLastCheckAt(timestamp: Long) {
        context.updateHubDataStore.edit { it[Keys.lastCheckAt] = timestamp }
    }

    suspend fun setResultsJson(json: String) {
        context.updateHubDataStore.edit { it[Keys.resultsJson] = json }
    }

    suspend fun saveCheckResults(timestamp: Long, json: String) {
        context.updateHubDataStore.edit {
            it[Keys.lastCheckAt] = timestamp
            it[Keys.resultsJson] = json
        }
    }

    /**
     * Remembers that Android rejected this exact store version. The next update
     * checks can hide it instead of offering an install that is guaranteed to
     * fail. A newer version gets a new key and is checked again.
     */
    suspend fun markUpdateIncompatible(
        packageName: String,
        versionName: String,
        source: UpdateSource? = null
    ) {
        if (packageName.isBlank() || versionName.isBlank()) return
        val key = incompatibleUpdateKey(packageName, versionName, source)
        context.updateHubDataStore.edit { preferences ->
            val current = preferences[Keys.incompatibleUpdates].orEmpty()
            if (key !in current) {
                preferences[Keys.incompatibleUpdates] = current + key
            }
        }
    }

    suspend fun clearIncompatibleUpdates(packages: Set<String>) {
        if (packages.isEmpty()) return
        context.updateHubDataStore.edit { preferences ->
            val current = preferences[Keys.incompatibleUpdates].orEmpty()
            val kept = current.filterTo(LinkedHashSet()) { key ->
                key.substringBefore('|') !in packages
            }
            if (kept.size != current.size) {
                preferences[Keys.incompatibleUpdates] = kept
            }
        }
    }

    private object Keys {
        val trackedPackages = stringSetPreferencesKey("tracked_packages")
        val enabledSources = stringSetPreferencesKey("enabled_sources")
        val themeMode = stringPreferencesKey("theme_mode")
        val palette = stringPreferencesKey("palette")
        val paletteInitialized = booleanPreferencesKey("palette_initialized")
        val weakDevice = booleanPreferencesKey("weak_device")
        val legacyPerformanceMode = stringPreferencesKey("performance_mode")
        val seasonalTheme = stringPreferencesKey("seasonal_theme")
        val onboarded = booleanPreferencesKey("onboarded")
        val autoCheck = booleanPreferencesKey("auto_check")
        val intervalHours = intPreferencesKey("interval_hours")
        val lastCheckAt = longPreferencesKey("last_check_at")
        val resultsJson = stringPreferencesKey("results_json")
        val checkSystemApps = booleanPreferencesKey("check_system_apps")
        val sourcesInitialized = booleanPreferencesKey("sources_initialized")
        val incompatibleUpdates = stringSetPreferencesKey("incompatible_updates")
        val storeFilterKey = stringPreferencesKey("store_filter_key")
    }
}
