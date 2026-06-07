# 文件共享服务器（多站点合并版）

将原来的 `5011-Normal_Phone` 和 `5005-FF` 两个项目合并为**一个项目、一个端口**，
通过不同的 URL 路径访问不同的共享目录。

## 站点对应关系

| URL 路径 | 配置项（.env） | 原项目 |
|----------|----------------|--------|
| `/small` | `Folder_Small` | 5011-Normal_Phone |
| `/large` | `Folder_Large` | 5005-FF |

## 访问地址

服务器启动后（默认端口 5005）：

- 站点选择首页：`http://<IP>:5005/`
- 小图站点：`http://<IP>:5005/small`
- 大图站点：`http://<IP>:5005/large`

## 配置

编辑 `.env` 文件：

```
Folder_Small=小图站点的共享目录
Folder_Large=大图站点的共享目录
USERNAME=登录用户名
PASSWORD=登录密码
PORT=5005
```

新增站点：在 `app.py` 的 `SITES` 字典里再加一项，并在 `.env` 中配置对应目录即可。

## 图片数量统计 API（供安卓小部件使用）

在任意 browse 目录路径后加 `/count_api`，即可获取该目录的图片数量。
**此接口免登录**，方便小部件直接拉取。

| URL | 说明 |
|-----|------|
| `/small/browse/releasing/count_api` | small 站点 `releasing` 目录的图片数 |
| `/large/browse/releasing/count_api` | large 站点 `releasing` 目录的图片数 |
| `/small/count_api` | small 站点根目录的图片数 |
| `...count_api?recursive=1` | 递归统计（含所有子目录） |

返回 JSON：

```json
{
  "success": true,
  "site": "small",
  "path": "releasing",
  "recursive": false,
  "count": 42
}
```

安卓小部件只需 GET 该 URL，解析 `count` 字段显示即可。

> 安全说明：该接口仅返回数字，不暴露文件内容；但在局域网内任何人都可访问。
> 如需加访问控制，可告知我加一个 token 校验（如 `?token=xxx`）。

## 运行

```
pip install -r requirements.txt
python app.py
```

或双击 `run_screenshots_webserver.cmd`（后台静默运行）。
