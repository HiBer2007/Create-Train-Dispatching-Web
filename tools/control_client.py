#!/usr/bin/env python3
"""
最小控制客户端：连上游戏内桥，读一帧遥测，然后下一道「扣车」指令。

用法：
    python control_client.py [ws://127.0.0.1:8765/ctd] [train-uuid]

不给 train-uuid 时用快照里第一列车。这个脚本是 D5 的可执行证据：
**外部进程能看见车，也能动它。**
"""

import base64
import hashlib
import json
import os
import socket
import struct
import sys

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WebSocket:
    def __init__(self, url):
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
            raise RuntimeError("handshake rejected")

        expected = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        if expected.encode() not in head:
            raise RuntimeError("bad accept key")

    def recv(self):
        head = self._read(2)
        opcode = head[0] & 0x0F
        n = head[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read(8))[0]
        return opcode, self._read(n)

    def send(self, payload: str):
        data = payload.encode()
        header = bytearray([0x81])
        n = len(data)
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
        masked = bytes(data[i] ^ mask[i & 3] for i in range(n))
        self.sock.sendall(bytes(header) + masked)

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise RuntimeError("closed")
            buf += chunk
        return buf

    def close(self):
        self.sock.close()


def main():
    url = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8765/ctd"
    want = sys.argv[2] if len(sys.argv) > 2 else None

    ws = WebSocket(url)
    print(f"connected: {url}")

    train = want
    frames = 0

    # 先读几帧，拿到一列车
    while train is None and frames < 200:
        opcode, payload = ws.recv()
        if opcode != 0x1:
            continue
        frames += 1
        try:
            msg = json.loads(payload)
        except json.JSONDecodeError:
            continue
        trains = msg.get("trains") or []
        if trains:
            train = trains[0].get("id")
            print(f"frame {frames}: {len(trains)} train(s), picked {train}")

    if not train:
        print("no train in telemetry; is a train loaded?")
        ws.close()
        return 1

    cmd = json.dumps({"control": {"train": train, "kind": "hold", "note": "python control client"}})
    ws.send(cmd)
    print(f"sent: {cmd}")

    # 再读几帧，确认桥还活着
    for _ in range(20):
        opcode, payload = ws.recv()
        if opcode == 0x1:
            frames += 1

    print(f"total frames={frames}")
    ws.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
