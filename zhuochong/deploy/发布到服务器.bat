@echo off
chcp 936 >nul
REM ============================================================
REM  阿罗娜 · 一键发布到云服务器
REM    1) git pull 最新代码   2) 本地打包(-Pcloud)
REM    3) scp 上传            4) 切换 jar + 重启 + 体检
REM  首次使用请先跑一次：deploy\push-jar.ps1 -SetupKey（配好免密，之后不再输密码）
REM  改版本号（顺带改机器人 QQ 名）：发布到服务器.bat -NickVersion 0.0.7
REM ============================================================
cd /d %~dp0..
echo ===== 一键发布 =====
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-jar.ps1" %*
echo.
echo ===== 结束（窗口保留，看清结果再关）=====
pause