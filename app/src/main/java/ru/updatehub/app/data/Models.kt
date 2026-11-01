package ru.updatehub.app.data

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val installedAt: Long,
    /** SHA-256 of the signing certificate, lowercase hex. */
    val signature: String? = null,
    /** SHA-1 of the signing certificate, lowercase hex without colons. */
    val signatureSha1: String? = null
)

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    BLACK("black");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class Palette(val key: String, val title: String) {
    ADAPTIVE("adaptive", "Адаптивная"),
    DEFAULT("default", "Стандарт"),
    OCEAN("ocean", "Океан"),
    FOREST("forest", "Лес"),
    SUNSET("sunset", "Закат"),
    LAVENDER("lavender", "Лаванда"),
    ROSE("rose", "Роза");

    companion object {
        fun fromKey(key: String?): Palette = entries.firstOrNull { it.key == key } ?: ADAPTIVE
    }
}

/** Visual effect drawn behind the app content. */
enum class SeasonalEffect(val title: String) {
    NONE("Обычный фон"),
    WINTER("Зимний снег"),
    AUTUMN("Осенние листья"),
    HALLOWEEN("Хэллоуин"),
    NEW_YEAR("Новый год")
}

/**
 * Seasonal decoration selected by the user. [AUTO] follows the current date:
 * winter snow, autumn leaves and holiday effects; spring and summer stay plain.
 */
enum class SeasonalTheme(val key: String, val title: String) {
    AUTO("auto", "По дате"),
    OFF("off", "Выключено"),
    WINTER("winter", "Зима"),
    AUTUMN("autumn", "Осень"),
    HALLOWEEN("halloween", "Хэллоуин"),
    NEW_YEAR("new_year", "Новый год");

    fun resolve(date: java.time.LocalDate = java.time.LocalDate.now()): SeasonalEffect =
        when (this) {
            AUTO -> autoFor(date)
            OFF -> SeasonalEffect.NONE
            WINTER -> SeasonalEffect.WINTER
            AUTUMN -> SeasonalEffect.AUTUMN
            HALLOWEEN -> SeasonalEffect.HALLOWEEN
            NEW_YEAR -> SeasonalEffect.NEW_YEAR
        }

    companion object {
        fun fromKey(key: String?): SeasonalTheme =
            entries.firstOrNull { it.key == key } ?: AUTO

        fun autoFor(date: java.time.LocalDate): SeasonalEffect {
            val month = date.monthValue
            val day = date.dayOfMonth
            return when {
                // Halloween: late October and the first days of November.
                (month == 10 && day >= 25) || (month == 11 && day <= 2) ->
                    SeasonalEffect.HALLOWEEN

                // New Year: December 26 through January 5.
                (month == 12 && day >= 26) || (month == 1 && day <= 5) ->
                    SeasonalEffect.NEW_YEAR

                month == 12 || month == 1 || month == 2 -> SeasonalEffect.WINTER
                month == 9 || month == 10 || month == 11 -> SeasonalEffect.AUTUMN
                else -> SeasonalEffect.NONE
            }
        }
    }
}

