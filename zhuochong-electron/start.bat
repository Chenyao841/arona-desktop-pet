@echo off
chcp 936 >nul
rem 桌面桌宠（Electron）启动器：桌面快捷方式「桌面桌宠」指向本文件
rem 注意：保持 CRLF 行尾，LF 行尾会让 cmd 读串行。
cd /d "%~dp0"
npm start
