#!/usr/bin/env python3
"""
ctd-web 的外部客户端示例 —— **只用标准库**，不装 websockets。

用法：
    python example_client.py [ws://127.0.0.1:8765/ctd]

它做两件事：
  1. 每帧收到快照，打印列车表；
  2. 收到 task 就回一个 taskResult（这里只回显，真实用途是排图/重新规划）。

协议见 README.md。这个文件同时也是「协议是否够用」的可执行检查：
如果写这个客户端时觉得别扭，说明协议该改。
"""

import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WebSocket:
    """最小 WebSocket 客户端：握手 + 文本帧收发。"""

    def __init__(self, url):
        # ws://host:port/path
        rest = url[5:]
        hostport, _, path = rest.partition("/")
        path = "/" + path
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()

        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {hostport}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode())

        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("handshake closed")
            head += chunk
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise RuntimeError("handshake rejected: " + head.split(b"\r\n")[0].decode())

        expected = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        if expected.encode() not in head:
            raise RuntimeError("bad Sec-WebSocket-Accept")
        self.buf = head.split(b"\r\n\r\n", 1)[1]

    def _recv_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        b0, b1 = self._recv_exact(2)
        opcode = b0 & 0x0F
        masked = b1 & 0x80
        length = b1 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._recv_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._recv_exact(8))[0]
        mask = self._recv_exact(4) if masked else b"\x00\x00\x00\x00"
        payload = bytearray(self._recv_exact(length))
        for i in range(length):
            payload[i] ^= mask[i & 3]
        if opcode == 0x8:
            raise ConnectionError("server closed")
        return opcode, bytes(payload)

    def send(self, text):
        payload = text.encode()
        header = bytearray([0x81])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n <= 0xFFFF:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(payload[i] ^ mask[i & 3] for i in range(n))
        self.sock.sendall(bytes(header) + masked)


def main():
    url = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8765/ctd"
    ws = WebSocket(url)
    print(f"connected: {url}")

    last_print = 0.0
    frames = 0

    while True:
        opcode, payload = ws.recv()
        if opcode != 0x1:
            continue
        try:
            msg = json.loads(payload)
        except json.JSONDecodeError:
            continue

        frames += 1

        # ① 任务：回声。真实实现应在这里排图/重新规划，然后回结果。
        if "task" in msg:
            task = msg["task"]
            reply = {
                "taskResult": {
                    "id": task.get("id", ""),
                    "ok": True,
                    # payload 是**不含内嵌引号**的字符串：桥用极简扫描取值，\"
                    # 这类转义它不处理。协议因此规定 payload 保持单层。
                    "payload": "echo:" + task.get("kind", ""),
                }
            }
            ws.send(json.dumps(reply))
            print(f"task {task.get('id')} kind={task.get('kind')} -> replied")

        # ② 快照：限流打印，避免刷屏
        elif "trains" in msg:
            now = time.time()
            if now - last_print >= 1.0:
                last_print = now
                trains = msg.get("trains", [])
                print(f"[t={msg.get('time')}] frames={frames} trains={len(trains)}")
                for t in trains[:5]:
                    print(f"   {t.get('name') or t.get('id')[:8]:16s} "
                          f"v={t.get('speed'):.3f} target={t.get('target'):.3f} "
                          f"MA={t.get('authority'):.0f}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nbye")
