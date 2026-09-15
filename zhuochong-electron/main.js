const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, screen, globalShortcut, dialog } = require('electron');
const path = require('path');
const http = require('http');
const fs = require('fs');
const { spawn } = require('child_process');
app.commandLine.appendSwitch('disable-gpu-cache');

// 控制台日志一律用 ASCII：npm start 那个窗口是 GBK 码页(936)，
// 写中文 console.log 会显示成「鍖哄煙绌块€?」这种乱码。
const DEBUG_PASS = false;   // 排查「点击穿透 / 光标兜底」问题时改成 true

let win, tray;
let isLocked = false; // 锁定状态，与渲染进程同步

// 应用图标（托盘 + 各窗口）：工作区「图标」文件夹里的 PNG
// 原先是 nativeImage.createEmpty() → 右下角托盘是空白占位，这里换成阿罗娜头像
const ICON_PNG = path.join(__dirname, '..', '图标', 'aluona3.png');        // 1000×1000，窗口/任务栏用
const TRAY_PNG = path.join(__dirname, '..', '图标', '托盘.png');            // 32×32，系统托盘专用（小尺寸才清晰）
function loadIcon(p) {
    try {
        const img = nativeImage.createFromPath(p);
        if (!img.isEmpty()) return img;
    } catch (e) { console.log('[MAIN] icon load failed ' + p + ': ' + e.message); }
    return null;
}
function appIcon() { return loadIcon(ICON_PNG) || nativeImage.createEmpty(); }
function trayIcon() { return loadIcon(TRAY_PNG) || appIcon(); }

// ============================================================
//  单实例检测（重复打开时不再多开一个进程）
//  背景：以前重复双击会再起一个 Electron，控制端口 3081 被占用先报一次错，
//        然后还是冒出一个新窗口。现在第二个进程直接退出，由已在运行的
//        那个进程弹提醒并把桌宠窗口叫到最前面。
// ============================================================
if (!app.requestSingleInstanceLock()) {
    app.quit();
    // CommonJS 模块顶层 return：后面的启动代码全部不执行（不建窗口、不监听 3081）
    return;
}
app.on('second-instance', () => remindAlreadyRunning());

// ============================================================
//  去掉窗口顶部的默认菜单栏（File / Edit / View / Window / Help）
//  设置页里用 window.open 打开的「区域标注工具」等页面，Electron 会给新窗口
//  装上默认应用菜单，顶部就多一行 File/Edit。这里对所有窗口统一隐藏菜单栏
//  （只隐藏、不 removeMenu：Ctrl+C/V、F12 等快捷键仍然有效）。
// ============================================================
app.on('browser-window-created', (event, w) => {
    try { w.setMenuBarVisibility(false); } catch (e) { /* 忽略 */ }
});
// window.open 出来的新窗口：设置页要换成本项目的「无边框方形 + 背景图」窗口，
// 其它页面（标注工具 / 操作册 / 管理页）保持普通窗口，只补图标 + 隐藏菜单栏
app.on('web-contents-created', (event, contents) => {
    try {
        contents.setWindowOpenHandler(({ url }) => {
            // 从基础设置窗口里打开的新窗口（区域标注工具 / 操作册 / 语音调试页）同样会被它的
            // 置顶挡住 → 先让设置窗口退出置顶，新窗口就能正常显示在前（点回设置窗口自动恢复）
            if (settingsWinRef && !settingsWinRef.isDestroyed() && contents === settingsWinRef.webContents) {
                yieldSettingsTop(settingsWinRef);
            }
            if (url && url.indexOf('桌宠设置.html') >= 0) {
                return { action: 'allow', overrideBrowserWindowOptions: settingsWindowOptions() };
            }
            return {
                action: 'allow',
                overrideBrowserWindowOptions: { autoHideMenuBar: true, icon: appIcon() }
            };
        });
    } catch (e) { /* 忽略 */ }
});

function remindAlreadyRunning() {
    if (win && !win.isDestroyed()) {
        if (!win.isVisible()) win.show();
        win.setAlwaysOnTop(true, 'screen-saver');
        win.focus();
    }
    balloon('桌面桌宠已经在运行', '不用重复打开啦，已经把这个窗口叫到前面了～');
    // 注意：桌宠窗口是置顶的，原生对话框会被它盖住，必须走 safeDialog 临时降置顶
    safeDialog({
        type: 'info',
        title: '桌面桌宠已经在运行',
        message: '阿罗娜已经在桌面上了～',
        detail: '不用再开一个。已把她的窗口叫到最前面，重复的启动请求已经忽略。',
        buttons: ['知道啦'],
        noLink: true
    });
}

// 托盘气泡提醒（失败也不影响主流程）
function balloon(title, content) {
    try {
        if (tray) tray.displayBalloon({ title: title, content: content });
    } catch (e) { /* 系统不支持气泡通知时忽略 */ }
}

// ============================================================
//  后端（Spring Boot，8080）检测与一键启动
// ============================================================
const PET_URL = 'http://localhost:8080/桌宠桌面版.html';
const BACKEND_DIR = path.join(__dirname, '..', 'zhuochong');
const BACKEND_BAT = path.join(__dirname, '启动后端.bat');
const BACKEND_HIDDEN_VBS = path.join(__dirname, '启动后端-隐藏.vbs');
const BACKEND_STOP_PS1 = path.join(__dirname, '停止后端.ps1');
const LOG_DIR = path.join(__dirname, 'logs');
let backendBooting = false;
let showingWaitPage = false;
let waitAbandoned = false;   // 用户在卡片上点了 ✕ / 先关掉 → 本轮不再自动等待


// 后端是否活着：有任意 HTTP 响应就算活着（401/403 也说明服务在跑），
// 只有连不上才算没启动。
// 注意必须用 127.0.0.1：Node 解析 localhost 可能先试 IPv6(::1)，而后端只监听 IPv4。
function checkBackend(timeoutMs) {
    return new Promise((resolve) => {
        const req = http.get({
            host: '127.0.0.1', port: 8080, path: '/basicinfo',
            timeout: timeoutMs || 2000
        }, (res) => { res.resume(); resolve(true); });
        req.on('timeout', () => { req.destroy(); resolve(false); });
        req.on('error', () => resolve(false));
    });
}

// 拉起后端：走「启动后端-隐藏.vbs」（WScript.Shell.Run 窗口样式 0 = 完全隐藏），
// 输出重定向到 logs\backend.log，所以启动时不会冒命令提示符窗口。
// 为什么不直接 spawn 批处理 / 用 PowerShell Start-Process：
//   ① 直接从 Electron spawn 批处理会继承「无控制台」状态，日志窗口可能根本不显示；
//   ② PowerShell Start-Process 会弹出一个可见的命令行窗口 —— 老师要求启动时不显现。
// 想看着命令行窗口调试时，手动双击「启动后端.bat」即可。
function startBackend() {
    backendBooting = true;
    try {
        const child = spawn('wscript.exe', [BACKEND_HIDDEN_VBS], { stdio: 'ignore', windowsHide: true });
        child.on('error', (e) => console.log('[MAIN] start backend failed: ' + e.message));
        console.log('[MAIN] backend start requested (hidden): ' + BACKEND_HIDDEN_VBS);
        return true;
    } catch (e) {
        console.log('[MAIN] start backend exception: ' + e.message);
        return false;
    }
}

