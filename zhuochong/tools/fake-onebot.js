/**
 * 假 NapCat（OneBot 11 正向 WebSocket 服务器）—— 本地开发/回归用，不参与打包（不在 src 下）。
 *
 * 为什么有它：真 NapCat 在云服务器上，3001 不对公网开放；改了 com.cy.qq 之后，
 * 用它就能在本机把「握手 → 收事件 → 过滤/冷却 → 回复」整条链路跑一遍，不消耗云上资源。
 *
 * 用法：
 *   1) node tools/fake-onebot.js                    （占用 3001，token=testtoken）
 *   2) 另开窗口跑后端（PowerShell）：
 *        $env:ZC_QQ_ENABLED='true'; $env:ZC_QQ_WS_URL='ws://127.0.0.1:3001'
 *        $env:ZC_QQ_WS_TOKEN='testtoken'; $env:ZC_QQ_SELF_ID='0'
 *        .\mvnw.cmd spring-boot:run
 *   3) 两个窗口对照看：本脚本收到 send_group_msg 就代表群回复成功
 *
 * 校验点：
 *  1) 握手带 Authorization: Bearer <token>，不匹配直接 401
 *  2) get_login_info → 返回 user_id（测「自动识别机器人 QQ 号」）
 *  3) 推送 message.group 事件（array 格式）：
 *     - @自己 + /指令   → 指令优先，应回本地文案（不花 token）
 *     - 不 @ 自己       → 应被忽略
 *     - 同一人 60s 内再 @ → 应被冷却忽略
 *     - 另一个号 @ + 闲聊 → 走 DeepSeekService
 *  4) 接收 send_group_msg，打印群号 + 文本（验证分段发言）
 *  5) 心跳：会看到后端定期发 get_status
 */
const http = require('http');
const crypto = require('crypto');

const PORT = 3001;
const TOKEN = 'testtoken';
const SELF = 123456;          // 假的机器人 QQ
const GROUP = 999001;
const USER_A = 555111;        // 会先发指令、再试冷却
const USER_B = 555222;        // 会走 AI
const ADMIN = 555999;         // 管理员 QQ（后端要配 ZC_QQ_ADMIN_QQ=555999）
const USER_C = 555333;        // 第三方
const USER_D = 555444;        // 第四方

const T0 = Date.now();
const ts = () => {
  const ms = Date.now() - T0;
  return String(ms).padStart(6, ' ') + 'ms';
};
const log = (...a) => console.log('[' + ts() + ']', ...a);
const received = [];
const stickers = [];
const privates = [];
// 自有昵称（get_login_info 报给后端；set_qq_profile 会改它，模拟"改名成功"）
let selfNick = process.env.FAKE_SELF_NICK || '阿罗娜（v0.0.6测试）';
const profileCalls = [];
const cardCalls = [];

// 测试用图片：直接吐一张本地表情 PNG，供后端的「下载 → 缩放 → 视觉理解」链路使用
const path = require('path');
const fs = require('fs');
const IMG_FILE = path.join(__dirname, '..', 'src', 'main', 'resources', 'static', 'qq_emoji', '32.png');

const server = http.createServer((req, res) => {
  if (req.url && req.url.startsWith('/testimg')) {
    try {
      const buf = fs.readFileSync(IMG_FILE);
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': buf.length });
      res.end(buf);
      log('→ 返回测试图片 /testimg（' + Math.round(buf.length / 1024) + 'KB）');
    } catch (e) {
      res.writeHead(404); res.end('no image'); log('✗ 测试图片不存在: ' + IMG_FILE);
    }
    return;
  }
  res.writeHead(426, { 'Content-Type': 'text/plain' });
  res.end('426 upgrade required (this is a WebSocket endpoint)');
});

server.on('upgrade', (req, socket) => {
  const auth = req.headers['authorization'] || '';
  log('收到握手请求 Authorization=', auth || '(空)');
  if (auth !== 'Bearer ' + TOKEN) {
    log('✗ token 不匹配 → 401');
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  const key = req.headers['sec-websocket-key'];
  const accept = crypto.createHash('sha1')
    .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
    .digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
  );
  log('✓ 握手成功，等待客户端调用...');

  sendText(socket, JSON.stringify({ post_type: 'meta_event', meta_event_type: 'lifecycle', sub_type: 'connect' }));

  // ===== 收帧 =====
  // 注意：JDK 的 WebSocket 会把大消息（比如 base64 表情图，几百 KB）拆成多个帧发：
  //   opcode=1 FIN=0  →  opcode=0 连续帧 …  → 最后一帧 FIN=1
  // 真 NapCat 会自己拼回来，这里也必须拼，否则表情包那条消息永远解析失败。
  let buf = Buffer.alloc(0);
  let pending = null; // 正在拼接的分片 [{...}]
  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    let frame;
    while ((frame = decodeFrame(buf)) !== null) {
      buf = buf.subarray(frame.length);
      if (frame.opcode === 8) { log('客户端关闭连接'); socket.end(); return; }

      if (frame.opcode === 0) { // 连续帧
        if (!pending) { log('⚠ 收到孤立的连续帧，忽略'); continue; }
        pending.push(frame.payload);
        if (frame.fin) {
          handleMessage(socket, Buffer.concat(pending).toString('utf8'));
          pending = null;
        }
        continue;
      }
      if (frame.opcode !== 1) continue; // 二进制/控制帧不关心

      if (frame.fin) {
        handleMessage(socket, frame.payload.toString('utf8'));
      } else {
        pending = [frame.payload];
      }
    }
  });
  socket.on('error', (e) => log('socket 错误:', e.message));

  // ===== 按时间线推事件 =====
  schedule(socket);
});

