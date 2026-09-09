# Day56 Performance & JVM · 题目与标准解答（Solutions）

> 性能定位方法论与 JVM/线程池调优，结论必须绑定证据与环境。

## Current

### Q1. 写出“CPU 高、延迟高”用 jcmd/jstack/JFR 定位的证据链。
1. **先分层假设**：CPU 高是应用计算、GC、锁竞争还是 I/O 等待？
2. `top -Hp <pid>` 找到高 CPU 线程 tid，转十六进制；
3. `jcmd <pid> Thread.print`（或 jstack）抓线程栈，用该 tid 定位具体方法栈；
4. `jcmd <pid> JFR.start settings=profile duration=60s filename=x.jfr`，JMC 看 CPU 采样火焰图、分配、GC Pause、锁阻塞、Socket 事件；
5. 交叉验证：CPU 采样热点 + GC 日志（暂停频率/时长）+ DB 慢查询/锁等待；
6. 只改一个变量（如优化热点 SQL/算法/加索引），重测对比 P95/P99 与 CPU，确认回归。
关键：**“CPU 高”是现象不是根因**；延迟高但 CPU 不高时更可能是锁、GC 停顿、连接池排队或下游 I/O。

---

### Q2. 按任务类型选线程池参数、队列与拒绝策略。
- **CPU 密集**：核心线程数 ≈ 核数（+1），队列有界，避免过多线程上下文切换；
- **阻塞 I/O 密集**：线程数可按 `核数 × (1 + 等待时间/计算时间)` 估算，但**上限受下游容量（DB 连接、下游 QPS）约束**，不是越大越好；
- **混合任务**：用多个独立池隔离（bulkhead），避免慢 I/O 占满公共池拖垮计算任务（本项目 importTaskExecutor 独立于 Web 线程池）；
- 队列必须**有界**，配合拒绝策略：AbortPolicy 快速失败、CallerRunsPolicy 反压到调用方；keepAlive 回收多余线程；线程命名便于 thread dump 辨认。
本项目 InfrastructureConfig：核心 2、最大 4、队列 100、命名线程工厂。

---

### Q3. 堆/Metaspace/直接内存/线程栈溢出的症状与首个证据。
| 问题 | 典型症状/报错 | 首个证据 |
|---|---|---|
| 堆不足 | `OutOfMemoryError: Java heap space`，GC 频繁、吞吐下降 | GC 日志 + heap dump（MAT 看支配树/泄漏点） |
| Metaspace 不足 | `OutOfMemoryError: Metaspace`，加载类数单调涨 | NMT（jcmd VM.native_memory）、类加载统计 |
| 直接内存不足 | `OutOfMemoryError: Direct buffer memory`，堆不高但进程 RSS 涨 | NMT、-XX:MaxDirectMemorySize、Netty/NIO 分配监控 |
| 线程栈/无法建线程 | `unable to create new native thread`、StackOverflowError | 线程数、ulimit -u、递归深度/-Xss |
注意：对象不可达才会被 GC 回收，局部变量出作用域不等于立即释放；扩大堆可能降低 GC 频率但拉长单次暂停并增加容器内存压力。

---

## External

### E1. 比较公共 ForkJoinPool、自定义 Executor、虚拟线程处理阻塞任务的风险。
- **commonPool**：并行流/`supplyAsync` 不指定 executor 时默认使用，大小≈核数-1；若在其中做阻塞 JDBC/HTTP，会占满少量公共线程拖慢所有无关并行任务——阻塞操作绝不能丢进 commonPool；
- **自定义 Executor**：显式控制 core/max/有界队列/拒绝策略与命名，阻塞任务用它并按下游容量定大小，可控可观测；
- **虚拟线程（Java 21）**：阻塞时卸载载体线程、成本极低，适合海量 I/O 并发；但不增加 DB 连接/CPU/下游容量，synchronized 长阻塞可能 pin 载体，大量 ThreadLocal 有内存开销，并发仍受连接池约束。

### E2. P95 上升但平均值稳定的原因。
均值对少量长尾不敏感，P95 直接反映尾部：① **排队**：线程池/连接池在高峰出现等待，多数请求仍快、少数排队很久；② **GC 暂停**：偶发 Full/Mixed GC 让落在暂停窗口的请求变慢；③ **锁竞争**：少数请求等行锁/监视器（如同热行提交）；④ 下游抖动/网络重传。用 JFR 看锁与 GC 事件、连接池等待指标、按时间分桶的 P95 定位，均值稳定不代表体验稳定。

### E3. 只改队列容量的基准实验与停止条件。
固定：代码、机器、JVM 参数、DB 数据量、并发模型与压测时长/warmup；唯一变量是线程池队列容量（如 10/100/1000）。观测：吞吐、P95/P99、拒绝数、队列等待时间、连接池占用、错误率。停止条件：错误率超阈值、P99 超 SLO、出现 OOM/连接耗尽、或队列无限堆积导致内存上涨即停止。预期结论：大队列只是把压力转为更长排队（延迟更差），并不提升吞吐，验证“队列削峰不等于增容”。