// 轮询等待后端起来
async function waitBackendUp(maxMs) {
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs) {
        if (await checkBackend(2000)) return true;
        await new Promise((r) => setTimeout(r, 2000));
    }
    return false;
}

// 轮询等待后端下去（端口释放）
async function waitBackendDown(maxMs) {
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs) {
        if (!(await checkBackend(1500))) return true;
        await new Promise((r) => setTimeout(r, 1000));
    }
    return false;
}

// 停掉后端：脚本会杀「监听 8080 的进程」+「跑 启动后端.bat 的命令行窗口」
// （后者连同它的 Maven/Java 子进程一起结束，这样日志窗口不会卡在 pause）
function stopBackend() {
    try {
        const child = spawn('powershell.exe',
            ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', BACKEND_STOP_PS1],
            { stdio: 'ignore', windowsHide: true });
        child.on('error', (e) => console.log('[MAIN] stop backend failed: ' + e.message));
        console.log('[MAIN] backend stop requested');
        return true;
    } catch (e) {
        console.log('[MAIN] stop backend exception: ' + e.message);
        return false;
    }
}

// ============================================================
//  「后端没起来」时的等待卡片
//  为什么不用原生对话框询问：桌宠窗口是全屏 + 置顶（screen-saver 层级），
//  原生 dialog 压不过它，会被它盖住、按钮点不到。所以：
//    1) 询问按钮直接做在卡片页面里（卡片自己就是置顶窗口，一定点得到）；
//    2) 等待期间把窗口缩成一张小卡片（可拖动、带 ✕），不再全屏挡住桌面，
//       也就能单独把它关掉。
// ============================================================
const WAIT_PAGE = path.join(__dirname, '等待后端.html');
const CARD_W = 470, CARD_H = 210;
let fullBounds = null;      // 桌宠正常状态下的窗口尺寸（全屏透明覆盖层）
let cardLoaded = false;     // 等待卡片页是否已经加载进窗口

// 把状态推给卡片页（页面可能还在加载，得等 did-finish-load）
function sendBackendStatus(payload) {
    if (!win || win.isDestroyed()) return;
    if (win.webContents.isLoading()) {
        win.webContents.once('did-finish-load', () => {
            if (win && !win.isDestroyed()) win.webContents.send('backend-status', payload);
        });
    } else {
        win.webContents.send('backend-status', payload);
    }
}

// 切到「等待卡片」：窗口缩成小卡片、贴屏幕右下角、可点可拖
function showCard(state) {
    if (!win || win.isDestroyed()) return;
    const area = screen.getPrimaryDisplay().workArea;
    if (!fullBounds) fullBounds = { x: area.x, y: area.y, width: area.width, height: area.height };
    showingWaitPage = true;
    isLocked = false;
    passThrough = false;
    win.setIgnoreMouseEvents(false, { forward: true });   // 卡片上的按钮必须点得到
    const margin = 16;                                    // 离屏幕边角的留白
    win.setBounds({
        x: Math.round(area.x + area.width - CARD_W - margin),
        y: Math.round(area.y + area.height - CARD_H - margin),
        width: CARD_W, height: CARD_H
    });
    win.setAlwaysOnTop(true, 'screen-saver');
    if (!win.isVisible()) win.show();
    if (!cardLoaded) {
        cardLoaded = true;
        win.loadFile(WAIT_PAGE);   // 页面上有三按钮：一键启动后端 / 我自己启动(IDEA) / 先关掉，右上角另有 ✕
    }
    if (state) sendBackendStatus({ state: state });
}

// 切回真正的桌宠页，并把窗口恢复成全屏覆盖层
function showPetPage() {
    showingWaitPage = false;
    cardLoaded = false;
    if (!win || win.isDestroyed()) return;
    passThrough = false;
    if (fullBounds) win.setBounds(fullBounds);
    win.setIgnoreMouseEvents(false, { forward: true });
    win.setAlwaysOnTop(true, 'screen-saver');
    if (!win.isVisible()) win.show();
    win.loadURL(PET_URL);
}

// 原生对话框会被置顶的桌宠窗口盖住，这里临时把置顶降下来
function safeDialog(options) {
    const wasTop = win && !win.isDestroyed() && win.isAlwaysOnTop();
    if (wasTop) win.setAlwaysOnTop(false);
    const p = dialog.showMessageBox(options);
    if (wasTop) p.finally(() => { if (win && !win.isDestroyed()) win.setAlwaysOnTop(true, 'screen-saver'); });
    return p;
}

// 启动流程：后端在 → 直接加载桌宠页；不在 → 显示等待卡片（按钮在卡片上，不弹原生对话框）
async function bootPetPage() {
    if (await checkBackend(2500)) {
        showPetPage();
        return;
    }
    showCard('down');
    // 由「重启后端 + 桌面桌宠」带起来的新进程（带 --wait-backend）：后端已经在被旧进程拉起了，
    // 这里**只等**（不再 spawn 一次启动脚本，否则两个 mvn 抢 8080 端口），起来了自动切回桌宠页
    if (process.argv.indexOf('--wait-backend') >= 0) {
        console.log('[MAIN] --wait-backend: waiting for backend to come up');
        backendBooting = true;
        waitAbandoned = false;
        sendBackendStatus({ state: 'starting' });
        finishWaiting(waitBackendUp(180000));
    }
}

// 一键启动后端：拉起命令行窗口 → 轮询等待 → 起来后自动切回桌宠页
async function startBackendAndWait() {
    if (backendBooting) return;
    backendBooting = true;
    waitAbandoned = false;
    sendBackendStatus({ state: 'starting' });
    balloon('正在启动后端', '命令行窗口会打印日志，首次大约 30 秒 ~ 2 分钟。');
    if (!startBackend()) {
        backendBooting = false;
        sendBackendStatus({ state: 'timeout' });
        safeDialog({
            type: 'error', title: '启动失败', message: '没能拉起后端启动脚本',
            detail: '请手动双击运行：' + BACKEND_BAT, buttons: ['知道了'], noLink: true
        });
        return;
    }
    await finishWaiting(waitBackendUp(180000));
}

// 用户自己在 IDEA 启动：只等，不拉脚本
async function waitForBackendOnly() {
    waitAbandoned = false;
    sendBackendStatus({ state: 'waiting' });
    balloon('正在等待后端', '后端起来后桌宠会自动出现～');
    await finishWaiting(waitBackendUp(180000));
}

