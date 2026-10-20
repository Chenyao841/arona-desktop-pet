/* ====== 桌宠管理 JS ====== */

// 交互状态
let reactiveMap = {};       // 隐藏式选项 { userOperation: choiceObj }
let currentChoices = null;  // 可见选项数组
// 管理页不显示的选项动作：这些入口属于「桌面桌宠」端
// （如「打开桌宠基础设置」= basicinfo，点击后打开桌宠设置窗口；管理页自身的编辑入口不走这里）
const MANAGER_HIDDEN_CHOICE_ACTIONS = ['basicinfo'];
let petImg = null;
let dialogBox = null;
let dialogText = null;
let dialogArrow = null;
let choicesContainer = null;
let editingId = null;
let navStack = [];          // 视图导航栈
let currentConvId = null;   // 当前查看的对话 ID（供"新增"使用）
let currentChoiceParentId = null;
let pendingCreateChoiceParent = null; // 新建子选项时用的父ID
let parentIdStack = [];
let canModCurrent = true;
let convScrollTop = 0;
let textSegments = [];
let soundVideo = null;
let soundUnlocked = false;
let inputMode = false;
let savedDialogState = null;
let continueTimer = null;       // continue 强制等待计时器
let continueLocked = false;     // 强制等待中，屏蔽交互
let typewriterTimer = null;
let talkSessionId = Date.now() + '';
let ttsAudio = null;           // TTS 音频元素
let idleEnabled = true, ttsEnabled = true;
// 桌宠记忆库（pet_memory）状态
let memoryDataMap = {};
let memoryEditingId = null;
let memoryEditData = { character_name: '阿罗娜', content: '' };

function pushView(viewName) {
    navStack.push(viewName);
updatePanelButtons();
    setPanelTitle('对话选项模块数据表');
}

function popView() {
    navStack.pop();
    updatePanelButtons();
    if (navStack.length === 0) setPanelTitle('对话功能模块数据表');
}

function setPanelTitle(title) {
    document.getElementById('panelTitle').textContent = title;
}

function updatePanelButtons() {
    const btnClose = document.getElementById('btnClose');
    const btnBack = document.getElementById('btnBack');
    if (navStack.length > 0) {
        btnClose.style.display = 'none';
        btnBack.style.display = '';
    } else {
        btnClose.style.display = '';
        btnBack.style.display = 'none';
    }
}

function goBack() {
if (navStack.length === 0) {
        currentConvId = null;
        currentChoiceParentId = null;
        parentIdStack = [];
        updatePanelButtons();
        switchToConvHeader();
        loadConversations();
        return;
    }
    popView();
    const prev = parentIdStack.pop();
if (prev) {
        canModCurrent = prev.canMod !== false;
        if (prev.type === 'conv' && navStack.length === 0) {
            switchToConvHeader();
            const scr = document.querySelector('.table-scroll');
            if (scr) scr.style.visibility = 'hidden';
            loadConversations(() => {
                if (scr && prev.scrollTop) scr.scrollTop = prev.scrollTop;
                if (scr) scr.style.visibility = '';
            });
            return;
        }
        if (prev.type === 'choice') {
            currentChoiceParentId = prev.id;
            setPanelTitle('对话选项模块数据表');
            const scr = document.querySelector('.table-scroll');
            if (scr) scr.style.visibility = 'hidden';
            axios.get('/choice/sub', { params: { choice_id: prev.id } }).then(r2 => {
                if (r2.data.motion) { showResult(r2.data); }
                else if (r2.data.data && r2.data.data.length) {
                    switchToChoiceHeader();
                    renderChoiceTable(r2.data.data);
                    if (scr && prev.scrollTop) scr.scrollTop = prev.scrollTop;
                    if (scr) scr.style.visibility = '';
                }
            });
        } else {
            currentChoiceParentId = null;
            setPanelTitle('对话选项模块数据表');
            const scr = document.querySelector('.table-scroll');
            if (scr) scr.style.visibility = 'hidden';
            axios.get('/choice', { params: { conversation_id: prev.id } }).then(r2 => {
                if (r2.data.motion) { showResult(r2.data); }
                else if (r2.data.data && r2.data.data.length) {
                    switchToChoiceHeader();
                    renderChoiceTable(r2.data.data);
                    if (scr && prev.scrollTop) scr.scrollTop = prev.scrollTop;
                    if (scr) scr.style.visibility = '';
                }
            });
        }
    } else {
        currentConvId = null;
        currentChoiceParentId = null;
        pendingCreateChoiceParent = null;
        switchToConvHeader();
        const scr = document.querySelector('.table-scroll');
        if (scr) scr.style.visibility = 'hidden';
        loadConversations(() => {
            if (scr && convScrollTop) scr.scrollTop = convScrollTop;
            if (scr) scr.style.visibility = '';
        });
    }
}

// axios 拦截器 — 自动附加 JWT token
axios.interceptors.request.use(config => {
    const token = localStorage.getItem('token');
    if (token) {
        config.headers['Authorization'] = 'Bearer ' + token;
    }
    return config;
});

// 当前登录用户信息
function currentIdentity() {
    return localStorage.getItem('identity') || '';
}

function currentUserId() {
    return localStorage.getItem('userId') || '';
}

function isAdmin() {
    return currentIdentity() !== '员工';
}

// 检查对话/选项是否属于当前用户
function isOwnData(recordUserId) {
    if (isAdmin()) return true;
    if (!recordUserId) return false;  // 公共数据(null)不属于员工
    return String(recordUserId) === currentUserId();
}

// 根据登录身份获取查询用的 user_id
function getUserId() {
    if (isAdmin()) return '';
    return currentUserId();
}

// 展示/隐藏加载状态
function setLoading(loading) {
    const tbody = document.getElementById('historyBody');
    if (loading) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;">加载中...</td></tr>';
    }
}

// 加载对话数据
function loadConversations(callback) {
    setLoading(true);
    axios.get('/conversation', { params: { user_id: getUserId() } })
        .then(result => {
            const list = result.data.data || [];
            renderTable(list);
            if (callback) callback();
        })
        .catch(err => {
            console.error('加载对话数据失败', err);
            document.getElementById('historyBody').innerHTML =
                '<tr><td colspan="6" style="text-align:center;padding:40px;color:red;">加载失败，请检查网络或登录状态</td></tr>';
        });
}

// 渲染表格
function renderTable(rows) {
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;">暂无数据</td></tr>';
        return;
    }

    const own = isAdmin();
    rows.forEach(r => { convDataMap[r.id] = r; });
    tbody.innerHTML = rows.map((r, i) => {
        const displayId = i + 1;
        const canMod = own || isOwnData(r.user_id);
        const actionBtns = canMod
            ? `<div class="btn-row"><button class="btn-add" onclick="editRow(${r.id})">编辑</button><button class="btn-delete" onclick="deleteRow(${r.id})">删除</button></div>`
            : '';
        return `<tr>
            <td>${displayId}</td>
            <td>${r.user_operation || '—'}</td>
            <td>${r.user_text || '—'}</td>
            <td>${r.boat_text || '—'}</td>
            <td><div class="motion-cell"><img src="${r.motion ? 'aluona/' + r.motion : ''}" alt="" onerror="this.style.display='none'"></div></td>
            <td>
                <div class="btn-row"><button class="btn-choice" onclick="showChoices(${r.id})">对话选项</button></div>
                ${actionBtns}
            </td>
        </tr>`;
    }).join('');
}

// 打开面板
function openPanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    navStack = [];
    updatePanelButtons();
    setPanelTitle('对话功能模块数据表');
    loadConversations();
}

// ===== 面板列宽（全局共用一个 <style>）=====
// 原因：以前每个面板各自 document.head.appendChild 一个 style（ruleColWidths / voiceColWidths /
// idleActColWidths / styleExColWidths / live2dRuleColWidths），选择器却完全相同
// （#historyTable th:nth-child(n)），优先级相同时「文档里靠后的那条」生效；
// 而返回已打开过的面板时只更新自己的 style.textContent、不会把它重新追加到末尾，
// 于是后开面板的列宽一直压着旧面板 → 语音库 4 列套上了闲置活动库 5 列的比例
// （第 3 列 48%→6%、第 4 列 35%→10%），操作列按钮被挤出并出现横向滚动条。
// 统一成一个 style 后，任何时候都只有一份列宽规则，切换面板必然生效。
function setPanelColWidths(css) {
    let st = document.getElementById('panelColWidths');
    if (!st) {
        st = document.createElement('style');
        st.id = 'panelColWidths';
        document.head.appendChild(st);
    }
    st.textContent = css || '';
}
function clearPanelColWidths() { setPanelColWidths(''); }

// ===== 表切换（面板栏 ← → 循环）=====
// 顺序即 ← → 的循环顺序；title 必须与各面板 setPanelTitle 设置的文字完全一致
const TABLE_CYCLE = [
    { title: '对话功能模块数据表', open: function() { openPanel(); } },
    { title: '本地规则库', open: function() { openRulePanel(); } },
    { title: '模型规则库', open: function() { openLive2dRulePanel(); } },
    { title: '语音库管理', open: function() { openVoicePanel(); } },
    { title: '闲置活动库', open: function() { openIdleActivityPanel(); } },
    { title: '风格语料库', open: function() { openStyleExemplarPanel(); } },
    { title: '桌宠记忆库', open: function() { openMemoryPanel(); } },
    { title: '历史对话管理', open: function() { openHistoryPanel(); } }
];

function currentTableIndex() {
    const t = (document.getElementById('panelTitle').textContent || '').trim();
    return TABLE_CYCLE.findIndex(x => x.title === t);
}

// 标题占位固定：← → 按「最长标题」的宽度定位，换到更短的标题时箭头也不移动。
// 取 TABLE_CYCLE 里最长的一条当量尺（目前是 9 个字的「对话功能模块数据表」）。
const TITLE_WIDTH_SAMPLE = TABLE_CYCLE.map(x => x.title).sort((a, b) => b.length - a.length)[0] || '对话功能模块数据表';
function fixTitleWidth() {
    const el = document.getElementById('panelTitle');
    if (!el) return;
    let probe = document.getElementById('titleWidthProbe');
    if (!probe) {
        probe = document.createElement('span');
        probe.id = 'titleWidthProbe';
        probe.style.cssText = 'position:absolute;left:-9999px;top:0;visibility:hidden;white-space:nowrap;pointer-events:none;';
        document.body.appendChild(probe);
    }
    const cs = getComputedStyle(el);
    probe.style.fontSize = cs.fontSize;
    probe.style.fontFamily = cs.fontFamily;
    probe.style.fontWeight = cs.fontWeight;
    probe.style.fontStyle = cs.fontStyle;
    probe.style.letterSpacing = cs.letterSpacing;
    probe.textContent = TITLE_WIDTH_SAMPLE;
    const w = Math.ceil(probe.getBoundingClientRect().width);
    if (w > 0) el.style.width = w + 'px';   // 覆盖 CSS 的 9em，按实际字体精确对齐
}
window.addEventListener('resize', fixTitleWidth);
document.addEventListener('DOMContentLoaded', fixTitleWidth);

/** 表切换前的公共清理（子视图栈、编辑态） */
function resetPanelState() {
    navStack = [];
    parentIdStack = [];
    currentChoiceParentId = null;
    pendingCreateChoiceParent = null;
    ruleEditingId = null; ruleEditData = {};
    memoryEditingId = null;
    document.getElementById('historyTable').style.display = '';
    document.querySelector('.panel-buttons .btn-add').style.display = '';
}

/** ← → 切换表：dir = -1 上一个 / 1 下一个 */
function switchTable(dir) {
    const n = TABLE_CYCLE.length;
    let i = currentTableIndex();
    if (i < 0) i = dir > 0 ? -1 : 0;   // 处在子视图（如对话选项）等未识别标题：→ 从第一个开始，← 从最后一个开始
    const next = ((i + dir) % n + n) % n;
    resetPanelState();
    TABLE_CYCLE[next].open();
}

// 关闭面板
function closePanel() {
    navStack = [];
    parentIdStack = [];
    currentChoiceParentId = null;
    pendingCreateChoiceParent = null;
    ruleEditingId = null; ruleEditData = {};
    switchToConvHeader();
    updatePanelButtons();
    // 清理面板状态
    document.getElementById('historyTable').style.display = '';
    document.querySelector('.panel-buttons .btn-add').style.display = '';
    clearPanelColWidths();   // 列宽样式一并清掉（恢复 CSS 默认列宽）
    document.getElementById('panelTitle').textContent = '对话功能模块数据表';
    document.getElementById('historyPanel').style.display = 'none';
}

// ===== 规则库管理 =====
let ruleEditData = {};
let ruleEditingId = null;
let ruleDataMap = {};

function openRulePanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    document.getElementById('panelTitle').textContent = '本地规则库';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    document.querySelector('.panel-buttons .btn-add').style.display = 'none';
    document.getElementById('historyTable').style.display = '';
    // 切换表头
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = '<th>序号</th><th>桌宠文本</th><th>关键词</th><th>表情</th><th>操作</th>';
    // 覆盖列宽：表情窄，操作宽
    setPanelColWidths('#historyTable th:nth-child(1),#historyTable td:nth-child(1){width:3%;}#historyTable th:nth-child(2),#historyTable td:nth-child(2){width:42%;}#historyTable th:nth-child(3),#historyTable td:nth-child(3){width:15%;}#historyTable th:nth-child(4),#historyTable td:nth-child(4){width:12%;}#historyTable th:nth-child(5),#historyTable td:nth-child(5){width:28%;}');
    loadRules();
}

function loadRules() {
    axios.get('/ai/rules').then(r => {
        renderRuleTable(r.data.data || []);
    });
}

function renderRuleTable(rows) {
    ruleDataMap = {};
    rows.forEach(r => { ruleDataMap[r.id] = r; });
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;">暂无规则</td></tr>';
        return;
    }
    tbody.innerHTML = rows.map((r, i) => `<tr>
        <td>${i + 1}</td>
        <td style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${(r.sample_text||'').replace(/"/g,'&quot;')}">${r.sample_text || '—'}</td>
        <td>${r.keyword || '—'}</td>
        <td><div class="motion-cell"><img src="${r.motion ? 'aluona/' + r.motion : ''}" alt="" onerror="this.style.display='none'"></div></td>
        <td>
            <div class="btn-row">
                <button class="btn-add" onclick="editRuleRow(${r.id})">编辑</button>
                <button class="btn-delete" onclick="deleteRuleRow(${r.id})">删除</button>
            </div>
        </td>
    </tr>`).join('');
}

