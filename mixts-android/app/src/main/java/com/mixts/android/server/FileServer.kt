package com.mixts.android.server

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import fi.iki.elonen.NanoHTTPD
import com.mixts.android.model.FileItem
import com.mixts.android.model.FileListResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Base64
import java.util.HashMap

class FileServer(
    private val port: Int,
    private val workspace: File,
    private val context: Context? = null,  // 可选，用于 MediaStore
    private val bindAddress: String = "0.0.0.0"  // 绑定地址，默认为 0.0.0.0（所有接口）
) : NanoHTTPD(port) {

    private var password: String? = null
    private val json = Json { prettyPrint = false }
    
    // 存储实际使用的 IP 地址（用于生成 URL）
    var actualIp: String? = null
        private set
    
    /**
     * 设置实际使用的 IP 地址
     * 在启动服务器时调用
     */
    fun setActualIp(ip: String?) {
        actualIp = ip
    }
    
    /**
     * 获取服务器的完整 URL
     * 使用存储的 actualIp 而不是 NanoHTTPD 内部的 hostname
     */
    fun getServerUrl(): String {
        val host = actualIp ?: "localhost"
        return "http://${host}:${listeningPort}"
    }

    fun setPassword(pwd: String?) {
        password = pwd
    }

    override fun serve(session: IHTTPSession): Response {
        // Password verification
        password?.let { pwd ->
            val authHeader = session.headers["authorization"]
            if (!verifyPassword(authHeader, pwd)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_PLAINTEXT,
                    "Unauthorized"
                ).apply {
                    addHeader("WWW-Authenticate", "Basic realm=\"Mixts\"")
                }
            }
        }

        return when (session.method) {
            Method.GET -> handleGet(session)
            Method.POST -> handlePost(session)
            else -> super.serve(session)
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            // API: File list
            uri == "/api/files" -> handleFileList()
            // API: File download
            uri.startsWith("/api/download/") -> handleDownload(uri)
            // Web: Home page
            uri == "/" || uri == "/index.html" -> handleWebIndex()
            // Web: Static resources
            else -> handleStaticResource(uri)
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        return when (session.uri) {
            "/api/upload" -> handleUpload(session)
            "/api/delete" -> handleDelete(session)
            else -> super.serve(session)
        }
    }

    private fun handleDelete(session: IHTTPSession): Response {
        return try {
            session.parseBody(null)
            val files = session.parameters["file"]
            if (files != null && files.isNotEmpty()) {
                val filename = files.first()
                val file = File(workspace, filename)
                if (file.exists() && file.isFile) {
                    file.delete()
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_PLAINTEXT,
                        "Delete successful"
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "File not found"
                    )
                }
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "No file specified"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Delete failed: ${e.message}"
            )
        }
    }

    private fun handleFileList(): Response {
        // 获取文件列表 - 兼容两种存储方式
        val files = mutableListOf<FileItem>()
        val debugInfo = mutableListOf<String>()
        
        // 1. 应用私有目录的文件
        val workspaceFiles = workspace.listFiles()?.filter { it.isFile } ?: emptyList()
        debugInfo.add("私有目录: ${workspaceFiles.size} 个文件")
        workspaceFiles.forEach { file ->
            files.add(
                FileItem(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            )
        }
        
        // 2. Downloads/Mixts 目录的文件 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadsFiles = getDownloadsFiles()
            debugInfo.add("Downloads/Mixts: ${downloadsFiles?.size ?: 0} 个文件")
            downloadsFiles?.let { files.addAll(it) }
        }

        // 使用序列化类构建响应
        val response = FileListResponse(files = files, debug = debugInfo)
        val jsonStr = json.encodeToString(response)
        
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=UTF-8",
            jsonStr
        )
    }

    private fun getDownloadsFiles(): List<FileItem>? {
        return try {
            val ctx = context ?: return null
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED,
                MediaStore.Downloads.RELATIVE_PATH
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Mixts%")
            
            android.util.Log.d("FileServer", "Querying Downloads with selection: $selection, args: ${selectionArgs.joinToString()}")
            
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                android.util.Log.d("FileServer", "MediaStore cursor count: ${cursor.count}")
                
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                
                mutableListOf<FileItem>().apply {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                        val size = cursor.getLong(sizeColumn)
                        val date = cursor.getLong(dateColumn) * 1000
                        val relativePath = cursor.getString(pathColumn)
                        
                        android.util.Log.d("FileServer", "Found file: $name, path: $relativePath")
                        
                        add(
                            FileItem(
                                name = name,
                                path = "content://downloads/$id",
                                size = size,
                                lastModified = date
                            )
                        )
                    }
                }
            } ?: run {
                android.util.Log.w("FileServer", "MediaStore query returned null")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("FileServer", "getDownloadsFiles error: ${e.message}")
            null
        }
    }

    private fun handleDownload(uri: String): Response {
        val filename = uri.removePrefix("/api/download/")
        
        // 1. 先尝试从私有目录下载
        val privateFile = File(workspace, filename)
        if (privateFile.exists() && privateFile.isFile) {
            return serveFile(privateFile, filename)
        }
        
        // 2. 尝试从 Downloads 下载 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadsFile = getDownloadsFileByName(filename)
            if (downloadsFile != null) {
                return serveFileFromDownloads(downloadsFile, filename)
            }
        }
        
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "File not found"
        )
    }

    private fun getDownloadsFileByName(filename: String): Uri? {
        return try {
            val ctx = context ?: return null
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(filename, "%Mixts%")
            
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun serveFile(file: File, filename: String): Response {
        val mimeType = getMimeType(filename)
        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            file.length()
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        }
    }

    private fun serveFileFromDownloads(uri: Uri, filename: String): Response {
        return try {
            val ctx = context ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Context not available"
            )
            
            val mimeType = ctx.contentResolver.getType(uri) ?: getMimeType(filename)
            
            ctx.contentResolver.openInputStream(uri)?.use { inputStream ->
                // 获取文件大小
                val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                
                newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    if (size >= 0) size else 0
                ).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
                }
            } ?: newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "File not found"
            )
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Download failed: ${e.message}"
            )
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            // 检查 Content-Type
            val contentType = session.headers["content-type"] ?: ""
            if (!contentType.contains("multipart/form-data", ignoreCase = true)) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid content type: $contentType"
                )
            }
            
            // 提取 boundary
            val boundaryMatch = Regex("""boundary=(.*?)(;|$)""").find(contentType)
            if (boundaryMatch == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing boundary in content-type"
                )
            }
            
            // 检查 Content-Length
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Empty request body"
                )
            }
            
            // 确保服务器仍在运行
            if (!isAlive) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Server not available"
                )
            }
            
            // 解析 body
            val filesMap = HashMap<String, String>()
            session.parseBody(filesMap)
            
            // 获取临时文件路径
            val tmpFilePath = filesMap["file"]
            if (tmpFilePath.isNullOrEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "No file uploaded"
                )
            }
            
            val tmpFile = File(tmpFilePath)
            if (!tmpFile.exists()) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Temp file not found"
                )
            }
            
            // 从 session.parameters 获取原始文件名
            val fileNames = session.parameters["file"]
            val originalFileName = fileNames?.firstOrNull() ?: "uploaded_file"
            
            // 根据 Android 版本选择保存方式
            val saveSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context != null) {
                // Android 10+ 使用 MediaStore 保存到 Downloads/Mixts
                saveToDownloads(originalFileName, tmpFile)
            } else {
                // Android 9 及以下保存到应用私有目录
                val destFile = File(workspace, originalFileName)
                tmpFile.copyTo(destFile, overwrite = true)
                true
            }
            
            if (saveSuccess) {
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_PLAINTEXT,
                    "Upload successful"
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Failed to save file"
                )
            }
        } catch (e: NullPointerException) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Invalid request: ${e.message}"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Upload failed: ${e.message}"
            )
        }
    }

    /**
     * 使用 MediaStore API 保存文件到 Downloads/Mixts 目录
     * 无需任何权限
     */
    private fun saveToDownloads(filename: String, sourceFile: File): Boolean {
        return try {
            val ctx = context ?: return false
            
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, getMimeType(filename))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mixts")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri == null) {
                // 如果创建失败，尝试保存到私有目录
                val destFile = File(workspace, filename)
                sourceFile.copyTo(destFile, overwrite = true)
                return true
            }
            
            resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // 标记完成
            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            // 出错时回退到私有目录
            val destFile = File(workspace, filename)
            sourceFile.copyTo(destFile, overwrite = true)
            true
        }
    }

    private fun handleWebIndex(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Mixts - 文件管理</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background: #fafafa; }
                    h1 { color: #333; }
                    .upload-form { background: #fff; padding: 20px; border-radius: 8px; margin: 20px 0; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                    .file-list { list-style: none; padding: 0; background: #fff; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                    .file-item { padding: 12px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; }
                    .file-item:last-child { border-bottom: none; }
                    .file-item a { color: #007bff; text-decoration: none; }
                    .file-item a:hover { text-decoration: underline; }
                    .file-info { display: flex; gap: 20px; align-items: center; }
                    .upload-area { border: 2px dashed #ddd; padding: 30px; text-align: center; border-radius: 8px; margin-bottom: 15px; transition: all 0.3s; }
                    .upload-area.dragover { border-color: #007bff; background: #f0f7ff; }
                    .upload-area.disabled { opacity: 0.5; pointer-events: none; }
                    .btn { background: #007bff; color: white; border: none; padding: 10px 20px; border-radius: 6px; cursor: pointer; font-size: 14px; }
                    .btn:hover { background: #0056b3; }
                    .btn:disabled { background: #ccc; cursor: not-allowed; }
                    .progress-bar { width: 100%; height: 8px; background: #eee; border-radius: 4px; margin-top: 15px; overflow: hidden; display: none; }
                    .progress-bar.active { display: block; }
                    .progress-fill { height: 100%; background: #007bff; width: 0%; transition: width 0.3s; }
                    .status-text { margin-top: 10px; font-size: 14px; color: #666; }
                    .file-input { display: none; }
                    .empty-tip { color: #999; padding: 20px; text-align: center; }
                </style>
            </head>
            <body>
                <h1>📁 Mixts 文件管理</h1>
                <div class="upload-form">
                    <h3>📤 上传文件</h3>
                    <div class="upload-area" id="uploadArea">
                        <p>📂 点击选择文件 或 拖拽文件到这里</p>
                        <input type="file" id="fileInput" class="file-input" multiple>
                    </div>
                    <div class="file-info">
                        <button id="uploadBtn" class="btn" onclick="uploadFile()">开始上传</button>
                        <button id="cancelBtn" class="btn" style="background: #dc3545; display: none;" onclick="cancelUpload()">取消上传</button>
                    </div>
                    <div class="progress-bar" id="progressBar">
                        <div class="progress-fill" id="progressFill"></div>
                    </div>
                    <div class="status-text" id="statusText"></div>
                </div>
                <h3>📂 文件列表</h3>
                <ul class="file-list" id="fileList">
                    <li class="empty-tip">加载中...</li>
                </ul>
                <script>
                    let xhr = null;
                    let selectedFiles = [];
                    
                    // 文件选择处理
                    const fileInput = document.getElementById('fileInput');
                    const uploadArea = document.getElementById('uploadArea');
                    
                    uploadArea.addEventListener('click', () => fileInput.click());
                    fileInput.addEventListener('change', (e) => {
                        selectedFiles = Array.from(e.target.files);
                        updateFileCount();
                    });
                    
                    // 拖拽处理
                    uploadArea.addEventListener('dragover', (e) => {
                        e.preventDefault();
                        uploadArea.classList.add('dragover');
                    });
                    uploadArea.addEventListener('dragleave', () => {
                        uploadArea.classList.remove('dragover');
                    });
                    uploadArea.addEventListener('drop', (e) => {
                        e.preventDefault();
                        uploadArea.classList.remove('dragover');
                        selectedFiles = Array.from(e.dataTransfer.files);
                        updateFileCount();
                    });
                    
                    function updateFileCount() {
                        const statusText = document.getElementById('statusText');
                        if (selectedFiles.length > 0) {
                            statusText.textContent = '已选择 ' + selectedFiles.length + ' 个文件';
                        } else {
                            statusText.textContent = '';
                        }
                    }
                    
                    function uploadFile() {
                        if (selectedFiles.length === 0) {
                            alert('请先选择要上传的文件');
                            return;
                        }
                        
                        const uploadBtn = document.getElementById('uploadBtn');
                        const cancelBtn = document.getElementById('cancelBtn');
                        const uploadArea = document.getElementById('uploadArea');
                        const progressBar = document.getElementById('progressBar');
                        const progressFill = document.getElementById('progressFill');
                        const statusText = document.getElementById('statusText');
                        
                        // 禁用上传按钮，显示取消按钮，禁用上传区域
                        uploadBtn.disabled = true;
                        uploadBtn.textContent = '上传中...';
                        cancelBtn.style.display = 'inline-block';
                        uploadArea.classList.add('disabled');
                        progressBar.classList.add('active');
                        
                        let uploadedCount = 0;
                        let currentIndex = 0;
                        
                        // 串行上传每个文件
                        function uploadNext() {
                            if (currentIndex >= selectedFiles.length) {
                                // 上传完成
                                uploadBtn.disabled = false;
                                uploadBtn.textContent = '开始上传';
                                cancelBtn.style.display = 'none';
                                uploadArea.classList.remove('disabled');
                                progressBar.classList.remove('active');
                                progressFill.style.width = '0%';
                                selectedFiles = [];
                                fileInput.value = '';
                                statusText.textContent = '上传完成！';
                                loadFiles();
                                setTimeout(() => { statusText.textContent = ''; }, 3000);
                                return;
                            }
                            
                            const file = selectedFiles[currentIndex];
                            statusText.textContent = '正在上传: ' + file.name + ' (' + (currentIndex + 1) + '/' + selectedFiles.length + ')';
                            
                            const formData = new FormData();
                            formData.append('file', file);
                            
                            xhr = new XMLHttpRequest();
                            
                            xhr.upload.addEventListener('progress', (e) => {
                                if (e.lengthComputable) {
                                    const percent = (e.loaded / e.total) * 100;
                                    progressFill.style.width = percent + '%';
                                }
                            });
                            
                            xhr.addEventListener('load', () => {
                                uploadedCount++;
                                currentIndex++;
                                uploadNext();
                            });
                            
                            xhr.addEventListener('error', () => {
                                statusText.textContent = '上传失败: ' + file.name;
                                uploadBtn.disabled = false;
                                cancelBtn.style.display = 'none';
                            });
                            
                            xhr.open('POST', '/api/upload');
                            xhr.send(formData);
                        }
                        
                        uploadNext();
                    }
                    
                    function cancelUpload() {
                        if (xhr) {
                            xhr.abort();
                            xhr = null;
                        }
                        
                        const uploadBtn = document.getElementById('uploadBtn');
                        const cancelBtn = document.getElementById('cancelBtn');
                        const uploadArea = document.getElementById('uploadArea');
                        const progressBar = document.getElementById('progressBar');
                        const progressFill = document.getElementById('progressFill');
                        const statusText = document.getElementById('statusText');
                        
                        uploadBtn.disabled = false;
                        uploadBtn.textContent = '开始上传';
                        cancelBtn.style.display = 'none';
                        uploadArea.classList.remove('disabled');
                        progressBar.classList.remove('active');
                        progressFill.style.width = '0%';
                        statusText.textContent = '已取消上传';
                        setTimeout(() => { statusText.textContent = ''; }, 2000);
                    }
                    
                    function loadFiles() {
                        fetch('/api/files')
                            .then(r => r.json())
                            .then(data => {
                                const files = data.files || [];
                                const list = document.getElementById('fileList');
                                
                                if (!files || files.length === 0) {
                                    list.innerHTML = '<li class="empty-tip">暂无文件</li>';
                                    return;
                                }
                                list.innerHTML = files.map(f => 
                                    '<li class="file-item"><a href="/api/download/' + encodeURIComponent(f.name) + '">' + f.name + '</a><span>' + (f.size/1024).toFixed(1) + ' KB</span></li>'
                                ).join('');
                            })
                            .catch(err => {
                                document.getElementById('fileList').innerHTML = '<li class="empty-tip">加载失败: ' + err.message + '</li>';
                            });
                    }
                    
                    loadFiles();
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=UTF-8",
            html
        )
    }

    private fun handleStaticResource(uri: String): Response {
        val file = File(workspace.parent, uri)
        return if (file.exists()) {
            newFixedLengthResponse(
                Response.Status.OK,
                getMimeType(uri),
                FileInputStream(file),
                file.length()
            )
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun verifyPassword(authHeader: String?, expectedPassword: String): Boolean {
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false
        try {
            val encoded = authHeader.removePrefix("Basic ")
            val decoded = String(Base64.getDecoder().decode(encoded))
            val parts = decoded.split(":")
            return parts.size == 2 && parts[1] == expectedPassword
        } catch (e: Exception) {
            return false
        }
    }

    private fun getMimeType(filename: String): String {
        return when (filename.lowercase().substringAfterLast('.')) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
