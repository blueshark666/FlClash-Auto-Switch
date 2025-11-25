package com.follow.clash.service.modules

import android.app.Service
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.NetworkCapabilities.TRANSPORT_USB
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import com.follow.clash.core.Core
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import android.os.Handler
import android.os.Looper
//import io.flutter.plugin.common.MethodChannel
import com.google.gson.Gson

//var flutterEngine: io.flutter.embedding.engine.FlutterEngine? = null
//
//fun attachFlutterEngine(engine: io.flutter.embedding.engine.FlutterEngine) {
//    flutterEngine = engine
//}


private data class NetworkInfo(
    @Volatile var losingMs: Long = 0, @Volatile var dnsList: List<InetAddress> = emptyList()
) {
    fun isAvailable(): Boolean = losingMs < System.currentTimeMillis()
}

class NetworkObserveModule(private val service: Service) : Module() {

    private val networkInfos = ConcurrentHashMap<Network, NetworkInfo>()
    private val connectivity by lazy {
        service.getSystemService<ConnectivityManager>()
    }
    private var preDnsList = listOf<String>()

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkInfos[network] = NetworkInfo()
            onUpdateNetwork()
            super.onAvailable(network)
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            networkInfos[network]?.losingMs = System.currentTimeMillis() + maxMsToLive
            onUpdateNetwork()
            setUnderlyingNetworks(network)
            super.onLosing(network, maxMsToLive)
        }

        override fun onLost(network: Network) {
            networkInfos.remove(network)
            onUpdateNetwork()
            setUnderlyingNetworks(network)
            super.onLost(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networkInfos[network]?.dnsList = linkProperties.dnsServers
            onUpdateNetwork()
            setUnderlyingNetworks(network)
            super.onLinkPropertiesChanged(network, linkProperties)
        }
    }


    override fun onInstall() {
        onUpdateNetwork()
        connectivity?.registerNetworkCallback(request, callback)
    }

    private fun networkToInt(entry: Map.Entry<Network, NetworkInfo>): Int {
        val capabilities = connectivity?.getNetworkCapabilities(entry.key)
        return when {
            capabilities == null -> 100
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(
                TRANSPORT_USB
            ) -> 2

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(
                TRANSPORT_SATELLITE
            ) -> 5

            else -> 20
        } + (if (entry.value.isAvailable()) 0 else 10)
    }

    fun onUpdateNetwork() {
        val dnsList = (networkInfos.asSequence().minByOrNull { networkToInt(it) }?.value?.dnsList
            ?: emptyList()).map { x -> x.asSocketAddressText(53) }

        Log.d("NetworkMonitor", "Current DNS list: $dnsList, previous: $preDnsList")

        if (dnsList == preDnsList) {
            Log.d("NetworkMonitor", "DNS list unchanged, skipping update")
            return
        }

        preDnsList = dnsList
        Core.updateDNS(dnsList.toSet().joinToString(","))

        // 新增：检查网络类型并切换代理
        checkAndSwitchProxyBasedOnNetwork()
    }

    private fun checkAndSwitchProxyBasedOnNetwork() {
        val bestNetwork = networkInfos.asSequence().minByOrNull { networkToInt(it) }?.key
        if (bestNetwork == null) {
            Log.d("NetworkMonitor", "No available network")
            return
        }

        val capabilities = connectivity?.getNetworkCapabilities(bestNetwork)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        Log.d("NetworkMonitor", "Network: $bestNetwork, isWifi=$isWifi, isCellular=$isCellular, currentIsWifi=$currentIsWifi")

        if (isWifi && currentIsWifi != true) {
            Log.d("NetworkMonitor", "Switching to DIRECT mode (Wi-Fi detected)")
            switchToDirectMode()
        } else if (isCellular && currentIsWifi != false) {
            Log.d("NetworkMonitor", "Switching to RULE mode (Cellular detected)")
            switchToRuleMode()
        }

        currentIsWifi = when {
            isWifi -> true
            isCellular -> false
            else -> currentIsWifi
        }
    }

    private fun switchToDirectMode() {
        val proxyParams = mapOf("group-name" to "GLOBAL", "proxy-name" to "DIRECT")
        val actionData = mapOf(
            "id" to System.currentTimeMillis().toString(),
            "method" to "changeProxy",
            "data" to gson.toJson(proxyParams)
        )
        Log.d("NetworkMonitor", "Invoking Core to switch DIRECT: ${gson.toJson(proxyParams)}")

        Core.invokeAction(gson.toJson(actionData)) { result ->
            Log.d("NetworkMonitor", "Core switch DIRECT result: $result")
        }
    }

    private fun switchToRuleMode() {
        val proxyParams = mapOf("group-name" to "GLOBAL", "proxy-name" to "RULE")
        val actionData = mapOf(
            "id" to System.currentTimeMillis().toString(),
            "method" to "changeProxy",
            "data" to gson.toJson(proxyParams)
        )
        Log.d("NetworkMonitor", "Invoking Core to switch RULE: ${gson.toJson(proxyParams)}")

        Core.invokeAction(gson.toJson(actionData)) { result ->
            Log.d("NetworkMonitor", "Core switch RULE result: $result")
        }
    }



    fun setUnderlyingNetworks(network: Network) {
//        if (service is VpnService && Build.VERSION.SDK_INT in 22..28) {
//            service.setUnderlyingNetworks(arrayOf(network))
//        }
    }

    override fun onUninstall() {
        connectivity?.unregisterNetworkCallback(callback)
        networkInfos.clear()
        onUpdateNetwork()
    }
}

fun InetAddress.asSocketAddressText(port: Int): String {
    return when (this) {
        is Inet6Address -> "[${numericToTextFormat(this)}]:$port"

        is Inet4Address -> "${this.hostAddress}:$port"

        else -> throw IllegalArgumentException("Unsupported Inet type ${this.javaClass}")
    }
}

private fun numericToTextFormat(address: Inet6Address): String {
    val src = address.address
    val sb = StringBuilder(39)
    for (i in 0 until 8) {
        sb.append(
            Integer.toHexString(
                src[i shl 1].toInt() shl 8 and 0xff00 or (src[(i shl 1) + 1].toInt() and 0xff)
            )
        )
        if (i < 7) {
            sb.append(":")
        }
    }
    if (address.scopeId > 0) {
        sb.append("%")
        sb.append(address.scopeId)
    }
    return sb.toString()
}