function editRuleRow(id) {
    ruleEditingId = id;
    const full = ruleDataMap[id] || {};
    loadAluonaMotions(() => {
        const tbody = document.getElementById('historyBody');
        const rows = tbody.querySelectorAll('tr');
        rows.forEach(row => {
            const cells = row.querySelectorAll('td');
            if (cells.length < 5) return;
            const btn = cells[4].querySelector('button');
            if (!btn) return;
            const onclick = btn.getAttribute('onclick') || '';
            if (onclick.includes('editRuleRow(' + id + ')')) {
                const seParts = (full.special_effect || '').split(';');
                const firstIsAction = seParts[0] && !seParts[0].includes(':');
                ruleEditData = {
                    id: id,
                    keyword: full.keyword || '',
                    motion: full.motion || '',
                    sound: full.sound_effect || '',
                    action: firstIsAction ? seParts[0] : '',
                    special_effect: full.special_effect || '',
                    sample_text: full.sample_text || ''
                };
                cells[2].innerHTML = `<input class="edit-input" value="${ruleEditData.keyword}" style="width:100%;text-align:center;" oninput="ruleEditData.keyword=this.value">`;
                cells[3].innerHTML = `<div class="motion-cell"><img src="aluona/${ruleEditData.motion}" alt="" onerror="this.style.display='none'" onclick="openMotionPopup()"></div>`;
                cells[4].innerHTML = `<div class="btn-row"><button class="btn-complete" onclick="submitRuleEdit(${id})">完成</button><button class="btn-close" onclick="cancelRuleEdit()">取消</button></div>`;
            }
        });
    });
}

function openRuleMotionPopup() {
    openMotionPopup(); // reuse existing motion popup, but with ruleEditData
}

function submitRuleEdit(id) {
    if (!ruleEditData.motion) { alert('请选择表情'); return; }
    axios.put('/ai/rules', {
        id: id,
        keyword: ruleEditData.keyword || '',
        motion: ruleEditData.motion,
        sound_effect: ruleEditData.sound || '',
        special_effect: ruleEditData.special_effect || ''
    }).then(() => {
        ruleEditingId = null; ruleEditData = {};
        loadRules();
    });
}

function cancelRuleEdit() {
    ruleEditingId = null; ruleEditData = {};
    loadRules();
}

function deleteRuleRow(id) {
    if (!confirm('确认删除此规则？')) return;
    axios.delete('/ai/rules/' + id).then(() => loadRules());
}

// ===== 语音库管理 =====
let voiceDataMap = {};
let voiceCandidateMap = {};

function openVoicePanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    document.getElementById('panelTitle').textContent = '语音库管理';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    document.querySelector('.panel-buttons .btn-add').style.display = 'none';
    document.getElementById('historyTable').style.display = '';
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = '<th>序号</th><th>用户操作</th><th>桌宠回复</th><th>操作</th>';
    // 试听列已并入操作列：4 列（序号/用户操作/桌宠回复/操作）
    setPanelColWidths('#historyTable th:nth-child(1),#historyTable td:nth-child(1){width:5%;}#historyTable th:nth-child(2),#historyTable td:nth-child(2){width:12%;}#historyTable th:nth-child(3),#historyTable td:nth-child(3){width:48%;}#historyTable th:nth-child(4),#historyTable td:nth-child(4){width:35%;}');
    loadVoices();
}

function loadVoices() {
    axios.get('/basic-responses').then(r => {
        renderVoiceTable(r.data.data || []);
    }).catch(() => {
        document.getElementById('historyBody').innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:red;">加载失败</td></tr>';
    });
}

function renderVoiceTable(rows) {
    voiceDataMap = {};
    rows.forEach(r => { voiceDataMap[r.key] = r; });
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;">暂无基础响应</td></tr>';
        return;
    }
    const warmupBtn = `<tr><td colspan="4" style="text-align:right;padding:6px 12px;">
        <button class="btn-add" onclick="voiceWarmup()" style="width:400px;height:72px;font-size:20px;display:inline-flex;padding:0 18px;">全部生成（缓存缺失语音）</button>
    </td></tr>`;
    tbody.innerHTML = warmupBtn + rows.map((r, i) => {
        const txt = (r.boat_text || '').replace(/"/g, '&quot;');
        const op = (r.user_operation || '—') + (r.key && r.key.indexOf('choice_') === 0 ? '（选项）' : '');
        return `<tr data-voice-key="${r.key}">
            <td>${i + 1}</td>
            <td style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${op}">${op}</td>
            <td style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${txt}">${r.boat_text || '—'}</td>
            <td style="white-space:nowrap;">
                <button class="btn-choice" style="display:inline-flex;width:110px;height:45px;font-size:16px;margin:0 6px 4px 0;vertical-align:middle;" onclick="voicePreview('${r.key}')">试听</button>
                <button class="btn-add" style="display:inline-flex;width:130px;height:45px;font-size:16px;margin:0 0 4px 6px;vertical-align:middle;" onclick="voiceRegen('${r.key}')">重新生成</button>
            </td>
        </tr>`;
    }).join('');
}

function playVoiceData(base64) {
    if (!ttsAudio) { ttsAudio = new Audio(); document.body.appendChild(ttsAudio); }
    ttsAudio.src = 'data:audio/wav;base64,' + base64;
    ttsAudio.play().catch(() => {});
}

function voicePreview(key) {
    const r = voiceDataMap[key];
    if (!r || !r.boat_text) return;
    axios.post('/ai/tts', { text: r.boat_text }).then(res => {
        if (res.data.code === 1 && res.data.data) playVoiceData(res.data.data);
        else alert('合成失败，请检查 TTS 服务');
    }).catch(() => alert('请求失败'));
}

function voiceRegen(key) {
    const r = voiceDataMap[key];
    if (!r || !r.boat_text) return;
    if (!confirm('重新生成这条语音？将生成 3 个候选供试听选择（约需数秒）')) return;
    axios.get('/ai/tts/candidates', { params: { text: r.boat_text } }).then(res => {
        if (res.data.code === 1 && Array.isArray(res.data.data) && res.data.data.length) {
            voiceCandidateMap[key] = res.data.data;
            renderVoiceCandidates(key);
        } else {
            alert('生成失败，请检查 TTS 服务');
        }
    }).catch(() => alert('请求失败'));
}

// 把这条基础交互的缓存语音 + 文本设为 TTS 参考音频
function voiceSetRef(key) {
    const r = voiceDataMap[key];
    if (!r || !r.boat_text) return;
    axios.post('/ai/tts/reference/add', { text: r.boat_text }).then(res => {
        if (res.data.code === 1) alert('已设为参考音频');
        else alert(res.data.message || '设置失败');
    }).catch(() => alert('请求失败'));
}

function renderVoiceCandidates(key) {
    const cands = voiceCandidateMap[key];
    if (!cands || !cands.length) return;
    const tbody = document.getElementById('historyBody');
    const old = document.getElementById('voiceCandRow_' + key);
    if (old) old.remove();
    const targetRow = tbody.querySelector('tr[data-voice-key="' + key + '"]');
    if (!targetRow) return;
    const candRow = document.createElement('tr');
    candRow.id = 'voiceCandRow_' + key;
    candRow.innerHTML = `<td colspan="4" style="padding:10px;background:rgba(122,203,246,0.08);">
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;">
            <span style="color:#7ACBF6;font-size:16px;">候选：</span>
            ${cands.map((c, i) => `
                <button class="btn-choice" onclick="voiceCandPreview('${key}', ${i})">试听${i + 1}</button>
                <button class="btn-add" onclick="voiceCandSelect('${key}', ${i})">选用${i + 1}</button>
            `).join('')}
        </div>
    </td>`;
    targetRow.after(candRow);
}

function voiceCandPreview(key, i) {
    const cands = voiceCandidateMap[key];
    if (cands && cands[i]) playVoiceData(cands[i]);
}

function voiceCandSelect(key, i) {
    const r = voiceDataMap[key];
    const cands = voiceCandidateMap[key];
    if (!r || !cands || !cands[i]) return;
    axios.post('/ai/tts/select', { text: r.boat_text, audio: cands[i] }).then(res => {
        if (res.data.code === 1) {
            const old = document.getElementById('voiceCandRow_' + key);
            if (old) old.remove();
            delete voiceCandidateMap[key];
            alert('已保存，之后对话将使用该语音');
        } else {
            alert('保存失败');
        }
    }).catch(() => alert('请求失败'));
}

function voiceWarmup() {
    if (!confirm('批量生成所有缺失的语音？将在后台执行，可能需要较长时间')) return;
    axios.post('/ai/tts/warmup').then(res => {
        alert(res.data.message || '已开始生成');
    }).catch(() => alert('请求失败'));
}

// ===== 闲置活动库管理 =====
let idleActivityDataMap = {};
let idleActivityEditingId = null;
let idleActivityEditData = {};

function openIdleActivityPanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    document.getElementById('panelTitle').textContent = '闲置活动库';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    document.querySelector('.panel-buttons .btn-add').style.display = '';
    document.getElementById('historyTable').style.display = '';
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = '<th>序号</th><th>活动描述</th><th>分类</th><th>状态</th><th>操作</th>';
    setPanelColWidths('#historyTable th:nth-child(1),#historyTable td:nth-child(1){width:5%;}#historyTable th:nth-child(2),#historyTable td:nth-child(2){width:55%;}#historyTable th:nth-child(3),#historyTable td:nth-child(3){width:6%;}#historyTable th:nth-child(4),#historyTable td:nth-child(4){width:10%;}#historyTable th:nth-child(5),#historyTable td:nth-child(5){width:24%;}');
    loadIdleActivities();
}

function loadIdleActivities() {
    axios.get('/ai/idle-activities').then(r => {
        renderIdleActivityTable(r.data.data || []);
    }).catch(() => {
        document.getElementById('historyBody').innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:red;">加载失败</td></tr>';
    });
}

function idleCategoryLabel(cat) {
    return cat === 'continuable' ? '延续性' : cat === 'sleep' ? '睡觉' : '单发';
}

function idleCategorySelect(selected) {
    const opts = [['continuable', '延续性'], ['simple', '单发'], ['sleep', '睡觉']];
    return '<select class="edit-input" style="width:100%;text-align:center;" onchange="idleActivityEditData.category=this.value">' +
        opts.map(o => `<option value="${o[0]}" ${o[0] === selected ? 'selected' : ''}>${o[1]}</option>`).join('') + '</select>';
}

function renderIdleActivityTable(rows) {
    idleActivityDataMap = {};
    rows.forEach(r => { idleActivityDataMap[r.id] = r; });
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;">暂无活动</td></tr>';
        return;
    }
    tbody.innerHTML = rows.map((r, i) => `<tr>
        <td>${i + 1}</td>
        <td style="max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${(r.theme || '').replace(/"/g, '&quot;')}">${r.theme || '—'}</td>
        <td>${idleCategoryLabel(r.category)}</td>
        <td>${r.enabled === 1 ? '<span style="color:#7ACBF6;">启用</span>' : '<span style="color:#999;">禁用</span>'}</td>
        <td>
            <div class="btn-row">
                <button class="btn-add" onclick="editIdleActivityRow(${r.id})">编辑</button>
                <button class="btn-choice" onclick="toggleIdleActivity(${r.id})">${r.enabled === 1 ? '停用' : '启用'}</button>
                <button class="btn-delete" onclick="deleteIdleActivityRow(${r.id})">删除</button>
            </div>
        </td>
    </tr>`).join('');
}

function addIdleActivityRow() {
    idleActivityEditingId = null;
    idleActivityEditData = { theme: '', category: 'continuable', enabled: 1 };
    const tbody = document.getElementById('historyBody');
    const row = document.createElement('tr');
    row.innerHTML = `<td>+</td>
        <td><input class="edit-input" style="width:100%;" placeholder="输入活动描述" oninput="idleActivityEditData.theme=this.value"></td>
        <td>${idleCategorySelect('continuable')}</td>
        <td><span style="color:#7ACBF6;">启用</span></td>
        <td><div class="btn-row"><button class="btn-complete" onclick="saveIdleActivity()">完成</button><button class="btn-close" onclick="loadIdleActivities()">取消</button></div></td>`;
    tbody.prepend(row);
}

function editIdleActivityRow(id) {
    const full = idleActivityDataMap[id] || {};
    idleActivityEditingId = id;
    idleActivityEditData = { theme: full.theme || '', category: full.category || 'simple', enabled: full.enabled === 1 ? 1 : 0 };
    const tbody = document.getElementById('historyBody');
    tbody.querySelectorAll('tr').forEach(row => {
        const cells = row.querySelectorAll('td');
        if (cells.length < 5) return;
        const btn = cells[4].querySelector('button');
        if (!btn) return;
        if ((btn.getAttribute('onclick') || '').includes('editIdleActivityRow(' + id + ')')) {
            cells[1].innerHTML = `<input class="edit-input" style="width:100%;" value="${idleActivityEditData.theme.replace(/"/g, '&quot;')}" oninput="idleActivityEditData.theme=this.value">`;
            cells[2].innerHTML = idleCategorySelect(idleActivityEditData.category);
            cells[3].innerHTML = `<select class="edit-input" style="width:100%;text-align:center;" onchange="idleActivityEditData.enabled=parseInt(this.value)"><option value="1" ${idleActivityEditData.enabled === 1 ? 'selected' : ''}>启用</option><option value="0" ${idleActivityEditData.enabled === 0 ? 'selected' : ''}>禁用</option></select>`;
            cells[4].innerHTML = `<div class="btn-row"><button class="btn-complete" onclick="saveIdleActivity()">完成</button><button class="btn-close" onclick="loadIdleActivities()">取消</button></div>`;
        }
    });
}

function saveIdleActivity() {
    const d = idleActivityEditData || {};
    if (!d.theme || !d.theme.trim()) { alert('请填写活动描述'); return; }
    const payload = { theme: d.theme, category: d.category, enabled: d.enabled };
    const done = () => { idleActivityEditingId = null; idleActivityEditData = {}; loadIdleActivities(); };
    if (idleActivityEditingId) {
        payload.id = idleActivityEditingId;
        axios.put('/ai/idle-activities', payload).then(done).catch(() => alert('保存失败'));
    } else {
        axios.post('/ai/idle-activities', payload).then(done).catch(() => alert('保存失败'));
    }
}