async function finishWaiting(promise) {
    const ok = await promise;
    backendBooting = false;   // 复位，否则超时后托盘菜单会被「正在启动中」一直挡住
    if (waitAbandoned) return;   // 用户已经点 ✕ 关掉等待 → 不再自动弹桌宠
    if (ok) {
        if (win && !win.isDestroyed()) win.show();
        showPetPage();
        balloon('后端已就绪', '桌宠马上就好～');
    } else {
        if (win && !win.isDestroyed() && !win.isVisible()) win.show();  // 卡片被关掉过也要让它露出来
        sendBackendStatus({ state: 'timeout' });
    }
}

// 卡片上的按钮 → 主进程
//   'minimize'（—）：只把卡片收起来，等待/启动继续，后端好了自动弹出桌宠
//   'close'（✕）/ '先关掉'：收起且不再自动等待，要用托盘菜单重新启动
ipcMain.on('backend-action', (event, action) => {
    if (action === 'minimize') {
        if (win && !win.isDestroyed()) win.hide();
        balloon('卡片已收起', '后端好了会自动把桌宠叫出来～');
        return;
    }
    if (action === 'close') {
        waitAbandoned = true;
        backendBooting = false;   // 别卡住托盘菜单（后台那次轮询仍会跑完，只是不再自动弹桌宠）
        if (win && !win.isDestroyed()) win.hide();
        balloon('已关闭等待', '要启动后端时，右键托盘 →「启动后端服务」或「重新加载桌宠页面」');
        console.log('[MAIN] wait abandoned by user');
        return;
    }
    if (action === 'start') { startBackendAndWait(); return; }
    if (action === 'self') { waitForBackendOnly(); return; }
});

// 打开日志目录（窗口都藏起来了，看日志走这里）
function openLogDir() {
    try {
        if (!fs.existsSync(LOG_DIR)) fs.mkdirSync(LOG_DIR, { recursive: true });
        spawn('explorer.exe', [LOG_DIR], { stdio: 'ignore', detached: false });
    } catch (e) { console.log('[MAIN] open log dir failed: ' + e.message); }
}

// 托盘菜单用：手动检查 + 启动 + 起来后重载桌宠页
async function trayStartBackend() {
    if (await checkBackend(1500)) {
        balloon('后端已经在运行', '端口 8080 有响应，不需要重复启动。想换一份代码跑就用「重启后端服务」。');
        return;
    }
    if (backendBooting) {
        balloon('后端正在启动中', '已经有启动流程在跑了，请稍等～');
        return;
    }
    showCard('down');          // 让卡片可见，用户能看到进度或重新选
    startBackendAndWait();
}

/**
 * 重启桌面桌宠本体 + 清旧缓存。
 * 为什么必须重启本体：改了 main.js / preload.js（主进程、IPC、窗口逻辑）时，
 * 「重新加载桌宠页面」只刷网页，主进程与 preload 还是旧的 —— 新功能要重开才生效（老师反馈）。
 * 清的是 Chromium 的**页面/脚本缓存**；localStorage（播放历史等）保留。
 * app.relaunch() 的语义是"当前实例退出后再起新进程"，所以不会撞单实例锁。
 */
async function relaunchPetApp() {
    try {
        const ses = require('electron').session.defaultSession;
        ses.clearCache().catch(() => {});
        ses.clearStorageData({ storages: ['shadercache', 'cachestorage', 'serviceworkers'] }).catch(() => {});
        console.log('[MAIN] caches cleared, relaunching pet app');
    } catch (e) { console.log('[MAIN] clear cache failed: ' + e.message); }
    // 带上 --wait-backend：新进程起来后直接等后端就绪，不再弹"一键启动"询问
    const args = process.argv.slice(1).filter((a) => a !== '--wait-backend').concat(['--wait-backend']);
    app.relaunch({ args: args });
    setTimeout(() => { app.__quitting = true; app.exit(0); }, 700);
}

// 托盘菜单用：重启后端 + 桌面桌宠（改了 Java 代码 or 主进程代码都用这一条）
async function trayRestartBackend() {
    if (backendBooting) {
        balloon('后端正在启动中', '等这一轮启动完再重启～');
        return;
    }
    balloon('正在重启后端 + 桌面桌宠', '先停后端 → 重新拉起 → 清缓存后桌宠会自己重开（约 10 秒），请稍等～');
    stopBackend();
    const down = await waitBackendDown(20000);
    console.log('[MAIN] backend stopped: ' + down);
    showCard('down');
    if (!down) {
        sendBackendStatus({ state: 'timeout' });
        safeDialog({
            type: 'warning', title: '停不掉后端', message: '8080 端口 20 秒后仍在响应',
            detail: '可能有别的程序占着 8080，或者后端还在关闭中。\n可以再等一会儿点一次「重启后端服务」，' +
                    '或手动运行：' + BACKEND_STOP_PS1,
            buttons: ['知道了'], noLink: true
        });
        return;
    }
    // 拉起后端（不等它编译完：新桌宠进程会显示"等待后端"卡片并自动接上）
    backendBooting = false;
    startBackend();
    // 清缓存 + 重启 Electron 本体（main.js / preload.js 的改动只有这样才生效）
    await relaunchPetApp();
}
// 本地控制端口：后端（麻将自动打牌等）在点击前 POST /lock 让窗口点击穿透，点击后 /unlock
function startControlServer() {
    const server = http.createServer((req, res) => {
        const url = req.url || '';
        if (url === '/lock' || url === '/unlock') {
            const lock = url === '/lock';
            if (win) win.setIgnoreMouseEvents(lock, { forward: true });
            isLocked = lock;
            passThrough = false;   // 麻将接管期间不让区域穿透兜底插手
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: true, locked: isLocked }));
        } else if (url === '/ping') {
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: true }));
        } else {
            res.writeHead(404);
            res.end();
        }
    });
    server.listen(3081, '127.0.0.1', () => console.log('[MAIN] control port ready: http://127.0.0.1:3081'));
    // 端口被占（例如上次没退干净）时不要让整个进程崩掉，只记一条日志
    server.on('error', (e) => console.log('[MAIN] control port 3081 failed: ' + e.message +
        ' (mahjong lock/unlock unavailable, other features fine)'));
}

// 简易 POST（供快捷键调后端）
function httpPost(url, done) {
    try {
        const u = new URL(url);
        const req = http.request({
            hostname: u.hostname, port: u.port, path: u.pathname, method: 'POST'
        }, (res) => { res.resume(); if (done) done(res.statusCode); });
        req.on('error', () => { if (done) done(-1); });
        req.end();
    } catch (e) { if (done) done(-1); }
}

// Ctrl+Shift+Q：强制停止麻将自动打牌（游戏界面/桌宠点击干扰时也能一键退出）
function stopMahjongByHotkey() {
    httpPost('http://127.0.0.1:8080/ai/mahjong/play/stop', () => {
        if (win && !win.isDestroyed()) win.webContents.send('mahjong-force-stop');
    });
    if (win) win.setIgnoreMouseEvents(false, { forward: true }); // 确保不再吞鼠标
    isLocked = false;
    console.log('[MAIN] Ctrl+Shift+Q: stop mahjong requested');
}

