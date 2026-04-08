package com.mixts.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * 检查端口是否可用
     */
    fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { socket ->
                // 成功创建 socket 说明端口可用
                true
            }
        } catch (e: IOException) {
            // 端口被占用
            false
        }
    }

    /**
     * 查找可用端口
     */
    fun findAvailablePort(startPort: Int = 8080, maxAttempts: Int = 10): Int {
        for (port in startPort until startPort + maxAttempts) {
            if (isPortAvailable(port)) {
                return port
            }
        }
        return startPort // 返回默认端口
    }

    /**
     * 获取占用端口的进程信息（简化版）
     */
    fun getPortUsageInfo(port: Int): String {
        return try {
            val socket = ServerSocket(port)
            socket.close()
            "端口 $port 可用"
        } catch (e: IOException) {
            "端口 $port 被占用: ${e.message}"
        }
    }

    /**
     * 获取本地 IPv4 地址
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * 获取所有本地 IPv4 地址
     */
    fun getAllLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null) {
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ips
    }

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