function toggleIdleActivity(id) {
    const r = idleActivityDataMap[id];
    if (!r) return;
    axios.put('/ai/idle-activities', { id: id, theme: r.theme, category: r.category, enabled: r.enabled === 1 ? 0 : 1 }).then(() => loadIdleActivities()).catch(() => alert('操作失败'));
}

function deleteIdleActivityRow(id) {
    if (!confirm('确认删除此活动？')) return;
    axios.delete('/ai/idle-activities/' + id).then(() => loadIdleActivities()).catch(() => alert('删除失败'));
}

// ===== 风格语料库（阶段四） =====
let styleExemplarEditData = { tags: '', content: '' };

function openStyleExemplarPanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    document.getElementById('panelTitle').textContent = '风格语料库';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    document.querySelector('.panel-buttons .btn-add').style.display = '';
    document.getElementById('historyTable').style.display = '';
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = '<th>序号</th><th>触发标签</th><th>语料内容（场景台词，多行）</th><th>操作</th>';
    setPanelColWidths('#historyTable th:nth-child(1),#historyTable td:nth-child(1){width:5%;}#historyTable th:nth-child(2),#historyTable td:nth-child(2){width:20%;}#historyTable th:nth-child(3),#historyTable td:nth-child(3){width:55%;}#historyTable th:nth-child(4),#historyTable td:nth-child(4){width:20%;}');
    loadStyleExemplars();
}

function loadStyleExemplars() {
    axios.get('/ai/style-exemplars').then(r => {
        renderStyleExemplarTable(r.data.data || []);
    }).catch(() => {
        document.getElementById('historyBody').innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;color:red;">加载失败</td></tr>';
    });
}

function renderStyleExemplarTable(rows) {
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;padding:40px;">暂无语料，点右上角「新增」粘贴场景台词</td></tr>';
        return;
    }
    const esc = s => (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;');
    tbody.innerHTML = rows.map((r, i) => `<tr>
        <td>${i + 1}</td>
        <td style="word-break:break-all;">${esc(r.tags)}</td>
        <td style="text-align:left;white-space:pre-wrap;word-break:break-word;max-width:520px;">${esc(r.content)}</td>
        <td><div class="btn-row"><button class="btn-delete" onclick="deleteStyleExemplarRow(${r.id})">删除</button></div></td>
    </tr>`).join('');
}

function addStyleExemplarRow() {
    styleExemplarEditData = { tags: '', content: '' };
    const tbody = document.getElementById('historyBody');
    const row = document.createElement('tr');
    row.innerHTML = `<td>+</td>
        <td><input class="edit-input" style="width:100%;" placeholder="概括性标签，用、或,分隔（如：被戳穿、偷懒、被抓包）" oninput="styleExemplarEditData.tags=this.value"></td>
        <td style="text-align:left;"><textarea class="edit-input" rows="6" style="width:100%;white-space:pre-wrap;" placeholder="粘贴对话场景：每行=一个气泡；括号=表情/动作；「内心os：」=内心独白；换人处标「阿罗娜：」" oninput="styleExemplarEditData.content=this.value"></textarea></td>
        <td><div class="btn-row"><button class="btn-complete" onclick="saveStyleExemplar()">完成</button><button class="btn-close" onclick="loadStyleExemplars()">取消</button></div></td>`;
    tbody.prepend(row);
}

function saveStyleExemplar() {
    const d = styleExemplarEditData || {};
    if (!d.content || !d.content.trim()) { alert('请粘贴语料内容'); return; }
    if (!d.tags || !d.tags.trim()) { alert('请填写触发标签'); return; }
    axios.post('/ai/style-exemplars', { tags: d.tags, content: d.content }).then(() => {
        loadStyleExemplars();
    }).catch(() => alert('保存失败'));
}

function deleteStyleExemplarRow(id) {
    if (!confirm('确认删除这条语料？')) return;
    axios.delete('/ai/style-exemplars/' + id).then(() => loadStyleExemplars()).catch(() => alert('删除失败'));
}

// 分发新增操作
function handleAdd() {
    const title = document.getElementById('panelTitle').textContent;
    if (title === '闲置活动库') {
        addIdleActivityRow();
    } else if (title === '风格语料库') {
        addStyleExemplarRow();
    } else if (title === '桌宠记忆库') {
        addMemoryRow();
    } else if (navStack.length > 0) {
        addChoice();
    } else {
        addConversation();
    }
}

// 新增对话
function addConversation() {
    axios.post('/conversation', {
        user_id: isAdmin() ? null : currentUserId(),
        user_operation: 'act',
        user_text: '',
        boat_text: '新对话',
        motion: 'aluona_kaixin.png',
        role: 'basic'
    }).then(() => {
        loadConversations(() => {
            const scroll = document.querySelector('.table-scroll');
            if (scroll) scroll.scrollTop = scroll.scrollHeight;
        });
    }).catch(err => {
        console.error('新增失败', err);
        alert('新增失败：' + (err.response ? err.response.status : err.message));
    });
}

// 新增选项
function addChoice() {
    if (currentChoiceParentId) {
        // 子选项模式
        const p = new URLSearchParams();
        p.append('parent_id', currentChoiceParentId);
        axios.post('/choice/sub', p).then(() => reloadCurrentView());
    } else if (currentConvId) {
        // 顶层选项模式
        const p = new URLSearchParams();
        p.append('conversation_id', currentConvId);
        axios.post('/choice', p).then(() => reloadCurrentView());
    }
}

// 刷新当前视图（直接调 API，不走 showChoices 避免模式判断错误）
function reloadCurrentView() {
    if (currentChoiceParentId) {
        // 子选项模式
        axios.get('/choice/sub', { params: { choice_id: currentChoiceParentId } }).then(r2 => {
            if (r2.data.motion) { showResult(r2.data); }
            else if (r2.data.data && r2.data.data.length) {
                switchToChoiceHeader();
                renderChoiceTable(r2.data.data);
            }
        });
    } else if (currentConvId) {
        axios.get('/choice', { params: { conversation_id: currentConvId } }).then(r2 => {
            if (r2.data.motion) { showResult(r2.data); }
            else if (r2.data.data && r2.data.data.length) {
                if (navStack.length === 0) {
                    pushView('choice');
                    const scr = document.querySelector('.table-scroll');
                    parentIdStack.push({ type: 'conv', id: currentConvId, canMod: canModCurrent, scrollTop: scr ? scr.scrollTop : 0 });
                }
                switchToChoiceHeader();
                renderChoiceTable(r2.data.data);
            }
        });
    }
}

// 切换表格表头为对话选项列
function switchToChoiceHeader() {
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = `<th>序号</th><th>选项操作</th><th>选项文本</th><th>桌宠回复</th><th>表情</th><th>操作</th>`;
    // 去掉动态列宽，恢复 CSS 默认列宽
    clearPanelColWidths();
}

// 恢复对话功能表头
function switchToConvHeader() {
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = `<th>序号</th><th>用户操作</th><th>用户文本</th><th>桌宠回复</th><th>表情</th><th>操作</th>`;
    clearPanelColWidths();
}

// 渲染选项表格
function renderChoiceTable(rows) {
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;">暂无选项数据</td></tr>';
        return;
    }
    rows.forEach(c => { choiceDataMap[c.id] = c; });
    tbody.innerHTML = rows.map((c, i) => {
        const btns = canModCurrent
            ? `<div class="btn-row"><button class="btn-add" onclick="startChoiceEdit(${c.id})">编辑</button><button class="btn-delete" onclick="requestDeleteChoice(${c.id})">删除</button></div>`
            : '';
        return `<tr data-choice-id="${c.id}">
            <td>${i + 1}</td>
            <td>${c.choiceAction || '—'}</td>
            <td>${c.choiceText || '—'}</td>
            <td>${c.boatText || '—'}</td>
            <td><div class="motion-cell"><img src="${c.motion ? 'aluona/' + c.motion : ''}" alt="" onerror="this.style.display='none'"></div></td>
            <td>
                <div class="btn-row"><button class="btn-choice" onclick="showChoices(${c.id})">对话选项</button></div>
                ${btns}
            </td>
        </tr>`;
    }).join('');
}

// 查看选项 → 进入子视图
function showChoices(id) {
    if (navStack.length === 0) {
        const scr = document.querySelector('.table-scroll');
        convScrollTop = scr ? scr.scrollTop : 0;
        currentConvId = id;
        pendingCreateChoiceParent = null;
        axios.get('/conversation', { params: { user_id: getUserId() } }).then(r => {
            const list = r.data.data || [];
            const conv = list.find(c => c.id === id);
            if (!conv || !conv.choice_id || conv.choice_id <= 0) {
                if (isOwnData(conv ? conv.user_id : null)) {
                    callReaction('search_choiceFail');
                } else {
                    callReaction('search_choiceFail2');
                }
                return;
            }
            axios.get('/choice', { params: { conversation_id: id } }).then(r2 => {
                if (r2.data.motion) {
                    showResult(r2.data);
                } else if (r2.data.data && r2.data.data.length) {
                    pushView('choice');
                    const scr = document.querySelector('.table-scroll');
                    parentIdStack.push({ type: 'conv', id: currentConvId, canMod: canModCurrent, scrollTop: scr ? scr.scrollTop : 0 });
                    currentChoiceParentId = null;
                    canModCurrent = isOwnData(conv.user_id);
                    switchToChoiceHeader();
                    renderChoiceTable(r2.data.data);
                }
            });
        });
    } else {
        // 对话选项模块 → 查子选项（先入栈，空则弹出）
        const prevParentId = currentChoiceParentId;
        currentChoiceParentId = id;
        pendingCreateChoiceParent = id;  // 保存用于新建操作
        const scr = document.querySelector('.table-scroll');
        const top = scr ? scr.scrollTop : 0;
        parentIdStack.push(prevParentId
            ? { type: 'choice', id: prevParentId, canMod: canModCurrent, scrollTop: top }
            : { type: 'conv', id: currentConvId, canMod: canModCurrent, scrollTop: top });
        pushView('choice');
        axios.get('/choice/sub', { params: { choice_id: id } }).then(r2 => {
            if (r2.data.motion) {
                if (canModCurrent) { showResult(r2.data); }
                else { callReaction('search_choiceFail2'); }
            } else if (r2.data.data && r2.data.data.length) {
                switchToChoiceHeader();
                renderChoiceTable(r2.data.data);
                r2.data.data.forEach(c => {
                    const uop = c.userOperation || 'chat';
                    if (uop !== 'chat') {
                        const cm = uop.match(/^continue(\d+)$/);
                        if (cm && !continueLocked) startContinueChain(parseInt(cm[1]), c);
                    }
                });
            }
        });
    }
}

// 加载 aluona 表情列表
function loadAluonaMotions(callback) {
    if (aluonaMotions.length) { callback(); return; }
    axios.get('/motions/aluona').then(r => {
        aluonaMotions = r.data.data || [];
        callback();
    });
}

// 生成表情下拉选项
let savedPopupScrollTop = 0;
let popupOriginals = {};

// 获取当前编辑数据对象（对话 or 选项）
function getEditData() {
    if (ruleEditingId) return ruleEditData;
    return choiceEditingId ? choiceEditData : editData;
}

function openMotionPopup() {
    const currentMotion = getEditData().motion || '';
    const currentSound = getEditData().sound || '';
    const currentAction = getEditData().action || '';
    // 保存原值
    popupOriginals = {
        motion: currentMotion,
        sound: currentSound,
        action: currentAction,
        special_effect: getEditData().special_effect || ''
    };
    const scr = document.querySelector('.table-scroll');
    if (scr) savedPopupScrollTop = scr.scrollTop;
    const popup = document.getElementById('motionPopup');
    const loadContent = () => {
        const motionOpts = aluonaMotions.map(m =>
            `<option value="${m}" ${m === currentMotion ? 'selected' : ''}>${m}</option>`
        ).join('');
        let effectOpts = '<option value="">—</option>';
        if (window.effectList) {
            effectOpts += window.effectList.map(e => {
                const sel = e.soundeffect === currentSound ? 'selected' : '';
                return `<option value="${e.soundeffect}" ${sel}>${e.soundeffect}</option>`;
            }).join('');
        }
        popup.innerHTML = `
            <div class="motion-popup-left">
                <img id="popupPreviewImg" src="aluona/${currentMotion}" alt="" onerror="this.style.display='none'">
            </div>
            <div class="motion-popup-right">
                <div><label>表情：</label>
                    <select id="popupMotion" onchange="onPopupMotionChange()">${motionOpts}</select></div>
                <div><label>特效：</label>
                    <select id="popupSound" onchange="onPopupSoundChange()">${effectOpts}</select></div>
                <div><label>动作：</label>
                    <input type="text" id="popupAction" value="${currentAction}" placeholder="如 bounce"></div>
                <div class="motion-popup-buttons" style="margin-top:auto;">
                    <button class="btn-choice" onclick="previewEffect()">效果预览</button>
                </div>
                <div class="motion-popup-buttons">
                    <button class="btn-add" onclick="confirmMotionPopup()">确定</button>
                    <button class="btn-close" onclick="closeMotionPopup()">取消</button>
                </div>
            </div>`;
        popup.classList.add('show');
        document.getElementById('historyTable').style.display = 'none';
    };

    if (!aluonaMotions.length) {
        loadAluonaMotions(loadContent);
    } else {
        loadContent();
    }
}

function onPopupMotionChange() {
    const val = document.getElementById('popupMotion').value;
    if (val) {
        getEditData().motion = val;
        const img = document.getElementById('popupPreviewImg');
        if (img) img.src = 'aluona/' + val;
    }
}

function onPopupSoundChange() {
    const sel = document.getElementById('popupSound');
    const val = sel.value;
    if (window.effectList && val) {
        const eff = window.effectList.find(e => e.soundeffect === val);
        if (eff && eff.location) {
            getEditData().special_effect = (getEditData().special_effect || '').replace(/^bounce;?/, '') + eff.location;
        }
    }
}

function confirmMotionPopup() {
    getEditData().motion = document.getElementById('popupMotion').value || getEditData().motion;
    getEditData().sound = document.getElementById('popupSound').value || '';
    getEditData().action = document.getElementById('popupAction').value.trim() || '';
    const parts = [];
    if (getEditData().action) parts.push(getEditData().action);
    if (getEditData().sound && window.effectList) {
        const eff = window.effectList.find(e => e.soundeffect === getEditData().sound);
        if (eff && eff.location) parts.push(eff.location);
    }
    getEditData().special_effect = parts.join(';');
    const img = document.querySelector('#historyBody tr .motion-cell img');
    if (img && getEditData().motion) img.src = 'aluona/' + getEditData().motion;
    popupOriginals = {};    // 清空原值，避免被覆盖
    closeMotionPopup();
}

