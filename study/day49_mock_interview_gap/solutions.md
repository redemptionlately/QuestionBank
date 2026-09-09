# Day49 Mock Interview Gap · 题目与标准解答（Solutions）

> 综合模拟面试复盘，答案结合本项目真实实现，区分已验证基线与外部设计。

## Current

### Q1. 60 分钟模拟并运行 test/package/E2E。
流程：10 分钟自述项目 → 30 分钟八股 + 项目追问 → 15 分钟手写代码/SQL → 5 分钟反问。过程中实际跑：`mvn test`（9 个测试）、`mvn package`（产出 jar）、启动后走一遍登录→发布→提交 E2E。本机无 Maven 时用 IDE 等价执行，并如实说明环境限制。

---

### Q2. 列五个“不会”，并把每个不会拆成五项。
诚实列缺口（示例：Redis 集群、Kafka 精确一次、K8s 调度、JVM 调优实战、分库分表）。每个不会按五项拆解：
1. **定义**：它是什么、解决什么问题；
2. **源码位置/对应物**：本项目里最接近的实现（如 Redis→ExpiringCache、MQ→@Async 任务）；
3. **边界**：现有实现哪里不够、差异是什么；
4. **证据**：要验证它需要什么实验/测试；
5. **复习缺口**：要补的最小知识点清单。
这样“不会”也能展示结构化理解，而不是直接说不知道。

---

### Q3. 口述各核心域概念（每域一两句精确版）。
- **对象模型**：封装状态与不变量，实体状态转换内聚（如 session.submit()）；
- **泛型**：编译期类型安全、类型擦除，PECS（extends 读、super 写）；
- **集合**：HashMap 平均 O(1)、非线程安全；ConcurrentHashMap 单操作原子；
- **异常**：checked 强制处理、unchecked 表示编程/运行错误，事务默认只对 RuntimeException 回滚；
- **I/O**：字节流 vs 字符流、NIO Files、try-with-resources、不可信输入禁用原生反序列化；
- **JUC**：可见性/原子性/有序性、锁、CAS、线程池、并发容器；
- **JMM**：happens-before、volatile 可见性禁重排但不保证复合原子；
- **JVM**：堆/栈/Metaspace、GC、类加载；
- **Spring**：IoC/AOP 代理、MVC、Security、声明式事务；
- **MySQL**：索引、MVCC、隔离级别、锁、执行计划；
- **HTTP**：方法语义、状态码、幂等、请求头；
- **算法**：复杂度 + 双指针/滑窗/哈希/树图/DP 各模型识别。

---

### Q4. 写出 HashMap 定位/扩容、volatile/synchronized、JVM 内存区、TWR 示例。
**HashMap**：`int idx = (n-1) & hash(key)`（hash 已扰动），负载因子 0.75，阈值到了容量翻倍并重新分桶；链表长度 ≥8 且数组容量 ≥64 时树化为红黑树，退化回链表在 ≤6。平均 O(1)、最坏 O(log n)（树化后），非线程安全。
**volatile vs synchronized**：volatile 保证可见性 + 禁重排，但 `count++`（读-改-写）不原子；synchronized 同时保证互斥与 happens-before（可见性 + 原子性）。
**JVM 内存区**：堆存对象（GC 管理，不随变量出作用域立即释放）、虚拟机栈存栈帧（局部变量/操作数栈）、Metaspace 存类元数据、程序计数器、直接内存为堆外。
**try-with-resources**：实现 AutoCloseable 的资源在 try 结束自动 close；主异常抛出时 close 的异常作为 **suppressed exception** 附着（`getSuppressed()`）。

---

### Q5. 项目问题的标准回答结构。
业务目标 → 领域模型 → 请求数据流 → 事务/锁 → 数据库事实 → 错误响应 → 证据边界。以提交幂等为例：目标（重复提交结果一致）→ 模型（Session/Item/Wrong）→ 数据流（header→filter→controller→service）→ 锁（FOR UPDATE 行锁 + 幂等键）→ 事实（结果 JSON 落库）→ 错误（400/409）→ 证据（并发测试 + 行号）。