function createWindow() {
    const { width, height } = screen.getPrimaryDisplay().workAreaSize;
    fullBounds = { x: 0, y: 0, width, height };   // 记下正常状态的窗口尺寸，等待卡片用完后要还原
    win = new BrowserWindow({
        width, height,
        x: 0, y: 0,
        icon: appIcon(),
        transparent: true,
        frame: false,
        alwaysOnTop: true,
        resizable: false,
        skipTaskbar: true,
        webPreferences: { nodeIntegration: false, contextIsolation: true, preload: path.join(__dirname, 'preload.js') }
    });
    // 后端挂了时不显示 Chromium 的「无法访问此网站」报错页，改成等待卡片（带一键启动按钮）
    win.webContents.on('did-fail-load', (e, code, desc, failedUrl, isMainFrame) => {
        if (!isMainFrame || code === -3 || showingWaitPage) return;   // -3 = ERR_ABORTED
        console.log('[MAIN] pet page load failed (' + code + ' ' + desc + '): ' + failedUrl);
        showCard('down');
    });
    bootPetPage();   // 先探测后端：在就直接加载桌宠页，不在就显示等待卡片
    win.setAlwaysOnTop(true, 'screen-saver');
    win.on('closed', () => {
        win = null;
        // ★ 桌宠关掉 → 依赖它的子窗口一起收起（音乐面板 / 画板）。
        //   否则主进程还活着（window-all-closed 里不退出），下次再打开桌宠会看到
        //   "播放器/画板还开着"（老师实测踩过）。这里直接 destroy：桌宠都没了，不用再演关闭动画。
        try { if (musicWinRef && !musicWinRef.isDestroyed()) { musicWinRef.destroy(); musicWinRef = null; } } catch (e) { }
        try { if (pictionaryWinRef && !pictionaryWinRef.isDestroyed()) { pictionaryWinRef.destroy(); pictionaryWinRef = null; } } catch (e) { }
    });
    win.on('blur', () => {
        // 失焦时把桌宠重新顶到最前；但**基础设置窗口 / 画板窗口开着的时候不要抢**，
        // 否则会把它们重新压到桌宠全屏透明层下面（用户就看不到、也点不到了）。
        const childOpen = (settingsWinRef && !settingsWinRef.isDestroyed())
            || (pictionaryWinRef && !pictionaryWinRef.isDestroyed())
            || (musicWinRef && !musicWinRef.isDestroyed());
        if (win && !childOpen) win.setAlwaysOnTop(true, 'screen-saver');
    });
}

function createTray() {
    const icon = trayIcon();   // 阿罗娜头像 32×32 透明 PNG（右下角托盘图标；原先是空白占位）
    tray = new Tray(icon);
    const contextMenu = Menu.buildFromTemplate([
        { label: '显示/隐藏', click: () => { if (win && !win.isDestroyed()) { win.isVisible() ? win.hide() : win.show(); } } },
        { label: '隐藏/显示对话框', click: () => { if (win) win.webContents.send('toggle-dialog'); } },
        { label: '基础设置', click: () => { openPetSettings(); }},
        { label: '音乐播放器（打开面板）', click: () => { openMusicPanel(); } },
        { label: '🎨 你画我猜（打开画板）', click: () => { openPictionaryWindow(); }},
        { label: '管理页面', click: () => {
            const mgr = new BrowserWindow({ width: 1000, height: 700, frame: true, icon: appIcon(),
                webPreferences: { nodeIntegration: false, contextIsolation: true } });
            mgr.loadURL('http://localhost:8080/桌宠管理.html');
            mgr.setMenuBarVisibility(false);
        }},
        { label: '开发者工具（F12）', click: () => { if (win) win.webContents.toggleDevTools(); } },
        { type: 'separator' },
        { label: '🔌 启动后端服务（未运行时）', click: () => trayStartBackend() },
        { label: '♻️ 重启后端 + 桌面桌宠（改 Java / 主进程代码用）', click: () => trayRestartBackend() },
        { label: '📂 打开日志目录（窗口隐藏后看这里）', click: () => openLogDir() },
        { label: '🔄 重新加载桌宠页面', click: () => { if (win && !win.isDestroyed()) win.webContents.reload(); } },
        { type: 'separator' },
        { label: '退出', click: () => { app.quit(); } }
    ]);
    tray.setToolTip('桌宠');
    tray.setContextMenu(contextMenu);
}

// ============================================================
//  基础设置窗口（桌宠设置.html）
//  背景图 login_beijing2.png 是 923×528（老师裁掉多余透明边后又标注了一次），所以窗口
//  按**这张图的宽高比**定尺寸，页面里 background-size:100% 100% 才不会变形；
//  页面用「区域标注工具」标注的「顶部窗口区域 / 内容区域2」百分比把 UI 摆到图里对应位置。
//  窗口仍是 frame:false + transparent（顶部标题行背景透明）——没有系统标题栏，
//  标题行与 ─ ✕ 由页面自己画（放在「顶部窗口区域」里），main.js 这边补对应的 IPC。
//  ★ 换背景图时：改下面这个比例 + 页面 CSS 里两个区域的百分比。
// ============================================================
const SETTINGS_IMG_W = 923, SETTINGS_IMG_H = 528;
const SETTINGS_ASPECT = SETTINGS_IMG_W / SETTINGS_IMG_H;
const SETTINGS_SCALE_MIN = 0.6, SETTINGS_SCALE_MAX = 2.0;
const SETTINGS_ANIM_MS = 600;         // 出现/关闭动画时长（原 1200ms，按要求加快 0.5 倍）
let settingsScale = 1.0;              // 默认 1.0 = 背景图原始尺寸（最清晰）；可在「基础设置 → 基础外观」里拖滑动条实时调
let settingsPos = null;               // 上次关闭时的窗口位置 {x, y}；null=没存过（居中）
let settingsWinRef = null;            // 当前打开的基础设置窗口（它开着时，桌宠失焦不抢回置顶）
let pictionaryPos = null;             // 画板窗口（你画我猜）上次位置 {x,y}；null=没存过（居中）
let pictionaryWinRef = null;          // 当前打开的画板窗口（它开着时，桌宠失焦同样不抢顶）
let musicPos = null;                  // 音乐播放器面板位置 {x,y}（存 settings-window.json 的 musicX/musicY）
let musicWinRef = null;               // 当前打开的音乐播放器面板（它开着时，桌宠失焦也不抢顶）