function previewEffect() {
    const sound = document.getElementById('popupSound').value;
    const motion = document.getElementById('popupMotion').value;
    const action = document.getElementById('popupAction').value.trim();
    const se = ''; // previewEffect 无外部 specialEffect

    // 切换表情
    if (motion) {
        document.getElementById('petImg').src = 'aluona/' + motion;
    }
    // 播放音效
    if (sound) {
        const src = sound.startsWith('soundeffects/') || sound.startsWith('http')
            ? sound : 'soundeffects/' + sound;
        if (!soundVideo) {
            soundVideo = document.createElement('video');
            soundVideo.className = 'effect-video';
            soundVideo.muted = true;
            soundVideo.playsInline = true;
            document.getElementById('petImg').parentElement.appendChild(soundVideo);
        }
        // 应用 effect 表中的位置参数
        soundVideo.style.cssText = '';
        if (window.effectList) {
            const eff = window.effectList.find(e => e.soundeffect === sound);
            if (eff && eff.location) {
                eff.location.split(';').forEach(pair => {
                    const [k, v] = pair.split(':');
                    if (k && v) soundVideo.style.setProperty(k.trim(), v.trim());
                });
            }
        }
        soundVideo.src = src;
        soundVideo.currentTime = 0;
        soundVideo.style.display = '';
        if (soundUnlocked) soundVideo.muted = false;
        const isLoop = se && se.includes('loop');
        soundVideo.loop = isLoop;
        if (!isLoop) soundVideo.onended = () => { soundVideo.style.display = 'none'; };
        soundVideo.play().catch(() => {});
    }
    // 触发动作
    if (action) {
        const petImg = document.getElementById('petImg');
        if (action === 'bounce') {
            petImg.classList.add('pet-bounce');
            setTimeout(() => petImg.classList.remove('pet-bounce'), 400);
        } else if (action === 'swing') {
            petImg.classList.add('pet-swing');
            setTimeout(() => petImg.classList.remove('pet-swing'), 500);
        } else if (action === 'drop') {
            petImg.classList.add('pet-drop');
            setTimeout(() => petImg.classList.remove('pet-drop'), 450);
        } else if (action === 'spin') {
            petImg.classList.add('pet-spin');
            setTimeout(() => petImg.classList.remove('pet-spin'), 650);
        } else if (action === 'spin2') {
            petImg.classList.add('pet-spin2');
            setTimeout(() => petImg.classList.remove('pet-spin2'), 1050);
        } else if (action === 'spin3') {
            petImg.classList.add('pet-spin3');
            setTimeout(() => petImg.classList.remove('pet-spin3'), 1450);
        } else if (action === 'tilt') {
            petImg.classList.add('pet-tilt');
            petImg.addEventListener('animationend', function handler() {
                petImg.classList.remove('pet-tilt');
                petImg.removeEventListener('animationend', handler);
            });
        } else if (action === 'escape') {
            document.querySelectorAll('#petImg, .effect-video').forEach(el => {
                el.classList.add('pet-escape');
                setTimeout(() => el.classList.remove('pet-escape'), 4600);
            });
        }
    }
}
function closeMotionPopup() {
    // 仅在取消时恢复原值（popupOriginals 不为空 = 未点确定直接关）
    if (popupOriginals.motion !== undefined) {
        getEditData().motion = popupOriginals.motion;
        getEditData().sound =popupOriginals.sound || '';
        getEditData().action = popupOriginals.action || '';
        getEditData().special_effect = popupOriginals.special_effect || '';
    }
    popupOriginals = {};

    document.getElementById('motionPopup').classList.remove('show');
    const scr = document.querySelector('.table-scroll');
    const table = document.getElementById('historyTable');
    if (table) {
        table.style.visibility = 'hidden';
        table.style.display = '';
    }
    if (scr && savedPopupScrollTop) scr.scrollTop = savedPopupScrollTop;
    if (table) requestAnimationFrame(() => { table.style.visibility = ''; });
}
function selectMotionFromPopup(motion) {
    getEditData().motion = motion;
    closeMotionPopup();
    // 更新编辑行中的预览图片
    const tbody = document.getElementById('historyBody');
    const row = tbody.querySelector('tr[data-choice-id]') || tbody.querySelector('tr');
    if (row) {
        const img = row.querySelector('.motion-cell img');
        if (img) img.src = 'aluona/' + motion;
    }
}

function toggleMotionSelect(img) {
    const sel = img.closest('td').querySelector('.motion-select-float');
    const show = sel.style.display === 'none';
    if (show) {
        const rect = img.getBoundingClientRect();
        sel.style.position = 'fixed';
        sel.style.zIndex = '200';
        sel.style.left = rect.left + 'px';
        sel.style.top = rect.bottom + 'px';
        sel.style.minWidth = rect.width + 'px';
    }
    sel.style.display = show ? 'block' : 'none';
    if (show) sel.focus();
}

function onMotionEditChange(sel) {
    getEditData().motion = sel.value;
    const img = sel.closest('td').querySelector('.motion-cell img');
    if (img) img.src = 'aluona/' + sel.value;
}

function motionDropdownHtml(currentMotion) {
    return aluonaMotions.map(m => {
        const sel = m === currentMotion ? 'selected' : '';
        return `<option value="${m}" ${sel}>${m}</option>`;
    }).join('');
}

