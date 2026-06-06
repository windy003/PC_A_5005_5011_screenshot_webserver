package com.example.screenshotviewer

data class FileItem(
    val name: String,
    val is_dir: Boolean,
    val path: String,
    val file_type: String?,
    val size: Long
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class DeleteRequest(
    val path: String
)

data class ApiResponse(
    val success: Boolean,
    val message: String?
)
