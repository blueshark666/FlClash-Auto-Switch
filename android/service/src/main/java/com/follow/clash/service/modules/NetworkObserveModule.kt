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
import com.google.gson.Gson
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

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
    
    // 模式相关变量
    private var currentModeCached: String? = null
    private val modeLastSwitchTs = AtomicLong(0L)
    private val MIN_MODE_SWITCH_INTERVAL_MS = 3000L // 3秒间隔限制
    
    // Gson实例
    private val gson = Gson()

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
        if (dnsList == preDnsList) {
            return
        }
        preDnsList = dnsList
        Core.updateDNS(dnsList.toSet().joinToString(","))
        checkAndSwitchModeBasedOnNetwork()
    }

    private fun checkAndSwitchModeBasedOnNetwork() {
        // 选取当前最佳网络
        val bestNetwork = networkInfos.asSequence().minByOrNull { networkToInt(it) }?.key ?: return
        val capabilities = connectivity?.getNetworkCapabilities(bestNetwork)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        // 映射策略：WiFi -> direct, 蜂窝 -> rule, 其它 -> direct
        val desiredMode = when {
            isWifi -> "direct"
            isCellular -> "rule"
            else -> "direct"
        }

        // 如果与缓存相同，则无需下发
        if (desiredMode == currentModeCached) return

        // 去抖：最小间隔限制
        val now = System.currentTimeMillis()
        val last = modeLastSwitchTs.get()
        if ((now - last) < MIN_MODE_SWITCH_INTERVAL_MS) {
            // 太频繁，跳过
            return
        }
        if (!modeLastSwitchTs.compareAndSet(last, now)) {
            return
        }

        // 更新缓存并通过 MethodChannel 发给 Flutter 处理下发
        currentModeCached = desiredMode
        sendModeUpdateToFlutter(desiredMode)
    }

    private fun sendModeUpdateToFlutter(mode: String) {
        try {

            
            val messageData = mapOf(
                "type" to "updateConfig", // Flutter 端按 type 解析并处理
                "data" to mapOf("mode" to mode)
            )
            val json = gson.toJson(messageData)

            // 调用 ServicePlugin 的 notifyOutboundModeChanged 方法
            try {
                // 使用反射获取ServicePlugin实例并调用notifyOutboundModeChanged方法
                val stateClass = Class.forName("com.follow.clash.State")
                val servicePluginField = stateClass.getDeclaredField("servicePlugin")
                servicePluginField.isAccessible = true
                val servicePlugin = servicePluginField.get(null)
                if (servicePlugin != null) {
                    val notifyMethod = servicePlugin.javaClass.getDeclaredMethod("notifyOutboundModeChanged", String::class.java)
                    notifyMethod.invoke(servicePlugin, mode)
                }
            } catch (e: Exception) {
                // 记录异常但继续执行
                e.printStackTrace()
            }

        } catch (e: Throwable) {
            // 出错时，清除缓存以便下一次重试
            currentModeCached = null
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