# MustRemember

- JVM 运行时区域包括堆、线程栈、Metaspace、Code Cache 和直接内存；对象不可达后才进入 GC 判断，局部变量离开作用域不等于立即释放。
- 类加载过程是加载、链接（验证/准备/解析）和初始化；双亲委派优先让父加载器尝试加载，线程上下文类加载器可改变框架资源发现。
- GC 调优先看分配速率、暂停、存活对象和堆外内存证据；不能只根据一次 Full GC 就更换收集器。
- 线程池核心参数是 core、max、queue、keepAlive、拒绝策略和线程命名；队列、超时和拒绝共同决定背压，不能无限扩容。
- `CompletableFuture` 的异步阶段应显式指定 executor；阻塞 I/O 混入公共池会拖慢无关任务，虚拟线程适合大量阻塞 I/O 但不消除数据库和 CPU 容量限制。
- 性能证据要区分端到端延迟、线程池排队、数据库耗时、GC 暂停和 CPU 样本；平均值不能替代 P95/P99。
- 项目源码/测试索引（MustRemember）：`common/InfrastructureConfig.java:22-56`（import 2/4/100/30s、general 2/8/200/10s 双池、CallerRuns、@EnableScheduling）、`common/InfrastructureExecutorRoutingTest`（4 用例反射断言池参数与线程名前缀）、`jvm/JvmConcurrencyEvidenceTest`（volatile 立即可见、普通标志可能被 JIT 提升、虚拟线程阻塞任务、有界队列饱和 AbortPolicy）、`jvm/AqsEvidenceTest`（手写 MiniReentrantLock/MiniSemaphore 与 JDK 对照 6 证据）、`tools/loadgen/Loadgen.java`（开环压测、虚拟线程、warmup 后取样、P50/P95/P99/P999）。
- 外部源码索引（MustRemember）：[JVM Tool Interface](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)、[JFR](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html)、[ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)、[Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)

# MustUnderstand

- 性能优化必须先提出瓶颈假设，再固定输入和环境、采集基线、只改一个变量并验证回归；“CPU 高”本身不是根因。
- 堆内存、直接内存、文件描述符、连接池和线程栈是不同资源；扩大堆可能减少 GC 频率但增加暂停和容器内存压力。
- 线程池大小受任务类型和下游容量约束；CPU 密集任务接近 CPU 核数，阻塞任务还要结合等待比例和外部服务容量。
- 外部源码索引（MustUnderstand）：[Java Flight Recorder](https://docs.oracle.com/javacomponents/jmc-8/jfr-runtime-guide/about-jfr.htm)、[G1 GC tuning](https://docs.oracle.com/en/java/javase/21/gctuning/)
