# Day41 JVM Profiling · 题目与标准解答（Solutions）

> Java 21 / HotSpot 通用知识，结合本项目 Spring Boot 进程。

## Current

### Q1. 按当天索引定位并口述 I/O 边界（方法论题）。
对运行中的 Spring Boot 进程，先用 `jps`/`jcmd`（不带参数列出 JVM）拿到 PID，再按需采 Thread/GC/JFR；所有结论绑定该时间窗、JVM 参数与机器，不外推。

---

### Q2. 对 M0 采一段短 JFR，指出热点与限制。
```bash
jps                                         # 找到 question-bank 进程 PID
jcmd $PID JFR.start name=m0 settings=profile duration=60s filename=output/m0.jfr
jcmd $PID JFR.check                          # 查看录制状态
# 结束后用 JDK Mission Control 打开 m0.jfr，看 CPU 采样、内存分配、GC、锁、IO 事件
```
- **热点**：火焰图/方法采样中占宽最大的栈即 CPU 热点；本项目可能落在 Jackson 序列化、Hibernate、加解密（BCrypt 本身就是 CPU 密集）；
- **限制**：60s 采样只代表该时间窗；采样可能漏掉极短事件；没有真实压测流量时热点不具代表性；JFR 看不到数据库内部等待与网络对端耗时。

---

### Q3. 区分 thread dump / heap dump / JFR / 采样 profiler / 插桩 profiler 的证据范围。
| 手段 | 是什么 | 能证明 | 不能证明 |
|---|---|---|---|
| thread dump | 某一时刻所有线程栈与状态/锁等待 | 死锁、线程阻塞点、锁竞争、线程数 | 时间维度趋势，需要多次采样 |
| heap dump | 某时刻堆中对象引用图 | 内存泄漏、大对象、占用最多的类 | 对象分配速率的时间过程 |
| JFR | 低开销持续事件记录（CPU/分配/锁/GC/IO） | 一段时间内综合行为与事件关联 | 非采样期之外的行为 |
| 采样 profiler | 定时取栈（如 async-profiler） | CPU 热点分布，开销低 | 短方法可能漏采，不直接等于端到端延迟 |
| 插桩 profiler | 方法进入/退出都记录 | 精确调用次数/耗时 | 开销大、可能扭曲结果（观察者效应） |

---

### Q4. 写出 jcmd Thread.print、GC.heap_info、JFR.start 及输出字段。
```bash
jcmd $PID Thread.print          # 线程栈：线程名、状态(RUNNABLE/BLOCKED/WAITING)、持锁/等锁、调用栈
jcmd $PID GC.heap_info          # 堆信息：各代/Region 容量、已用、GC 相关
jcmd $PID VM.native_memory summary   # 需开启 NMT，看 native/Metaspace/线程栈/直接内存
jcmd $PID JFR.start name=x settings=profile duration=60s filename=x.jfr
```
Thread.print 关键字段：`java.lang.Thread.State`、`- waiting to lock <地址>` / `locked <地址>`、`parking to wait for`；GC.heap_info 看 used/capacity。

---

### Q5. 为 CPU、分配、锁、I/O 四种瓶颈分别选证据。
| 瓶颈 | 首选证据 |
|---|---|
| CPU 高 | JFR/async-profiler CPU 采样火焰图、线程栈 |
| 内存分配压力/泄漏 | JFR 分配事件、GC 频率与停顿、heap dump 对比（多次 dump 看增长） |
| 锁竞争 | thread dump 的 BLOCKED、JFR 锁竞争事件（monitor blocked/Java Monitor） |
| I/O 等待 | JFR Socket/File 事件、结合 DB 慢查询与网络耗时；线程多为 RUNNABLE 但实际在 socketRead |
单一 CPU 火焰图解释不了 DB 等待、网络排队、GC 停顿，需要对应证据交叉。

---

### Q6. 口述类加载阶段、双亲委派、各内存区 OOM 差异；为 G1 延迟问题选 JFR 字段。
**类加载**：加载（Loading，找到 class 字节流生成 Class 对象）→ 链接（验证 Verification → 准备 Preparation（静态变量赋默认值）→ 解析 Resolution（符号引用转直接引用））→ 初始化（Initialization，执行 `<clinit>` 静态变量赋值与静态块）。**静态初始化在类首次主动使用时执行**（new、访问静态字段/方法、反射、子类初始化触发父类等）。
**双亲委派**：类加载请求先委托父加载器，父加载不到才自己加载，保证核心类（java.*）由启动类加载器加载，防止伪造核心类；Tomcat/插件系统/模块化框架用自定义 ClassLoader 打破以实现隔离与热部署。

**不同区域 OOM**：
| 错误 | 区域 | 典型原因 | 诊断 |
|---|---|---|---|
| `Java heap space` | 堆 | 对象太多/内存泄漏/堆太小 | heap dump + GC 日志 |
| `Metaspace` | Metaspace（类元数据，本地内存） | 类加载泄漏（动态生成类不卸载） | NMT、类加载统计 |
| `Direct buffer memory` | 堆外直接内存 | NIO ByteBuffer 未释放、直接内存上限小 | NMT、-XX:MaxDirectMemorySize |
| `unable to create new native thread` | 线程栈（本地） | 线程数超过系统限制 | 线程数/ulimit |

**G1 延迟问题看的 JFR 字段**：GC Pause 事件（Young/Mixed/Full GC 暂停时长与频率）、并发标记阶段、evacuation 失败（to-space exhausted）、分配速率、大对象（humongous allocation）。G1 按 Region 管理堆，以 `-XX:MaxGCPauseMillis` 暂停目标选回收集合（CSet）；不能只看 GC 次数，要看**暂停总时长与 P99**。

---

## External

### E1. 制造锁竞争并保存原始环境。
用多线程争抢同一把锁（或本项目并发提交同一 session 的 FOR UPDATE），连续多次 `Thread.print` 观察 BLOCKED 线程与持锁线程，JFR 看 monitor 等待时长。保存证据时同时记录：JDK 版本、JVM 参数（-Xmx、GC）、机器规格、命令输出原始文件与时间窗，保证可复现。

### E2. 用证据区分 heap / Metaspace / 直接内存不足；说明自定义 ClassLoader。
- heap：heap dump 有大量业务对象、GC 后堆仍高；
- Metaspace：NMT 显示 Class 元数据持续增长、加载类数单调上升（常见于反复创建 ClassLoader 的脚本/代理框架）；
- 直接内存：堆不高但进程 RSS 持续涨、NMT 中 Internal/Other 增长，Netty/NIO 场景多见。
插件/容器场景（Tomcat 每个 webapp 一个 WebappClassLoader、OSGi、Java 模块化）会先自己加载再委托（或部分打破双亲委派），以实现应用间类隔离与独立卸载，代价是更容易产生类加载器泄漏，分析时要看 ClassLoader 层级而非默认委派链。
