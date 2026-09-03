# 必须会背会写

- `jcmd <pid> Thread.print` 显示线程栈、状态和锁等待；heap dump 显示对象引用图；JFR 记录 CPU、分配、锁、GC、I/O 等事件
- 采样 profiler 在时间点取栈，开销低但可能遗漏短事件；插桩 profiler 记录方法进入/退出，细节更多但开销更高
- JDK 诊断入口包括 `jcmd PID VM.native_memory summary`、`GC.heap_info`、`Thread.print` 和 `JFR.start name=... duration=...`
- 类加载链通常经历加载、链接（验证、准备、解析）和初始化；双亲委派降低核心类被伪造的风险，应用服务器和插件系统可能使用自定义 ClassLoader
- 堆、线程栈、Metaspace、Code Cache 和直接内存的故障表现不同；`OutOfMemoryError: Java heap space`、`Metaspace`、`Direct buffer memory` 需要不同证据
- G1 按 Region 管理堆并以暂停目标选择回收集合；Minor/Young、Mixed、Full GC 的触发与代际、并发标记和空间压力有关，不能只看 GC 次数判断性能
- CPU 火焰图的横向宽度表示采样占比，不直接表示 wall-clock 端到端延迟
- JFR 命令骨架是：
  ```bash
  jcmd $PID JFR.start name=m0 settings=profile duration=60s filename=output/m0.jfr
  jcmd $PID JFR.check
  ```
- 外部源码索引（会背会写）：[JFR.start](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html) 的 settings/duration/filename 参数；[JFR Event API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/module-summary.html)

# 必须理解

- 性能结论绑定输入、JVM、机器、采样配置、预热和时间窗；一次采样只能说明该时间窗的行为
- CPU、分配、锁和 I/O 瓶颈需要不同证据；单火焰图不能解释数据库等待、网络排队和 GC 停顿
- 外部源码索引（必须理解）：[JDK Mission Control JFR guide](https://docs.oracle.com/javacomponents/jmc-8/jfr-runtime-guide/about-jfr.html) 的事件、聚合和时间窗解释
- 外部源码索引（会背会写）：[JVMS Class Loading](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html) 的加载、链接和初始化；[jcmd command reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)
- 外部源码索引（必须理解）：[G1 Garbage Collector](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector.html) 的 Region、并发标记和暂停目标
- 官方：[JFR API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/module-summary.html)、[JFR Guide](https://docs.oracle.com/javacomponents/jmc-8/jfr-runtime-guide/about-jfr.html)
