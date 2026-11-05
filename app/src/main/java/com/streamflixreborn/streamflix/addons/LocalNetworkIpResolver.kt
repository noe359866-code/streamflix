package com.streamflixreborn.streamflix.addons

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteOrder
import java.util.Locale

object LocalNetworkIpResolver {

    private val IGNORED_INTERFACE_PREFIXES = listOf(
        "tun", "ppp", "rmnet", "ccmni", "dummy", "lo", "p2p", "docker", "veth"
    )

    /**
     * Captures the local LAN / Wi-Fi IPv4 address of the Android TV device (e.g. `192.168.1.50`).
     */
    fun resolveLocalLanIpv4(context: Context): String? {
        // 1. Prefer active Wi-Fi or Ethernet site-local IPv4 interface
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()

            for (netIf in interfaces) {
                if (!netIf.isUp || netIf.isLoopback || netIf.isVirtual) continue
                val ifName = netIf.name.lowercase(Locale.ROOT)
                if (IGNORED_INTERFACE_PREFIXES.any { ifName.startsWith(it) }) continue

                val priority = when {
                    ifName.startsWith("wlan") || ifName.startsWith("wifi") -> 0
                    ifName.startsWith("eth") || ifName.startsWith("en") -> 1
                    else -> 2
                }

                for (addr in netIf.inetAddresses.toList()) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("169.254.")) continue // skip link-local APIPA
                    val score = if (addr.isSiteLocalAddress) priority else priority + 10
                    candidates.add(score to host)
                }
            }

            candidates.minByOrNull { it.first }?.second?.let { return it }
        }

        // 2. Fallback to WifiManager connection info IPv4
        return runCatching {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return@runCatching null
            @Suppress("DEPRECATION")
            var ipInt = wifiManager.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return@runCatching null
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                ipInt = Integer.reverseBytes(ipInt)
            }
            val bytes = byteArrayOf(
                ((ipInt ushr 24) and 0xFF).toByte(),
                ((ipInt ushr 16) and 0xFF).toByte(),
                ((ipInt ushr 8) and 0xFF).toByte(),
                (ipInt and 0xFF).toByte(),
            )
            Inet4Address.getByAddress(bytes).hostAddress?.takeIf { it != "0.0.0.0" }
        }.getOrNull()
    }
}
