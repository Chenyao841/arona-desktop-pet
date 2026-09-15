# -*- coding: utf-8 -*-
"""
本地 OCR 服务（供桌宠桌面操控用）
依赖：pip install rapidocr_onnxruntime  （会自动带上 onnxruntime / opencv-python / numpy 等）
启动：python ocr_server.py
端口：9881

POST /ocr   body: {"image": "<base64 图片>"}
返回：[{"text": "...", "x": .., "y": .., "w": .., "h": .., "score": ..}, ...]
"""
import base64
import json

import cv2
import numpy as np
from rapidocr_onnxruntime import RapidOCR

engine = RapidOCR()


def ocr(img_bytes):
    nparr = np.frombuffer(img_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    res = engine(img)
    out = []

    # 兼容两种返回格式：
    # 旧版 rapidocr_onnxruntime：返回 (result, elapse)，result 是 [[box, text, score], ...]
    # 新版 rapidocr：返回对象，带 .boxes / .txts / .scores
    if isinstance(res, tuple):
        items = res[0] if res[0] else []
        for item in items:
            box, text, score = item[0], item[1], item[2]
            xs = [float(p[0]) for p in box]
            ys = [float(p[1]) for p in box]
            out.append({
                "text": text,
                "x": int(round(min(xs))),
                "y": int(round(min(ys))),
                "w": int(round(max(xs) - min(xs))),
                "h": int(round(max(ys) - min(ys))),
                "score": round(float(score), 3),
            })
    else:
        boxes = getattr(res, "boxes", None) or []
        txts = getattr(res, "txts", None) or []
        scores = getattr(res, "scores", None) or []
        for i, text in enumerate(txts):
            box = boxes[i] if i < len(boxes) else None
            score = scores[i] if i < len(scores) else 0.0
            if box is None:
                continue
            xs = [float(p[0]) for p in box]
            ys = [float(p[1]) for p in box]
            out.append({
                "text": text,
                "x": int(round(min(xs))),
                "y": int(round(min(ys))),
                "w": int(round(max(xs) - min(xs))),
                "h": int(round(max(ys) - min(ys))),
                "score": round(float(score), 3),
            })
    return out


from http.server import HTTPServer, BaseHTTPRequestHandler


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path.rstrip('/') == '/ocr':
            try:
                length = int(self.headers.get('Content-Length', '0') or '0')
                body = self.rfile.read(length)
                data = json.loads(body.decode('utf-8'))
                img_b64 = data.get('image', '')
                img_bytes = base64.b64decode(img_b64)
                out = ocr(img_bytes)
                resp = json.dumps(out, ensure_ascii=False).encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)
            except Exception as e:
                resp = json.dumps({"error": str(e)}, ensure_ascii=False).encode('utf-8')
                self.send_response(500)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(resp)))
                self.end_headers()
                self.wfile.write(resp)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *args):
        pass  # 静默，避免刷屏


if __name__ == '__main__':
    port = 9881
    server = HTTPServer(('127.0.0.1', port), Handler)
    print('OCR server listening on 127.0.0.1:%d' % port, flush=True)
    server.serve_forever()