function handleMessage(socket, text) {
  let msg;
  try {
    msg = JSON.parse(text);
  } catch (e) {
    log('非法 JSON（前 120 字）:', text.slice(0, 120));
    return;
  }
  onApi(socket, msg);
}

function onApi(socket, msg) {
  const { action, echo, params } = msg;
  if (action === 'get_login_info') {
    log('← get_login_info（客户端来问自己是谁）');
    reply(socket, echo, { user_id: SELF, nickname: selfNick });
    return;
  }
  if (action === 'get_status') {
    log('← get_status（心跳探活）');
    reply(socket, echo, { online: true, good: true });
    return;
  }
  if (action === 'get_group_info') {
    log('← get_group_info（问群名）');
    reply(socket, echo, { group_id: params.group_id, group_name: '测试群', member_count: 3 });
    return;
  }
  if (action === 'set_qq_profile') {
    const nn = (params && params.nickname) || '';
    profileCalls.push(nn);
    log('← set_qq_profile nickname=' + JSON.stringify(nn) + '（旧名 ' + JSON.stringify(selfNick) + '）');
    if (!nn) {
      sendText(socket, JSON.stringify({ status: 'failed', retcode: 100, data: null, echo }));
      return;
    }
    selfNick = nn;
    log('✓ 昵称已改为 ' + JSON.stringify(selfNick) + '（再 get_login_info 会返回新名字）');
    reply(socket, echo, {});
    return;
  }
  if (action === 'set_group_card') {
    cardCalls.push({ group: params.group_id, user: params.user_id, card: params.card });
    log('← set_group_card 群=' + params.group_id + ' QQ=' + params.user_id
      + ' card=' + JSON.stringify(params.card));
    reply(socket, echo, {});
    return;
  }
  if (action === 'send_private_msg') {
    const segs = params.message || [];
    const text = segs.map((s) => (s.data && s.data.text) || '').join('');
    const quote = segs.find((s) => s.type === 'reply');
    privates.push(text);
    log('← send_private_msg 私聊→' + params.user_id
      + (quote ? ' 🔁引用=' + quote.data.id : '')
      + ' 文本=' + JSON.stringify(text));
    reply(socket, echo, { message_id: 8000 + privates.length });
    return;
  }
  if (action === 'send_group_msg') {
    const segs = params.message || [];
    const text = segs.map((s) => (s.data && s.data.text) || '').join('');
    const image = segs.find((s) => s.type === 'image');
    const quote = segs.find((s) => s.type === 'reply');
    const quoteTag = quote ? ' 🔁引用=' + quote.data.id : '';
    if (image) {
      const file = (image.data && image.data.file) || '';
      const kb = Math.round(file.length / 1024);
      stickers.push(kb);
      log('← send_group_msg 群=' + params.group_id + ' 📷 图片 base64≈' + kb + 'KB'
        + quoteTag
        + (file.startsWith('base64://') ? '（base64:// 格式 ✓）' : '（⚠ 不是 base64:// 格式: ' + file.slice(0, 40) + '）'));
    } else {
      received.push({ text, quote: quote ? quote.data.id : null, at: Date.now() });
      log('← send_group_msg 群=' + params.group_id + quoteTag + ' 文本=' + JSON.stringify(text));
    }
    reply(socket, echo, { message_id: 9000 + received.length + stickers.length });
    return;
  }
  log('← 未处理的 action:', action);
  reply(socket, echo, {});
}

function reply(socket, echo, data) {
  sendText(socket, JSON.stringify({ status: 'ok', retcode: 0, data, echo }));
}

