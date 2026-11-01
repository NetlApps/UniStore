package ru.updatehub.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

data class NetworkSnapshot(
    val metered: Boolean = false,
    val cellular: Boolean = false,
    val wifi: Boolean = false,
    val vpnActive: Boolean = false,
    /**
     * True when Android reports a VPN network but UniStore's own default
     * network is not it. This is what a VPN's "allowed apps" list produces:
     * the browser goes through the tunnel while an unlisted app keeps using
     * the carrier directly.
     */
    val vpnBypassed: Boolean = false
)

/**
 * Small wrapper around ConnectivityManager. It is deliberately read-only: it
 * never changes the system network and does not try to force traffic into
 * another app's VPN, which Android does not permit. The result is used to
 * lower request concurrency on cellular links and to explain a VPN bypass in
 * the store status row.
 */
class NetworkMonitor(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun snapshot(): NetworkSnapshot {
        val manager = connectivity ?: return NetworkSnapshot()
        return try {
            val active = manager.activeNetwork
            val capabilities = active?.let(manager::getNetworkCapabilities)
            val activeVpn =
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val anyVpn = manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            NetworkSnapshot(
                metered = manager.isActiveNetworkMetered,
                cellular = capabilities
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
                wifi = capabilities
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                vpnActive = activeVpn || anyVpn,
                vpnBypassed = anyVpn && !activeVpn
            )
        } catch (_: Exception) {
            NetworkSnapshot()
        }
    }
}
