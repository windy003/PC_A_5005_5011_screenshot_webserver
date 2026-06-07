# -*- coding: utf-8 -*-
"""
图片定时流转脚本

流水线（以"文件修改时间 mtime"为时间基准）：
  1. 每天 8:00，从 Source_Folder1 / Source_Folder2 中筛出"距 8:00 的 mtime 在 3~4 天"的图片
     -> 移到 3-4days 文件夹
  2. 统计 3-4days 中的文件数 N，把 8:00~16:00（共 8 小时）平均分成 N 份，间隔 = 8 小时 / N
  3. 按 mtime 先后顺序，每隔（8/N 小时）把 3-4days 中的图片逐个移到 Releasing

进程常驻自调度；发布计划写入 state.json，进程重启后可恢复。
"""

import os
import sys
import json
import time
import shutil
import logging
from datetime import datetime, timedelta
from pathlib import Path

try:
    from dotenv import load_dotenv
except ImportError:
    print("缺少依赖 python-dotenv，请先运行: pip install -r requirements.txt")
    sys.exit(1)


# ----------------------------------------------------------------------------
# 路径与配置
# ----------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")

# 目标文件夹（创建在脚本所在目录下）
RANGE_DIR = BASE_DIR / "3-4days"
RELEASING_DIR = BASE_DIR / "Releasing"
STATE_FILE = BASE_DIR / "state.json"
LOG_FILE = BASE_DIR / "manage_screenshots.log"

# 支持的图片扩展名
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tiff", ".heic"}


def _get_int(key, default):
    try:
        return int(os.getenv(key, str(default)))
    except (TypeError, ValueError):
        return default


def _get_float(key, default):
    try:
        return float(os.getenv(key, str(default)))
    except (TypeError, ValueError):
        return default


# 可选配置（.env 中未填则用默认值）
RANGE_MIN_DAYS = _get_float("Range_Min_Days", 3.0)             # 每日筛选下界（含）
RANGE_MAX_DAYS = _get_float("Range_Max_Days", 4.0)            # 每日筛选上界（不含）
DAILY_HOUR = _get_int("Daily_Run_Hour", 8)                    # 每日触发的小时
DAILY_MINUTE = _get_int("Daily_Run_Minute", 0)               # 每日触发的分钟
RELEASE_WINDOW_HOURS = _get_float("Release_Window_Hours", 8.0)  # 发布总窗口（8:00~16:00 = 8 小时）
POLL_INTERVAL = _get_int("Poll_Interval_Seconds", 60)        # 主循环轮询间隔（秒）

SOURCE_FOLDERS = []
for key in ("Source_Folder1", "Source_Folder2"):
    val = (os.getenv(key) or "").strip().strip('"').strip("'")
    if val:
        SOURCE_FOLDERS.append(Path(val))


# ----------------------------------------------------------------------------
# 日志
# ----------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, encoding="utf-8"),
        logging.StreamHandler(sys.stdout),
    ],
)
log = logging.getLogger("manage_screenshots")


# ----------------------------------------------------------------------------
# 工具函数
# ----------------------------------------------------------------------------
def is_image(path: Path) -> bool:
    return path.is_file() and path.suffix.lower() in IMAGE_EXTS


def file_mtime(path: Path) -> datetime:
    return datetime.fromtimestamp(path.stat().st_mtime)


def unique_dest(dest_dir: Path, name: str) -> Path:
    """目标目录已存在同名文件时，追加 _1 / _2 ... 避免覆盖。"""
    dest = dest_dir / name
    if not dest.exists():
        return dest
    stem, suffix = Path(name).stem, Path(name).suffix
    i = 1
    while True:
        cand = dest_dir / f"{stem}_{i}{suffix}"
        if not cand.exists():
            return cand
        i += 1


def safe_move(src: Path, dest_dir: Path) -> Path | None:
    """移动文件并保留 mtime（shutil.move 同卷为 rename，跨卷为 copy2+remove，均保留 mtime）。"""
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = unique_dest(dest_dir, src.name)
    try:
        shutil.move(str(src), str(dest))
        log.info("移动: %s -> %s", src, dest)
        return dest
    except Exception as e:
        log.error("移动失败 %s -> %s : %s", src, dest, e)
        return None


def ensure_dirs():
    for d in (RANGE_DIR, RELEASING_DIR):
        d.mkdir(parents=True, exist_ok=True)


# ----------------------------------------------------------------------------
# 状态持久化
# ----------------------------------------------------------------------------
def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception as e:
            log.warning("读取 state.json 失败，使用空状态: %s", e)
    return {"last_daily_date": None, "pending_releases": []}


