package com.example.screenshotviewer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.chrisbanes.photoview.PhotoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class ImageViewerActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.4f  // 固定字体缩放，忽略系统字体大小设置
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }


    private lateinit var imageViewPager: ViewPager2
    private lateinit var saveImageButton: Button
    private lateinit var deleteButton: Button
    private lateinit var undoButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var floatingPanel: LinearLayout
    private lateinit var pageCountTextView: TextView
    private lateinit var addTextButton: Button
    private lateinit var mergeTextButton: Button
    private lateinit var textOverlay: TextView

    private var images: List<FileItem> = emptyList()
    private var currentPosition: Int = 0
    private var baseUrl: String = ""
    private var isUIVisible: Boolean = true
    private lateinit var pagerAdapter: ImagePagerAdapter
    // 保存合并了文字的 bitmap，key 为图片路径，跨 adapter 重建后仍有效
    private val mergedBitmaps = mutableMapOf<String, Bitmap>()

    // 操作类型
    private enum class OperationType {
        DELETE, SAVE
    }

    // 操作历史记录（无限制）
    private data class OperationItem(
        val type: OperationType,
        val image: FileItem,
        val position: Int
    )
    private val operationHistory = ArrayDeque<OperationItem>()

    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 隐藏ActionBar
        supportActionBar?.hide()

        setContentView(R.layout.activity_image_viewer)

        // Get data from intent
        val imagesJson = intent.getStringExtra("IMAGES_JSON") ?: ""
        currentPosition = intent.getIntExtra("POSITION", 0)
        baseUrl = intent.getStringExtra("BASE_URL") ?: ""

        // Parse images list
        if (imagesJson.isNotEmpty()) {
            val gson = Gson()
            val type = object : TypeToken<List<FileItem>>() {}.type
            images = gson.fromJson(imagesJson, type)
        }

        initViews()
        setupViewPager()
        setupListeners()
    }

    private fun initViews() {
        imageViewPager = findViewById(R.id.imageViewPager)
        saveImageButton = findViewById(R.id.saveImageButton)
        deleteButton = findViewById(R.id.deleteButton)
        undoButton = findViewById(R.id.undoButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        floatingPanel = findViewById(R.id.floatingPanel)
        pageCountTextView = findViewById(R.id.pageCountTextView)
        addTextButton = findViewById(R.id.addTextButton)
        mergeTextButton = findViewById(R.id.mergeTextButton)
        textOverlay = findViewById(R.id.textOverlay)

        // 设置悬浮窗拖动功能
        setupDraggablePanel()
        // 设置文字覆盖层拖动功能
        setupDraggableTextOverlay()
    }

    private fun setupDraggableTextOverlay() {
        var dX = 0f
        var dY = 0f
        var isDragging = false

        val gestureDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    showTextInputDialog()
                    return true
                }
            })

        textOverlay.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    isDragging = true
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTextInputDialog() {
        val editText = EditText(this).apply {
            hint = "请输入文字（支持多行）"
            setText(textOverlay.text)
            setPadding(40, 20, 40, 20)
            minLines = 3
            maxLines = 10
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        AlertDialog.Builder(this)
            .setTitle("输入文字")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val inputText = editText.text.toString()
                if (inputText.isNotEmpty()) {
                    textOverlay.text = inputText
                    textOverlay.visibility = View.VISIBLE
                } else {
                    textOverlay.visibility = View.GONE
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清除文字") { _, _ ->
                textOverlay.text = ""
                textOverlay.visibility = View.GONE
            }
            .show()
    }

    private fun setupDraggablePanel() {
        val dragHandle = findViewById<TextView>(R.id.dragHandle)
        var dX = 0f
        var dY = 0f

        dragHandle.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = floatingPanel.x - event.rawX
                    dY = floatingPanel.y - event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    floatingPanel.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupViewPager() {
        pagerAdapter = ImagePagerAdapter(images, baseUrl) {
            // 点击图片时切换UI显示/隐藏
            toggleUI()
        }
        imageViewPager.adapter = pagerAdapter
        imageViewPager.setCurrentItem(currentPosition, false)

        // 禁用用户滑动，只允许通过按钮切换图片
        imageViewPager.isUserInputEnabled = false

        // 更新图片信息
        updateImageInfo()

        // 监听页面变化
        imageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                updateImageInfo()
            }
        })
    }

    private fun setupListeners() {
        saveImageButton.setOnClickListener {
            Toast.makeText(this, "保存按钮点击 - 位置:$currentPosition 列表大小:${images.size}", Toast.LENGTH_SHORT).show()
            if (checkStoragePermission()) {
                val currentImage = getCurrentImage()
                if (currentImage != null) {
                    markAsSaved(currentImage)
                } else {
                    Toast.makeText(this, "错误：无法获取当前图片", Toast.LENGTH_SHORT).show()
                }
            } else {
                requestStoragePermission()
            }
        }

        deleteButton.setOnClickListener {
            Toast.makeText(this, "删除按钮点击 - 位置:$currentPosition 列表大小:${images.size}", Toast.LENGTH_SHORT).show()
            val currentImage = getCurrentImage()
            if (currentImage != null) {
                markAsDeleted(currentImage)
            } else {
                Toast.makeText(this, "错误：无法获取当前图片", Toast.LENGTH_SHORT).show()
            }
        }

        undoButton.setOnClickListener {
            undoLastDelete()
        }

        prevButton.setOnClickListener {
            if (currentPosition > 0) {
                imageViewPager.setCurrentItem(currentPosition - 1, true)
            }
        }

        nextButton.setOnClickListener {
            if (currentPosition < images.size - 1) {
                imageViewPager.setCurrentItem(currentPosition + 1, true)
            }
        }

        addTextButton.setOnClickListener {
            showTextInputDialog()
        }

        mergeTextButton.setOnClickListener {
            mergeTextIntoImage()
        }
    }

    private fun updateImageInfo() {
        // 更新页码显示
        pageCountTextView.text = "${currentPosition + 1} / ${images.size}"

        // 更新按钮状态
        prevButton.isEnabled = currentPosition > 0
        nextButton.isEnabled = currentPosition < images.size - 1
        prevButton.alpha = if (currentPosition > 0) 1f else 0.3f
        nextButton.alpha = if (currentPosition < images.size - 1) 1f else 0.3f
    }

    fun toggleUI() {
        isUIVisible = !isUIVisible

        if (isUIVisible) {
            // 显示悬浮窗
            floatingPanel.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        } else {
            // 隐藏悬浮窗
            floatingPanel.animate()
                .alpha(0f)
                .setDuration(300)
                .start()
        }
    }

    private fun saveImageToLocal() {
        lifecycleScope.launch {
            try {
                val currentImage = getCurrentImage()
                if (currentImage == null) {
                    Toast.makeText(this@ImageViewerActivity, "无法获取当前图片", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                saveImageButton.isEnabled = false
                saveImageButton.text = "保存中..."

                val success = downloadImageToLocal(currentImage)
                if (success) {
                    Toast.makeText(
                        this@ImageViewerActivity,
                        "已保存到 DCIM/Screenshots/${currentImage.name}",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 保存成功后自动删除服务器上的图片
                    deleteImageAfterSave(currentImage)
                } else {
                    Toast.makeText(
                        this@ImageViewerActivity,
                        "保存失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    saveImageButton.isEnabled = true
                    saveImageButton.text = "保存到本地"
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    "保存失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                saveImageButton.isEnabled = true
                saveImageButton.text = "保存到本地"
            }
        }
    }

    private fun getCurrentImage(): FileItem? {
        return if (currentPosition >= 0 && currentPosition < images.size) images[currentPosition] else null
    }

    private suspend fun downloadImageToLocal(image: FileItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = RetrofitClient.getOkHttpClient()
                val imageUrl = "$baseUrl/stream/${image.path}"
                val request = Request.Builder()
                    .url(imageUrl)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false

                val imageBytes = response.body?.bytes() ?: return@withContext false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+: Use MediaStore API
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, image.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Screenshots")
                    }

                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
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

                    // Notify media scanner so the image appears in gallery
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

    private fun checkStoragePermission(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: Check READ_MEDIA_IMAGES permission
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10-12: No permission needed for MediaStore
                true
            }
            else -> {
                // Android 9 and below: Check WRITE_EXTERNAL_STORAGE
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun requestStoragePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+: Request READ_MEDIA_IMAGES
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                    STORAGE_PERMISSION_CODE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-12: Request WRITE_EXTERNAL_STORAGE
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
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
                val currentImage = getCurrentImage()
                if (currentImage != null) {
                    markAsSaved(currentImage)
                }
            } else {
                Toast.makeText(this, "需要存储权限才能保存图片", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 只有在真正退出时才执行待处理的操作（不是配置变化导致的暂停）
        if (isFinishing) {
            executePendingDeletes()
        }
    }

    private fun deleteImageAfterSave(image: FileItem) {
        lifecycleScope.launch {
            try {
                saveImageButton.text = "删除中..."

                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val response = apiService.deleteFile(image.path)

                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@ImageViewerActivity, "服务器图片已删除", Toast.LENGTH_SHORT).show()

                    // Remove image from list and update adapter
                    val mutableImages = images.toMutableList()
                    val position = imageViewPager.currentItem
                    mutableImages.removeAt(position)
                    images = mutableImages

                    if (images.isEmpty()) {
                        // No more images, close the viewer
                        finish()
                    } else {
                        // Update the ViewPager
                        pagerAdapter = ImagePagerAdapter(images, baseUrl) {
                            toggleUI()
                        }
                        imageViewPager.adapter = pagerAdapter
                        reapplyMergedBitmaps()
                        // Stay at same position or go to previous if we deleted the last one
                        val newPosition = if (position >= images.size) images.size - 1 else position
                        imageViewPager.setCurrentItem(newPosition, false)
                        updateImageInfo()
                    }
                } else {
                    Toast.makeText(
                        this@ImageViewerActivity,
                        "删除失败: ${response.body()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    "删除失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                saveImageButton.isEnabled = true
                saveImageButton.text = "保存到本地"
            }
        }
    }

    // 标记为保存（加入保存历史）
    private fun markAsSaved(image: FileItem) {
        val position = currentPosition

        // 添加到操作历史
        operationHistory.addLast(OperationItem(OperationType.SAVE, image, position))

        // 从当前列表中移除
        val mutableImages = images.toMutableList()
        mutableImages.removeAt(position)
        images = mutableImages

        // 更新UI
        if (images.isEmpty()) {
            // 所有图片都标记保存了，但先不关闭Activity，允许撤销
            Toast.makeText(this, "已标记保存，可点击撤销恢复", Toast.LENGTH_SHORT).show()
            deleteButton.isEnabled = false
            saveImageButton.isEnabled = false
        } else {
            // 确保按钮是启用的
            deleteButton.isEnabled = true
            saveImageButton.isEnabled = true

            // 更新ViewPager
            pagerAdapter = ImagePagerAdapter(images, baseUrl) { toggleUI() }
            imageViewPager.adapter = pagerAdapter
            reapplyMergedBitmaps()
            val newPosition = if (position >= images.size) images.size - 1 else position
            imageViewPager.setCurrentItem(newPosition, false)
            currentPosition = newPosition
            updateImageInfo()
        }

        updateUndoButton()
        Toast.makeText(this, "已标记保存，退出时执行保存操作", Toast.LENGTH_SHORT).show()
    }

    // 标记为删除（加入删除历史）
    private fun markAsDeleted(image: FileItem) {
        val position = currentPosition

        // 添加到操作历史（无限制）
        operationHistory.addLast(OperationItem(OperationType.DELETE, image, position))

        // 从当前列表中移除
        val mutableImages = images.toMutableList()
        mutableImages.removeAt(position)
        images = mutableImages

        // 更新UI
        if (images.isEmpty()) {
            // 所有图片都删除了，但先不关闭Activity，允许撤销
            Toast.makeText(this, "已标记删除，可点击撤销恢复", Toast.LENGTH_SHORT).show()
            deleteButton.isEnabled = false
            saveImageButton.isEnabled = false
        } else {
            // 确保按钮是启用的
            deleteButton.isEnabled = true
            saveImageButton.isEnabled = true

            // 更新ViewPager
            pagerAdapter = ImagePagerAdapter(images, baseUrl) { toggleUI() }
            imageViewPager.adapter = pagerAdapter
            reapplyMergedBitmaps()
            val newPosition = if (position >= images.size) images.size - 1 else position
            imageViewPager.setCurrentItem(newPosition, false)
            currentPosition = newPosition
            updateImageInfo()
        }

        updateUndoButton()
        Toast.makeText(this, "已标记删除，退出时执行删除操作", Toast.LENGTH_SHORT).show()
    }

    // 撤销最后一次操作
    private fun undoLastDelete() {
        if (operationHistory.isEmpty()) return

        val operation = operationHistory.removeLast()
        val mutableImages = images.toMutableList()

        // 恢复到原位置
        val insertPosition = minOf(operation.position, mutableImages.size)
        mutableImages.add(insertPosition, operation.image)
        images = mutableImages

        // 更新UI
        pagerAdapter = ImagePagerAdapter(images, baseUrl) { toggleUI() }
        imageViewPager.adapter = pagerAdapter
        reapplyMergedBitmaps()
        imageViewPager.setCurrentItem(insertPosition, false)
        currentPosition = insertPosition

        // 恢复按钮状态
        deleteButton.isEnabled = true
        saveImageButton.isEnabled = true

        updateImageInfo()
        updateUndoButton()

        val actionText = when (operation.type) {
            OperationType.DELETE -> "删除"
            OperationType.SAVE -> "保存"
        }
        Toast.makeText(this, "已撤销${actionText}：${operation.image.name}", Toast.LENGTH_SHORT).show()
    }

    // 更新撤销按钮状态
    private fun updateUndoButton() {
        val count = operationHistory.size
        undoButton.isEnabled = count > 0
        undoButton.alpha = if (count > 0) 1f else 0.5f
        undoButton.text = if (count > 0) "撤销($count)" else "撤销"
    }

    // 执行真正的删除操作
    private suspend fun executeDeleteAsync(image: FileItem) {
        withContext(Dispatchers.IO) {
            try {
                val apiService = RetrofitClient.getClient(baseUrl + "/")
                apiService.deleteFile(image.path)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 执行真正的保存操作
    private suspend fun executeSaveAsync(image: FileItem) {
        try {
            val success = downloadImageToLocal(image)
            if (success) {
                // 保存成功后删除服务器上的图片
                withContext(Dispatchers.IO) {
                    try {
                        val apiService = RetrofitClient.getClient(baseUrl + "/")
                        apiService.deleteFile(image.path)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 执行所有待处理的操作（保存和删除）
    private fun executePendingDeletes() {
        if (operationHistory.isEmpty()) return

        // 使用 GlobalScope 确保即使 Activity 销毁也能执行完
        GlobalScope.launch(Dispatchers.IO) {
            try {
                while (operationHistory.isNotEmpty()) {
                    val operation = operationHistory.removeFirst()
                    when (operation.type) {
                        OperationType.DELETE -> {
                            val apiService = RetrofitClient.getClient(baseUrl + "/")
                            apiService.deleteFile(operation.image.path)
                        }
                        OperationType.SAVE -> {
                            executeSaveAsync(operation.image)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun mergeTextIntoImage() {
        if (textOverlay.visibility != View.VISIBLE || textOverlay.text.isEmpty()) {
            Toast.makeText(this, "请先添加文字", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val currentImage = getCurrentImage() ?: return@launch

                mergeTextButton.isEnabled = false
                mergeTextButton.text = "合并中..."

                // 下载原图
                val imageBytes = withContext(Dispatchers.IO) {
                    val client = RetrofitClient.getOkHttpClient()
                    val imageUrl = "$baseUrl/stream/${currentImage.path}"
                    val request = Request.Builder().url(imageUrl).build()
                    val response = client.newCall(request).execute()
                    response.body?.bytes()
                }

                if (imageBytes == null) {
                    Toast.makeText(this@ImageViewerActivity, "无法加载图片", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)

                // 计算图片在 ViewPager 中的显示区域（fit-center 缩放）
                val viewW = imageViewPager.width.toFloat()
                val viewH = imageViewPager.height.toFloat()
                val imgW = mutableBitmap.width.toFloat()
                val imgH = mutableBitmap.height.toFloat()
                val scale = minOf(viewW / imgW, viewH / imgH)
                val displayW = imgW * scale
                val displayH = imgH * scale
                val offsetX = (viewW - displayW) / 2f
                val offsetY = (viewH - displayH) / 2f

                // 文字在屏幕上的位置 → 映射到原图像素坐标
                val textScreenX = textOverlay.x
                val textScreenY = textOverlay.y
                val textImgX = (textScreenX - offsetX) / scale
                val textImgY = (textScreenY - offsetY) / scale

                // 文字画笔（字体大小从屏幕像素换算到图片像素）
                val textSizeInImage = textOverlay.textSize / scale
                val textPaint = Paint().apply {
                    color = Color.RED
                    textSize = textSizeInImage
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                }

                val text = textOverlay.text.toString()
                val lineHeight = textSizeInImage * 1.2f
                text.split("\n").forEachIndexed { i, line ->
                    canvas.drawText(line, textImgX, textImgY - textPaint.ascent() + i * lineHeight, textPaint)
                }

                // 保存合并后的 bitmap（跨 adapter 重建后仍可恢复）
                mergedBitmaps[currentImage.path] = mutableBitmap

                // 立即刷新当前图片显示
                pagerAdapter.overrideBitmap(currentPosition, mutableBitmap)

                Toast.makeText(this@ImageViewerActivity, "文字已合并到图片", Toast.LENGTH_SHORT).show()
                textOverlay.visibility = View.GONE
                textOverlay.text = ""

            } catch (e: Exception) {
                Toast.makeText(this@ImageViewerActivity, "合并失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                mergeTextButton.isEnabled = true
                mergeTextButton.text = "合并文字"
            }
        }
    }

    private suspend fun saveMergedBitmap(bitmap: Bitmap, originalName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val mergedName = "merged_$originalName"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, mergedName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Screenshots")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        }
                    } ?: return@withContext false
                } else {
                    val screenshotsDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Screenshots"
                    )
                    if (!screenshotsDir.exists()) screenshotsDir.mkdirs()
                    val outputFile = File(screenshotsDir, mergedName)
                    FileOutputStream(outputFile).use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                    }
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

    // 将已合并的 bitmap 重新应用到新 adapter（撤销后重建 adapter 时调用）
    private fun reapplyMergedBitmaps() {
        images.forEachIndexed { index, item ->
            mergedBitmaps[item.path]?.let { bitmap ->
                pagerAdapter.overrideBitmap(index, bitmap)
            }
        }
    }

    private fun confirmDeleteCurrentImage() {
        val currentImage = getCurrentImage()
        if (currentImage == null) {
            Toast.makeText(this, "无法获取当前图片", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 ${currentImage.name} 吗？")
            .setPositiveButton("删除") { _, _ -> deleteCurrentImage(currentImage) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCurrentImage(image: FileItem) {
        lifecycleScope.launch {
            try {
                deleteButton.isEnabled = false
                deleteButton.text = "删除中..."

                val apiService = RetrofitClient.getClient(baseUrl + "/")
                val response = apiService.deleteFile(image.path)

                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(this@ImageViewerActivity, "删除成功", Toast.LENGTH_SHORT).show()

                    // Remove image from list and update adapter
                    val mutableImages = images.toMutableList()
                    val position = imageViewPager.currentItem
                    mutableImages.removeAt(position)
                    images = mutableImages

                    if (images.isEmpty()) {
                        // No more images, close the viewer
                        finish()
                    } else {
                        // Update the ViewPager
                        pagerAdapter = ImagePagerAdapter(images, baseUrl) {
                            toggleUI()
                        }
                        imageViewPager.adapter = pagerAdapter
                        reapplyMergedBitmaps()
                        // Stay at same position or go to previous if we deleted the last one
                        val newPosition = if (position >= images.size) images.size - 1 else position
                        imageViewPager.setCurrentItem(newPosition, false)
                        updateImageInfo()
                    }
                } else {
                    Toast.makeText(
                        this@ImageViewerActivity,
                        "删除失败: ${response.body()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@ImageViewerActivity,
                    "删除失败: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                deleteButton.isEnabled = true
                deleteButton.text = "删除"
            }
        }
    }
}