// 进入编辑模式
function editRow(id) {
    if (!canModCurrent) { callReaction('permissionDeny'); return; }
    editingId = id;
    editData = {};
    loadAluonaMotions(() => {
        const tbody = document.getElementById('historyBody');
        const rows = tbody.querySelectorAll('tr');
        rows.forEach(row => {
            const cells = row.querySelectorAll('td');
            if (cells.length < 6) return;
            const firstText = cells[0].textContent.trim();
            if (parseInt(firstText) !== id) return;

            // 缓存原始数据
            const orig = {
                user_operation: cells[1].textContent.trim(),
                user_text: cells[2].textContent.trim(),
                boat_text: cells[3].textContent.trim(),
                motion: cells[4].querySelector('img') ? cells[4].querySelector('img').src.replace(/.*\//, '') : ''
            };
            // 预填入 editData，不修改时保持原值
            editData.user_operation = orig.user_operation === '—' ? '' : orig.user_operation;
            editData.user_text = orig.user_text === '—' ? '' : orig.user_text;
            editData.boat_text = orig.boat_text === '—' ? '' : orig.boat_text;
            getEditData().motion = orig.motion;
            // 音效/特效从缓存读取
            const origConv = convDataMap[id] || {};
            getEditData().sound = origConv.sound_effect || '';
            const sePartsConv = (origConv.special_effect || '').split(';');
            getEditData().action = (sePartsConv[0] && !sePartsConv[0].includes(':')) ? sePartsConv[0] : '';
            getEditData().special_effect = origConv.special_effect || '';

            // 用户操作 → input
            cells[1].innerHTML = `<input class="edit-input" data-field="user_operation" value="${orig.user_operation === '—' ? '' : orig.user_operation}" style="width:100%;text-align:center;">`;
            // 用户文本 → input
            cells[2].innerHTML = `<input class="edit-input" data-field="user_text" value="${orig.user_text === '—' ? '' : orig.user_text}" style="width:100%;text-align:center;">`;
            // 桌宠回复 → input
            cells[3].innerHTML = `<input class="edit-input" data-field="boat_text" value="${orig.boat_text === '—' ? '' : orig.boat_text}" style="width:100%;text-align:center;">`;
            // 表情 → 图片 + 弹出选择容器
            const currentMotionName = getEditData().motion || orig.motion;
            cells[4].innerHTML = `<div class="motion-cell"><img src="aluona/${currentMotionName}" alt="" onerror="this.style.display='none'" onclick="openMotionPopup()"></div>`;
            /* 旧下拉框（已注释）
            cells[4].innerHTML = `<div class="motion-cell"><img src="aluona/${currentMotionName}" alt="" onerror="this.style.display='none'" onclick="toggleMotionSelect(this)"></div>
                <select class="motion-select-float" data-field="motion" style="display:none" onchange="onMotionEditChange(this)" onblur="this.style.display='none'">${motionDropdownHtml(currentMotionName)}</select>`;
            */
            // 操作列按钮
            cells[5].innerHTML = `<div class="btn-row"><button class="btn-choice" onclick="showChoices(${id})">对话选项</button></div>
                <div class="btn-row"><button class="btn-complete" onclick="submitEdit(${id})">完成</button><button class="btn-close" onclick="cancelEdit(${id})">取消</button></div>`;

            // 绑定输入事件
            cells[1].querySelector('input').addEventListener('input', function() { editData.user_operation = this.value; });
            cells[2].querySelector('input').addEventListener('input', function() { editData.user_text = this.value; });
            cells[3].querySelector('input').addEventListener('input', function() { editData.boat_text = this.value; });
            cells[4].querySelector('select').addEventListener('change', function() { getEditData().motion = this.value; });
        });
    });
}

// 取消编辑 → 恢复原按钮
function cancelEdit(id) {
    editingId = null;
    editData = {};
    const scr = document.querySelector('.table-scroll');
    const scrollTop = scr ? scr.scrollTop : 0;
    const table = document.getElementById('historyTable');
    if (table) table.style.visibility = 'hidden';
    const loadingImg = document.getElementById('loadingImg');
    if (loadingImg) loadingImg.style.display = '';
    loadConversations(() => {
        if (scr && scrollTop) scr.scrollTop = scrollTop;
        setTimeout(() => {
            if (table) table.style.visibility = '';
            if (loadingImg) loadingImg.style.display = 'none';
        }, 500);
    });
}

// 提交编辑
function submitEdit(id) {
    const uop = editData.user_operation;
    const bt = editData.boat_text;
    if (!uop || !uop.trim()) { callReaction('user_operationBlank'); return; }

    const body = {
        id: id,
        user_operation: uop,
        user_text: editData.user_text || '',
        boat_text: bt,
        motion: getEditData().motion || '',
        sound_effect: editData.sound || '',
        special_effect: editData.special_effect || ''
    };
    const scroll = document.querySelector('.table-scroll');
    const scrollTop = scroll ? scroll.scrollTop : 0;
    axios.put('/conversation', body).then(r => {
        // 延迟刷新，保持编辑态避免闪烁
        setTimeout(() => {
            editingId = null;
            editData = {};
            loadConversations(() => {
                if (scroll) scroll.scrollTop = scrollTop;
            });
        }, 300);
        // 先显示后端响应
        if (r.data.code === 1 && r.data.motion) {
            document.getElementById('petImg').src = 'aluona/' + r.data.motion;
            if (r.data.message) document.getElementById('dialogText').textContent = r.data.message;
        }
    });
}

// ===== 选项编辑 =====
let choiceEditingId = null;
let choiceEditData = {};

function startChoiceEdit(id) {
    if (!canModCurrent) { callReaction('permissionDeny'); return; }
    choiceEditingId = id;
    const origChoice = choiceDataMap[id] || {};
    const seParts = (origChoice.specialEffect || '').split(';');
    const firstIsAction = seParts[0] && !seParts[0].includes(':');
    choiceEditData = {
        sound: origChoice.soundEffect || '',
        action: firstIsAction ? seParts[0] : '',
        special_effect: origChoice.specialEffect || ''
    };
    loadAluonaMotions(() => {
        const tbody = document.getElementById('historyBody');
        const rows = tbody.querySelectorAll('tr[data-choice-id]');
        rows.forEach(row => {
            if (parseInt(row.getAttribute('data-choice-id')) !== id) return;
            const cells = row.querySelectorAll('td');
            const bkpAction = cells[1].textContent.trim();
            const bkpText = cells[2].textContent.trim();
            const bkpBoat = cells[3].textContent.trim();
            const bkpMotion = cells[4].querySelector('img')
                ? cells[4].querySelector('img').src.replace(/.*\//, '') : '';

            choiceEditData.choiceAction = bkpAction === '—' ? '' : bkpAction;
            choiceEditData.choiceText = bkpText === '—' ? '' : bkpText;
            choiceEditData.boatText = bkpBoat === '—' ? '' : bkpBoat;
            choiceEditData.motion = bkpMotion;

            cells[1].innerHTML = `<input class="edit-input" data-field="choiceAction" value="${choiceEditData.choiceAction}" style="width:100%;text-align:center;">`;
            cells[2].innerHTML = `<input class="edit-input" data-field="choiceText" value="${choiceEditData.choiceText}" style="width:100%;text-align:center;">`;
            cells[3].innerHTML = `<input class="edit-input" data-field="boatText" value="${choiceEditData.boatText}" style="width:100%;text-align:center;">`;
            cells[4].innerHTML = `<div class="motion-cell"><img src="aluona/${bkpMotion}" alt="" onerror="this.style.display='none'" onclick="openMotionPopup()"></div>`;
            cells[5].innerHTML = `<div class="btn-row"><button class="btn-choice" onclick="showChoices(${id})">对话选项</button></div>
                <div class="btn-row"><button class="btn-complete" onclick="submitChoiceEdit(${id})">完成</button><button class="btn-close" onclick="cancelChoiceEdit()">取消</button></div>`;

            cells[1].querySelector('input').addEventListener('input', function() { choiceEditData.choiceAction = this.value; });
            cells[2].querySelector('input').addEventListener('input', function() { choiceEditData.choiceText = this.value; });
            cells[3].querySelector('input').addEventListener('input', function() { choiceEditData.boatText = this.value; });
        });
    });
}

function onChoiceMotionEditChange(sel) {
    choiceEditData.motion = sel.value;
    const img = sel.closest('td').querySelector('.motion-cell img');
    if (img) img.src = 'aluona/' + sel.value;
}

function cancelChoiceEdit() {
    choiceEditingId = null;
    choiceEditData = {};
    const scr = document.querySelector('.table-scroll');
    const scrollTop = scr ? scr.scrollTop : 0;
    const table = document.getElementById('historyTable');
    if (table) table.style.visibility = 'hidden';
    const loadingImg = document.getElementById('loadingImg');
    if (loadingImg) loadingImg.style.display = '';
    reloadCurrentView();
    setTimeout(() => {
        if (scr && scrollTop) scr.scrollTop = scrollTop;
        if (table) table.style.visibility = '';
        if (loadingImg) loadingImg.style.display = 'none';
    }, 500);
}

function submitChoiceEdit(id) {
const bt = choiceEditData.boatText;

    const choiceText = choiceEditData.choiceText || '';
    const userOp = choiceText.trim() ? 'chat' : (choiceEditData.choiceAction || 'act');

    const body = {
        id: id,
        choiceAction: choiceEditData.choiceAction || '',
        choiceText: choiceText,
        boatText: bt,
        motion: choiceEditData.motion || '',
        userOperation: userOp,
        soundEffect: choiceEditData.sound || '',
        specialEffect: choiceEditData.special_effect || ''
    };

    const scroll = document.querySelector('.table-scroll');
    const scrollTop = scroll ? scroll.scrollTop : 0;
    axios.put('/choice', body).then(r => {
        setTimeout(() => {
            choiceEditingId = null;
            choiceEditData = {};
            reloadCurrentView();
            if (scroll) scroll.scrollTop = scrollTop;
        }, 300);
        if (r.data.code === 1 && r.data.motion) {
            document.getElementById('petImg').src = 'aluona/' + r.data.motion;
            if (r.data.message) document.getElementById('dialogText').textContent = r.data.message;
        }
    });
}

let pendingDeleteId = null;
let pendingDeleteChoiceId = null;
let editData = {};              // 编辑中的字段值
let aluonaMotions = [];
let convDataMap = {};
let choiceDataMap = {};         // 选项原始数据缓存 {id: ConversationChoice}
let idleTimer = null;
let idleSeconds = 0;            // 累计空闲秒数
let idleTriggers = [];          // [{sec:5}, {sec:20}, ...] 阈值队列
let idleIndex = 0;              // 当前已触发的索引

// 请求删除对话 → 触发确认弹窗
function deleteRow(id) {
    if (!canModCurrent) { callReaction('permissionDeny'); return; }
    pendingDeleteId = id;
    callReaction('delete_conversation');
}

// 请求删除选项 → 触发确认弹窗
function requestDeleteChoice(id) {
    if (!canModCurrent) { callReaction('permissionDeny'); return; }
    pendingDeleteChoiceId = id;
    callReaction('delete_choice');
}

// 确认删除对话
function confirmDelete(choice) {
    if (!pendingDeleteId) return;
    const delId = pendingDeleteId;
    pendingDeleteId = null;
    const scroll = document.querySelector('.table-scroll');
    const scrollTop = scroll ? scroll.scrollTop : 0;
    axios.delete('/conversation/' + delId).then(() => {
        if (choice) {
            document.getElementById('dialogText').textContent = choice.boatText || '已删除';
            if (choice.motion) {
                document.getElementById('petImg').src = 'aluona/' + choice.motion;
            }
        }
        navStack = [];
        parentIdStack = [];
        updatePanelButtons();
        switchToConvHeader();
        loadConversations(() => {
            if (scroll) scroll.scrollTop = scrollTop;
        });
    });
}

// 确认删除选项
function confirmDeleteChoice(choice) {
    if (!pendingDeleteChoiceId) return;
    const delId = pendingDeleteChoiceId;
    pendingDeleteChoiceId = null;
    const scroll = document.querySelector('.table-scroll');
    const scrollTop = scroll ? scroll.scrollTop : 0;
    // 判断是否为当前视图最后一个选项
    const rows = document.querySelectorAll('#historyBody tr[data-choice-id]');
    const isLast = rows.length <= 1;
    axios.delete('/choice/' + delId).then(() => {
        if (choice) {
            document.getElementById('dialogText').textContent = choice.boatText || '已删除';
            if (choice.motion) {
                document.getElementById('petImg').src = 'aluona/' + choice.motion;
            }
        }
        setTimeout(() => {
            if (isLast) {
                goBack();
            } else {
                reloadCurrentView();
                if (scroll) scroll.scrollTop = scrollTop;
            }
        }, 300);
    });
}

// ========== 桌宠交互逻辑 ==========

// 发送交互请求
function callReaction(userOperation) {
    axios.post('/reaction', { user_operation: userOperation })
        .then(result => {
            if (result.data.code === 1) {
                showResult(result.data);
            }
        });
}

// 处理后端响应
// 打字机逐字渲染 符号停顿
function typewriterText(el, fullText, speed, callback) {
    if (typewriterTimer) clearInterval(typewriterTimer);
    let i = 0;
    const baseSpeed = speed || 30;
    const punct = '，。！？…、：；（）—,.!?;:';
    el.textContent = '';
    function nextChar() {
        if (i >= fullText.length) {
            typewriterTimer = null;
            if (callback) callback();
            return;
        }
        el.textContent = fullText.substring(0, i + 1);
        const ch = fullText[i];
        i++;
        const delay = punct.includes(ch) ? baseSpeed * 10 : baseSpeed;
        typewriterTimer = setTimeout(nextChar, delay);
    }
    nextChar();
}

function showResult(data) {
    playEffects(data.soundEffect, data.specialEffect);
    // 更新桌宠表情
    if (data.motion) {
        const src = data.motion.startsWith('http') ? data.motion : 'aluona/' + data.motion;
        document.getElementById('petImg').src = src;
    }
    // 更新对话框文本 — 分段处理 + 打字机
    if (data.message) {
        const normalized = data.message.replace(/\r\n/g, '\n');
        const parts = normalized.split('\n\n').filter(p => p.trim());
        if (parts.length > 1) {
            textSegments = parts;
            typewriterText(document.getElementById('dialogText'), textSegments.shift(), 30);
            document.getElementById('dialogBox').classList.add('dialog-clickable');
        } else {
            textSegments = [];
            typewriterText(document.getElementById('dialogText'), data.message, 30);
        }
        playTts(data.message);
    }
    // 分离可见/隐藏选项 + continue 检测
    reactiveMap = {};
    if (data.data && Array.isArray(data.data) && data.data.length) {
        const visible = [];
        data.data.forEach(c => {
            const uop = c.userOperation || 'chat';
            // 桌宠端专用入口（如「打开桌宠基础设置」）在管理页不显示
            if (MANAGER_HIDDEN_CHOICE_ACTIONS.indexOf(c.choiceAction) >= 0) return;
            if (uop !== 'chat') {
                reactiveMap[uop] = c;
                // 检测 continue 自动链
                const cm = uop.match(/^continue(\d+)$/);
                if (cm && !continueLocked) startContinueChain(parseInt(cm[1]), c);
            } else if (c.choiceText && c.choiceText.trim()) {
                visible.push(c);
            }
        });
        if (visible.length) {
            currentChoices = visible;
            document.getElementById('dialogBox').classList.add('dialog-clickable');
        } else {
            currentChoices = null;
            document.getElementById('dialogBox').classList.remove('dialog-clickable');
        }
    } else {
        currentChoices = null;
        document.getElementById('dialogBox').classList.remove('dialog-clickable');
    }
}

// 渲染选项按钮
function renderChoices() {
    const container = document.getElementById('choicesContainer');
    if (!currentChoices || !currentChoices.length) {
        container.innerHTML = '';
        document.getElementById('choicesWrapper').style.display = 'none';
        return;
    }
    container.innerHTML = currentChoices.map((c, i) =>
        `<div class="choice-btn" data-idx="${i}" onclick="onChoiceClick(${i})">${c.choiceText}</div>`
    ).join('');
    document.getElementById('choicesWrapper').style.display = 'flex';
}

// 选项点击
function onChoiceClick(idx) {
    const choice = currentChoices[idx];
    if (!choice) return;
    // 更新表情和文本
    if (choice.motion) {
        const src = choice.motion.startsWith('http') ? choice.motion : 'aluona/' + choice.motion;
        document.getElementById('petImg').src = src;
    }
    if (choice.boatText) {
        playEffects(choice.soundEffect, choice.specialEffect);
        const normalized = choice.boatText.replace(/\r\n/g, '\n');
        const parts = normalized.split('\n\n').filter(p => p.trim());
        if (parts.length > 1) {
            textSegments = parts;
            typewriterText(document.getElementById('dialogText'), textSegments.shift(), 30);
            document.getElementById('dialogBox').classList.add('dialog-clickable');
        } else {
            textSegments = [];
            typewriterText(document.getElementById('dialogText'), choice.boatText, 30);
        }
        playTts(choice.boatText);
    }
    // 非 CRUD 操作记录到 AI 历史
    const act = choice.choiceAction || '';
    if (act && !act.startsWith('delete_') && !act.startsWith('create_') && act !== 'search_conversationForm') {
        axios.post('/talk', { user_text: '', user_operation: act, session_id: talkSessionId, boat_text: choice.boatText || '', motion: choice.motion || '' });
    }
    if (choice.choiceAction === 'music_play_song') {
        const song = (window._songChoices && window._songChoices[idx]) ? window._songChoices[idx] : null;
        if (song) { musicPlayer.playSong(song); }   // 网易云：先取直链再播
    } else if (choice.choiceAction === 'delete_converYes') {
        confirmDelete(choice);
    } else if (choice.choiceAction === 'delete_converNo') {
        pendingDeleteId = null;
    } else if (choice.choiceAction === 'delete_choiceYes') {
        confirmDeleteChoice(choice);
    } else if (choice.choiceAction === 'delete_choiceNo') {
        pendingDeleteChoiceId = null;
    } else if (choice.choiceAction === 'search_conversationForm') {
        openPanel();
    } else if (choice.choiceAction === 'goto_login') {
        location.href = '登录.html';
    } else if (choice.choiceAction === 'basicinfo') {
        // 「桌宠基础设置」已挪到桌宠「基础设置」窗口的「AI 设定」页，这里直接打开那个窗口
        window.open('桌宠设置.html?t=' + Date.now(), '_blank', 'width=1257,height=748');
    } else if (choice.choiceAction === 'historyinfo') {
        openHistoryPanel();
    } else if (choice.choiceAction === 'rule_info') {
        openRulePanel();
    } else if (choice.choiceAction === 'create_choiceYes') {
        if (pendingCreateChoiceParent) {
            const p = new URLSearchParams();
            p.append('parent_id', pendingCreateChoiceParent);
            axios.post('/choice/sub', p).then(() => reloadCurrentView());
        } else if (currentConvId) {
            const p = new URLSearchParams();
            p.append('conversation_id', currentConvId);
            axios.post('/choice', p).then(() => reloadCurrentView());
        }
    } else if (choice.choiceAction === 'create_choiceNo') {
        // 取消新建，不做任何操作
    } else if (handleMusicAction(choice.choiceAction)) {
        // 音乐操作已处理
    } else if (choice.nextChoiceId && choice.nextChoiceId > 0) {
        axios.get('/choice/sub', { params: { choice_id: choice.id } }).then(r2 => {
            if (r2.data.motion) return;
            if (r2.data.data) {
                const vis = [];
                r2.data.data.forEach(c => {
                    const uop = c.userOperation || 'chat';
                    if (MANAGER_HIDDEN_CHOICE_ACTIONS.indexOf(c.choiceAction) >= 0) return;
                    if (uop !== 'chat') {
                        reactiveMap[uop] = c;
                        const cm = uop.match(/^continue(\d+)$/);
                        if (cm && !continueLocked) startContinueChain(parseInt(cm[1]), c);
                    } else if (c.choiceText && c.choiceText.trim()) {
                        vis.push(c);
                    }
                });
                if (vis.length) {
                    currentChoices = vis;
                    document.getElementById('dialogBox').classList.add('dialog-clickable');
                }
            }
        });
    }
    currentChoices = null;
    renderChoices();
    document.getElementById('dialogBox').classList.remove('dialog-clickable');
}

// 执行隐藏选项（被事件触发时调用）
// 播放音效与特效
function playEffects(soundEffect, specialEffect) {
    // 先停止当前播放的音效/视频
    if (soundVideo) {
        soundVideo.pause();
        soundVideo.src = '';
        soundVideo.style.display = 'none';
        soundVideo.style.cssText = '';
    }
    if (soundEffect) {
        if (!soundVideo) {
            soundVideo = document.createElement('video');
            soundVideo.className = 'effect-video';
            soundVideo.muted = true;
            soundVideo.playsInline = true;
            document.getElementById('petImg').parentElement.appendChild(soundVideo);
        }
        // 从 effect 表加载音效的默认位置和大小
        if (window.effectList) {
            const eff = window.effectList.find(e => e.soundeffect === soundEffect);
            if (eff && eff.location) {
                eff.location.split(';').forEach(pair => {
                    const [k, v] = pair.split(':');
                    if (k && v) soundVideo.style.setProperty(k.trim(), v.trim());
                });
            }
        }
        const src = soundEffect.startsWith('soundeffects/') || soundEffect.startsWith('http')
            ? soundEffect : 'soundeffects/' + soundEffect;
        soundVideo.src = src;
        soundVideo.currentTime = 0;
        soundVideo.style.display = '';
        if (soundUnlocked) soundVideo.muted = false;
        const isLoop = specialEffect && specialEffect.includes('loop');
        soundVideo.loop = isLoop;
        if (!isLoop) soundVideo.onended = () => { soundVideo.style.display = 'none'; };
        soundVideo.play().catch(() => {});
    }
    if (specialEffect) {
        if (specialEffect.startsWith('bounce')) {
            const petImg = document.getElementById('petImg');
            petImg.classList.add('pet-bounce');
            setTimeout(() => petImg.classList.remove('pet-bounce'), 400);
        } else if (specialEffect.startsWith('swing')) {
            const petImg = document.getElementById('petImg');
            petImg.classList.add('pet-swing');
            setTimeout(() => petImg.classList.remove('pet-swing'), 500);
        } else if (specialEffect.startsWith('drop')) {
            const petImg = document.getElementById('petImg');
            petImg.classList.add('pet-drop');
            setTimeout(() => petImg.classList.remove('pet-drop'), 450);
        } else if (specialEffect.startsWith('spin2')) {
            petImg.classList.add('pet-spin2');
            setTimeout(() => petImg.classList.remove('pet-spin2'), 1050);
        } else if (specialEffect.startsWith('spin3')) {
            petImg.classList.add('pet-spin3');
            setTimeout(() => petImg.classList.remove('pet-spin3'), 1450);
        } else if (specialEffect.startsWith('spin')) {
            const petImg = document.getElementById('petImg');
            petImg.classList.add('pet-spin');
            setTimeout(() => petImg.classList.remove('pet-spin'), 650);
        } else if (specialEffect.startsWith('tilt')) {
            const petImg = document.getElementById('petImg');
            petImg.classList.add('pet-tilt');
            setTimeout(() => petImg.classList.remove('pet-tilt'), 850);
        } else if (specialEffect.startsWith('escape')) {
            document.querySelectorAll('#petImg, .effect-video').forEach(el => {
                el.classList.add('pet-escape');
                setTimeout(() => el.classList.remove('pet-escape'), 4600);
            });
        }
        // specialEffect 中的位置参数覆盖 effect 表默认值
        if (soundVideo && specialEffect.includes(':')) {
            specialEffect.split(';').forEach(pair => {
                const [k, v] = pair.split(':');
                if (!k || !v) return;
                if (k.trim() === 'volume') {
                    soundVideo.volume = parseFloat(v) || 1;
                } else {
                    soundVideo.style.setProperty(k.trim(), v.trim());
                }
            });
        }
    }
}

function executeHiddenChoice(choice) {
    const action = choice.choiceAction;
    delete reactiveMap[action];
    if (handleMusicAction(action)) return;
    if (choice.motion) {
        const src = choice.motion.startsWith('http') ? choice.motion : 'aluona/' + choice.motion;
        document.getElementById('petImg').src = src;
    }
    if (choice.boatText) {
        playEffects(choice.soundEffect, choice.specialEffect);
        const normalized = choice.boatText.replace(/\r\n/g, '\n');
        const parts = normalized.split('\n\n').filter(p => p.trim());
        if (parts.length > 1) {
            textSegments = parts;
            typewriterText(document.getElementById('dialogText'), textSegments.shift(), 60);
            document.getElementById('dialogBox').classList.add('dialog-clickable');
        } else {
            textSegments = [];
            typewriterText(document.getElementById('dialogText'), choice.boatText, 60);
        }
        playTts(choice.boatText);
    }
    if (choice.nextChoiceId && choice.nextChoiceId > 0) {
        axios.get('/choice/sub', { params: { choice_id: choice.id } }).then(r2 => {
            if (r2.data.motion) return;     // fail 响应，跳过
            if (r2.data.data) {
                r2.data.data.forEach(c => {
                    const uop = c.userOperation || 'chat';
                    if (uop !== 'chat') {
                        reactiveMap[uop] = c;
                        const cm = uop.match(/^continue(\d+)$/);
                        if (cm && !continueLocked) startContinueChain(parseInt(cm[1]), c);
                    }
                });
            }
        });
    }
    currentChoices = null;
    renderChoices();
    document.getElementById('dialogBox').classList.remove('dialog-clickable');
}

// 对话框点击 → 展开选项
document.addEventListener('click', function(e) {
    // 桌宠图片点击
    if (e.target.closest('#petImg')) {
        if (isInteractionLocked()) return;
        if (reactiveMap['click']) {
            const c = reactiveMap['click'];
            delete reactiveMap['click'];
            delete reactiveMap['mouseout'];
            executeHiddenChoice(c);
        } else {
            callReaction('click');
        }
        return;
    }
    // 对话框点击 → 优先播下一段文本（强制等待中跳过）
    if (e.target.closest('#dialogBox')) {
        if (isInteractionLocked() && textSegments.length === 0) return;
        if (textSegments.length > 0) {
            document.getElementById('dialogText').textContent = textSegments.shift();
            if (textSegments.length === 0) {
                document.getElementById('dialogBox').classList.remove('dialog-clickable');
                // 全部播完，若有选项则恢复可点击样式
                if (currentChoices && currentChoices.length) {
                    document.getElementById('dialogBox').classList.add('dialog-clickable');
                }
            }
            return;
        }
        if (currentChoices && currentChoices.length) {
            const wrapper = document.getElementById('choicesWrapper');
            if (wrapper.style.display === 'flex') {
                wrapper.style.display = 'none';
            } else {
                renderChoices();
            }
        }
    }
});

// 预加载音效视频
function preloadSoundEffects() {
    axios.get('/conversation', { params: { user_id: '' } }).then(r => {
        const list = r.data.data || [];
        const seen = new Set();
        list.forEach(c => {
            if (c.sound_effect && !seen.has(c.sound_effect)) {
                seen.add(c.sound_effect);
                const src = c.sound_effect.startsWith('soundeffects/') ? c.sound_effect : 'soundeffects/' + c.sound_effect;
                const pre = document.createElement('video');
                pre.preload = 'auto';
                pre.src = src;
                pre.muted = true;
                pre.style.display = 'none';
                document.body.appendChild(pre);
            }
        });
    });
}

// ===== Continue 强制等待链 =====
function startContinueChain(seconds, choice) {
    console.log('startContinueChain:', seconds, 's, choice:', choice ? choice.id : null, 'boatText:', choice ? choice.boatText : null);
    if (continueTimer) clearTimeout(continueTimer);
    continueLocked = true;
    idleSeconds = 0;
    idleIndex = 0;
    continueTimer = setTimeout(() => {
        console.log('continueChain firing for choice:', choice ? choice.id : null);
        continueLocked = false;
        if (choice) executeHiddenChoice(choice);
    }, seconds * 1000);
}
// 强制等待期间屏蔽交互
function isInteractionLocked() {
    return continueLocked || inputMode;
}

// ===== 对话输入模式 =====
function enterInputMode() {
    if (inputMode) return;
    // 保存当前状态
    const petImg = document.getElementById('petImg');
    const dialogText = document.getElementById('dialogText');
    savedDialogState = {
        text: dialogText.textContent,
        motion: petImg.src.replace(/.*\//, ''),
        reactiveMapSnap: Object.assign({}, reactiveMap),
        currentChoicesSnap: currentChoices
    };
    // 加载用户信息
    axios.get('/basicinfo', { params: { id: 2 } }).then(r => {
        const info = r.data.data || {};
        document.getElementById('dialogName').textContent = info['姓名'] || '老师';
        document.getElementById('dialogRole').textContent = info['身份'] || '';
    });
    // 切换为 textarea
    const ta = document.createElement('textarea');
    ta.id = 'talkInput';
    ta.className = 'talk-input';
    ta.placeholder = '输入文本后按 Enter 发送...';
    ta.rows = 3;
    dialogText.parentElement.replaceChild(ta, dialogText);
    inputMode = true;
    ta.focus();
    ta.addEventListener('keydown', onTalkKey);
}

function exitInputMode(restore) {
    if (!inputMode) return;
    inputMode = false;
    const ta = document.getElementById('talkInput');
    if (!ta) return;
    const dialogText = document.createElement('div');
    dialogText.className = 'dialog-text';
    dialogText.id = 'dialogText';
    if (restore && savedDialogState) {
        dialogText.textContent = savedDialogState.text;
        document.getElementById('petImg').src = 'aluona/' + savedDialogState.motion;
        reactiveMap = savedDialogState.reactiveMapSnap;
        currentChoices = savedDialogState.currentChoicesSnap;
    }
    ta.parentElement.replaceChild(dialogText, ta);
    savedDialogState = null;
    // 恢复默认名字
    loadDialogInfo();
}

let pendingQuickSay = null;

function tryHandleLocal(text) {
    try { return handleLocalCommand(text); } catch (e) { console.error(e); return false; }
}

function handleLocalCommand(text) {
    if (typeof musicPlayer === 'undefined') return false;
    const t = text.trim();
    if (!t) return false;
    const say = (msg, motion) => { pendingQuickSay = { msg, motion }; };

    // 停用前先加载播放列表以确保模糊搜索可用
    const ensureList = (callback) => {
        if (musicPlayer.playlist.length) { callback(); return; }
        musicPlayer.loadPlaylist(() => callback());
    };

    // —— 播放指定歌曲：播放/放/来一首/想听 + 歌名 ——
    const playMatch = t.match(/^(?:播放|放|来一首|我想听|听一首)\s*(.+)/);
    if (playMatch) {
        const keyword = playMatch[1].replace(/[《》""'']/g, '').trim().toLowerCase();
        if (/^(歌曲|音乐|一首歌|一首|歌|什么|啥)$/.test(keyword)) return false;
        const doPlay = () => {
            const found = musicPlayer.playlist.find(f => f.name.toLowerCase().includes(keyword));
            if (found) { musicPlayer.play(musicPlayer.playlist.indexOf(found)); quickSay('好的，正在播放：' + (found.displayName || found.name), 'aluona_kaixin.png'); }
        };
        if (!musicPlayer.playlist.length) { musicPlayer.loadPlaylist(doPlay); return true; }
        doPlay();
        return musicPlayer.playlist.some(f => f.name.toLowerCase().includes(keyword));
    }

    // —— 无歌名播放 ——
    if (/^(?:播放音乐|放音乐|来点音乐|放歌|听歌|播歌)$/.test(t)) {
        ensureList(() => {
            if (musicPlayer.playing()) { say('已经在播放啦~', 'aluona_huaiyi.png'); }
            else { musicPlayer.toggle(); say('来点音乐~' + (musicPlayer.getCurrentName() ? ' 正在播放：' + musicPlayer.getCurrentName() : ''), 'aluona_kaixin.png'); }
        });
        return true;
    }

    // —— 暂停 ——
    if (/^(?:暂停|暂停音乐|停下|别放了|别播了)$/.test(t)) {
        musicPlayer.pause(); say('已暂停', 'aluona_zhengchang.png'); return true;
    }

    // —— 继续 ——
    if (/^(?:继续|继续播放|接着放|继续播)$/.test(t)) {
        musicPlayer.resume(); say('继续播放~', 'aluona_kaixin.png'); return true;
    }

    // —— 下一首 ——
    if (/^(?:下一首|下一曲|切歌|换一首|切一首|跳过)$/.test(t)) {
        ensureList(() => {
            musicPlayer.next();
            setTimeout(() => { if (musicPlayer.getCurrentName()) say('下一首：' + musicPlayer.getCurrentName(), 'aluona_kaixin.png'); }, 300);
        });
        return true;
    }

    // —— 上一首 ——
    if (/^(?:上一首|上一曲|前一首)$/.test(t)) {
        ensureList(() => {
            musicPlayer.prev();
            setTimeout(() => { if (musicPlayer.getCurrentName()) say('上一首：' + musicPlayer.getCurrentName(), 'aluona_kaixin.png'); }, 300);
        });
        return true;
    }

    // —— 播放模式 ——
    if (/^(?:随机播放|随机模式|随机)$/.test(t))   { musicPlayer.setMode('random'); say('已切换为随机播放', 'aluona_qidai.png'); return true; }
    if (/^(?:单曲循环|循环播放|循环|单曲)$/.test(t)) { musicPlayer.setMode('loop'); say('已切换为单曲循环', 'aluona_zhengjing.png'); return true; }
    if (/^(?:顺序播放|列表播放|顺序|列表)$/.test(t)) { musicPlayer.setMode('sequential'); say('已切换为顺序播放', 'aluona_zhengchang.png'); return true; }

    // —— 音量调节 ——
    let vm = t.match(/^(?:音量|声音)\s*(\d+)\s*%?$/);
    if (vm) {
        const v = parseInt(vm[1]) / 100;
        musicPlayer.setVolume(v); say('音量已设为 ' + Math.round(v * 100) + '%', 'aluona_zhengchang.png'); return true;
    }
    if (/^(?:音量加大|声音大一点|大声点|大点声|响一点)$/.test(t)) {
        musicPlayer.setVolume(musicPlayer.volume + 0.1); say('音量 ' + Math.round(musicPlayer.volume * 100) + '%', 'aluona_zhengchang.png'); return true;
    }
    if (/^(?:音量减小|声音小一点|小声点|轻一点)$/.test(t)) {
        musicPlayer.setVolume(musicPlayer.volume - 0.1); say('音量 ' + Math.round(musicPlayer.volume * 100) + '%', 'aluona_zhengchang.png'); return true;
    }
    if (/^(?:静音|消音)$/.test(t))   { musicPlayer.setVolume(0); say('已静音', 'aluona_zhengchang.png'); return true; }
    if (/^(?:取消静音|解除静音|恢复声音)$/.test(t)) { musicPlayer.setVolume(0.5); say('已恢复音量', 'aluona_zhengchang.png'); return true; }

    // —— 直播模式开关 ——
    if (/^(?:开启直播|打开直播|开启直播模式|打开直播模式|直播模式开)$/.test(t)) {
        axios.get('/ai/config').then(r0 => {
            const cfg = r0.data.data;
            if (cfg) { cfg.live_mode = 1; axios.post('/ai/config', cfg).then(() => {
                quickSay('直播模式已开启～弹幕互动准备好了！', 'aluona_jidong.png');
                axios.post('/talk', { user_text: '用户已开启直播模式，你现在在B站直播中，有粉丝发弹幕互动，请以VUP身份热情回应', user_operation: 'system', session_id: talkSessionId });
            });}
        });
        return true;
    }
    if (/^(?:关闭直播|关直播|关闭直播模式|关直播模式|直播模式关)$/.test(t)) {
        axios.get('/ai/config').then(r0 => {
            const cfg = r0.data.data;
            if (cfg) { cfg.live_mode = 0; axios.post('/ai/config', cfg).then(() => {
                quickSay('直播模式已关闭～现在可以放松一下啦', 'aluona_zhengchang.png');
                axios.post('/talk', { user_text: '用户已关闭直播模式，你现在不在直播了，恢复日常闲聊模式', user_operation: 'system', session_id: talkSessionId });
            });}
        });
        return true;
    }
    if (/^(?:开启闲置|打开闲置|闲置开)$/.test(t)) { idleEnabled = true; quickSay('闲置提醒已开启', 'aluona_zhengchang.png'); return true; }
    if (/^(?:关闭闲置|关闲置|闲置关)$/.test(t)) { idleEnabled = false; quickSay('闲置提醒已关闭', 'aluona_zhengchang.png'); return true; }
    if (/^(?:开启语音|打开语音|语音开|开语音)$/.test(t)) {
        ttsEnabled = true; quickSay('语音播报已开启～', 'aluona_kaixin.png');
        axios.post('/talk', { user_text: '用户已开启你的语音功能，你现在可以说话了', user_operation: 'system', session_id: talkSessionId });
        return true;
    }
    if (/^(?:关闭语音|关语音|语音关|静音模式)$/.test(t)) {
        ttsEnabled = false; quickSay('语音播报已关闭，阿罗娜暂时安静啦', 'aluona_kangju.png');
        axios.post('/talk', { user_text: '用户暂时关闭了你的语音功能，你现在不能说话，请用文字继续交流', user_operation: 'system', session_id: talkSessionId });
        return true;
    }

    if (/^(?:提醒|看弹幕|弹幕提醒)$/.test(t)) {
        const msgs = ['老师！快看弹幕！粉丝们在跟你说话呢～','诶诶——弹幕区有人叫你哦老师！','老师！弹幕！现在！立刻！马上看！','弹幕弹幕弹幕！老师你的粉丝在呼唤你～'];
        quickSay(msgs[Math.floor(Math.random()*msgs.length)], 'aluona_jidong.png');
        return true;
    }

    // —— 曲库展示（网易云推荐） ——
    if (/^(?:曲库|有什么歌|看看曲库|歌曲列表|来点推荐|推荐几首|有哪些歌)$/.test(t)) {
        axios.get('/ai/music/recommend').then(r => {
            const songs = (r.data.data || []).slice(0, 5).map(s => ({ id: s.id, name: s.name, displayName: s.name, artist: s.artist || '', url: null }));
            if (!songs.length) { quickSay('暂时拿不到推荐歌曲哦', 'aluona_kunhuo.png'); return; }
            window._songChoices = songs;
            currentChoices = songs.map(s => ({ choiceText: s.displayName || s.name, choiceAction: 'music_play_song', boatText: '正在播放：' + (s.displayName || s.name) }));
            document.getElementById('dialogBox').classList.add('dialog-clickable');
            renderChoices();
            const lines = [];
            for (let i = 0; i < songs.length; i += 2) {
                const a = (i + 1) + '. ' + (songs[i].displayName || songs[i].name);
                const b = songs[i + 1] ? '    ' + (i + 2) + '. ' + (songs[i + 1].displayName || songs[i + 1].name) : '';
                lines.push(a + b);
            }
            quickSay('曲库随机推荐：\n' + lines.join('\n'), 'aluona_qidai.png');
        }).catch(() => { quickSay('曲库加载失败', 'aluona_huaiyi.png'); });
        return true;
    }

    // —— 查询 ——
    if (/^(?:当前播放|什么歌|什么音乐|正在播放|现在放的什么|这是.?什么歌)$/.test(t)) {
        if (musicPlayer.playing() && musicPlayer.getCurrentName()) {
            say('正在播放：' + musicPlayer.getCurrentName() + '（' + (musicPlayer.mode === 'loop' ? '单曲循环' : musicPlayer.mode === 'random' ? '随机播放' : '顺序播放') + '）', 'aluona_kaixin.png');
        } else {
            say('当前没有在播放音乐哦', 'aluona_kunhuo.png');
        }
        return true;
    }

    // —— 停止（关掉音乐） ——
    if (/^(?:关掉|关闭音乐|停止播放|别放了|关音乐)$/.test(t)) {
        musicPlayer.pause();
        if (musicPlayer.audio) { musicPlayer.audio.src = ''; }
        say('音乐已关闭', 'aluona_zhengchang.png');
        return true;
    }

    return false;
}

function quickSay(msg, motion) {
    if (typewriterTimer) { clearTimeout(typewriterTimer); typewriterTimer = null; }
    document.getElementById('dialogText').textContent = msg;
    if (motion) document.getElementById('petImg').src = 'aluona/' + motion;
}

function onTalkKey(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        const text = e.target.value.trim();
        if (!text) { exitInputMode(true); return; }
        if (reactiveMap['talk']) {
            const c = reactiveMap['talk'];
            delete reactiveMap['talk'];
            exitInputMode(false);
            executeHiddenChoice(c);
        } else if (tryHandleLocal(text)) {
            exitInputMode(false);
            if (pendingQuickSay) { quickSay(pendingQuickSay.msg, pendingQuickSay.motion); pendingQuickSay = null; }
        } else {
            // 等待动画：播放循环音效 + 打字机加载点
            exitInputMode(false);
            callReaction('loading');
            axios.post('/talk', { user_text: text, session_id: talkSessionId }).then(r => {
                if (typewriterTimer) { clearTimeout(typewriterTimer); typewriterTimer = null; }
                if (r.data.code === 1) showResult(r.data);
            });
        }
    }
    if (e.key === 'Escape') {
        exitInputMode(true);
    }
}

// 点击对话框外区域恢复
document.addEventListener('click', e => {
    if (inputMode && !e.target.closest('#dialogBox')) {
        exitInputMode(true);
    }
});

// 登录时发送 login 响应
// ===== 桌宠记忆库（pet_memory）=====
// 记忆内容会全量注入对话 system prompt，所以面板提供增/删/改/清空
// （状态变量 memoryDataMap / memoryEditingId / memoryEditData 声明在文件顶部，与其它面板状态一致）

function memEsc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function fmtTime(t) {
    if (!t) return '—';
    const d = new Date(t);
    if (isNaN(d.getTime())) return String(t).replace('T', ' ').substring(0, 19);
    const p = n => (n < 10 ? '0' + n : '' + n);
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}

function openMemoryPanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    document.getElementById('panelTitle').textContent = '桌宠记忆库';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    document.querySelector('.panel-buttons .btn-add').style.display = '';   // 记忆库支持新增
    document.getElementById('historyTable').style.display = '';
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = '<th>序号</th><th>角色</th><th>记忆内容</th><th>更新时间</th><th>操作</th>';
    setPanelColWidths('#historyTable th:nth-child(1),#historyTable td:nth-child(1){width:5%;}#historyTable th:nth-child(2),#historyTable td:nth-child(2){width:9%;}#historyTable th:nth-child(3),#historyTable td:nth-child(3){width:52%;}#historyTable th:nth-child(4),#historyTable td:nth-child(4){width:14%;}#historyTable th:nth-child(5),#historyTable td:nth-child(5){width:20%;}');
    loadMemories();
}

function loadMemories() {
    axios.get('/ai/memory/list').then(r => {
        renderMemoryTable(r.data.data || []);
    }).catch(() => {
        document.getElementById('historyBody').innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:red;">加载失败</td></tr>';
    });
}

function memoryFooterRow(n) {
    return `<tr><td colspan="5" style="text-align:left;padding:14px 8px;">
        <button class="btn-delete" onclick="clearMemories()">清空全部记忆</button>
        <span style="margin-left:14px;color:#9aa;">共 ${n} 条 · 记忆会全量注入对话提示词，改错会直接影响 AI 行为</span>
    </td></tr>`;
}

function renderMemoryTable(rows) {
    memoryDataMap = {};
    memoryEditingId = null;
    rows.forEach(r => { memoryDataMap[r.id] = r; });
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;">暂无记忆（对话积累到一定条数后由 AI 自动提炼，也可点左上「新增」手动补一条）</td></tr>'
            + memoryFooterRow(0);
        return;
    }
    tbody.innerHTML = rows.map((r, i) => `<tr>
        <td>${i + 1}</td>
        <td>${memEsc(r.character_name || '—')}</td>
        <td style="text-align:left;white-space:pre-wrap;word-break:break-word;">${memEsc(r.content)}</td>
        <td>${fmtTime(r.updated_at || r.created_at)}</td>
        <td>
            <div class="btn-row">
                <button class="btn-add" onclick="editMemoryRow(${r.id})">编辑</button>
                <button class="btn-delete" onclick="deleteMemoryRow(${r.id})">删除</button>
            </div>
        </td>
    </tr>`).join('') + memoryFooterRow(rows.length);
}

