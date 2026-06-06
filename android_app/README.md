# Screenshot Viewer Android App

这是一个 Android 应用，用于浏览、下载和删除 PC Screenshot Web Server 上的截图。

## 功能特性

- ✅ 输入服务器网址并登录
- ✅ 浏览服务器上的所有图片
- ✅ 点击图片全屏查看
- ✅ 保存图片到本地 `内部存储/DCIM/Screenshots/` 目录
- ✅ 删除服务器上的图片
- ✅ 网格布局显示图片缩略图

## 使用方法

### 1. 构建应用

在 Android Studio 中打开 `android_app` 文件夹:

1. 打开 Android Studio
2. 选择 "Open an Existing Project"
3. 选择 `android_app` 文件夹
4. 等待 Gradle 同步完成
5. 连接 Android 设备或启动模拟器
6. 点击 "Run" 按钮

### 2. 使用应用

1. **输入服务器信息**:
   - 服务器地址: `http://你的电脑IP:5000` (例如: `http://192.168.1.100:5000`)
   - 用户名: 默认 `admin`
   - 密码: 默认 `admin123`

2. **点击"连接并浏览图片"**:
   - 应用会登录服务器并加载所有图片

3. **浏览图片**:
   - 图片以网格形式显示
   - 点击图片可全屏查看
   - 点击"保存"按钮将图片保存到本地
   - 点击"删除"按钮删除服务器上的图片

4. **保存图片**:
   - 图片会保存到 `内部存储/DCIM/Screenshots/` 目录
   - 保存后可在相册中查看

## 技术栈

- **语言**: Kotlin
- **网络请求**: Retrofit + OkHttp
- **图片加载**: Glide
- **UI**: Material Design Components
- **异步处理**: Kotlin Coroutines

## 权限说明

应用需要以下权限:

- `INTERNET`: 访问网络服务器
- `WRITE_EXTERNAL_STORAGE`: 保存图片到本地存储 (Android 9 及以下)
- `READ_EXTERNAL_STORAGE`: 读取存储 (Android 12 及以下)

## 注意事项

1. **网络配置**:
   - 确保手机和电脑在同一局域网
   - 服务器地址需要使用电脑的局域网 IP，不能使用 `localhost` 或 `127.0.0.1`

2. **权限**:
   - 首次使用时需要授予存储权限
   - Android 10+ 使用了作用域存储，无需存储权限

3. **服务器配置**:
   - 确保 PC 端服务器已启动
   - 确保防火墙允许 5000 端口访问

## 目录结构

```
android_app/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/screenshotviewer/
│   │       │   ├── MainActivity.kt          # 主界面
│   │       │   ├── ImageViewerActivity.kt   # 图片查看器
│   │       │   ├── ImageAdapter.kt          # RecyclerView 适配器
│   │       │   ├── ApiService.kt            # 网络 API 接口
│   │       │   └── Models.kt                # 数据模型
│   │       ├── res/
│   │       │   └── layout/
│   │       │       ├── activity_main.xml           # 主界面布局
│   │       │       ├── activity_image_viewer.xml   # 图片查看器布局
│   │       │       └── item_image.xml              # 图片列表项布局
│   │       └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## 常见问题

### Q: 无法连接服务器?
A:
1. 检查手机和电脑是否在同一局域网
2. 检查服务器 IP 地址是否正确
3. 检查服务器是否正在运行
4. 检查防火墙是否允许访问

### Q: 保存图片失败?
A:
1. 检查是否授予了存储权限
2. 确认 DCIM/Screenshots 目录是否存在

### Q: 图片加载缓慢?
A:
1. 检查网络连接质量
2. 大图片加载需要时间，请耐心等待

## 许可

本项目仅供学习和个人使用。
