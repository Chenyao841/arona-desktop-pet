@echo off
chcp 936 >nul
REM ============================================================
REM  阿罗娜 · QQ 机器人本地调试启动（Windows）
REM  做的事：载入「环境变量配置.bat」→ 强制打开 QQ 模块 → 起后端(8080)
REM
REM  前置条件（缺一个就连不上，日志会有 [QQ] 报错）：
REM   1) NapCat 已启动且小号扫码登录
REM   2) NapCat 的 OneBot 11「正向 WebSocket 服务器」已开，端口 3001
REM      且 host 必须是 0.0.0.0（默认 127.0.0.1 时连不进去）
REM   3) 「环境变量配置.bat」里的 ZC_QQ_WS_TOKEN 与 NapCat 里配的 token 一致
REM
REM  起来之后：在测试群里 @ 机器人 试试；自检见 http://127.0.0.1:8080/qq/status
REM ============================================================
call "%~dp0环境变量配置.bat" 2>nul
set ZC_QQ_ENABLED=true

echo ===== QQ 机器人 · 本地调试启动 =====
echo WS 地址   : %ZC_QQ_WS_URL%
echo 自身 QQ 号 : %ZC_QQ_SELF_ID%   ^(0 = 连上后自动识别^)
echo 触发方式   : %ZC_QQ_TRIGGER_MODE%   ^(只响应 @ 自己^)
echo 冷却       : 同一人 %ZC_QQ_USER_COOLDOWN% 秒内只回一次
echo 自检接口   : http://127.0.0.1:8080/qq/status
echo 分段预览   : http://127.0.0.1:8080/qq/split?text=你好。今天天气不错。
echo 关闭窗口即可停止服务
echo.
start "ZhuoChong-QQ" cmd /c "cd /d %~dp0 && mvnw spring-boot:run"
echo 等待后端启动（20 秒）...
timeout /t 20 /nobreak >nul
echo.
echo ---- /qq/status ----
curl -s http://127.0.0.1:8080/qq/status
echo.
echo.
echo 连接成功后后端日志会打印: ^[QQ^] NapCat WS 已连接 / 自动识别机器人 QQ 号
pause