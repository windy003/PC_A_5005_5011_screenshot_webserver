package com.example.screenshotviewer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: TextInputEditText
    private lateinit var usernameInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var rememberPasswordCheckbox: CheckBox
    private lateinit var autoLoginCheckbox: CheckBox
    private lateinit var connectButton: Button
    private lateinit var sortButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var imagesRecyclerView: RecyclerView
    private lateinit var imageAdapter: ImageAdapter

    private lateinit var sharedPreferences: SharedPreferences

    private var baseUrl = ""
    private var currentImages = mutableListOf<FileItem>()
    private var sortDescending = true

    private val STORAGE_PERMISSION_CODE = 100
    private val PREFS_NAME = "ScreenshotViewerPrefs"
    private val KEY_SERVER_URL = "server_url"
    private val KEY_USERNAME = "username"
    private val KEY_PASSWORD = "password"
    private val KEY_REMEMBER_PASSWORD = "remember_password"
    private val KEY_AUTO_LOGIN = "auto_login"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        initViews()
        loadSavedCredentials()
        setupRecyclerView()
        setupListeners()
        requestStoragePermission()

        // 如果启用了自动登录，则自动连接
        if (autoLoginCheckbox.isChecked) {
            autoLogin()
        }
    }

    private fun initViews() {
        serverUrlInput = findViewById(R.id.serverUrlInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        rememberPasswordCheckbox = findViewById(R.id.rememberPasswordCheckbox)
        autoLoginCheckbox = findViewById(R.id.autoLoginCheckbox)
        connectButton = findViewById(R.id.connectButton)
        sortButton = findViewById(R.id.sortButton)
        progressBar = findViewById(R.id.progressBar)
        imagesRecyclerView = findViewById(R.id.imagesRecyclerView)
    }

    private fun loadSavedCredentials() {
        // 加载保存的服务器地址和用户名（总是加载）
        val savedUrl = sharedPreferences.getString(KEY_SERVER_URL, "http://192.168.1.100:5000")
        val savedUsername = sharedPreferences.getString(KEY_USERNAME, "admin")
        val rememberPassword = sharedPreferences.getBoolean(KEY_REMEMBER_PASSWORD, true)
        val autoLogin = sharedPreferences.getBoolean(KEY_AUTO_LOGIN, false)

        serverUrlInput.setText(savedUrl)
        usernameInput.setText(savedUsername)
        rememberPasswordCheckbox.isChecked = rememberPassword
        autoLoginCheckbox.isChecked = autoLogin

        // 如果记住密码，则加载密码
        if (rememberPassword) {
            val savedPassword = sharedPreferences.getString(KEY_PASSWORD, "admin123")
            passwordInput.setText(savedPassword)
        }
    }

    private fun saveCredentials() {
        val editor = sharedPreferences.edit()

        // 总是保存服务器地址和用户名
        editor.putString(KEY_SERVER_URL, serverUrlInput.text.toString())
        editor.putString(KEY_USERNAME, usernameInput.text.toString())
        editor.putBoolean(KEY_REMEMBER_PASSWORD, rememberPasswordCheckbox.isChecked)
        editor.putBoolean(KEY_AUTO_LOGIN, autoLoginCheckbox.isChecked)

        // 只有在勾选了"记住密码"时才保存密码
        if (rememberPasswordCheckbox.isChecked) {
            editor.putString(KEY_PASSWORD, passwordInput.text.toString())
        } else {
            editor.remove(KEY_PASSWORD)
        }

        editor.apply()
    }

    private fun autoLogin() {
        val url = serverUrlInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString().trim()

        if (url.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
            baseUrl = url.removeSuffix("/")
            loginAndLoadImages(username, password)
        }
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter(
            images = currentImages,
            baseUrl = baseUrl,
            onImageClick = { image -> openImageViewer(image) },
            onDownloadClick = { image -> downloadImage(image) },
            onDeleteClick = { image -> confirmDelete(image) }
        )
        imagesRecyclerView.layoutManager = GridLayoutManager(this, 2)
        imagesRecyclerView.adapter = imageAdapter
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            val url = serverUrlInput.text.toString().trim()
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写所有字段", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            baseUrl = url.removeSuffix("/")
            loginAndLoadImages(username, password)
        }

        sortButton.setOnClickListener {
            sortDescending = !sortDescending
            applySort()
            sortButton.text = if (sortDescending) "排序：从新到旧" else "排序：从旧到新"
        }
    }

    private fun applySort() {
        val comparator = compareBy<FileItem> { it.name }
        val sorted = if (sortDescending) {
            currentImages.sortedWith(comparator.reversed())
        } else {
            currentImages.sortedWith(comparator)
        }
        currentImages.clear()
        currentImages.addAll(sorted)
        imageAdapter.updateImages(currentImages)
    }

    private fun loginAndLoadImages(username: String, password: String) {
        lifecycleScope.launch {
            try {
                showLoading(true)

                // Login
                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val loginResponse = apiService.login(username, password)

                if (loginResponse.isSuccessful) {
                    Toast.makeText(this@MainActivity, "登录成功", Toast.LENGTH_SHORT).show()

                    // 保存凭据
                    saveCredentials()

                    // 重新创建adapter，使用正确的baseUrl
                    setupRecyclerView()

                    loadImages()
                } else {
                    Toast.makeText(this@MainActivity, "登录失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun loadImages(path: String = "") {
        withContext(Dispatchers.IO) {
            try {
                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val response = apiService.browseDirectory(path)

                if (response.isSuccessful) {
                    val html = response.body()?.string() ?: ""
                    val images = parseImagesFromHtml(html)

                    withContext(Dispatchers.Main) {
                        currentImages.clear()
                        currentImages.addAll(images)
                        applySort()
                        sortButton.visibility = if (currentImages.isNotEmpty()) View.VISIBLE else View.GONE
                        sortButton.text = if (sortDescending) "排序：从新到旧" else "排序：从旧到新"
                        Toast.makeText(
                            this@MainActivity,
                            "找到 ${images.size} 张图片",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "加载图片失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun parseImagesFromHtml(html: String): List<FileItem> {
        val images = mutableListOf<FileItem>()

        try {
            // 从 HTML 中提取图片信息
            // 查找所有 data-type="image" 的 div
            val pattern = """<div class="file-item" data-type="image">.*?src="/stream/([^"]+)".*?<a href="/view/[^"]+">([^<]+)</a>.*?<div class="file-size">([^<]+)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)

            pattern.findAll(html).forEach { matchResult ->
                try {
                    // 保留原始的URL编码路径，不要decode
                    val encodedPath = matchResult.groupValues[1]
                    val name = matchResult.groupValues[2]
                    val sizeStr = matchResult.groupValues[3]

                    // 解析文件大小
                    val size = parseSizeString(sizeStr)

                    images.add(
                        FileItem(
                            name = name,
                            is_dir = false,
                            path = encodedPath,  // 保存已编码的路径
                            file_type = "image",
                            size = size
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return images
    }

    private fun parseSizeString(sizeStr: String): Long {
        try {
            val parts = sizeStr.trim().split(" ")
            if (parts.size == 2) {
                val value = parts[0].toDouble()
                val unit = parts[1]

                return when (unit) {
                    "B" -> value.toLong()
                    "KB" -> (value * 1024).toLong()
                    "MB" -> (value * 1024 * 1024).toLong()
                    "GB" -> (value * 1024 * 1024 * 1024).toLong()
                    else -> 0L
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return 0L
    }

    private fun openImageViewer(image: FileItem) {
        val position = currentImages.indexOf(image)
        val gson = Gson()
        val imagesJson = gson.toJson(currentImages)

        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra("IMAGES_JSON", imagesJson)
            putExtra("POSITION", position)
            putExtra("BASE_URL", baseUrl)
        }
        startActivity(intent)
    }

    private fun downloadImage(image: FileItem) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                val success = downloadImageToLocal(image)
                if (success) {
                    Toast.makeText(
                        this@MainActivity,
                        "已保存到 DCIM/Screenshots/",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "保存失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private suspend fun downloadImageToLocal(image: FileItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = RetrofitClient.getOkHttpClient()
                val request = Request.Builder()
                    .url("$baseUrl/stream/${image.path}")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false

                val imageBytes = response.body?.bytes() ?: return@withContext false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: Use MediaStore API
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, image.name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Screenshots")
                    }

                    val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(imageBytes)
                        }
                    } ?: return@withContext false
                } else {
                    // Android 9 and below: Use legacy file storage
                    val screenshotsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Screenshots"
                    )
                    if (!screenshotsDir.exists()) {
                        screenshotsDir.mkdirs()
                    }

                    val outputFile = File(screenshotsDir, image.name)
                    FileOutputStream(outputFile).use { output ->
                        output.write(imageBytes)
                    }

                    // Notify media scanner
                    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    intent.data = android.net.Uri.fromFile(outputFile)
                    sendBroadcast(intent)
                }

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun confirmDelete(image: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 ${image.name} 吗?")
            .setPositiveButton("删除") { _, _ -> deleteImage(image) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteImage(image: FileItem) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val response = apiService.deleteFile(image.path)

                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@MainActivity, "删除成功", Toast.LENGTH_SHORT).show()
                    // Reload images
                    loadImages()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "删除失败: ${response.body()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "删除失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        connectButton.isEnabled = !show
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: Request READ_MEDIA_IMAGES
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                        STORAGE_PERMISSION_CODE
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10-12: No permission needed for MediaStore
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-9: Request WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        STORAGE_PERMISSION_CODE
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要存储权限才能保存图片", Toast.LENGTH_LONG).show()
            }
        }
    }
}