function schedule(socket) {
  // ===== 表情包闸门测试时间线 =====
  // 后端建议：ZC_QQ_REPLY_DELAY=1 ZC_QQ_USER_COOLDOWN=0 ZC_QQ_EMOJI_GAP=1
  // 期望：
  //   ① 平淡回复 + 首次（无间隔限制）→ 不发（情绪不够）
  //   ② 强情绪回复 + 已过间隔     → 发（并重置计数）
  //   ③ 强情绪回复 + 间隔不足     → 不发（冷却）
  //   ④ 平淡回复 + 已过间隔       → 不发（情绪不够）

  // ① 平淡：今天星期三
  setTimeout(() => push(socket, {
    user: USER_A, nick: '小明', text: '今天是星期三吧？', at: true, id: 2001,
  }), 2500);

  // ② 强情绪：好可爱！！
  setTimeout(() => push(socket, {
    user: USER_B, nick: '老师', text: '阿罗娜你今天怎么这么可爱！！', at: true, id: 2002,
  }), 5000);

  // ③ 强情绪但刚发过图：间隔不够
  setTimeout(() => push(socket, {
    user: USER_C, nick: '刷屏哥', text: '哇！！你也太厉害了吧！！', at: true, id: 2003,
  }), 7500);

  // ④ 平淡但间隔够了：情绪不够
  setTimeout(() => push(socket, {
    user: USER_D, nick: '路人', text: '现在几点了？', at: true, id: 2004,
  }), 10000);

  // 管理员看表情包概况 + 调间隔
  setTimeout(() => pushPrivate(socket, { user: ADMIN, nick: '老板', text: '/emoji', id: 2010 }), 12500);
  setTimeout(() => pushPrivate(socket, { user: ADMIN, nick: '老板', text: '/emoji gap 5', id: 2011 }), 13500);

  // 汇总
  setTimeout(() => {
    log('==== 本次共收到 ' + received.length + ' 条群文字 + ' + stickers.length + ' 张表情 + '
      + privates.length + ' 条私聊 ====');
    received.forEach((r, i) => log('  群文字#' + (i + 1) + (r.quote ? '（引用 ' + r.quote + '）' : '') + ' ' + JSON.stringify(r.text)));
    stickers.forEach((kb, i) => log('  表情#' + (i + 1) + ' base64≈' + kb + 'KB'));
    privates.forEach((t, i) => log('  私聊#' + (i + 1) + ' ' + JSON.stringify(t)));
    if (profileCalls.length) log('  改昵称请求：' + profileCalls.map((n) => JSON.stringify(n)).join(' → ')
      + '（最终 ' + JSON.stringify(selfNick) + '）');
    if (cardCalls.length) log('  改群名片请求：' + cardCalls.length + ' 次，最后一次 '
      + JSON.stringify(cardCalls[cardCalls.length - 1]));
  }, 20000);
}
function pushPrivate(socket, { user, nick, text, id }) {
  const event = {
    post_type: 'message',
    message_type: 'private',
    sub_type: 'friend',
    self_id: SELF,
    user_id: user,
    message_id: id,
    raw_message: text,
    message: [{ type: 'text', data: { text } }],
    sender: { user_id: user, nickname: nick, card: '' },
    time: Math.floor(Date.now() / 1000),
  };
  log('→ 推送私聊 #' + id + ' 来自 ' + nick + '(' + user + '): ' + text);
  sendText(socket, JSON.stringify(event));
}

function push(socket, { user, nick, text, at, id, imageUrl }) {
  const message = [];
  if (at) message.push({ type: 'at', data: { qq: String(SELF) } });
  if (text) message.push({ type: 'text', data: { text } });
  if (imageUrl) {
    message.push({
      type: 'image',
      data: { file: 'test.png', url: imageUrl, sub_type: 0, summary: '[图片]' },
    });
  }
  const cq = (at ? '[CQ:at,qq=' + SELF + ']' : '')
    + (imageUrl ? '[CQ:image,file=test.png,url=' + imageUrl + ']' : '')
    + (text || '');
  const event = {
    post_type: 'message',
    message_type: 'group',
    sub_type: 'normal',
    self_id: SELF,
    group_id: GROUP,
    user_id: user,
    message_id: id,
    raw_message: cq,
    message,
    sender: { user_id: user, nickname: nick, card: '' },
    time: Math.floor(Date.now() / 1000),
  };
  log('→ 推送事件 #' + id + ' @=' + at + ' 来自 ' + nick + '(' + user + '): ' + text);
  sendText(socket, JSON.stringify(event));
}

// ===== 极简 WebSocket 帧编解码 =====
function sendText(socket, str) {
  const payload = Buffer.from(str, 'utf8');
  let header;
  if (payload.length < 126) {
    header = Buffer.from([0x81, payload.length]);
  } else if (payload.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81; header[1] = 126; header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81; header[1] = 127; header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  socket.write(Buffer.concat([header, payload]));
}

function decodeFrame(buf) {
  if (buf.length < 2) return null;
  const first = buf[0], second = buf[1];
  const fin = (first & 0x80) !== 0;
  const opcode = first & 0x0f;
  const masked = (second & 0x80) !== 0;
  let len = second & 0x7f;
  let offset = 2;
  if (len === 126) {
    if (buf.length < offset + 2) return null;
    len = buf.readUInt16BE(offset); offset += 2;
  } else if (len === 127) {
    if (buf.length < offset + 8) return null;
    len = Number(buf.readBigUInt64BE(offset)); offset += 8;
  }
  let mask = null;
  if (masked) {
    if (buf.length < offset + 4) return null;
    mask = buf.subarray(offset, offset + 4); offset += 4;
  }
  if (buf.length < offset + len) return null;
  const payload = Buffer.from(buf.subarray(offset, offset + len));
  if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
  return { opcode, fin, payload, length: offset + len };
}

server.listen(PORT, '0.0.0.0', () => {
  log('假 NapCat 已启动: ws://0.0.0.0:' + PORT + '（token=' + TOKEN + '，自身QQ=' + SELF + '，群=' + GROUP + '）');
});
