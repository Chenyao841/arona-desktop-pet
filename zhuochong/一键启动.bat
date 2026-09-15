@echo off
chcp 936 >nul

REM 载入本机环境变量（数据库/工具路径改「环境变量配置.bat」一处就够）
call "%~dp0环境变量配置.bat" 2>nul
rem 一键启动：后端(8080) + 桌面桌宠(Electron)，注意保持 CRLF 行尾。
echo ===== 启动桌宠系统 =====
echo [1/2] 启动后端服务...
start "ZhuoChong-Server" cmd /c "cd /d %~dp0 && mvnw spring-boot:run"
echo 等待后端启动（15秒）...
timeout /t 15 /nobreak >nul
echo [2/2] 启动桌面桌宠...
start "ZhuoChong-Pet" cmd /c "cd /d %~dp0..\zhuochong-electron && npm start"
echo ===== 启动完成 =====
echo 后端: http://localhost:8080/桌宠管理.html
echo 桌宠: Electron 窗口已打开
pause
