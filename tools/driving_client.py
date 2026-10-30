#!/usr/bin/env python3
"""
外部驾驶客户端：**用 Python 进程真的开动游戏里的列车**。

和 `control_client.py` 的区别：
  - 那个演示的是「操作员扣车」（一次性指令）；
  - 这个演示的是「远程调度内核」——每帧回一条行车指令，
    宿主把它当成 EXTERNAL 来源写进控制通道。

协议（见 README）：服务端每帧推一份快照，客户端回一份指令。

    {"trains": [{"id": "<uuid>", "authority": 2000.0, "cap": 0.3}],
     "aspects": [{"signal": "<uuid>", "side": "FIRST", "aspect": "ctd:clear"}]}

`cap` 是限速（格/tick）。0.3 格/tick ≈ 21.6 km/h。
"""

import base64
import hashlib
import json
import os
import socket
import struct
import sys

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

CAP = 0.3
AUTHORITY = 2000.0


class WebSocket:
    def __init__(self, url):
        rest = url[5:]
        hostport, _, path = rest.partition("/")
        path = "/" + path
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=10)
        key = base64.b64encode(os.urandom(16)).decode()

        self.sock.sendall((
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {hostport}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        ).encode())

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
    seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 25.0

    ws = WebSocket(url)
    print(f"connected: {url}  cap={CAP}")

    import time
    end = time.time() + seconds
    frames = 0
    sent = 0

    while time.time() < end:
        opcode, payload = ws.recv()
        if opcode != 0x1:
            continue
        frames += 1
        try:
            msg = json.loads(payload)
        except json.JSONDecodeError:
            continue

        trains = msg.get("trains") or []
        if not trains:
            continue

        cmd = {
            "trains": [
                {"id": t.get("id"), "authority": AUTHORITY, "cap": CAP}
                for t in trains
                if t.get("id")
            ]
        }
        ws.send(json.dumps(cmd))
        sent += 1

        if sent % 40 == 1:
            t = trains[0]
            print(f"frame {frames}: {len(trains)} train(s), "
                  f"reported speed={t.get('speed')} -> commanding cap={CAP}")

    print(f"done. frames={frames} commands={sent}")
    ws.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
