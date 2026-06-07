@echo off
chcp 65001 >nul
cd /d "%~dp0"

REM 首次运行可取消下一行注释自动安装依赖
REM pip install -r requirements.txt

python manage_screenshots.py
pause