// 尺寸/位置配置存在 userData 下的小 json 里（改了就写入，下次开窗沿用）
function settingsCfgPath() { return path.join(app.getPath('userData'), 'settings-window.json'); }
function clampScale(s) {
    const n = Number(s);
    if (!isFinite(n)) return 1.0;
    return Math.max(SETTINGS_SCALE_MIN, Math.min(SETTINGS_SCALE_MAX, Math.round(n * 100) / 100));
}
function loadSettingsCfg() {
    try {
        const f = settingsCfgPath();
        if (fs.existsSync(f)) {
            const j = JSON.parse(fs.readFileSync(f, 'utf8'));
            if (j && typeof j.scale === 'number') settingsScale = clampScale(j.scale);
            if (j && typeof j.x === 'number' && typeof j.y === 'number') settingsPos = { x: Math.round(j.x), y: Math.round(j.y) };
            // 画板窗口（你画我猜）位置：和基础设置窗口分开记，各记各的
            if (j && typeof j.boardX === 'number' && typeof j.boardY === 'number') pictionaryPos = { x: Math.round(j.boardX), y: Math.round(j.boardY) };
            // 音乐播放器面板位置
            if (j && typeof j.musicX === 'number' && typeof j.musicY === 'number') musicPos = { x: Math.round(j.musicX), y: Math.round(j.musicY) };
        }
    } catch (e) { console.log('[MAIN] read settings-window.json failed: ' + e.message); }
}
function saveSettingsCfg() {
    try {
        const o = { scale: settingsScale };
        if (settingsPos) { o.x = Math.round(settingsPos.x); o.y = Math.round(settingsPos.y); }
        if (pictionaryPos) { o.boardX = Math.round(pictionaryPos.x); o.boardY = Math.round(pictionaryPos.y); }
        if (musicPos) { o.musicX = Math.round(musicPos.x); o.musicY = Math.round(musicPos.y); }
        fs.writeFileSync(settingsCfgPath(), JSON.stringify(o, null, 2));
    } catch (e) { console.log('[MAIN] write settings-window.json failed: ' + e.message); }
}
// 整块屏幕（含任务栏那条区域）—— 动画的「屏幕外」必须按它算：
// 用 workArea 的话，底部任务栏那一带其实还在屏幕上，窗口会「没完全出去 / 一开始就在屏幕里」。
function displayBoundsOf(rect) {
    return screen.getDisplayMatching(rect).bounds;
}
function offScreenYBelow(rect) {
    const b = displayBoundsOf(rect);
    return b.y + b.height;      // 窗口顶边 = 屏幕下沿 → 整个窗口都在屏幕外
}
// 把保存的位置夹回屏幕内：至少留 120px 在屏幕里（否则窗口找不回来）
function clampSettingsPos(x, y, w, h) {
    const d = screen.getDisplayMatching({ x: x, y: y, width: w, height: h }).bounds;
    const keep = 120;
    const minX = d.x - w + keep, maxX = d.x + d.width - keep;
    const minY = d.y, maxY = d.y + d.height - keep;
    return {
        x: Math.round(Math.max(minX, Math.min(maxX, x))),
        y: Math.round(Math.max(minY, Math.min(maxY, y)))
    };
}

// 按比例算窗口尺寸（保持背景图比例；屏幕放不下就等比缩小）
function settingsWindowSize(scale) {
    const area = screen.getPrimaryDisplay().workArea;
    let w = Math.round(SETTINGS_IMG_W * scale);
    let h = Math.round(SETTINGS_IMG_H * scale);
    const maxW = Math.min(area.width - 40, Math.round((area.height - 40) * SETTINGS_ASPECT));
    if (w > maxW) { w = Math.max(560, maxW); h = Math.round(w / SETTINGS_ASPECT); }
    return { width: w, height: h };
}
function settingsWindowBounds() {
    const area = screen.getPrimaryDisplay().workArea;
    const s = settingsWindowSize(settingsScale);
    // 有存过的位置就用它（夹回屏幕内），否则居中
    if (settingsPos) {
        const p = clampSettingsPos(settingsPos.x, settingsPos.y, s.width, s.height);
        return { width: s.width, height: s.height, x: p.x, y: p.y };
    }
    return {
        width: s.width, height: s.height,
        x: Math.round(area.x + (area.width - s.width) / 2),
        y: Math.round(area.y + (area.height - s.height) / 2)
    };
}
// 出现/关闭动画：时间驱动的二次 ease-in（越走越快），y 从 fromY 滑到 toY
// 只改 y → 用 setPosition 而不是 setBounds，少一次尺寸变更、透明窗口重绘更稳
function animateWindowY(w, fromY, toY, ms, done) {
    const t0 = Date.now();
    const b = w.getBounds();
    const step = () => {
        if (!w || w.isDestroyed()) return;
        const p = Math.min(1, (Date.now() - t0) / ms);
        const e = p * p;                      // 二次加速：速度逐渐加快
        const y = Math.round(fromY + (toY - fromY) * e);
        try { w.setPosition(b.x, y); } catch (err) { }
        if (p < 1) setTimeout(step, 16); else if (done) done();
    };
    step();
}
// 给基础设置窗口挂上「下方滑入 / 向下滑出」动画 + 位置记忆
// posSink: 位置保存回调（默认存基础设置窗口的位置；画板窗口传自己的回调，各记各的）
function setupSettingsWindow(w, target, posSink) {
    const savePos = typeof posSink === 'function' ? posSink : (b) => { settingsPos = { x: b.x, y: b.y }; saveSettingsCfg(); };
    // ★ 动画起点/终点用**整块屏幕**（display.bounds）算，不用 workArea：
    //   workArea 不含任务栏，按它算的话窗口会停在任务栏那一带，等于「没完全移出屏幕」。
    const offY = offScreenYBelow({ x: target.x, y: target.y, width: target.width, height: target.height });
    let animating = false;                       // 动画期间不要记录位置（否则会把屏幕外的坐标存下来）
    try { w.setBounds({ x: target.x, y: offY, width: target.width, height: target.height }); } catch (e) { }
    let slid = false;
    const slideIn = () => {
        if (slid || w.isDestroyed()) return;
        slid = true;
        animating = true;
        animateWindowY(w, w.getBounds().y, target.y, SETTINGS_ANIM_MS, () => { animating = false; });
    };
    // 页面画好了再滑上来，避免滑进来一张白屏；万一页面加载异常，3 秒后也照常滑入
    w.webContents.once('did-finish-load', slideIn);
    setTimeout(slideIn, 3000);
    // 位置记忆：拖窗口时（moved / move 事件）防抖保存
    let posTimer = null;
    const rememberPos = () => {
        clearTimeout(posTimer);
        posTimer = setTimeout(() => {
            if (!w || w.isDestroyed() || animating) return;
            const b = w.getBounds();
            // 只记「确实在屏幕里」的位置，避免把异常坐标写进配置
            const d = displayBoundsOf(b);
            if (b.y + b.height <= d.y || b.y >= d.y + d.height) return;
            savePos(b);
        }, 400);
    };
    w.on('move', rememberPos);
    w.on('moved', rememberPos);
    // 关闭：先向下滑出屏幕再真正关掉（程序整体退出时不演动画，免得退出被拖住）
    let closing = false;
    w.on('close', (e) => {
        if (closing) return;
        // 先记位置（不管是不是整体退出，都尽量留下最后一次的屏幕内位置）
        if (!animating) {
            const b0 = w.getBounds();
            const d0 = displayBoundsOf(b0);
            if (b0.y + b0.height > d0.y && b0.y < d0.y + d0.height) {
                savePos(b0);
                console.log('[MAIN] window pos saved on close: ' + b0.x + ',' + b0.y);
            }
        }
        if (app.__quitting) return;      // 整体退出 → 不演动画
        e.preventDefault();
        closing = true;
        const b = w.getBounds();
        animating = true;
        animateWindowY(w, b.y, offScreenYBelow(b), SETTINGS_ANIM_MS, () => {
            animating = false;
            try { w.close(); } catch (err) { }
        });
    });
}
// 让基础设置窗口「暂时退到最上层之下」，好让资源管理器 / 它打开的其它窗口显示在前：
// 取消置顶 → 本窗口重新获得焦点时自动恢复置顶。
// （不然「📂 打开文件夹」弹出的资源管理器会被设置窗口一直盖住，只能先移开/隐藏它）
function yieldSettingsTop(w) {
    if (!w || w.isDestroyed() || !w.isAlwaysOnTop()) return;
    try { w.setAlwaysOnTop(false); } catch (e) { return; }
    console.log('[MAIN] settings window yielded top');
    const restore = () => {
        try { if (!w.isDestroyed()) w.setAlwaysOnTop(true, 'screen-saver'); } catch (e) { }
        w.removeListener('focus', restore);
    };
    w.once('focus', restore);
}
ipcMain.on('settings-yield-top', (event) => {
    yieldSettingsTop(BrowserWindow.fromWebContents(event.sender));
});

