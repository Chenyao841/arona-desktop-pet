@echo off
REM ============================================================
REM  阿罗娜桌宠 · 环境变量配置（改这一处就够）
REM  用法：双击 一键启动.bat / 启动后端.bat 时会自动 call 本文件；
REM        也可以在命令行里先 call 一次，再自行启动后端（IDEA 里启动的话
REM        请把同样的值填到 application.yml 的 zc.db.* 或系统环境变量里）。
REM  注意：本文件必须保存为 GBK/ANSI + CRLF，否则中文会乱码。
REM ============================================================

REM ---------- 数据库（必改）----------
set ZC_DB_HOST=127.0.0.1
set ZC_DB_PORT=3306
set ZC_DB_NAME=db03
set ZC_DB_USER=root
set ZC_DB_PASSWORD=chenyao841

REM ---------- 本地工具路径（可选，用不到就留空）----------
REM GPT-SoVITS 的 python.exe 与其工作目录（要语音才需要）
set ZC_PYTHON=D:\BaiduNetdiskDownload\GPT-SoVITS\GPT-SoVITS-v2pro-20250604\GPT-SoVITS-v2pro-20250604\runtime\python.exe
set ZC_TTS_DIR=D:\BaiduNetdiskDownload\GPT-SoVITS\GPT-SoVITS-v2pro-20250604\GPT-SoVITS-v2pro-20250604
set ZC_TTS_AUTOSTART=true

REM 网易云音乐 API（要放歌才需要：node.exe 与 app.js）
set ZC_NODE=C:\Users\陈曜\scoop\apps\nodejs\current\node.exe
set ZC_NETEASE_APP=D:\NeteaseCloudMusicApi\app.js
set ZC_NETEASE_AUTOSTART=true

REM ---------- B站直播间（要弹幕才需要）----------
set ZC_BILI_ROOM_ID=4198805

REM ---------- QQ 机器人（NapCat 正向 WS / OneBot 11；不用就保持 ZC_QQ_ENABLED=false）----------
REM 云端 NapCat 的 3001 正向 WS 已开；token 见服务器 ~/notes.txt（本地测试填 NapCat WebUI 里配的 token）
set ZC_QQ_ENABLED=false
set ZC_QQ_WS_URL=ws://127.0.0.1:3001
set ZC_QQ_WS_TOKEN=
set ZC_QQ_SELF_ID=0
REM 只理 @ 自己；同一人 60s 只回一次；群白名单留空=不限（想更安全就填测试群号，逗号分隔）
set ZC_QQ_TRIGGER_MODE=at
set ZC_QQ_USER_COOLDOWN=60
set ZC_QQ_GROUP_WHITELIST=
REM P1：表情包 / 看图（素材在 src\main\resources\static\qq_emoji\，标注见 emoji_map.json）
set ZC_QQ_EMOJI=true
REM 表情包：中间至少隔几条回复才允许再发一张（默认 5；0=不限间隔）
set ZC_QQ_EMOJI_GAP=5
REM 哪些情绪算「情绪变化较大」（逗号分隔），不在集合里的情绪不发表情
set ZC_QQ_EMOJI_STRONG=激动,害羞,生气,慌张,难过,开心,震惊,俏皮
set ZC_QQ_VISION=true
REM ---------- P3：管理员 / 记忆隔离 / 额度 ----------
REM 管理员 QQ（逗号分隔）：只有这些人能私聊机器人、能用 /status /groups /usage 等管理指令
set ZC_QQ_ADMIN_QQ=
REM 私聊只接待管理员（true；改成 false 就谁私聊都回，不推荐）
set ZC_QQ_ADMIN_ONLY_PRIVATE=true
REM 记忆隔离：group（每群一份）/ user（每群每人一份）/ off（与桌宠共用，不推荐）
set ZC_QQ_MEMORY_SCOPE=group
REM 每 N 条群消息后台提炼一次长期记忆（0=不自动提炼）
set ZC_QQ_MEMORY_EVERY=20
REM 每日 token 上限（0=不限；只统计 QQ 群，不含桌宠端）
set ZC_QQ_DAILY_TOKENS=0
REM ---------- P4：回复节奏 / 恶意@防护 / 引用回复 ----------
REM 收到 @ 后至少等几秒再回（想得快也不提前说；0=立刻回）
set ZC_QQ_REPLY_DELAY=3
REM 延迟窗口内同一人连续 @ 到这个次数就拉黑（只有管理员能 /unban）
set ZC_QQ_BLACKLIST_BURST=4
REM 引用回复（回复带"回复 XX：…"引用框）
set ZC_QQ_QUOTE=true
REM 额度用尽时对被 @ 的固定说明
set ZC_QQ_QUOTA_REPLY=今天我说话太多啦，额度用完了……管理员充值后我就回来～
REM 同时允许几条消息在思考
set ZC_QQ_AI_THREADS=4

exit /b 0
