#!/usr/bin/env bash
# 解析 -Xlog:gc* 日志（G1 与 ZGC 两种格式）：GC 次数/类型、停顿分布、吞吐占比、回收效率。
set -u
LOG="${1:?用法: gc-report.sh <gc日志文件>}"
python - "$LOG" << 'EOF'
import re, sys

path = sys.argv[1]
pauses = []          # (uptime_s, name, before_mb, after_mb, capacity_mb, pause_ms)
uptime_end = 0.0

# G1: GC(12) Pause Young (Normal) (G1 Evacuation Pause) 234M->45M(1024M) 5.678ms
pat_g1 = re.compile(
    r'\[.*?\]\[([\d.]+)s\].*?GC\((\d+)\)\s+Pause\s+(\w[\w ]*?)\s.*?(\d+)M->(\d+)M\((\d+)M\)\s+([\d.]+)ms')
# ZGC: GC(0) Y: Pause Mark Start (Major) 0.012ms
pat_zgc = re.compile(
    r'\[.*?\]\[([\d.]+)s\].*?GC\((\d+)\)\s+(?:Y|O):\s+(Pause\s+[\w ]+?)\s+([\d.]+)ms')
# ZGC 回收汇总: GC(4) Garbage Collection (Warmup) 24M(2%)->12M(1%)
pat_zgc_sum = re.compile(
    r'\[.*?\]\[([\d.]+)s\].*?GC\((\d+)\)\s+Garbage Collection\s+\((\w+)\)\s+(\d+)M\(\d+%\)->(\d+)M\(\d+%\)')
pat_uptime = re.compile(r'\[.*?\]\[([\d.]+)s\]')

for line in open(path, encoding='utf-8', errors='replace'):
    m = pat_g1.search(line)
    if m:
        uptime, _, name, before, after, cap, ms = m.groups()
        pauses.append((float(uptime), name.strip(), int(before), int(after), int(cap), float(ms)))
        u = pat_uptime.search(line); uptime_end = max(uptime_end, float(u.group(1)))
        continue
    m = pat_zgc.search(line)
    if m:
        uptime, _, name, ms = m.groups()
        pauses.append((float(uptime), name.strip(), -1, -1, -1, float(ms)))
        u = pat_uptime.search(line); uptime_end = max(uptime_end, float(u.group(1)))
    m = pat_zgc_sum.search(line)
    if m:
        pass  # 汇总行不进停顿表
    u = pat_uptime.search(line)
    if u:
        uptime_end = max(uptime_end, float(u.group(1)))

if not pauses:
    print("日志里没有解析到 GC 停顿记录")
    sys.exit(0)

total_pause = sum(p[5] for p in pauses)
def count(kw): return sum(1 for p in pauses if kw in p[1])
ps = sorted(p[5] for p in pauses)

if pauses[0][2] >= 0:  # G1
    young = count('Young'); mixed = count('Mixed'); full = count('Full')
    print(f"收集器: G1   观测窗口: {uptime_end:.0f}s   停顿次数: {len(pauses)}   累计停顿: {total_pause:.0f}ms")
    print(f"G1 吞吐估算: {(1 - total_pause/1000/uptime_end)*100:.2f}%（停顿时间占比口径）")
    print(f"Young: {young} 次   Mixed: {mixed} 次   Full: {full} 次")
    reclaimed = [p[2]-p[3] for p in pauses if 'Young' in p[1]]
    if reclaimed:
        print(f"Young 回收均值: {sum(reclaimed)/len(reclaimed):.0f}M/次   堆容量: {pauses[0][4]}M")
    if full:
        print(f"!!! 出现 {full} 次 Full GC")
else:  # ZGC
    collections = len(set())  # 按GC编号去重
    ids = set()
    for line in open(path, encoding='utf-8', errors='replace'):
        m = re.search(r'GC\((\d+)\)\s+(?:Y|O):\s+Pause', line)
        if m: ids.add(m.group(1))
    print(f"收集器: ZGC(generational)   观测窗口: {uptime_end:.0f}s   停顿阶段数: {len(pauses)}（{len(ids)} 轮回收）   累计停顿: {total_pause:.2f}ms")
    print(f"ZGC 吞吐估算: {(1 - total_pause/1000/uptime_end)*100:.3f}%（仅 STW 阶段口径）")
    print(f"Mark Start: {count('Mark Start')}   Mark End: {count('Mark End')}   Relocate Start: {count('Relocate Start')}")

print(f"单次停顿 P50: {ps[len(ps)//2]:.3f}ms   P95: {ps[int(len(ps)*0.95)]:.3f}ms   MAX: {ps[-1]:.3f}ms")
print()
print("最慢 5 次:")
for p in sorted(pauses, key=lambda x: -x[5])[:5]:
    heap = f"  {p[2]}M->{p[3]}M/{p[4]}M" if p[2] >= 0 else ""
    print(f"  uptime={p[0]:.1f}s  {p[1]}{heap}  {p[5]:.3f}ms")
EOF
