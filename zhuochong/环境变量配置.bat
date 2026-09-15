@echo off
REM ============================================================
REM  ���������� �� �����������ã�����һ���͹���
REM  �÷���˫�� һ�����.bat / ������.bat ʱ���Զ� call ���ļ���
REM        Ҳ���������������� call һ�Σ������������ˣ�IDEA ������Ļ�
REM        ���ͬ����ֵ� application.yml �� zc.db.* ��ϵͳ�����������
REM  ע�⣺���ļ����뱣��Ϊ GBK/ANSI + CRLF���������Ļ����롣
REM ============================================================

REM ---------- ���ݿ⣨�ظģ�----------
set ZC_DB_HOST=127.0.0.1
set ZC_DB_PORT=3306
set ZC_DB_NAME=db03
set ZC_DB_USER=root
set ZC_DB_PASSWORD=CHANGE_ME_DB_PASSWORD

REM ---------- ���ع���·������ѡ���ò�������գ�----------
REM GPT-SoVITS �� python.exe ���乤��Ŀ¼��Ҫ�������Ҫ��
set ZC_PYTHON=PUT_YOUR_PATH_HERE
set ZC_TTS_DIR=PUT_YOUR_PATH_HERE
set ZC_TTS_AUTOSTART=true

REM ���������� API��Ҫ�Ÿ����Ҫ��node.exe �� app.js��
set ZC_NODE=用户目录\YOUR_NAME\scoop\apps\nodejs\current\node.exe
set ZC_NETEASE_APP=D:\NeteaseCloudMusicApi\app.js
set ZC_NETEASE_AUTOSTART=true

REM ---------- Bվֱ���䣨Ҫ��Ļ����Ҫ��----------
set ZC_BILI_ROOM_ID=4198805

exit /b 0
