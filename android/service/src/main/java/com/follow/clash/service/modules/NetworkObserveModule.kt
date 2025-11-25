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
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import com.follow.clash.core.Core
import com.follow.clash.service.GlobalState
import com.google.gson.Gson
import io.flutter.plugin.common.MethodChannel
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
    private val gson = Gson()
    private var currentIsWifi = false

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
        
        // 检测是否连接到WiFi并切换代理
        checkAndSwitchProxyBasedOnNetwork()
    }
    
    private fun checkAndSwitchProxyBasedOnNetwork() {
        // 获取当前最佳网络
        val bestNetwork = networkInfos.asSequence().minByOrNull { networkToInt(it) }?.key ?: return
        
        // 检查是否为WiFi连接
        val capabilities = connectivity?.getNetworkCapabilities(bestNetwork)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        // 如果网络状态发生变化且当前是WiFi连接，则切换到直连模式
        if (isWifi && !currentIsWifi) {
            switchToDirectMode()
        }
        
        // 更新当前网络状态
        currentIsWifi = isWifi
    }
    
    private fun switchToDirectMode() {
        // 构建切换代理的参数
        val proxyParams = mapOf(
            "group-name" to "GLOBAL", // 根据实际配置调整代理组名称
            "proxy-name" to "DIRECT"
        )
        
        // 构建action数据
        val actionData = mapOf(
            "id" to System.currentTimeMillis().toString(),
            "method" to "changeProxy",
            "data" to gson.toJson(proxyParams)
        )
        
        // 调用Core.invokeAction方法切换到直连模式
        Core.invokeAction(gson.toJson(actionData)) { result ->
            // 更新通知栏显示为直连模式
            val notificationParams = GlobalState.notificationParams?.copy(
                stopText = "DIRECT Mode"
            )
            notificationParams?.let {
                GlobalState.notificationParams = it
            }
            
            // 通过MethodChannel直接发送消息给Flutter端
            // 这会触发Flutter端service.dart中的message处理逻辑
            GlobalState.application?.let {
                Handler(Looper.getMainLooper()).post {
                    val flutterEngine = it.flutterEngine
                    if (flutterEngine != null) {
                        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.follow.clash/service")
                        
                        val messageData = mapOf(
                            "type" to "modeUpdate",
                            "data" to "direct"
                        )
                        
                        channel.invokeMethod("message", gson.toJson(messageData))
                    }
                }
            }
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