enum class UpdateSource(
    val key: String,
    val title: String,
    val subtitle: String,
    val brandColor: Long,
    val storePackages: List<String>,
    private val websiteTemplate: String,
    /** Whether the source exposes a direct APK download that can be installed in-app. */
    val supportsDirectDownload: Boolean = false,
    /** Whether the store works on any Android phone instead of one manufacturer. */
    val isUniversal: Boolean = false
) {
    GOOGLE_PLAY(
        key = "google_play",
        title = "Google Play",
        subtitle = "Официальный магазин Google",
        brandColor = 0xFF34A853,
        storePackages = listOf("com.android.vending"),
        websiteTemplate = "https://play.google.com/store/apps/details?id=%s",
        isUniversal = true
    ),
    RUSTORE(
        key = "rustore",
        title = "RuStore",
        subtitle = "Магазин приложений VK",
        brandColor = 0xFF0077FF,
        storePackages = listOf("ru.vk.store"),
        websiteTemplate = "https://www.rustore.ru/catalog/app/%s",
        supportsDirectDownload = true,
        isUniversal = true
    ),
    GETAPPS(
        key = "getapps",
        title = "GetApps",
        subtitle = "Магазин Xiaomi",
        brandColor = 0xFFFF6900,
        storePackages = listOf("com.xiaomi.mipicks", "com.xiaomi.market"),
        websiteTemplate = "https://global.app.mi.com/details?id=%s"
    ),
    GALAXY_STORE(
        key = "galaxy_store",
        title = "Galaxy Store",
        subtitle = "Магазин Samsung",
        brandColor = 0xFF1428A0,
        storePackages = listOf("com.sec.android.app.samsungapps"),
        websiteTemplate = "https://galaxystore.samsung.com/detail/%s"
    ),
    APKPURE(
        key = "apkpure",
        title = "APKPure",
        subtitle = "Каталог APK-файлов",
        brandColor = 0xFF24B34B,
        storePackages = listOf("com.apkpure.aegon"),
        websiteTemplate = "https://apkpure.com/-/%s",
        supportsDirectDownload = true,
        isUniversal = true
    ),
    FDROID(
        key = "fdroid",
        title = "F-Droid",
        subtitle = "Каталог свободного ПО",
        brandColor = 0xFF1976D2,
        storePackages = listOf("org.fdroid.fdroid"),
        websiteTemplate = "https://f-droid.org/packages/%s/",
        supportsDirectDownload = true,
        isUniversal = true
    ),
    APTOIDE(
        key = "aptoide",
        title = "Aptoide",
        subtitle = "Независимый магазин",
        brandColor = 0xFFFE6D00,
        storePackages = listOf("cm.aptoide.pt"),
        websiteTemplate = "https://en.aptoide.com/search?query=%s",
        supportsDirectDownload = true,
        isUniversal = true
    ),
    VIVO_STORE(
        key = "vivo_store",
        title = "V-Appstore",
        subtitle = "Магазин vivo и iQOO",
        brandColor = 0xFF415FFF,
        storePackages = listOf("com.vivo.appstore"),
        websiteTemplate = "https://h5.appstore.vivo.com.cn/"
    ),
    FLYME_STORE(
        key = "flyme_store",
        title = "Flyme Store",
        subtitle = "Магазин Meizu",
        brandColor = 0xFF0095FF,
        storePackages = listOf("com.meizu.mstore", "com.meizu.appcenter"),
        websiteTemplate = "https://app.flyme.cn/apps/public/detail?package_name=%s"
    );

    fun webUrl(packageName: String): String = websiteTemplate.replace("%s", packageName)

    /**
     * Lightweight page used to measure the store connection. It is requested
     * with HEAD, so the probe does not download the full catalog.
     */
    val healthUrl: String
        get() = when (this) {
            GOOGLE_PLAY -> "https://play.google.com/store/apps?hl=ru&gl=RU"
            RUSTORE -> "https://www.rustore.ru/"
            GETAPPS -> "https://global.app.mi.com/"
            GALAXY_STORE -> "https://galaxystore.samsung.com/"
            // The root page answers HEAD with Cloudflare 403 even when the
            // catalogue is reachable. A category page is a real 200.
            APKPURE -> "https://apkpure.com/app"
            FDROID -> "https://f-droid.org/"
            APTOIDE -> "https://en.aptoide.com/"
            VIVO_STORE -> "https://h5.appstore.vivo.com.cn/"
            FLYME_STORE -> "https://app.flyme.cn/"
        }

    /**
     * Threshold for the green "good connection" dot. Google Play and APKPure
     * are served from overseas CDNs, so a healthy response through a VPN can
     * take 1.5–2.5 seconds. Measuring them with the same 1.2 s limit as a
     * local store made every cold start look like a weak link.
     */
    val healthOnlineMaxMs: Long
        get() = when (this) {
            GOOGLE_PLAY -> 3_000L
            APKPURE, GALAXY_STORE -> 2_000L
            else -> STORE_ONLINE_MAX_MS
        }

    fun launchUris(packageName: String): List<String> = when (this) {
        GOOGLE_PLAY -> listOf(
            "market://details?id=$packageName",
            webUrl(packageName)
        )

        // RuStore registers an App Link for https://www.rustore.ru/catalog...
        // Its custom scheme ignores the path and opens the store home page.
        RUSTORE -> listOf(webUrl(packageName))

        GETAPPS -> listOf(
            "mimarket://details?id=$packageName",
            webUrl(packageName)
        )

        GALAXY_STORE -> listOf(
            "samsungapps://ProductDetail/$packageName",
            webUrl(packageName)
        )

        APKPURE -> listOf(webUrl(packageName))

        FDROID -> listOf(webUrl(packageName))

        APTOIDE -> listOf(
            "market://details?id=$packageName",
            webUrl(packageName)
        )

        VIVO_STORE -> listOf(webUrl(packageName))

        FLYME_STORE -> listOf(webUrl(packageName))
    }

    /**
     * Links that open the section where the store checks for app updates.
     * Stores do not let third-party apps press their own buttons, so the best
     * we can do is land exactly on that section and let the store start its
     * normal update flow.
     */
    fun updateCenterUris(): List<String> = when (this) {
        GOOGLE_PLAY -> listOf(
            "market://myapps",
            "https://play.google.com/store/apps"
        )

        // RuStore registers /mine and /autoupdates as App Links.
        RUSTORE -> listOf(
            "rustore://mine",
            "https://www.rustore.ru/mine",
            "https://www.rustore.ru/autoupdates"
        )

        GETAPPS -> listOf(
            "mimarket://update",
            "mimarket://home",
            "https://global.app.mi.com/"
        )

        GALAXY_STORE -> listOf(
            "samsungapps://UpdateList",
            "samsungapps://Update",
            "https://galaxystore.samsung.com/"
        )

        APKPURE -> listOf("https://apkpure.com/")

        FDROID -> listOf("https://f-droid.org/")

        APTOIDE -> listOf(
            "aptoide://updates",
            "https://en.aptoide.com/"
        )

        VIVO_STORE -> listOf("https://h5.appstore.vivo.com.cn/")

        FLYME_STORE -> listOf("https://app.flyme.cn/")
    }

    companion object {
        val defaultEnabledKeys: Set<String> = setOf(
            UpdateSource.GOOGLE_PLAY.key,
            UpdateSource.RUSTORE.key,
            UpdateSource.GETAPPS.key
        )

        fun fromKey(key: String?): UpdateSource? = entries.firstOrNull { it.key == key }

        fun fromKeys(keys: Set<String>): List<UpdateSource> =
            entries.filter { it.key in keys }
    }
}