function addMemoryRow() {
    memoryEditingId = null;
    memoryEditData = { character_name: '阿罗娜', content: '' };
    const tbody = document.getElementById('historyBody');
    const row = document.createElement('tr');
    row.innerHTML = `<td>+</td>
        <td><input class="edit-input" style="width:100%;text-align:center;" value="阿罗娜" oninput="memoryEditData.character_name=this.value"></td>
        <td><textarea class="edit-input" style="width:100%;min-height:70px;text-align:left;" placeholder="输入要记住的内容（会作为长期记忆注入对话）" oninput="memoryEditData.content=this.value"></textarea></td>
        <td>—</td>
        <td><div class="btn-row"><button class="btn-complete" onclick="saveMemory()">完成</button><button class="btn-close" onclick="loadMemories()">取消</button></div></td>`;
    tbody.prepend(row);
}

function editMemoryRow(id) {
    const full = memoryDataMap[id] || {};
    memoryEditingId = id;
    memoryEditData = { character_name: full.character_name || '阿罗娜', content: full.content || '' };
    const tbody = document.getElementById('historyBody');
    tbody.querySelectorAll('tr').forEach(row => {
        const cells = row.querySelectorAll('td');
        if (cells.length < 5) return;
        const btn = cells[4].querySelector('button');
        if (!btn) return;
        if ((btn.getAttribute('onclick') || '').includes('editMemoryRow(' + id + ')')) {
            cells[2].innerHTML = `<textarea class="edit-input" style="width:100%;min-height:70px;text-align:left;" oninput="memoryEditData.content=this.value">${memEsc(memoryEditData.content)}</textarea>`;
            cells[3].innerHTML = fmtTime(full.updated_at || full.created_at);
            cells[4].innerHTML = `<div class="btn-row"><button class="btn-complete" onclick="saveMemory()">完成</button><button class="btn-close" onclick="loadMemories()">取消</button></div>`;
        }
    });
}

