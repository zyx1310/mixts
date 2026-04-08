package com.mixts.android.model

import kotlinx.serialization.Serializable

@Serializable
data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long
)

@Serializable
data class FileListResponse(
    val files: List<FileItem>,
    val debug: List<String> = emptyList()
)