function settingsWindowOptions() {
    const b = settingsWindowBounds();
    return {
        width: b.width, height: b.height, x: b.x, y: b.y,
        icon: appIcon(),
        frame: false,            // 无系统标题栏
        transparent: true,       // 背景图四周透明能透出桌面
        resizable: false,
        maximizable: false,
        // 和桌宠同一档置顶（screen-saver）：桌宠是全屏置顶透明层，别的置顶前台程序
        // 也可能挡住普通窗口 —— 设置窗口不跟着置顶就会「打开却看不见」。
        alwaysOnTop: true,
        autoHideMenuBar: true,
        useContentSize: true,    // width/height 按网页可视区算，保证背景图比例不受窗口边框影响
        backgroundColor: '#00000000',
        // ★ backgroundThrottling:false 很关键：窗口一旦被判为「被遮挡」，Chromium 默认
        //   会暂停绘制，表现就是「看不到窗口内容和入场动画，点一下桌面才冒出来」。
        webPreferences: {
            nodeIntegration: false, contextIsolation: true, backgroundThrottling: false,
            preload: path.join(__dirname, 'preload.js')
        }
    };
}
function openPetSettings() {
    const target = settingsWindowBounds();
    const w = new BrowserWindow(settingsWindowOptions());
    settingsWinRef = w;
    w.setAlwaysOnTop(true, 'screen-saver');   // 与桌宠同级（构造参数里的只是默认档）
    w.loadURL('http://localhost:8080/桌宠设置.html?t=' + Date.now());
    w.setMenuBarVisibility(false);
    setupSettingsWindow(w, target);   // 从屏幕下方滑入；关闭时向下滑出
    w.once('ready-to-show', () => { try { w.moveTop(); w.focus(); } catch (e) { } });
    w.on('closed', () => { settingsWinRef = null; });
    return w;
}

// ============================================================
//  画板窗口（你画我猜.html）
//  ★ 与基础设置窗口**同一套**：大小（基础设置里调的 settingsScale）、位置记忆、
//    从屏幕下方滑入 / 向下滑出的弹出动画、screen-saver 同级置顶。
//    背景图当画板的装饰边框，页面里用标注好的两块区域摆 UI：
//      顶部窗口区域 → 「题目：XX」（居中大号）+ ─ ✕
//      内容区域2   → 半透明画布（保留背景图，不整块遮住）
//    置顶必须是 screen-saver 档：桌宠是全屏置顶透明层，普通窗口会被它压住「打开却看不见」；
//    同时桌宠失焦时的「抢回置顶」要跳过（见 createWindow 里的 blur 处理）。
// ============================================================
function pictionaryWindowBounds() {
    const area = screen.getPrimaryDisplay().workArea;
    const s = settingsWindowSize(settingsScale);      // 与基础设置窗口同尺寸
    if (pictionaryPos) {
        const p = clampSettingsPos(pictionaryPos.x, pictionaryPos.y, s.width, s.height);
        return { width: s.width, height: s.height, x: p.x, y: p.y };
    }
    return {
        width: s.width, height: s.height,
        x: Math.round(area.x + (area.width - s.width) / 2),
        y: Math.round(area.y + (area.height - s.height) / 2)
    };
}
function pictionaryWindowOptions() {
    const b = pictionaryWindowBounds();
    return Object.assign({}, settingsWindowOptions(), { x: b.x, y: b.y, width: b.width, height: b.height });
}
function openPictionaryWindow() {
    if (pictionaryWinRef && !pictionaryWinRef.isDestroyed()) {
        try { pictionaryWinRef.show(); pictionaryWinRef.moveTop(); pictionaryWinRef.focus(); } catch (e) { }
        return pictionaryWinRef;
    }
    const target = pictionaryWindowBounds();
    const w = new BrowserWindow(pictionaryWindowOptions());
    pictionaryWinRef = w;
    w.setAlwaysOnTop(true, 'screen-saver');
    w.loadURL('http://localhost:8080/你画我猜.html?t=' + Date.now());
    w.setMenuBarVisibility(false);
    // 位置单独记（settings-window.json 里的 boardX/boardY），动画与关闭逻辑和设置窗口一致
    setupSettingsWindow(w, target, (b) => { pictionaryPos = { x: b.x, y: b.y }; saveSettingsCfg(); });
    w.once('ready-to-show', () => { try { w.moveTop(); w.focus(); } catch (e) { } });
    w.on('closed', () => {
        pictionaryWinRef = null;
        // 画板被关掉 → 通知桌宠页收尾（这一局作废）
        if (win && !win.isDestroyed()) win.webContents.send('pet:pictionary', { type: 'closed' });
        console.log('[MAIN] pictionary window closed');
    });
    console.log('[MAIN] pictionary window opened');
    return w;
}
// 桌宠页：命令 / 托盘 打开画板；结束时关掉画板；猜题结果转发给桌宠页去做表情与说话
ipcMain.on('open-pictionary', () => { openPictionaryWindow(); });
ipcMain.on('close-pictionary', () => {
    if (pictionaryWinRef && !pictionaryWinRef.isDestroyed()) pictionaryWinRef.close();
});
ipcMain.on('pictionary-event', (event, payload) => {
    if (win && !win.isDestroyed()) win.webContents.send('pet:pictionary', payload);
});
// 桌宠页 → 画板窗口：refresh 重新读当前局（题目/轮次）；giveup 让画板执行「公布答案」
ipcMain.on('pictionary-refresh', (event, cmd) => {
    if (pictionaryWinRef && !pictionaryWinRef.isDestroyed()) pictionaryWinRef.webContents.send('board:refresh', cmd || 'refresh');
});