function saveMemory() {
    const content = (memoryEditData.content || '').trim();
    if (!content) { alert('记忆内容不能为空'); return; }
    if (memoryEditingId == null) {
        axios.post('/ai/memory/add', { character_name: memoryEditData.character_name || '阿罗娜', content: content })
            .then(() => loadMemories())
            .catch(e => alert('新增失败：' + (e.response && e.response.data && e.response.data.message || e)));
    } else {
        axios.put('/ai/memory/update', { id: memoryEditingId, content: content })
            .then(() => loadMemories())
            .catch(e => alert('保存失败：' + (e.response && e.response.data && e.response.data.message || e)));
    }
}

function deleteMemoryRow(id) {
    if (!confirm('确认删除这条记忆？')) return;
    axios.delete('/ai/memory/' + id).then(() => loadMemories()).catch(() => alert('删除失败'));
}

function clearMemories() {
    if (!confirm('确认清空全部记忆？此操作不可撤销（AI 会忘掉长期记忆）')) return;
    axios.post('/ai/memory/clear', {}).then(() => loadMemories()).catch(() => alert('清空失败'));
}

// ===== 历史对话管理 =====
function openHistoryPanel() {
    const panel = document.getElementById('historyPanel');
    panel.style.display = 'flex';
    document.getElementById('panelTitle').textContent = '历史对话管理';
    document.querySelector('.panel-buttons .btn-add').style.display = 'none';
    document.getElementById('historyTable').style.display = '';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    switchToHistoryHeader();
    loadHistoryData();
}

function switchToHistoryHeader() {
    const thead = document.querySelector('#historyTable thead tr');
    thead.innerHTML = '<th>序号</th><th>用户操作</th><th>用户文本</th><th>桌宠回复</th><th>表情</th><th>操作</th>';
    clearPanelColWidths();   // 6 列走 CSS 默认列宽，避免残留其它面板的列宽规则
}

function loadHistoryData() {
    axios.get('/ai/history').then(r => {
        const list = r.data.data || [];
        renderHistoryTable(list);
    });
}

function renderHistoryTable(rows) {
    const tbody = document.getElementById('historyBody');
    if (!rows || rows.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:40px;">暂无数据</td></tr>';
        return;
    }
    tbody.innerHTML = rows.map((r, i) => {
        const starText = r.starred ? '取消关注' : '特别关注';
        const starColor = r.starred ? 'color:#82E7F4;' : '';
        return `<tr>
            <td>${i + 1}</td>
            <td>${r.user_operation || '—'}</td>
            <td>${r.user_text || '—'}</td>
            <td>${r.boat_text || '—'}</td>
            <td><div class="motion-cell"><img src="${r.motion ? 'aluona/' + r.motion : ''}" alt="" onerror="this.style.display='none'"></div></td>
            <td>
                <div class="btn-row"><button class="btn-choice" onclick="toggleStar(${r.id}, ${r.starred})" style="${starColor}">${starText}</button></div>
                <div class="btn-row"><button class="btn-add" onclick="editHistoryRow(${r.id})">编辑</button><button class="btn-delete" onclick="deleteHistoryRow(${r.id})">删除</button></div>
            </td>
        </tr>`;
    }).join('');
}

function toggleStar(id, current) {
    const newVal = current ? 0 : 1;
    axios.put('/ai/history/star/' + id + '?starred=' + newVal).then(() => loadHistoryData());
}

function editHistoryRow(id) { alert('编辑功能待实现'); }
function deleteHistoryRow(id) {
    if (confirm('确认删除？')) {
        axios.delete('/ai/history/' + id).then(() => loadHistoryData());
    }
}

// TTS 语音播报
function playTts(text) {
    if (!ttsEnabled) return;
    if (!text || !text.trim()) return;
    // 按换行拆分段落，逐段请求 TTS 实现语音停顿
    const segments = text.replace(/\r\n/g, '\n').split('\n').filter(s => s.trim());
    if (segments.length === 0) return;
    const queue = segments.map(s => s.trim());
    let idx = 0;

    function playNext() {
        if (idx >= queue.length) return;
        const seg = queue[idx];
        idx++;
        axios.post('/ai/tts', { text: seg }).then(r => {
            if (r.data.code === 1 && r.data.data) {
                if (!ttsAudio) { ttsAudio = new Audio(); document.body.appendChild(ttsAudio); }
                ttsAudio.src = 'data:audio/wav;base64,' + r.data.data;
                ttsAudio.onended = () => setTimeout(playNext, 200);
                ttsAudio.play().catch(() => { setTimeout(playNext, 300); });
            } else {
                playNext();
            }
        }).catch(() => { playNext(); });
    }

    playNext();
}

