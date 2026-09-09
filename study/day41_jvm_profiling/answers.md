# Current
- 打开并按当天 study.md 的“源码索引（MustRemember）”或“外部源码索引（MustRemember）”逐项定位文件、类/方法、SQL 对象和行号，独立写出对应代码或 SQL，并口述输入、输出与边界。

- 对 M0 采短 JFR
- 指出热点和限制
- 区分 thread dump、heap dump、JFR、采样 profiler 和插桩 profiler 的证据范围。
- 写出 `jcmd` Thread.print、GC.heap_info 和 JFR.start 命令及各自输出字段。
- 为 CPU、分配、锁、I/O 四种瓶颈分别选择证据。
- 口述类加载阶段、双亲委派、堆/栈/Metaspace/直接内存的 OOM 差异，并为一次 G1 延迟问题选择 JFR 字段。
- 写出类加载的加载、链接、初始化顺序，并说明静态初始化何时执行。
- 分别为 Java heap、Metaspace、direct buffer OOM 选择诊断命令和证据。

# External

- 制造锁竞争
- 保存原始文件环境
- 用 `jcmd` 或 JFR 证据区分堆内存、Metaspace 和直接内存不足。
- 说明双亲委派在插件/容器场景中可能被哪些自定义 ClassLoader 改变。
