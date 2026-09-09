#!/usr/bin/env python3
"""极简轮询负载均衡器（纯标准库）：把请求轮流转发到多个上游实例。

用途：多实例吞吐对比——1 个实例 vs N 个实例（经本 LB）。生产请用 nginx/网关，
这里自造轮子只是为了在没有 nginx 的本机拿到可复现的对比数据。
"""
import argparse
import http.client
import itertools
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=18080)
parser.add_argument("--targets", required=True, help="逗号分隔 host:port，如 127.0.0.1:8081,127.0.0.1:8082")
args = parser.parse_args()

UPSTREAMS = [("127.0.0.1", int(t.rsplit(":", 1)[1])) for t in args.targets.split(",")]
counter = itertools.count()
counter_lock = threading.Lock()
HOP_HEADERS = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
               "te", "trailers", "transfer-encoding", "upgrade", "content-length", "host"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _forward(self):
        with counter_lock:
            host, port = UPSTREAMS[next(counter) % len(UPSTREAMS)]
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        try:
            conn = http.client.HTTPConnection(host, port, timeout=15)
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
            conn.request(self.command, self.path, body=body, headers=headers)
            resp = conn.getresponse()
            data = resp.read()
        except Exception as error:
            self.send_response(502)
            msg = ("upstream failed: %s" % error).encode()
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)
            return
        self.send_response(resp.status)
        for k, v in resp.getheaders():
            if k.lower() not in HOP_HEADERS:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_POST = _forward
    do_GET = _forward

    def log_message(self, *ignored):
        pass


ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
