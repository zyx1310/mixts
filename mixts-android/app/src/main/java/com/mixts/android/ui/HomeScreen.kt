package com.mixts.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mixts.android.BuildConfig
import com.mixts.android.viewmodel.HomeViewModel
import java.io.File

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val serverError by viewModel.serverError.collectAsStateWithLifecycle()
    val workspacePath by viewModel.workspacePath.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    
    val context = LocalContext.current

    // Permission launcher for notifications
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Handle result if needed */ }

    // Request notification permission on Android 13+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showSettings) {
        SettingsScreen(
            password = password,
            onPasswordChange = { viewModel.setPassword(it) },
            onDismiss = { viewModel.hideSettings() }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Title with Settings Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mixts",
                    style = MaterialTheme.typography.headlineLarge
                )
                Row {
                    IconButton(onClick = { viewModel.toggleSettings() }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                }
            }

            // Center: Server Card
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                // Server Card
                ServerCard(
                    serverState = serverState,
                    serverUrl = serverUrl,
                    onStart = { viewModel.startServer() },
                    onStop = { viewModel.stopServer() },
                    context = context
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 状态信息卡片
                StatusInfoCard(
                    workspacePath = workspacePath,
                    errorMessage = serverError,
                    onOpenFolder = {
                        // 打开文件管理器应用
                        try {
                            val path = workspacePath ?: return@StatusInfoCard
                            
                            // 使用系统 API 打开 Downloads/Mixts 目录
                            // 对于 Android 10+，使用 MediaStore 管理的目录
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path == "Downloads/Mixts") {
                                // 使用 SAF 打开 Downloads/Mixts 目录
                                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                                val mixtsDir = File(downloadsDir, "Mixts")
                                
                                // 如果目录不存在，先创建
                                if (!mixtsDir.exists()) {
                                    mixtsDir.mkdirs()
                                }
                                
                                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(android.net.Uri.fromFile(mixtsDir), "resource/folder")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            } else {
                                val file = File(path)
                                
                                if (!file.exists() || !file.isDirectory) {
                                    Toast.makeText(context, "目录不存在", Toast.LENGTH_SHORT).show()
                                    return@StatusInfoCard
                                }
                                
                                // 使用 file:// URI 拉起文件管理器
                                android.net.Uri.fromFile(file).let { uri ->
                                    android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "resource/folder")
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                }
                            }
                            
                            // 弹出"打开方式"选择器，让用户选择文件管理器
                            context.startActivity(android.content.Intent.createChooser(intent, "选择文件管理器打开目录"))
                        } catch (e: Exception) {
                            // 如果出错，尝试直接打开系统默认文件管理器
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                                    type = "*/*"
                                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "打开文件位置"))
                            } catch (e2: Exception) {
                                Toast.makeText(context, "无法打开文件管理器", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // Bottom: Version Info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        text = "Debug Build",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    serverState: ServerState,
    serverUrl: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 二维码显示（仅在服务运行时）
            if (serverUrl != null && serverState == ServerState.RUNNING) {
                val qrBitmap = remember(serverUrl) { generateQRCode(serverUrl, 200) }
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "服务器地址二维码",
                        modifier = Modifier
                            .size(150.dp)
                            .padding(bottom = 16.dp)
                    )
                }
            }

            // Status Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (serverState) {
                    ServerState.STOPPED -> {
                        StatusIndicator(color = MaterialTheme.colorScheme.outline)
                        Text("服务已停止", style = MaterialTheme.typography.titleMedium)
                    }
                    ServerState.STARTING -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text("正在启动...", style = MaterialTheme.typography.titleMedium)
                    }
                    ServerState.RUNNING -> {
                        StatusIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("服务运行中", style = MaterialTheme.typography.titleMedium)
                    }
                    ServerState.ERROR -> {
                        StatusIndicator(color = MaterialTheme.colorScheme.error)
                        Text("启动失败", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server URL - 可点击复制
            if (serverUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Server URL", serverUrl))
                            Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = serverUrl,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击地址可复制到剪贴板",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Control Button
            when (serverState) {
                ServerState.STARTING -> {
                    CircularProgressIndicator()
                }
                else -> {
                    Button(
                        onClick = { if (serverState == ServerState.RUNNING) onStop() else onStart() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serverState == ServerState.RUNNING)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = if (serverState == ServerState.RUNNING) "停止服务" else "启动服务"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusInfoCard(
    workspacePath: String?,
    errorMessage: String?,
    onOpenFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 文件保存位置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "文件保存位置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = workspacePath ?: "未知",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 打开文件夹图标
                IconButton(onClick = onOpenFolder) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "打开位置",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // 错误信息（如果存在）
            if (!errorMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "运行错误",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(color: Color) {
    Surface(
        modifier = Modifier.size(12.dp),
        shape = MaterialTheme.shapes.small,
        color = color
    ) {}
}

/**
 * 生成二维码图片
 */
private fun generateQRCode(content: String, size: Int): Bitmap? {
    return try {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 1
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
