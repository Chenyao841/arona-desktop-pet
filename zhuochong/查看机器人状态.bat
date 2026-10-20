@echo off
chcp 936 >nul
REM ============================================================
REM  查看服务器 QQ 机器人状态（服务 + 连接 + 今日消耗）
REM ============================================================
set HOST=root@47.93.180.198
set SVC=zhuochong-qq
echo ===== %SVC% 状态 =====
ssh %HOST% "systemctl is-active %SVC%; systemctl show -p ActiveEnterTimestamp --value %SVC%"
echo.
echo ---- /qq/status ----
ssh %HOST% "curl -s --max-time 6 http://127.0.0.1:8080/qq/status | head -c 500"
echo.
pause