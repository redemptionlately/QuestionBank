# MustRemember

- `jcmd <pid> Thread.print` 显示线程栈、状态和锁等待；heap dump 显示对象引用图；JFR 记录 CPU、分配、锁、GC、I/O 等事件
- 采样 profiler 在时间点取栈，开销低但可能遗漏短事件；插桩 profiler 记录方法进入/退出，细节更多但开销更高——项目真实插桩实例：monitor-starter 的 MyBatisQueryMonitor（MyBatis Interceptor + Plugin.wrap 包 StatementHandler，统计真实落库 SQL 数与慢 SQL，阈值 app.mybatis.slow-sql-threshold-ms 默认 1000ms）
- JDK 诊断入口包括 `jcmd PID VM.native_memory summary`、`GC.heap_info`、`Thread.print` 和 `JFR.start name=... duration=...`
- 类加载链经历加载、链接（验证、准备、解析）和初始化，静态初始化在首次主动使用时执行；双亲委派降低核心类被伪造风险——4 条证据在 [ClassLoaderEvidenceTest.java](../../app/src/test/java/com/allen/questionbank/jvm/ClassLoaderEvidenceTest.java) :87/:107/:135/:155（委派/同名异类/打破委派/TCCL）
- 堆、线程栈、Metaspace、Code Cache 和直接内存的故障表现不同；`OutOfMemoryError: Java heap space`、`Metaspace`、`Direct buffer memory` 需要不同证据
- G1 按 Region 管理堆并以暂停目标选择回收集合；Minor/Young、Mixed、Full GC 的触发与代际、并发标记和空间压力有关，不能只看 GC 次数判断性能；项目脚本 scripts/gc-report.sh、scripts/gc-compare-zgc.sh，历史实测（绑定 -Xms512m-Xmx1g/MaxGCPauseMillis=200 环境）G1 23 次停顿约 147ms、Full GC 0
- 项目 JVM 证据测试：[AqsEvidenceTest.java](../../app/src/test/java/com/allen/questionbank/jvm/AqsEvidenceTest.java) 6 例（手写 AQS 锁/信号量对照）、[JmmHappensBeforeEvidenceTest.java](../../app/src/test/java/com/allen/questionbank/jvm/JmmHappensBeforeEvidenceTest.java) 3 例、[JvmConcurrencyEvidenceTest.java](../../app/src/test/java/com/allen/questionbank/jvm/JvmConcurrencyEvidenceTest.java) 4 例（volatile 可见性、普通字段提升、5000 虚拟线程、拒绝策略）；登录 BCrypt CPU 占比约 84% 由 JFR 实测、scripts/bcrypt-strength-benchmark.sh 可复现
- CPU 火焰图的横向宽度表示采样占比，不直接表示 wall-clock 端到端延迟
- JFR 命令骨架是：
  ```bash
  jcmd $PID JFR.start name=m0 settings=profile duration=60s filename=output/m0.jfr
  jcmd $PID JFR.check
  ```
- 外部源码索引（MustRemember）：[JFR.start](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html) 的 settings/duration/filename 参数；[JFR Event API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/module-summary.html)

# MustUnderstand

- 性能结论绑定输入、JVM（21.0.12）、机器、采样配置、预热和时间窗；一次采样只能说明该时间窗的行为，历史数字不得脱离环境外推
- CPU、分配、锁和 I/O 瓶颈需要不同证据；单火焰图不能解释数据库等待、网络排队和 GC 停顿
- 虚拟线程（Loadgen newVirtualThreadPerTaskExecutor、JvmConcurrencyEvidenceTest:70）不改变 OOM 分区语义，但诊断 native thread 时要区分载体线程与海量虚拟线程
- 外部源码索引（MustUnderstand）：[JDK Mission Control JFR guide](https://docs.oracle.com/javacomponents/jmc-8/jfr-runtime-guide/about-jfr.html) 的事件、聚合和时间窗解释
- 外部源码索引（MustUnderstand）：[JVMS Class Loading](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html) 的加载、链接和初始化；[jcmd command reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)
- 外部源码索引（MustUnderstand）：[G1 Garbage Collector](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector.html) 的 Region、并发标记和暂停目标
- 官方：[JFR API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/module-summary.html)、[JFR Guide](https://docs.oracle.com/javacomponents/jmc-8/jfr-runtime-guide/about-jfr.html)
