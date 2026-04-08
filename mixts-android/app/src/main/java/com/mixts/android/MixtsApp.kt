package com.mixts.android

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.mixts.android.server.FileServer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

@HiltAndroidApp
class MixtsApp : Application() {

    companion object {
        private const val TAG = "MixtsApp"
        private const val PREFS_NAME = "mixts_prefs"
        private const val KEY_FOLDER_CREATED = "folder_created"
        
        @Volatile
        private var instance: MixtsApp? = null
        
        fun getInstance(): MixtsApp = instance ?: throw IllegalStateException("Application not initialized")
        
        // FileServer 单例
        @Volatile
        private var fileServer: FileServer? = null
        
        // 服务器运行状态 - 用于主动通知 UI 更新
        private val _serverState = MutableStateFlow(false)
        val serverState: StateFlow<Boolean> = _serverState.asStateFlow()
        
        // 服务器 URL
        private val _serverUrl = MutableStateFlow<String?>(null)
        val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()
        
        fun getFileServer(): FileServer? = fileServer
        
        fun setFileServer(server: FileServer?, url: String?) {
            fileServer = server
            _serverState.value = server?.isAlive == true
            _serverUrl.value = url
        }
        
        fun isServerRunning(): Boolean = fileServer?.isAlive == true
        
        fun getServerUrl(): String? = fileServer?.let {
            if (it.isAlive) {
                it.getServerUrl()
            } else null
        }
        
        fun stopServer() {
            fileServer?.stop()
            fileServer = null
            // 通知状态更新
            _serverState.value = false
            _serverUrl.value = null
        }
    }
    
    private val workspace: File
        get() = getExternalFilesDir(null) ?: filesDir
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 设置全局异常捕获
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            Log.e(TAG, "Uncaught exception: ${throwable.message}\n${sw.toString()}")
        }
        
        Log.d(TAG, "Application created, workspace: ${workspace.absolutePath}")
        
        // Android 10+ 确保 Downloads/Mixts 目录存在 - 改为后台执行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 使用 HandlerThread 在后台线程创建目录，避免阻塞主线程
            HandlerThread("MixtsInit").apply {
                start()
                Handler(looper).post {
                    ensureDownloadsFolder()
                }
            }
        }
    }
    
    /**
     * 确保 Downloads/Mixts 目录存在
     * 创建一个占位文件以便在文件管理器中可以看到目录
     * 只创建一次，后续启动会跳过
     */
    private fun ensureDownloadsFolder() {
        // 检查是否已经创建过
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FOLDER_CREATED, false)) {
            Log.d(TAG, "Downloads/Mixts folder already exists, skipping")
            return
        }
        
        try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "这是Mixts用来上传下载的目录.txt")
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, 
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/Mixts")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            
            val resolver = contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                // 写入说明内容
                val message = "这是 Mixts 用来上传下载的目录\n\n" +
                        "Mixts 是一个局域网文件传输应用\n" +
                        "通过 Wi-Fi 可以在电脑和手机之间传输文件\n\n" +
                        "使用方法：\n" +
                        "1. 在手机上启动 Mixts 服务\n" +
                        "2. 在电脑浏览器中访问显示的地址\n" +
                        "3. 上传或下载文件"
                
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(message.toByteArray(Charsets.UTF_8))
                }
                
                // 标记完成
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                
                // 记录已创建
                prefs.edit().putBoolean(KEY_FOLDER_CREATED, true).apply()
                
                Log.d(TAG, "Downloads/Mixts folder and placeholder file created")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure Downloads folder: ${e.message}")
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // 应用终止时确保服务器停止
        stopServer()
        Log.d(TAG, "Application terminated, server stopped")
    }
}
