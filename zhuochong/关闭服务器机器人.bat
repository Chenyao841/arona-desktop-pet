@echo off
chcp 936 >nul
REM ============================================================
REM  关闭服务器上的 QQ 机器人（systemctl stop zhuochong-qq）
REM  群里的 @ 将不再响应；重新开：双击 启动服务器机器人.bat
REM  注意：NapCat 容器不受影响（小号仍在线，只是没人回）
REM ============================================================
set HOST=root@47.93.180.198
set SVC=zhuochong-qq
echo ===== 停止 %SVC% =====
ssh %HOST% "systemctl stop %SVC%; sleep 3; systemctl is-active %SVC%"
echo.
echo 已停止。群里 @ 不会再回复。要恢复：双击 启动服务器机器人.bat
pause