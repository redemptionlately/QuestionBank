#!/usr/bin/env python
"""复现"运行中 jar 被同路径覆盖 → 旧句柄按缓存 zip 索引读到错位字节"的机制。

事故背景（2026-09-10 实锤）：
  用户在 05:40 从 deploy/question-bank-app.jar 启动长驻实例（PID 94740）。
  证据脚本（multi-instance-evidence.sh / multi-instance-throughput.sh）打包后
  `cp -f` 覆盖了同一个文件。Windows 允许覆盖已打开的文件，但运行中 JVM 的
  ZipFile 句柄持有旧central directory（offset 缓存）——之后对新 class 的
  懒加载按缓存 offset 读新文件字节 → 读到错位数据 → class 数据损坏。

  现象：BCrypt 校验失败触发 Tomcat 错误日志路径（DirectJDKLog → JUL →
  SLF4JBridgeHandler → logback，首次需要 ThrowableProxy 类）→
  `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`，
  且真正的异常被日志崩溃掩盖。

本脚本纯 zipfile 层模拟同一机制：
  1) 复制真实 fat jar 并打开句柄（模拟运行中 JVM）；
  2) 构造"布局不同"的替代 jar（lib 区前插入 padding entry，所有后续 offset 漂移）；
  3) 覆盖原路径；
  4) 旧句柄按缓存 offset 读取嵌套 jar → 期望读到错位字节（BadZipFile/垃圾）。

用法：python scripts/repro-jar-overwrite.py
预期输出：覆盖前内层 zip 合法=True；覆盖后合法=False。
"""
import io
import os
import shutil
import sys
import zipfile

SRC = os.path.join(os.path.dirname(__file__), "..", "deploy", "question-bank-app.jar")
WORK = os.path.join(os.path.dirname(__file__), "..", "output", "jar_overwrite_repro")
TARGET = "BOOT-INF/lib/logback-classic-1.5.18.jar"


def zip_ok(data):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            return z.testzip() is None
    except Exception:
        return False


def main():
    if not os.path.isfile(SRC):
        print("SKIP: deploy/question-bank-app.jar 不存在")
        sys.exit(2)
    os.makedirs(WORK, exist_ok=True)
    a = os.path.join(WORK, "running_copy.jar")
    b = os.path.join(WORK, "replacement.jar")
    shutil.copy(SRC, a)

    zin = zipfile.ZipFile(a)                      # 模拟运行中 JVM 持有的句柄
    data_before = zin.read(TARGET)
    ok_before = zip_ok(data_before)

    with zipfile.ZipFile(a) as zsrc, zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as zout:
        inserted = False
        for item in zsrc.infolist():
            if not inserted and item.filename.startswith("BOOT-INF/lib/") and item.filename.endswith(".jar"):
                zout.writestr(zipfile.ZipInfo("BOOT-INF/lib/padding-marker.txt"),
                              os.urandom(8192))
                inserted = True
            zout.writestr(item, zsrc.read(item.filename))

    shutil.copy(b, a)                              # 模拟 cp -f 覆盖

    try:
        data_after = zin.read(TARGET)              # 旧句柄按缓存 offset 读新文件
        ok_after = zip_ok(data_after)
    except Exception as e:                          # 读本身就抛错 = 机制复现
        print("覆盖后 旧句柄读取本身即失败: %s: %s" % (type(e).__name__, e))
        ok_after = False

    print("覆盖前 logback-classic 从旧句柄读出: 内层 zip 合法 =", ok_before)
    print("覆盖后 logback-classic 从旧句柄读出: 内层 zip 合法 =", ok_after)
    print("结论: %s" % ("复现成功——旧句柄按缓存索引读到错位字节" if not ok_after else "未复现"))


if __name__ == "__main__":
    main()