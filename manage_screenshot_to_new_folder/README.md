# 图片定时流转脚本

按"文件修改时间(mtime)"把图片从源文件夹定时、均匀地发布到 Releasing。

## 流水线

```
Source_Folder1 ┐
               ├─(每天8:00, mtime 3~4天)→ 3-4days ─(8:00~16:00, 8h/N间隔, 按mtime顺序)→ Releasing
Source_Folder2 ┘
```

1. **每天 8:00**：从 `Source_Folder1` / `Source_Folder2` 筛出 mtime 距 8:00 在 3~4 天的图片 → 移到 `3-4days`
2. **算间隔**：数 `3-4days` 文件数 N，把 8:00~16:00（8 小时）平均分成 N 份，间隔 = 8 小时 / N
3. **逐个发布**：按 mtime 先后，每隔（8/N 小时）把图片移到 `Releasing`，16:00 前发完

`3-4days` / `Releasing` 会自动在脚本目录下创建。

## 使用

1. 安装依赖：
   ```
   pip install -r requirements.txt
   ```
2. 编辑 `.env`，填入两个源文件夹路径：
   ```
   Source_Folder1=D:\你的\目录1
   Source_Folder2=D:\你的\目录2
   ```
3. 运行（双击 `run.cmd` 或命令行）：
   ```
   python manage_screenshots.py
   ```

脚本会常驻运行、自行调度，按 `Ctrl+C` 退出。

## 说明

- 时间基准是**文件修改时间 mtime**（移动文件不会改变 mtime，因此跨文件夹后年龄连续，每张图片恰好被某一天的 8:00 命中，不漏不重）。
- 发布计划保存在 `state.json`，进程重启后会恢复未发完的计划；`last_daily_date` 防止当天重复触发。
- 目标目录若有同名文件，会自动加 `_1`、`_2` 后缀，不覆盖。
- 运行日志输出到控制台并写入 `manage_screenshots.log`。
- `.env` 中的可选参数（筛选区间、每日时间、发布窗口、轮询间隔等）见 `.env.example`。
