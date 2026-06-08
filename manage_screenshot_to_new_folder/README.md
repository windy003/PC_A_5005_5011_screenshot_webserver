# 图片定时流转脚本

按"文件修改时间(mtime)"把图片从源文件夹定时、均匀地发布出去。**每个源文件夹独立处理**，
生成的 `3-4days` / `Releasing` 子文件夹都在各自的源文件夹内（不在本项目目录）。

## 流水线（对每个源文件夹独立执行）

```
<Source_Folder>/  ─(每天8:00, mtime 3~4天)→  <Source_Folder>/3-4days  ─(8:00~16:00, 8h/N间隔, 按mtime顺序)→  <Source_Folder>/Releasing
```

1. **每天 8:00**：扫描源文件夹顶层，筛出 mtime 距 8:00 在 3~4 天的图片 → 移到该源下的 `3-4days`
2. **算间隔**：数该源 `3-4days` 文件数 N，把 8:00~16:00（8 小时）平均分成 N 份，间隔 = 8 小时 / N
3. **逐个发布**：按 mtime 先后，每隔（8/N 小时）把图片移到该源下的 `Releasing`，16:00 前发完

`3-4days` / `Releasing` 会自动在每个源文件夹内创建。扫描只看源文件夹**顶层**（不递归），所以已经移进子文件夹的图片不会被重复处理。

## 使用

1. 安装依赖：
   ```
   pip install -r requirements.txt
   ```
2. 编辑 `.env`，填入两个源文件夹路径：
   ```
   Source_Folder1=C:\...\Small_Screenshots
   Source_Folder2=C:\...\Large_Screenshots
   ```
3. 运行（双击 `manage_screenshots.cmd` 或命令行）：
   ```
   python manage_screenshots.py
   ```

脚本会常驻运行、自行调度，按 `Ctrl+C` 退出。

## 说明

- 时间基准是**文件修改时间 mtime**（移动文件不改变 mtime，每张图片恰好被某一天的 8:00 命中，不漏不重）。
- 发布计划保存在 `state.json`（含每个文件所属的源），进程重启后会恢复未发完的计划；`last_daily_date` 防止当天重复触发。
- 目标目录若有同名文件，会自动加 `_1`、`_2` 后缀，不覆盖。
- 运行日志输出到控制台并写入 `manage_screenshots.log`。
- `.env` 中的可选参数（筛选区间、每日时间、发布窗口、轮询间隔等）见 `.env.example`。
