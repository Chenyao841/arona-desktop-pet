# -*- coding: utf-8 -*-
# 依赖：pip install requests websocket-client brotli
import json
import struct
import time
import hashlib
import threading
import requests
import websocket
import brotli
import zlib

ROOM_ID = 1982822181
COOKIE = "bili_jct=d36beeac4f58f43ce813aba826495d5c;SESSDATA=51d7ec96%2C1802267821%2C94f54%2A82CjDaPcpwJIUJDu_eg3uAHwlOe1jq-UZjQhYMGsSZFqjiPDaAVe7g8sR9e31MfJfR9KsSVlJBWmY1NTFrNzZpbDAzZlctRXdERFJxRHR2RjBWeWd3SEE0UktvbDYta0dkeXVmU1d6RGM5VjBvWjVBR2ZNSXFiT2VGZmd1YldRRktqZ2xkZVpFS21nIIEC"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

headers = {"User-Agent": UA, "Referer": "https://www.bilibili.com/", "Cookie": COOKIE}

# 1. WBI 密钥
nav = requests.get("https://api.bilibili.com/x/web-interface/nav", headers=headers).json()
img_url = nav["data"]["wbi_img"]["img_url"]
sub_url = nav["data"]["wbi_img"]["sub_url"]
img_key = img_url.split("/")[-1].split(".")[0]
sub_key = sub_url.split("/")[-1].split(".")[0]

MIXIN_TAB = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52]
orig = img_key + sub_key
mixin_key = "".join(orig[i] for i in MIXIN_TAB)[:32]

# 2. getDanmuInfo（WBI 签名）
wts = int(time.time())
query = f"id={ROOM_ID}&wts={wts}"
w_rid = hashlib.md5((query + mixin_key).encode()).hexdigest()
print(f"img_key={img_key}")
print(f"sub_key={sub_key}")
print(f"mixin_key={mixin_key}")
print(f"query={query}")
print(f"w_rid={w_rid}")
danmu_url = f"https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id={ROOM_ID}&wts={wts}&w_rid={w_rid}"
danmu = requests.get(danmu_url, headers=headers).json()
print("getDanmuInfo code:", danmu.get("code"))
token = danmu["data"]["token"]
hl = danmu["data"]["host_list"][0]
host = hl["host"]
port = hl.get("wss_port", hl.get("port"))
print(f"token={token[:20]}... host={host} port={port}")

# 3. 包构建
def build_packet(protover, op, seq, body):
    return struct.pack(">IHHII", 16 + len(body), 16, protover, op, seq) + body

auth_json = json.dumps({"uid": 0, "roomid": ROOM_ID, "protover": 3, "platform": "web", "type": 2, "key": token}, separators=(",", ":"))
auth_packet = build_packet(1, 7, 1, auth_json.encode())
heartbeat_packet = build_packet(1, 2, 1, b"[object Object]")

# 4. 连接 + 认证 + 立即心跳
ws = websocket.create_connection(f"wss://{host}:{port}/sub", header={"User-Agent": UA}, timeout=15)
print("connected, sending auth")
ws.send(auth_packet, opcode=websocket.ABNF.OPCODE_BINARY)
print("auth sent, sending heartbeat immediately")
ws.send(heartbeat_packet, opcode=websocket.ABNF.OPCODE_BINARY)

def heartbeat_loop():
    while True:
        time.sleep(30)
        try:
            ws.send(heartbeat_packet, opcode=websocket.ABNF.OPCODE_BINARY)
            print("heartbeat sent")
        except Exception:
            break
threading.Thread(target=heartbeat_loop, daemon=True).start()

# 5. 接收
while True:
    data = ws.recv()
    if isinstance(data, str):
        continue
    offset = 0
    while offset + 16 <= len(data):
        total_len, header_len, protover, op, seq = struct.unpack_from(">IHHII", data, offset)
        body = data[offset + header_len : offset + total_len]
        offset += total_len
        if op == 8:
            print(">>> AUTH OK")
        elif op == 3:
            print(">>> heartbeat reply")
        elif op == 5:
            try:
                if protover == 3:
                    body = brotli.decompress(body)
                elif protover == 2:
                    body = zlib.decompress(body)
            except Exception as e:
                print("decompress err:", e)
                continue
            text = body.decode("utf-8", errors="ignore")
            for part in text.split("}{"):
                if not part.startswith("{"):
                    part = "{" + part
                if not part.endswith("}"):
                    part = part + "}"
                try:
                    msg = json.loads(part)
                    cmd = msg.get("cmd", "")
                    if cmd == "INTERACT_WORD":
                        print(">>> ENTER:", msg["data"].get("uname"))
                    elif cmd == "DANMU_MSG":
                        print(">>> DANMU:", msg["info"][1])
                except Exception:
                    pass
