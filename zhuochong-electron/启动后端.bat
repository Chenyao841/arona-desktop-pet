@echo off
chcp 936 >nul

REM 载入本机环境变量（数据库/工具路径改「环境变量配置.bat」一处就够）
call "%~dp0..\zhuochong\环境变量配置.bat" 2>nul
rem ============================================================
rem  桌宠后端（Spring Boot，端口 8080）启动器
rem  由桌面桌宠的托盘菜单/启动提醒调用，也可以直接双击运行。
rem  注意：本文件保持 CRLF 行尾 + GBK 编码，否则 cmd 会读串行。
rem ============================================================
title 桌宠后端服务 - http://localhost:8080
cd /d "%~dp0..\zhuochong"

echo ===== 启动桌宠后端（mvnw spring-boot:run） =====
echo 工作目录: %CD%
echo 首次编译较慢（约 30 秒 ~ 2 分钟），起好后这个窗口会持续打印日志。
echo 关闭本窗口即可停止后端。
echo.
call mvnw.cmd spring-boot:run

echo.
echo ===== 后端已退出（退出码 %ERRORLEVEL%） =====
pause