// ===== 音乐播放器 =====
const musicPlayer = {
    audio: null,
    playlist: [],
    currentIndex: -1,
    mode: 'sequential',
    volume: 0.4,
    init() {
        if (this.audio) return;
        this.audio = new Audio();
        this.audio.volume = this.volume;
        this.audio.onended = () => this.onEnded();
        document.body.appendChild(this.audio);
    },
    loadPlaylist(callback) {
        // 本地音乐已移除：播放源统一走网易云（热歌榜推荐）
        axios.get('/ai/music/recommend').then(r => {
            const songs = r.data.data || [];
            this.playlist = songs.map(s => ({
                id: s.id,
                name: s.name + (s.artist ? ' - ' + s.artist : ''),
                displayName: s.name,
                artist: s.artist || '',
                url: null
            }));
            if (callback) callback(this.playlist);
        }).catch(() => { this.playlist = []; if (callback) callback(this.playlist); });
    },
    /** 播放一首网易云歌曲（先取直链再播） */
    playSong(song) {
        if (!song) return;
        this.init();
        const doPlay = (url) => {
            if (!url) { quickSay && quickSay('这首歌暂时拿不到播放地址', 'aluona_huaiyi.png'); return; }
            song.url = url;
            this.audio.src = url;
            this.audio.play().catch(() => {});
        };
        if (song.url) { doPlay(song.url); return; }
        axios.get('/ai/music/url', { params: { id: song.id } }).then(r => doPlay(r.data.data)).catch(() => doPlay(null));
    },
    play(index) {
        this.init();
        if (index < 0 || index >= this.playlist.length) return;
        this.currentIndex = index;
        this.playSong(this.playlist[index]);
    },
    pause() { if (this.audio) this.audio.pause(); },
    resume() { if (this.audio) this.audio.play().catch(() => {}); },
    toggle() {
        if (!this.audio || !this.audio.src) { this.play(0); return; }
        this.audio.paused ? this.resume() : this.pause();
    },
    next() {
        if (!this.playlist.length) return;
        let idx;
        if (this.mode === 'random') {
            idx = Math.floor(Math.random() * this.playlist.length);
        } else {
            idx = (this.currentIndex + 1) % this.playlist.length;
        }
        this.play(idx);
    },
    prev() {
        if (!this.playlist.length) return;
        let idx = this.currentIndex <= 0 ? this.playlist.length - 1 : this.currentIndex - 1;
        this.play(idx);
    },
    setVolume(v) {
        this.volume = Math.max(0, Math.min(1, v));
        if (this.audio) this.audio.volume = this.volume;
    },
    setMode(m) { this.mode = m; },
    getCurrentName() {
        if (this.currentIndex < 0 || this.currentIndex >= this.playlist.length) return '';
        const s = this.playlist[this.currentIndex];
        return s.displayName || s.name;
    },
    playing() { return this.audio && !this.audio.paused; },
    onEnded() {
        if (this.mode === 'loop') { this.play(this.currentIndex); }
        else { this.next(); }
    }
};

function handleMusicAction(action) {
    if (!action) return false;
    if (action === 'music_play')       { musicPlayer.toggle(); return true; }
    if (action === 'music_pause')      { musicPlayer.pause(); return true; }
    if (action === 'music_next')       { musicPlayer.next(); return true; }
    if (action === 'music_prev')       { musicPlayer.prev(); return true; }
    if (action === 'music_vol_up')     { musicPlayer.setVolume(musicPlayer.volume + 0.1); return true; }
    if (action === 'music_vol_down')   { musicPlayer.setVolume(musicPlayer.volume - 0.1); return true; }
    if (action === 'music_loop')       { musicPlayer.setMode('loop'); return true; }
    if (action === 'music_random')     { musicPlayer.setMode('random'); return true; }
    if (action === 'music_sequential') { musicPlayer.setMode('sequential'); return true; }
    return false;
}

function loadDialogInfo() {
    axios.get('/basicinfo', { params: { id: 1 } }).then(r => {
        const info = r.data.data;
        if (info) {
            document.getElementById('dialogName').textContent = info['姓名'] || '阿罗娜';
            document.getElementById('dialogRole').textContent = info['身份'] || '什亭之匣';
        }
    });
}

function initLogin() {
    loadDialogInfo();
    callReaction('login');
}

// ===== 空闲检测 =====
function initIdle() {
    // 加载所有 idle 阈值
    axios.get('/conversation', { params: { user_id: '' } }).then(r => {
        const list = r.data.data || [];
        const triggers = [];
        list.forEach(c => {
            const m = (c.user_operation || '').match(/^idle(\d+)$/);
            if (m) triggers.push({ sec: parseInt(m[1]), record: c });
        });
        idleTriggers = triggers.sort((a, b) => a.sec - b.sec);
    });

    // 每秒计时
    idleTimer = setInterval(() => {
        if (!idleEnabled) { idleSeconds = 0; return; }
        if (idleIndex >= idleTriggers.length) return;
        idleSeconds++;
        const next = idleTriggers[idleIndex];
        if (idleSeconds >= next.sec) {
            idleIndex++;
            if (!continueLocked) callReaction(next.record.user_operation);
        }
    }, 1000);

    // 用户操作重置
    const resetIdle = () => { idleSeconds = 0; idleIndex = 0; };
    ['mousemove', 'keydown', 'click', 'scroll'].forEach(evt => {
        document.addEventListener(evt, resetIdle);
    });
}

// 页面加载
document.addEventListener('DOMContentLoaded', () => {
    petImg = document.getElementById('petImg');
    dialogBox = document.getElementById('dialogBox');
    dialogText = document.getElementById('dialogText');
    dialogArrow = document.getElementById('dialogArrow');
    choicesContainer = document.getElementById('choicesContainer');

    document.getElementById('historyPanel').style.display = 'none';
    document.getElementById('choicesWrapper').style.display = 'none';

    requestAnimationFrame(() => {
        initLogin();
        initIdle();
        // 预加载所有音效视频
        preloadSoundEffects();
        axios.get('/effects').then(r => { window.effectList = r.data.data || []; });
        musicPlayer.loadPlaylist();
        document.addEventListener('click', () => { soundUnlocked = true; }, { once: true });
    });

    // Enter 键：切换输入模式（输入框内由 onTalkKey 处理，此处忽略）
    document.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !inputMode && !e.target.closest('#talkInput') && document.activeElement === document.body) {
            e.preventDefault();
            enterInputMode();
        }
    });

    // 鼠标移入/移出（只注册一次）
    petImg.addEventListener('mouseenter', () => {
        if (isInteractionLocked()) return;
        if (reactiveMap['hover']) {
            const c = reactiveMap['hover'];
            delete reactiveMap['hover'];
            delete reactiveMap['click'];
            executeHiddenChoice(c);
        } else {
            callReaction('hover');
        }
    });
    petImg.addEventListener('mouseleave', () => {
        if (isInteractionLocked()) return;
        if (reactiveMap['mouseout']) {
            const c = reactiveMap['mouseout'];
            delete reactiveMap['mouseout'];
            delete reactiveMap['click'];
            delete reactiveMap['hover'];
            executeHiddenChoice(c);
        }
    });
});

// ===== Live2D 模型规则数据库（情境规则库） =====
let l2dRuleParts = {};
let l2dRuleList = [];
let l2dRuleEditId = null;
let l2dRuleSfx = [];
let l2dRuleBc = null;

function openLive2dRulePanel() {
    document.getElementById('historyPanel').style.display = 'flex';
    document.getElementById('panelTitle').textContent = '模型规则库';
    document.getElementById('btnClose').style.display = '';
    document.getElementById('btnBack').style.display = 'none';
    const addBtn = document.querySelector('.panel-buttons .btn-add');
    addBtn.style.display = '';
    addBtn.textContent = '新增情境';
    addBtn.onclick = () => editLive2dRule(null);
    document.getElementById('historyTable').style.display = '';
    document.querySelector('#historyTable thead tr').innerHTML = '<th>序号</th><th>桌宠文本</th><th>情境</th><th>表情</th><th>操作</th>';
    // 情境列半宽（20%→10%），节省 10% 均分给表情(20→25%)与操作(20→25%)
    setPanelColWidths('#historyTable th:nth-child(1),#historyTable td:nth-child(1){width:4%;}#historyTable th:nth-child(2),#historyTable td:nth-child(2){width:36%;}#historyTable th:nth-child(3),#historyTable td:nth-child(3){width:10%;}#historyTable th:nth-child(4),#historyTable td:nth-child(4){width:25%;}#historyTable th:nth-child(5),#historyTable td:nth-child(5){width:25%;}');
    axios.get('/ai/live2d/parts').then(r => { l2dRuleParts = r.data.data || {}; }).catch(() => { l2dRuleParts = {}; });
    axios.get('/soundeffects/list').then(r => { l2dRuleSfx = r.data.data || []; }).catch(() => { l2dRuleSfx = []; });
    loadLive2dRules();
}

function loadLive2dRules() {
    axios.get('/ai/live2d/rules').then(r => {
        l2dRuleList = r.data.data || [];
        renderLive2dRules(l2dRuleList);
    }).catch(() => {});
}

function renderLive2dRules(rows) {
    const tbody = document.getElementById('historyBody');
    if (!rows || !rows.length) { tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:#999;">暂无规则</td></tr>'; return; }
    tbody.innerHTML = rows.map((r, i) => {
        let exprTxt = '—';
        try { const e = JSON.parse(r.expression || '{}'); const ks = Object.keys(e); exprTxt = ks.length ? ks.map(k => e[k] ? k + ':' + e[k] : k).join('、') : '—'; } catch (e) {}
        const bt = (r.boat_text || '').replace(/"/g, '&quot;');
        return `<tr><td>${i + 1}</td><td title="${bt}">${r.boat_text || '—'}</td><td>${r.situation || ''}</td><td>${exprTxt}</td>
            <td><button class="btn-choice" onclick="editLive2dRule(${r.id})">编辑</button> <button class="btn-add" onclick="deleteLive2dRule(${r.id})">删除</button></td></tr>`;
    }).join('');
}

function deleteLive2dRule(id) {
    if (!confirm('确认删除这条规则？')) return;
    axios.post('/ai/live2d/rule/delete', { id }).then(() => loadLive2dRules());
}

function editLive2dRule(id) {
    l2dRuleEditId = id;
    const rule = id ? (l2dRuleList.find(x => x.id === id) || {}) : { situation: '', boat_text: '', expression: '{}', action: '', sound_effect: '' };
    let expr = {};
    try { expr = JSON.parse(rule.expression || '{}'); } catch (e) {}
    let modal = document.getElementById('l2dRuleModal');
    if (!modal) {
        modal = document.createElement('div');
        modal.id = 'l2dRuleModal';
        modal.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.4);display:flex;align-items:center;justify-content:center;z-index:9999;';
        document.body.appendChild(modal);
    }
    let regionHtml = '';
    for (const region in l2dRuleParts) {
        const parts = l2dRuleParts[region] || [];
        const cur = expr[region] || '';
        regionHtml += `<div style="margin:4px 0;"><label style="display:inline-block;width:60px;">${region}</label><select class="l2d-region" data-region="${region}" style="width:160px;">
            <option value="" ${cur === '' ? 'selected' : ''}>空置</option>
            ${parts.map(p => `<option value="${p.part_name}" ${cur === p.part_name ? 'selected' : ''}>${p.part_name}</option>`).join('')}
        </select></div>`;
    }
    modal.innerHTML = `<div style="background:#fff;padding:20px;border-radius:8px;min-width:420px;max-height:80vh;overflow:auto;">
        <div style="font-size:18px;font-weight:bold;margin-bottom:12px;">${id ? '编辑情境规则' : '新增情境规则'}</div>
        <label style="display:block;margin:6px 0 2px;">情境（多个用 , 或 ; 分隔）</label>
        <input id="l2dSituation" style="width:100%;padding:6px;" value="${(rule.situation || '').replace(/"/g, '&quot;')}">
        <label style="display:block;margin:6px 0 2px;">桌宠文本</label>
        <input id="l2dBoatText" style="width:100%;padding:6px;" value="${(rule.boat_text || '').replace(/"/g, '&quot;')}">
        <div style="margin:8px 0;">${regionHtml}</div>
        <label style="display:block;margin:6px 0 2px;">动作</label>
        <select id="l2dAction" style="width:100%;padding:6px;"><option value="">空</option>${['bounce','drop','escape','swing','spin','spin2','spin3','tilt'].map(a => `<option value="${a}" ${rule.action === a ? 'selected' : ''}>${a}</option>`).join('')}</select>
        <label style="display:block;margin:6px 0 2px;">音效</label>
        <select id="l2dSound" style="width:100%;padding:6px;"><option value="">空</option>${l2dRuleSfx.map(s => `<option value="${s}" ${rule.sound_effect === s ? 'selected' : ''}>${s}</option>`).join('')}</select>
        <div style="margin-top:14px;text-align:right;">
            <button class="btn-choice" onclick="previewLive2dRule()">预览动作音效</button>
            <button class="btn-add" onclick="saveLive2dRule()">保存</button>
            <button class="btn-close" onclick="closeLive2dRuleModal()">关闭</button>
        </div>
    </div>`;
    modal.style.display = 'flex';
    modal.querySelectorAll('.l2d-region').forEach(sel => sel.addEventListener('change', previewLive2dRuleExpr));
}

function readLive2dExpr() {
    const expr = {};
    document.querySelectorAll('#l2dRuleModal .l2d-region').forEach(sel => { if (sel.value) expr[sel.dataset.region] = sel.value; });
    return expr;
}

function l2dRuleChannel() {
    try { if (!l2dRuleBc) l2dRuleBc = new BroadcastChannel('l2d-preview'); } catch (e) {}
    return l2dRuleBc;
}

// 实时预览：仅表情（下拉选择即触发）
function previewLive2dRuleExpr() {
    const bc = l2dRuleChannel();
    const msg = { type: 'preview', expr: readLive2dExpr() };
    console.log('[L2D预览] 发送表情:', msg);
    if (bc) bc.postMessage(msg);
}

// 预览动作+音效（按钮触发）
function previewLive2dRule() {
    const bc = l2dRuleChannel();
    const msg = { type: 'preview', expr: readLive2dExpr(), action: document.getElementById('l2dAction').value, sound_effect: document.getElementById('l2dSound').value };
    console.log('[L2D预览] 发送动作音效:', msg);
    if (bc) bc.postMessage(msg);
}

function saveLive2dRule() {
    const situation = document.getElementById('l2dSituation').value.trim();
    if (!situation) { alert('情境不能为空'); return; }
    axios.post('/ai/live2d/rule/save', {
        id: l2dRuleEditId || null,
        situation,
        boat_text: document.getElementById('l2dBoatText').value,
        expression: JSON.stringify(readLive2dExpr()),
        action: document.getElementById('l2dAction').value,
        sound_effect: document.getElementById('l2dSound').value,
        special_effect: ''
    }).then(r => {
        if (r.data.code === 1) { closeLive2dRuleModal(); loadLive2dRules(); }
        else alert(r.data.message || '保存失败');
    });
}

function closeLive2dRuleModal() {
    const m = document.getElementById('l2dRuleModal');
    if (m) m.style.display = 'none';
}