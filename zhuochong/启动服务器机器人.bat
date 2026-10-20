@echo off
chcp 936 >nul
REM ============================================================
REM  启动服务器上的 QQ 机器人（systemctl start zhuochong-qq）
REM  前提：本机能 ssh root@47.93.180.198（建议先跑一次 deploy\push-jar.ps1 -SetupKey 配免密）
REM ============================================================
set HOST=root@47.93.180.198
set SVC=zhuochong-qq
echo ===== 启动 %SVC% =====
ssh %HOST% "systemctl start %SVC%; sleep 8; systemctl is-active %SVC%; systemctl show -p ActiveEnterTimestamp --value %SVC%"
echo.
echo ---- /qq/status ----
ssh %HOST% "curl -s --max-time 6 http://127.0.0.1:8080/qq/status | head -c 400"
echo.
pause