/**
 * A manufacturer store that has no public version API. UniStore cannot check
 * it automatically, but can still take the user to its update section.
 */
data class ManualStore(
    val key: String,
    val title: String,
    val subtitle: String,
    val brandColor: Long,
    val storePackages: List<String>,
    val webUrl: String,
    val updateAction: String? = null,
    val launchUris: List<String> = emptyList()
) {
    companion object {
        val all: List<ManualStore> = listOf(
            ManualStore(
                key = "huawei_appgallery",
                title = "AppGallery",
                subtitle = "Магазин Huawei",
                brandColor = 0xFFCF0A2C,
                storePackages = listOf("com.huawei.appmarket"),
                webUrl = "https://appgallery.huawei.com/",
                updateAction = "com.huawei.appmarket.intent.action.ThirdUpdateAction",
                launchUris = listOf("appmarket://")
            ),
            ManualStore(
                key = "honor_app_market",
                title = "Honor App Market",
                subtitle = "Магазин Honor",
                brandColor = 0xFF00A3E0,
                storePackages = listOf("com.hihonor.appmarket"),
                webUrl = "https://www.honor.com/global/",
                updateAction = "com.huawei.appmarket.intent.action.ThirdUpdateAction"
            ),
            ManualStore(
                key = "heytap_store",
                title = "HeyTap Store",
                subtitle = "Магазины OPPO, realme и OnePlus",
                brandColor = 0xFF00A650,
                storePackages = listOf("com.heytap.market", "com.oppo.market"),
                webUrl = "https://www.oppo.com/en/"
            ),
            ManualStore(
                key = "palm_store",
                title = "Palm Store",
                subtitle = "Магазины Tecno, Infinix и itel",
                brandColor = 0xFF00A0E9,
                storePackages = listOf("com.transsion.palmstore", "com.transsion.appstore"),
                webUrl = "https://www.tecno-mobile.com/"
            ),
            ManualStore(
                key = "amazon_appstore",
                title = "Amazon Appstore",
                subtitle = "Магазин Amazon",
                brandColor = 0xFFFF9900,
                storePackages = listOf("com.amazon.venezia"),
                webUrl = "https://www.amazon.com/appstore",
                launchUris = listOf("amzn://apps/android")
            ),
            ManualStore(
                key = "lenovo_app_store",
                title = "Lenovo App Store",
                subtitle = "Магазины Lenovo и Motorola",
                brandColor = 0xFFE2231A,
                storePackages = listOf("com.lenovo.leos.appstore", "com.lenovo.appstore"),
                webUrl = "https://www.lenovo.com/"
            ),
            ManualStore(
                key = "zte_app_store",
                title = "ZTE App Store",
                subtitle = "Магазин ZTE и nubia",
                brandColor = 0xFF0066B3,
                storePackages = listOf("com.zte.appstore"),
                webUrl = "https://www.ztedevices.com/"
            )
        )
    }
}

