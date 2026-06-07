package com.example.screenshotviewer

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 小部件配置界面：放置小部件时弹出，也可用于重新配置。
 * 可自定义名称、服务器地址、统计路径。
 */
class CountWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认取消，用户未保存就退出则不添加小部件
        setResult(RESULT_CANCELED)
        setContentView(R.layout.widget_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val nameInput = findViewById<EditText>(R.id.cfgNameInput)
        val urlInput = findViewById<EditText>(R.id.cfgUrlInput)
        val pathInput = findViewById<EditText>(R.id.cfgPathInput)
        val saveButton = findViewById<Button>(R.id.cfgSaveButton)

        val prefs = getSharedPreferences(CountWidgetProvider.PREFS, Context.MODE_PRIVATE)
        val mainPrefs = getSharedPreferences(CountWidgetProvider.MAIN_PREFS, Context.MODE_PRIVATE)

        // 判断该小部件属于哪个站点（small / large），用于预设默认名称与路径
        val providerName = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)?.provider?.className ?: ""
        val site = if (providerName.contains("Large", ignoreCase = true)) "large" else "small"

        // 预填：已有配置优先，否则按站点给默认值
        nameInput.setText(prefs.getString(CountWidgetProvider.keyName(appWidgetId), site))
        urlInput.setText(
            prefs.getString(
                CountWidgetProvider.keyUrl(appWidgetId),
                originOf(mainPrefs.getString("server_url", null))
            )
        )
        pathInput.setText(
            prefs.getString(CountWidgetProvider.keyPath(appWidgetId), "$site/browse/releasing")
        )

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            val path = pathInput.text.toString().trim()

            if (url.isEmpty()) {
                Toast.makeText(this, "请填写服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(CountWidgetProvider.keyName(appWidgetId), name)
                .putString(CountWidgetProvider.keyUrl(appWidgetId), url)
                .putString(CountWidgetProvider.keyPath(appWidgetId), path)
                .apply()

            val mgr = AppWidgetManager.getInstance(this)
            CountWidgetProvider.updateWidget(this, mgr, appWidgetId)  // 立即用缓存显示
            CountWidgetProvider.requestRefresh(this, appWidgetId)     // 后台拉取最新数量

            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
            finish()
        }
    }

    /** 从完整地址中提取出 协议+主机+端口，例如
     *  http://192.168.1.100:5005/small/browse/releasing -> http://192.168.1.100:5005 */
    private fun originOf(url: String?): String {
        val def = "http://192.168.1.100:5005"
        if (url.isNullOrBlank()) return def
        val i = url.indexOf("://")
        if (i == -1) return def
        val after = url.substring(i + 3)
        val fs = after.indexOf('/')
        return if (fs == -1) url else url.substring(0, i + 3 + fs)
    }
}