// ============================================================
//  音乐播放器窗口（音乐播放器.html）
//  网易云的播放真身在桌宠页（<audio> + neteaseQueue），这个窗口只是**遥控器**：
//    面板 → main → 桌宠页（指令）；桌宠页 → main → 面板（状态，每秒一次）
//  窗口沿用基础设置/画板那一套：无边框透明 + 位置记忆 + 下方滑入/滑出动画 + screen-saver 同级置顶。
//  「隐藏操作面板」= 滑出后 hide()（音乐不停，下次播放指令再滑进来）；
//  「× 关闭音乐播放器」= 面板先让桌宠页停止播放，再关窗。
// ============================================================
const MUSIC_W = 560, MUSIC_H = 196;
const MUSIC_LIST_EXTRA = 276;         // 打开「▽列表」时窗口向上临时加高这么多（列表 260 + 间距）

function musicWindowBounds() {
    const area = screen.getPrimaryDisplay().workArea;
    const w = MUSIC_W, h = MUSIC_H;
    if (musicPos) {
        const p = clampSettingsPos(musicPos.x, musicPos.y, w, h);
        return { width: w, height: h, x: p.x, y: p.y };
    }
    // 没存过位置 → 默认停屏幕右下角（任务栏上方）
    return {
        width: w, height: h,
        x: Math.round(area.x + area.width - w - 24),
        y: Math.round(area.y + area.height - h - 24)
    };
}
function musicWindowOptions() {
    const b = musicWindowBounds();
    return Object.assign({}, settingsWindowOptions(), { x: b.x, y: b.y, width: b.width, height: b.height });
}
function openMusicPanel() {
    if (musicWinRef && !musicWinRef.isDestroyed()) {
        // 已经开着/被隐藏过：按记录的位置重新滑入（隐藏时窗口被摆到了屏幕外，这里先复位再动画）
        const t = musicWindowBounds();
        try { musicWinRef.setBounds({ x: t.x, y: offScreenYBelow(t), width: t.width, height: t.height }); } catch (e) { }
        try { musicWinRef.show(); } catch (e) { }
        animateWindowY(musicWinRef, musicWinRef.getBounds().y, t.y, SETTINGS_ANIM_MS, () => {
            // 动画结束后再确认一次位置（这期间可能有别的 setBounds/夹取把窗口挪偏）
            try { musicWinRef.setPosition(t.x, t.y); } catch (e) { }
        });
        try { musicWinRef.moveTop(); musicWinRef.focus(); } catch (e) { }
        return musicWinRef;
    }
    const target = musicWindowBounds();
    const w = new BrowserWindow(musicWindowOptions());
    musicWinRef = w;
    w.setAlwaysOnTop(true, 'screen-saver');
    w.loadURL('http://localhost:8080/音乐播放器.html?t=' + Date.now());
    w.setMenuBarVisibility(false);
    // 位置记忆：窗口**至少大半个在屏幕里**才记 —— 隐藏动画会把窗口往下滑出屏幕，
    // 期间 400ms 防抖的保存有可能正好抓到"半出屏"的坐标，那样下次呼出就会贴到屏幕底边（老师实测踩过）
    setupSettingsWindow(w, target, (b) => {
        const d = displayBoundsOf(b);
        const visible = Math.min(b.y + b.height, d.y + d.height) - Math.max(b.y, d.y);
        // 打开「▽列表」时窗口向下延申、顶边不动，所以直接存 b.y 就够
        if (visible >= b.height * 0.6) { musicPos = { x: b.x, y: b.y }; saveSettingsCfg(); }
    });
    w.once('ready-to-show', () => { try { w.moveTop(); w.focus(); } catch (e) { } });
    w.on('closed', () => { musicWinRef = null; console.log('[MAIN] music panel closed'); });
    console.log('[MAIN] music panel opened');
    return w;
}
// 隐藏操作面板：向下滑出后 hide()（音乐继续播）。
// ★ 先把当前位置记下来再动画 —— 动画之后窗口就在屏幕外了，那时再记已经没意义。
function hideMusicPanel() {
    const w = musicWinRef;
    if (!w || w.isDestroyed()) return;
    let keep = null;
    try {
        const b = w.getBounds();
        const d = displayBoundsOf(b);
        const visible = Math.min(b.y + b.height, d.y + d.height) - Math.max(b.y, d.y);
        if (visible >= b.height * 0.6) { keep = { x: b.x, y: b.y, width: b.width, height: b.height }; }
    } catch (e) { }
    if (keep) { musicPos = { x: keep.x, y: keep.y }; saveSettingsCfg(); console.log('[MAIN] music panel pos saved: ' + keep.x + ',' + keep.y); }
    const b0 = w.getBounds();
    animateWindowY(w, b0.y, offScreenYBelow(b0), SETTINGS_ANIM_MS, () => {
        try { w.hide(); } catch (e) { }
        // 隐藏后把窗口摆回原位：下次 show() 直接就位，不用再从屏幕外找回来
        if (keep) { try { w.setBounds(keep); } catch (e) { } }
    });
}
function closeMusicPanel() {
    if (musicWinRef && !musicWinRef.isDestroyed()) musicWinRef.close();   // close 事件里会先滑出再关
}
/**
 * 打开「▽列表」时把窗口**向上**临时加高（底边不动），列表关掉再收回原高度。
 * 不然下拉会超出窗口被裁掉（窗口只有 MUSIC_H 高，浏览器外的东西根本画不出来）。
 * 屏幕上沿空间不够时按可用高度收着长，绝不越过屏幕顶。
 */