/**
 * UniStore updates itself only through the system installer, never through a
 * store. Keeping it out of the tracked list avoids a permanent fake update
 * entry that no enabled store can actually install.
 */
const val UNISTORE_PACKAGE = "ru.updatehub.app"

/**
 * Packages of every store UniStore knows about. Store apps are managed from
 * Settings (connection, update section) and must never appear in the update
 * list as ordinary applications.
 */
object KnownStores {
    val packages: Set<String> = buildSet {
        UpdateSource.entries.forEach { addAll(it.storePackages) }
        ManualStore.all.forEach { addAll(it.storePackages) }
    }
}

data class AppSettings(
    val trackedPackages: Set<String> = emptySet(),
    val enabledSources: Set<String> = UpdateSource.defaultEnabledKeys,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: Palette = Palette.ADAPTIVE,
    val weakDevice: Boolean = false,
    val seasonalTheme: SeasonalTheme = SeasonalTheme.AUTO,
    val onboarded: Boolean = false,
    val autoCheck: Boolean = false,
    val intervalHours: Int = 12,
    val lastCheckAt: Long = 0L,
    val resultsJson: String = "",
    val checkSystemApps: Boolean = false,
    /** `package|version` pairs that Android refused to install on this device. */
    val incompatibleUpdates: Set<String> = emptySet()
)

/** Stable key for a store version that cannot be installed on this device. */
fun incompatibleUpdateKey(packageName: String, versionName: String): String =
    "$packageName|$versionName"

enum class UpdateStatus {
    UPDATE_AVAILABLE,
    INCOMPATIBLE,
    UP_TO_DATE,
    NOT_FOUND,
    ERROR,
    NOT_INSTALLED,
    UNKNOWN
}

/** Three-state connection quality shown next to every store. */
enum class StoreConnection {
    CHECKING,
    ONLINE,
    SLOW,
    OFFLINE
}

data class StoreHealth(
    val connection: StoreConnection = StoreConnection.CHECKING,
    val latencyMs: Long? = null
)

const val STORE_ONLINE_MAX_MS = 1_200L

/**
 * A successful response slower than [onlineMaxMs] means a weak link. The
 * threshold is configurable per store because overseas CDNs naturally answer
 * slower than local ones.
 */
fun storeHealthForLatency(
    latencyMs: Long?,
    onlineMaxMs: Long = STORE_ONLINE_MAX_MS
): StoreHealth = when {
    latencyMs == null -> StoreHealth(StoreConnection.OFFLINE)
    latencyMs <= onlineMaxMs -> StoreHealth(StoreConnection.ONLINE, latencyMs)
    else -> StoreHealth(StoreConnection.SLOW, latencyMs)
}

fun storeHealthLabel(health: StoreHealth): String {
    val latency = health.latencyMs
    return when (health.connection) {
        StoreConnection.CHECKING -> "Проверка связи…"
        StoreConnection.ONLINE ->
            latency?.let { "Хорошая связь · ${formatStoreLatency(it)}" } ?: "Хорошая связь"

        StoreConnection.SLOW ->
            latency?.let { "Слабая связь · ${formatStoreLatency(it)}" } ?: "Слабая связь"

        StoreConnection.OFFLINE -> "Нет связи"
    }
}

private fun formatStoreLatency(ms: Long): String =
    if (ms < 1_000L) {
        "$ms мс"
    } else {
        String.format(java.util.Locale.ROOT, "%.1f с", ms / 1_000.0)
    }

data class StoreVersion(
    val source: UpdateSource,
    val versionName: String,
    val versionCode: Long? = null,
    val storeUrl: String,
    val title: String? = null,
    /** SHA-256 of the signing certificate reported by the store, lowercase hex. */
    val signature: String? = null,
    /** SHA-1 of the signing certificate reported by the store, lowercase hex. */
    val signatureSha1: String? = null
)

sealed interface UpdateInstallState {
    data object Resolving : UpdateInstallState

    data class Downloading(
        val downloaded: Long,
        val total: Long
    ) : UpdateInstallState {
        val progress: Float?
            get() = if (total > 0) {
                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data object Verifying : UpdateInstallState

    /** Downloaded and verified, waiting for its turn in the install queue. */
    data object Ready : UpdateInstallState
    data object Installing : UpdateInstallState
    data class Failed(val message: String) : UpdateInstallState
    data object Completed : UpdateInstallState
}

data class AppUpdateResult(
    val packageName: String,
    val installedVersion: String,
    val status: UpdateStatus,
    val best: StoreVersion? = null,
    val versions: List<StoreVersion> = emptyList(),
    val checkedAt: Long = 0L,
    val message: String? = null
)
