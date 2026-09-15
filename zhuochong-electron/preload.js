const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
    moveWindow: (dx, dy) => ipcRenderer.send('move-window', dx, dy),
    setIgnoreMouse: (ignore) => ipcRenderer.send('set-ignore-mouse', ignore),
    close: () => ipcRenderer.send('close-window'),
    minimize: () => ipcRenderer.send('minimize-window'),
    onToggleLock: (callback) => ipcRenderer.on('toggle-lock', callback),
    onToggleDialog: (callback) => ipcRenderer.on('toggle-dialog', callback),
    onForceStopMahjong: (callback) => ipcRenderer.on('mahjong-force-stop', callback),
    // 「等待后端」卡片页用：回报按钮点击 / 接收启动进度
    backendAction: (action) => ipcRenderer.send('backend-action', action),
    onBackendStatus: (callback) => ipcRenderer.on('backend-status', (event, data) => callback(data)),
    // 区域点击穿透（与「锁定」区分开）+ 主进程兜底光标位置
    setPassThrough: (ignore) => ipcRenderer.send('set-pass-through', ignore),
    onCursor: (callback) => ipcRenderer.on('pet:cursor', (event, pos) => callback(pos)),
    // 基础设置窗口大小（滑动条实时调，主进程按背景图比例缩放并记住）
    setSettingsScale: (scale) => ipcRenderer.send('set-settings-scale', scale),
    getSettingsScale: () => ipcRenderer.invoke('get-settings-scale'),
    // 打开文件夹/新窗口前，让设置窗口暂时退出置顶，免得把它盖住（重新获得焦点时自动恢复）
    yieldTopForOtherWindow: () => ipcRenderer.send('settings-yield-top'),
    // ===== 你画我猜 =====
    openPictionary: () => ipcRenderer.send('open-pictionary'),      // 桌宠页：开局时弹出画板窗口
    closePictionary: () => ipcRenderer.send('close-pictionary'),    // 桌宠页：结束时收起画板
    pictionaryEvent: (payload) => ipcRenderer.send('pictionary-event', payload),  // 画板页：猜题结果转发给桌宠页
    onPictionary: (callback) => ipcRenderer.on('pet:pictionary', (event, data) => callback(data)),
    refreshBoard: (cmd) => ipcRenderer.send('pictionary-refresh', cmd),            // 桌宠页 → 画板：刷新题目 / 让它公布答案
    onBoardRefresh: (callback) => ipcRenderer.on('board:refresh', (event, data) => callback(data)),
    // ===== 音乐播放器面板（音乐播放器.html）=====
    openMusicPanel: () => ipcRenderer.send('open-music-panel'),        // 桌宠页：播放指令后弹出
    hideMusicPanel: () => ipcRenderer.send('hide-music-panel'),        // 面板：隐藏操作面板（音乐不停）
    closeMusicPanel: () => ipcRenderer.send('close-music-panel'),      // 面板：× 关闭音乐播放器
    musicCmd: (payload) => ipcRenderer.send('music-cmd', payload),     // 面板 → 桌宠页：指令
    onMusicCmd: (callback) => ipcRenderer.on('pet:music-cmd', (event, data) => callback(data)),
    musicPanelState: (state) => ipcRenderer.send('music-panel-state', state),   // 桌宠页 → 面板：状态
    onMusicState: (callback) => ipcRenderer.on('music:state', (event, data) => callback(data)),
    musicPanelResize: (listOpen) => ipcRenderer.send('music-panel-resize', listOpen)   // 面板：▽列表展开/收起 → 窗口向下延申/收回
});