function musicPanelResize(listOpen) {
    const w = musicWinRef;
    if (!w || w.isDestroyed()) return;
    try {
        const b = w.getBounds();
        const d = displayBoundsOf(b);
        const maxDown = (d.y + d.height) - b.y;              // 屏幕上沿→下沿：向下还能长多少
        const want = listOpen ? (MUSIC_H + MUSIC_LIST_EXTRA) : MUSIC_H;
        const h = Math.max(MUSIC_H, Math.min(want, maxDown));
        if (h === b.height) return;
        // 顶边不动、向下延申（▽ 下拉就该从下方展开）
        w.setBounds({ x: b.x, y: b.y, width: b.width, height: h });
        console.log('[MAIN] music panel height -> ' + h);
    } catch (e) { }
}
ipcMain.on('open-music-panel', () => { openMusicPanel(); });
ipcMain.on('hide-music-panel', () => { hideMusicPanel(); });
ipcMain.on('close-music-panel', () => { closeMusicPanel(); });
ipcMain.on('music-panel-resize', (event, listOpen) => { musicPanelResize(!!listOpen); });
// 面板 → 桌宠页：指令（上一首/暂停/下一首/插播/音量/拖动进度/停止）
ipcMain.on('music-cmd', (event, payload) => {
    if (win && !win.isDestroyed()) win.webContents.send('pet:music-cmd', payload);
});
// 桌宠页 → 面板：状态（每秒推一次；面板没开就直接丢弃）
ipcMain.on('music-panel-state', (event, state) => {
    if (musicWinRef && !musicWinRef.isDestroyed()) musicWinRef.webContents.send('music:state', state);
});

// 页面滑动条调窗口大小：**松手后**才发这里（拖动中不缩放，否则窗口一动滑块就跟着跑）。
// 缩放以窗口**左上角**为锚点：面板左上角位置不动、滑块位置基本不变，反复微调好按；
// 超出屏幕时再夹回屏幕内。并写入配置供下次开窗沿用。
ipcMain.handle('get-settings-scale', () => settingsScale);
ipcMain.on('set-settings-scale', (event, v) => {
    settingsScale = clampScale(v);
    saveSettingsCfg();
    const w = BrowserWindow.fromWebContents(event.sender);
    if (w && !w.isDestroyed()) {
        const old = w.getBounds();
        const s = settingsWindowSize(settingsScale);
        const area = screen.getDisplayMatching(old).workArea;
        const x = Math.max(area.x, Math.min(area.x + area.width - s.width, old.x));
        const y = Math.max(area.y, Math.min(area.y + area.height - s.height, old.y));
        w.setBounds({ x: Math.round(x), y: Math.round(y), width: s.width, height: s.height });
    }
});

// 无边框窗口的自绘标题栏按钮
ipcMain.on('close-window', (event) => {
    const w = BrowserWindow.fromWebContents(event.sender);
    if (w && !w.isDestroyed()) w.close();
});
ipcMain.on('minimize-window', (event) => {
    const w = BrowserWindow.fromWebContents(event.sender);
    if (w && !w.isDestroyed()) w.minimize();
});

ipcMain.on('move-window', (event, dx, dy) => {
    if (win) { const [x, y] = win.getPosition(); win.setPosition(x + dx, y + dy); }
});
ipcMain.on('set-ignore-mouse', (event, ignore) => {
    // 用户按锁按钮 / 后端麻将 /lock 走这里：这是「主动锁定」，穿透兜底不许插手
    isLocked = !!ignore;
    passThrough = false;
    if (win && !win.isDestroyed()) win.setIgnoreMouseEvents(isLocked, { forward: true });
});

// ============================================================
//  区域点击穿透的「兜底光标」
//  桌宠页 updatePass() 的逻辑是：鼠标不在桌宠区域 → 整窗点击穿透，移回桌宠
//  区域 → 恢复捕获。问题是**恢复那一步依赖 Electron 的
//  setIgnoreMouseEvents(true, {forward:true}) 把 mousemove 转发进页面**，
//  这条转发不可靠（实测会收不到），于是窗口就永久停在「整窗穿透」上：
//  对话框 / 模型 / 右下角按钮 / 眼部跟踪全部没反应，
//  只能靠 Ctrl+Shift+Z（它直接 setIgnoreMouse(false)）救回来。
//  这里加一条不依赖事件转发的兜底：主进程定时读真实光标位置推给页面，
//  页面用真实坐标重新判定要不要恢复捕获。
//  注意几种「不该插手」的情况：用户主动锁定、麻将自动打牌接管、等待卡片显示中。
// ============================================================
let passThrough = false;    // 区域穿透（≠ 主动锁定 isLocked）
let cursorTimer = null;

function sendCursor() {
    try {
        if (!win || win.isDestroyed()) return;
        if (isLocked || showingWaitPage || !win.isVisible()) return;
        const p = screen.getCursorScreenPoint();     // DIP 坐标
        const b = win.getBounds();                   // 同样是 DIP；减掉窗口原点换成页面 client 坐标
        // focused 一起带上：页面据此决定「要不要继续保持鼠标跟踪」——
        // 老师还没去点桌面（窗口仍有焦点）时保持跟踪，点了桌面（失焦）就让它自然松开。
        win.webContents.send('pet:cursor', { x: p.x - b.x, y: p.y - b.y, focused: win.isFocused() });
    } catch (e) { /* 页面正在切换/重建时忽略 */ }
}
function startCursorPoll() {
    if (!cursorTimer) cursorTimer = setInterval(sendCursor, 50);   // 20Hz：够跟手，也够快恢复捕获
}
function stopCursorPoll() {
    if (cursorTimer) { clearInterval(cursorTimer); cursorTimer = null; }
}

ipcMain.on('set-pass-through', (event, ignore) => {
    passThrough = !!ignore;
    // 锁定期间无论如何都保持穿透，避免兜底把麻将/锁定状态顶掉
    if (win && !win.isDestroyed()) win.setIgnoreMouseEvents(isLocked || passThrough, { forward: true });
    if (DEBUG_PASS) console.log('[MAIN] pass-through -> ' + passThrough + ' (locked=' + isLocked + ')');
});

app.whenReady().then(() => {
    loadSettingsCfg();   // 读基础设置窗口的尺寸配置（userData/settings-window.json）
    const ok = globalShortcut.register('Ctrl+Shift+Z', () => {
        if (win) {
            if (isLocked) { win.show(); win.focus(); } // 解锁时让窗口获得焦点，确保键盘输入到桌宠
            win.webContents.send('toggle-lock');
        }
    });
    console.log('[MAIN] Ctrl+Shift+Z registered:', ok);
    globalShortcut.register('Ctrl+Shift+A', () => {
        if (win) win.isVisible() ? win.hide() : win.show();
    });
    globalShortcut.register('Ctrl+Shift+D', () => {
        if (win) win.webContents.send('toggle-dialog');
    });
    const qOk = globalShortcut.register('Ctrl+Shift+Q', () => stopMahjongByHotkey());
    console.log('[MAIN] Ctrl+Shift+Q (force stop mahjong) registered:', qOk);
    globalShortcut.register('F12', () => {
        if (win) win.webContents.toggleDevTools();
    });
    createWindow(); createTray(); startControlServer();
    startCursorPoll();   // 区域穿透的兜底光标（说明见 set-pass-through 上面那段注释）
});
app.on('before-quit', () => { app.__quitting = true; });   // 整体退出时不演关闭动画，免得拖住退出
app.on('will-quit', () => { stopCursorPoll(); globalShortcut.unregisterAll(); });
app.on('window-all-closed', () => {});
app.on('activate', () => { if (!win) createWindow(); });
