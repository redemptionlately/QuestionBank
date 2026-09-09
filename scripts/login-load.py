#!/usr/bin/env python3
"""登录接口开环压测驱动（纯标准库，供多实例吞吐对比使用）。

输出 JSON：{rps, ok, fail, p50_ms, p95_ms, p99_ms, duration_s}。
注意：必须显式禁用系统代理——沙箱会给 urllib 塞 http_proxy，打 127.0.0.1 会被代理挡回 502。
"""
import argparse
import json
import statistics
import threading
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--url", required=True, help="完整登录 URL")
parser.add_argument("--seconds", type=float, default=15.0)
parser.add_argument("--concurrency", type=int, default=16)
parser.add_argument("--username", default="admin")
parser.add_argument("--password", default="admin123")
args = parser.parse_args()

BODY = json.dumps({"username": args.username, "password": args.password}).encode()
HEADERS = {"Content-Type": "application/json"}
# 关键：空 ProxyHandler = 绕开环境变量里的代理（沙箱会拦截 127.0.0.1）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

ok, fail, stop = 0, 0, False
latencies = []
lock = threading.Lock()


def worker():
    global ok, fail
    while not stop:
        start = time.perf_counter()
        try:
            req = urllib.request.Request(args.url, data=BODY, headers=HEADERS, method="POST")
            with OPENER.open(req, timeout=10) as resp:
                resp.read()
            status_ok = True
        except Exception:
            status_ok = False
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        with lock:
            if status_ok:
                ok += 1
                latencies.append(elapsed_ms)
            else:
                fail += 1


threads = [threading.Thread(target=worker, daemon=True) for _ in range(args.concurrency)]
start = time.perf_counter()
for t in threads:
    t.start()
time.sleep(args.seconds)
stop = True
for t in threads:
    t.join(timeout=15)
duration = time.perf_counter() - start

latencies.sort()


def pct(p):
    if not latencies:
        return 0.0
    idx = min(len(latencies) - 1, int(len(latencies) * p / 100.0))
    return round(latencies[idx], 1)


print(json.dumps({
    "url": args.url,
    "rps": round(ok / duration, 1),
    "ok": ok,
    "fail": fail,
    "p50_ms": pct(50),
    "p95_ms": pct(95),
    "p99_ms": pct(99),
    "duration_s": round(duration, 2),
    "concurrency": args.concurrency,
}))