def save_state(state: dict):
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
    except Exception as e:
        log.error("写入 state.json 失败: %s", e)


# ----------------------------------------------------------------------------
# 步骤 1 + 2：每天 8:00，Source -> 3-4days，并排定发布计划
# ----------------------------------------------------------------------------
def run_daily(now: datetime, state: dict):
    run_time = now.replace(hour=DAILY_HOUR, minute=DAILY_MINUTE, second=0, microsecond=0)

    # 1) 从源文件夹筛出 mtime 距 run_time 在 [3, 4) 天的图片，移到 3-4days
    lo = timedelta(days=RANGE_MIN_DAYS)
    hi = timedelta(days=RANGE_MAX_DAYS)
    moved = 0
    for folder in SOURCE_FOLDERS:
        if not folder.exists():
            log.warning("源文件夹不存在，跳过: %s", folder)
            continue
        for path in list(folder.iterdir()):
            if not is_image(path):
                continue
            age = run_time - file_mtime(path)
            if lo <= age < hi:
                if safe_move(path, RANGE_DIR):
                    moved += 1
    log.info("每日筛选: %d 张图片移入 3-4days", moved)

    # 2) 统计 3-4days，按 mtime 升序排定 8:00~16:00 的发布时间表
    files = sorted([p for p in RANGE_DIR.iterdir() if is_image(p)], key=lambda p: p.stat().st_mtime)
    n = len(files)
    if n == 0:
        log.info("3-4days 为空，本日无发布计划")
        state["pending_releases"] = []
        return

    interval_hours = RELEASE_WINDOW_HOURS / n
    log.info("3-4days 共 %d 张，发布间隔 = %.4f 小时（%.1f 分钟）",
             n, interval_hours, interval_hours * 60)

    pending = []
    for i, path in enumerate(files):
        due = run_time + timedelta(hours=interval_hours * i)
        pending.append({"filename": path.name, "due": due.isoformat()})
    state["pending_releases"] = pending
    log.info("已排定发布计划：%s ~ %s", pending[0]["due"], pending[-1]["due"])


# ----------------------------------------------------------------------------
# 步骤 3：到点把 3-4days 中的图片移到 Releasing
# ----------------------------------------------------------------------------
def process_releases(now: datetime, state: dict):
    pending = state.get("pending_releases", [])
    if not pending:
        return
    remaining = []
    for item in pending:
        try:
            due = datetime.fromisoformat(item["due"])
        except Exception:
            continue
        if due <= now:
            src = RANGE_DIR / item["filename"]
            if src.exists():
                log.info("到点发布 -> Releasing: %s", item["filename"])
                safe_move(src, RELEASING_DIR)
            else:
                log.warning("待发布文件已不存在，跳过: %s", item["filename"])
        else:
            remaining.append(item)
    if len(remaining) != len(pending):
        state["pending_releases"] = remaining


# ----------------------------------------------------------------------------
# 主循环
# ----------------------------------------------------------------------------
def main():
    log.info("=" * 60)
    log.info("图片定时流转脚本启动")
    log.info("源文件夹: %s", [str(p) for p in SOURCE_FOLDERS] or "(未配置！请编辑 .env)")
    log.info("每天 %02d:%02d 筛选 mtime 在 %.1f~%.1f 天的图片，%.1f 小时内均匀发布",
             DAILY_HOUR, DAILY_MINUTE, RANGE_MIN_DAYS, RANGE_MAX_DAYS, RELEASE_WINDOW_HOURS)
    log.info("轮询间隔 %d 秒", POLL_INTERVAL)
    log.info("=" * 60)

    if not SOURCE_FOLDERS:
        log.warning("未在 .env 中配置 Source_Folder1 / Source_Folder2，将不会移动任何文件。")

    ensure_dirs()
    state = load_state()

    while True:
        try:
            now = datetime.now()
            today_str = now.date().isoformat()

            # 步骤 1+2：到达每日触发时刻且当天尚未执行过
            run_time = now.replace(hour=DAILY_HOUR, minute=DAILY_MINUTE, second=0, microsecond=0)
            if now >= run_time and state.get("last_daily_date") != today_str:
                log.info("触发每日处理（%s）", today_str)
                run_daily(now, state)
                state["last_daily_date"] = today_str
                save_state(state)

            # 步骤 3
            process_releases(now, state)
            save_state(state)

        except Exception as e:
            log.exception("主循环异常: %s", e)

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        log.info("收到中断信号，退出。")
