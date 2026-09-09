#!/usr/bin/env python3
"""增量覆盖率（diff-coverage）门禁。

为什么需要它：Jacoco 的全量门禁只回答"整个代码库有多少被跑到"，
但它会被历史存量稀释——新增 100 行一行没测，全量覆盖率可能只掉 0.3%，门禁照样绿。
diff-coverage 只问一件事：**这次改动新增/修改的那些可执行行，有没有被测试跑到**。

原理（不依赖任何第三方服务，纯本地可复跑）：
  1. 从 jacoco.xml 取出逐行覆盖：package/sourcefile/line，ci>0 即该行被执行过；
     jacoco 只给"可执行行"建 line 记录，所以没有记录的行 = 不可执行（注释/空行/纯声明），
     必须排除，否则会把覆盖率无意义地拉低。
  2. 从 git diff -U0 取出变更行（hunk 头 +c,d 的新侧行号区间）。
  3. 两者按 (文件路径, 行号) 求交集，统计"变更且可执行"的行的覆盖比例。

用法：
  python3 scripts/diff-coverage.py --base <git-ref> [--head HEAD] [--min 80]
  python3 scripts/diff-coverage.py --base WORKTREE            # 比对未提交的工作区改动
  python3 scripts/diff-coverage.py --base origin/main --merge-base   # PR 语义（三点 diff）

退出码：0=通过；1=低于阈值；2=用法/环境错误。
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HUNK_RE = re.compile(r"^@@+ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def run_git(args):
    proc = subprocess.run(["git"] + args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        sys.stderr.write("git 命令失败: git %s\n%s\n" % (" ".join(args), proc.stderr.strip()))
        sys.exit(2)
    return proc.stdout


def load_jacoco(path, src_root):
    """返回 {(源文件相对路径, 行号): 是否被覆盖} 与报告级整体行覆盖率。"""
    root = ET.parse(path).getroot()
    covered = {}
    for pkg in root.findall("package"):
        pkg_dir = pkg.attrib.get("name", "")
        for sf in pkg.findall("sourcefile"):
            rel = "%s/%s/%s" % (src_root, pkg_dir, sf.attrib.get("name", "")) if pkg_dir else \
                  "%s/%s" % (src_root, sf.attrib.get("name", ""))
            rel = rel.replace("\\", "/").replace("//", "/")
            for line in sf.findall("line"):
                covered[(rel, int(line.attrib["nr"]))] = int(line.attrib.get("ci", "0")) > 0
    overall = None
    for c in root.findall("counter"):
        if c.attrib.get("type") == "LINE":
            missed, cov = int(c.attrib["missed"]), int(c.attrib["covered"])
            overall = (cov / (missed + cov) * 100.0) if (missed + cov) else None
    return covered, overall


def changed_lines(base, head, src_root, merge_base=False, repo_root=None):
    """返回 {文件路径: set(变更行号)}，只取新侧（+）行。"""
    if base == "WORKTREE":
        diff = run_git(["diff", "-U0", "--", src_root])
    elif merge_base:
        diff = run_git(["diff", "-U0", "%s...%s" % (base, head), "--", src_root])
    else:
        diff = run_git(["diff", "-U0", base, head, "--", src_root])

    result = {}
    cur = None
    for raw in diff.splitlines():
        if raw.startswith("+++ "):
            path = raw[4:].strip().split("\t")[0]
            if path == "/dev/null":
                cur = None
            else:
                # 只剥掉 git 的 "b/" 前缀本身。绝不能用 lstrip/replace——
                # 路径内部完全可能含 "b/"（本项目 importjob/ImportJob.java 就命中过，
                # 无脑 replace 会让整个文件的变更行静默逃过门禁，交叉验证时才发现）
                cur = path[2:] if path[:2] in ("a/", "b/") else path
        elif raw.startswith("@@"):
            m = HUNK_RE.match(raw)
            if not m or cur is None:
                continue
            start = int(m.group(1))
            count = 1 if m.group(2) is None else int(m.group(2))
            if count == 0:
                continue  # 纯删除
            result.setdefault(cur, set()).update(range(start, start + count))
    return result


def main():
    ap = argparse.ArgumentParser(description="增量覆盖率（diff-coverage）门禁")
    ap.add_argument("--jacoco", default="app/target/site/jacoco/jacoco.xml", help="jacoco XML 报告路径")
    ap.add_argument("--base", required=True, help="基线 git ref；WORKTREE 表示未提交改动")
    ap.add_argument("--head", default="HEAD", help="目标 git ref")
    ap.add_argument("--src-root", default="app/src/main/java", help="主代码根（jacoco 与 git 路径对齐用）")
    ap.add_argument("--min", type=float, default=80.0, help="增量覆盖率下限（%%）")
    ap.add_argument("--merge-base", action="store_true", help="用三点 diff（PR 语义，比对 merge-base）")
    ap.add_argument("--show", type=int, default=30, help="最多列出多少条未覆盖行")
    args = ap.parse_args()

    jacoco_path = Path(args.jacoco)
    if not jacoco_path.exists():
        sys.stderr.write("找不到 jacoco 报告: %s（先跑测试生成）\n" % jacoco_path)
        sys.exit(2)

    covered, overall = load_jacoco(str(jacoco_path), args.src_root)
    changes = changed_lines(args.base, args.head, args.src_root, args.merge_base)

    total_changed = sum(len(v) for v in changes.values())
    exec_total = exec_covered = 0
    missed = []
    for path, lines in sorted(changes.items()):
        for nr in sorted(lines):
            hit = covered.get((path, nr))
            if hit is None:
                continue  # 不可执行行，不计入分母
            exec_total += 1
            if hit:
                exec_covered += 1
            else:
                missed.append((path, nr))

    print("=" * 72)
    print("增量覆盖率（diff-coverage）")
    print("  基线/目标        : %s .. %s%s" % (args.base, args.head, "（三点 diff）" if args.merge_base else ""))
    print("  jacoco 报告      : %s" % jacoco_path)
    if overall is not None:
        print("  报告整体行覆盖率 : %.1f%%（仅供参考，非门禁口径）" % overall)
    print("  变更文件数       : %d" % len(changes))
    print("  变更行数         : %d" % total_changed)
    print("  其中可执行行     : %d（覆盖 %d / 未覆盖 %d）" % (exec_total, exec_covered, len(missed)))
    print("=" * 72)

    if exec_total == 0:
        print("本次改动没有新增/修改任何可执行代码行（纯测试/文档/构建改动或不可执行行）→ 门禁直接通过")
        return 0

    ratio = exec_covered / exec_total * 100.0
    print("  增量覆盖率       : %.1f%%（门禁 ≥ %.1f%%）" % (ratio, args.min))

    if ratio + 1e-9 < args.min:
        print("\n未覆盖的变更行（最多 %d 条）：" % args.show)
        for path, nr in missed[:args.show]:
            print("  %s:%d" % (path, nr))
        if len(missed) > args.show:
            print("  ... 另有 %d 条" % (len(missed) - args.show))
        print("\n门禁失败：增量覆盖率 %.1f%% 低于 %.1f%%" % (ratio, args.min))
        return 1

    if missed:
        print("  未覆盖变更行     : %d 条（未触发门禁，因整体达标）" % len(missed))
    print("门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