### Q6. 区分四类问题的回答入口。
Java 语义问题→查 JLS 语言规则；Spring 问题→先问“Bean 是否被容器管理、调用是否经过代理”；SQL 问题→看执行计划与隔离级别；并发问题→找共享状态、可见性/原子性与失败边界。

---

### Q7. Spring AOP/JDBC/MQ/TCP-Linux/分页/探针：定义+代码命令+失败边界+项目结合。
- **AOP**：代理织入横切；失败边界是 self-invocation；项目中 @Transactional/@PreAuthorize 都靠它；
- **JDBC**：DataSource→Connection→PreparedStatement→ResultSet；失败边界是连接泄漏/注入；项目用 JPA 但锁定查询本质是 FOR UPDATE；
- **MQ**：producer confirm/broker 持久化/consumer ack/重试/死信；项目对应物是持久化 import_job + @Async worker；
- **TCP/Linux**：ss/lsof/curl -v/top；失败边界是把五段延迟都算给应用；
- **分页**：稳定排序（含唯一 tiebreaker）、限制最大 page size、深分页用游标；
- **容器探针**：liveness 决定是否重启、readiness 决定是否接流量。

---

### Q8. Git 协作 + 最小 CI；用判分逻辑说明 Strategy/事件。
**Git 协作**：小步提交、feature 分支、`status/diff/log` 检查、merge/rebase 后冲突解决必须重新测试。
**最小 CI**：依赖缓存 → 编译 → 单元/集成测试 → 静态检查 → 打包镜像 → 发布；密钥通过受控环境变量/Secrets 注入（不写代码/镜像）；任一阶段失败阻断后续发布。
**设计模式（讲解决的问题，不背名）**：判分规则用 **Strategy**——把 SINGLE/MULTIPLE/TRUE_FALSE（未来主观题）判分封装为可替换策略，新增题型不改提交主流程；不适用场景：规则永远唯一且无变化时引入 Strategy 是过度设计。发布后通知/失效缓存用 **Observer/事件**（afterCommit 事件）解耦；不适用：需要强一致、立即知道结果的核心写路径。

### Q9. Stream/Optional 无副作用链 + Path/Files + 滚动回滚。
```java
// 无副作用：不在 map/filter 里改外部状态
List<String> names = papers.stream()
    .filter(Objects::nonNull)
    .map(PaperVersion::getTitle)
    .sorted()
    .toList();
String first = Optional.ofNullable(maybe).map(PaperVersion::getTitle).orElseThrow(() -> notFound("无"));
```
Path/Files 显式 UTF-8、JSON 新增可空字段向后兼容（见 Day04/16）。
**滚动发布回滚**：配置外置（环境变量/配置中心）、迁移只加兼容列（expand/contract）、镜像按版本回退；回滚到旧镜像时数据库不能有旧代码无法兼容的破坏性变更，因此迁移必须前向兼容。

---

## External

### E1. 按 JD 调整优先级、追问通过才算 learned。
对照目标 JD 把知识点排序（如后端岗优先事务/索引/并发/项目，算法岗优先题型深度）。一个知识点要能扛住连续三层追问（是什么→为什么→项目里怎样/反例）才算掌握，否则仍标缺口。

### E2. merge/rebase/cherry-pick 提交图变化与 CI 顺序。
- merge：保留分叉并生成合并提交（历史真实、有菱形）；rebase：把本分支提交“搬”到目标分支顶端，历史线性但改写 commit hash，已推送共享分支 rebase 有风险；cherry-pick：摘单个提交应用到当前分支；冲突都需解决后重新跑测试。
- CI 顺序与密钥位置见 Q8；发布阶段用不可变镜像 + 版本标签，回滚即重新部署上一版本标签。
