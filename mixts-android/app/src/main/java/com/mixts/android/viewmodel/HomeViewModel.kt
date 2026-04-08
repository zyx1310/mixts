package com.mixts.android.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mixts.android.MixtsApp
import com.mixts.android.model.FileItem
import com.mixts.android.server.FileServer
import com.mixts.android.server.FileServerService
import com.mixts.android.ui.ServerState
import com.mixts.android.util.NetworkUtils
import com.mixts.android.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val preferencesManager: PreferencesManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MixtsViewModel"
    }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    private val _workspacePath = MutableStateFlow<String?>(null)
    val workspacePath: StateFlow<String?> = _workspacePath.asStateFlow()

    private val _debugInfo = MutableStateFlow<String?>(null)
    val debugInfo: StateFlow<String?> = _debugInfo.asStateFlow()

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    // 不再在 ViewModel 中持有 FileServer 引用，使用 Application 单例
    private val workspace: File
        get() = application.getExternalFilesDir(null) ?: application.filesDir

    init {
        // 延迟初始化，非阻塞UI渲染
        // 直接设置默认值，避免阻塞
        _workspacePath.value = getDisplayPath()
        
        // 加载密码
        viewModelScope.launch(Dispatchers.IO) {
            loadPassword()
        }

        // 监听 MixtsApp 的服务器状态变化（主动通知，不需要轮询）
        viewModelScope.launch {
            MixtsApp.serverState.collect { isRunning ->
                if (isRunning) {
                    _serverState.value = ServerState.RUNNING
                    _serverUrl.value = MixtsApp.serverUrl.value
                    logDebugInfo("服务器运行中: ${_serverUrl.value}")
                } else {
                    _serverState.value = ServerState.STOPPED
                    _serverUrl.value = null
                    logDebugInfo("服务器已停止")
                }
            }
        }
        
        Log.d(TAG, "Workspace: ${workspace.absolutePath}")
    }
    
    private fun getDisplayPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 保存到 Downloads/Mixts
            "Downloads/Mixts"
        } else {
            // Android 9 及以下保存到应用私有目录
            workspace.absolutePath
        }
    }

    private fun loadPassword() {
        _password.value = preferencesManager.getPassword()
    }

    fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            _serverState.value = ServerState.STARTING
            logDebugInfo("开始启动服务...")

            try {
                val port = 8080

                // 检查端口是否可用
                if (!NetworkUtils.isPortAvailable(port)) {
                    val usageInfo = NetworkUtils.getPortUsageInfo(port)
                    logDebugInfo("端口被占用: $usageInfo", isError = true)
                    Log.e(TAG, "Port 8080 is in use: $usageInfo")
                    _serverState.value = ServerState.ERROR
                    return@launch
                }

                val passwordValue = preferencesManager.getPassword()

                // 获取所有可用IP
                val allIPs = NetworkUtils.getAllLocalIpAddresses()
                logDebugInfo("可用IP列表: ${allIPs.joinToString(", ")}")

                // 获取主要IP (通常使用第一个)
                val ip = NetworkUtils.getLocalIpAddress()
                val url = if (ip != null) "http://$ip:$port" else "http://localhost:$port"

                logDebugInfo("使用IP: $ip")
                logDebugInfo("服务地址: $url")
                logDebugInfo("工作目录: ${workspace.absolutePath}")

                // 使用 Application 单例启动服务器，传入 bindAddress = "0.0.0.0" 确保所有接口都能访问
                val server = FileServer(port, workspace, application, "0.0.0.0").apply {
                    setPassword(passwordValue.takeIf { it.isNotEmpty() })
                    // 设置实际 IP 地址，用于生成正确的访问 URL
                    setActualIp(ip)
                    start()
                }
                // 设置服务器并通知状态更新
                MixtsApp.setFileServer(server, url)

                // Android 10+ Downloads/Mixts 目录初始化由 MixtsApp 在应用启动时处理
                // 这里不再重复创建，避免创建多个占位文件

                Log.d(TAG, "Server URL: $url")
                Log.d(TAG, "Workspace: ${workspace.absolutePath}")
                Log.d(TAG, "Password set: ${passwordValue.isNotEmpty()}")

                clearError()
                logDebugInfo("服务启动成功!")

                // Start foreground service for notification
                startForegroundService(port, url)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                logDebugInfo(e.message ?: "启动失败", isError = true)
                _serverState.value = ServerState.ERROR
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logDebugInfo("正在停止服务...")
                // 使用 Application 单例停止服务器
                MixtsApp.stopServer()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping server", e)
            } finally {
                _serverState.value = ServerState.STOPPED
                _serverUrl.value = null
                clearError()
                logDebugInfo("服务已停止")

                // Stop foreground service
                stopForegroundService()
            }
        }
    }

    private fun startForegroundService(port: Int, url: String) {
        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Notification permission not granted, skipping foreground service")
                return
            }
        }

        try {
            val intent = FileServerService.createStartIntent(application, port, url)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(intent)
            } else {
                application.startService(intent)
            }
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = FileServerService.createStopIntent(application)
            application.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
        }
    }

    fun setPassword(password: String) {
        preferencesManager.setPassword(password)
        _password.value = password
        logDebugInfo("密码已更新")
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
    }

    fun hideSettings() {
        _showSettings.value = false
    }

    private fun logDebugInfo(message: String, isError: Boolean = false) {
        _debugInfo.value = message
        if (isError) {
            _serverError.value = message
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
    }
    
    private fun clearError() {
        _serverError.value = null
    }

    /**
     * 注意：服务器的生命周期由 FileServerService 管理，
     * 此 ViewModel 只负责启动/停止服务，不负责在 onCleared 中停止服务器
     */
    override fun onCleared() {
        super.onCleared()
        // 不要在这里停止服务器，因为 ForegroundService 会继续运行
        // fileServer?.stop() 已移除
    }
}
