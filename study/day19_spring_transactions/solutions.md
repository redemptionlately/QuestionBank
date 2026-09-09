# Day19 Spring Transactions · 题目与标准解答（Solutions）

> 依据：`study.md` + 真实 `BankService.createDraft`、`PracticeService.submit`。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
锚点：`BankService` 35-52 行草稿事务（PaperVersion + 全部 QuestionVersion 同成败）；`PracticeService` 52-85 行提交事务（Session + Item + 错题同成败）。

---

### Q2. 运行 M0 rollback。
对应集成测试 `invalidDraftRollsBackEntireVersion`：创建草稿时第二题非法（如标准答案不在选项中），`validateQuestion` 抛 `ApiException(RuntimeException)`。断言：
- PaperVersion 没有新增（`papers.count()` 前后相等）；
- 第一题即使已 `save`，也随事务整体回滚，`questions.count()` 不变。
这验证了“一个业务不变量 = 一个事务”：不允许留下“版本建了但题目残缺”的半成品。

---

### Q3. 画事务代理顺序。
```
调用方
  │ 调用 service.createDraft(...)
  ▼
Spring 事务代理（TransactionInterceptor）      ← self-invocation 会绕过它
  │ 1. getTransaction：从连接池取连接、setAutoCommit(false)、绑定到当前线程
  ▼
目标方法 BankService.createDraft（业务逻辑 + 多次 Repository 写）
  │ 正常返回
  ▼
代理：commit（flush SQL → connection.commit() → 归还连接）
  │ 若抛出匹配回滚规则的异常
  ▼
代理：rollback（connection.rollback() → 归还连接），异常继续向上抛
```
事务由 AOP 代理在方法外包裹，业务代码本身不写 begin/commit。

---

### Q4. 写出运行时异常、checked exception、传播级别、隔离级别的配置示例。

```java
// 回滚规则：默认 RuntimeException/Error 回滚；checked 异常默认提交，需要 rollbackFor 才回滚
@Transactional(rollbackFor = Exception.class)
public void importAll() throws IOException { }   // 连 IOException(checked) 也回滚

// 传播级别
@Transactional(propagation = Propagation.REQUIRED)      // 默认：有事务就加入，没有就新建
@Transactional(propagation = Propagation.REQUIRES_NEW)  // 挂起当前事务，另开一个独立事务
@Transactional(propagation = Propagation.SUPPORTS)      // 有则加入，没有就非事务执行
@Transactional(propagation = Propagation.NESTED)        // 嵌套保存点，外层回滚连带内层

// 隔离级别
@Transactional(isolation = Isolation.READ_COMMITTED)    // 读已提交（MySQL InnoDB 默认可重复读）
@Transactional(isolation = Isolation.REPEATABLE_READ)
@Transactional(isolation = Isolation.SERIALIZABLE)
@Transactional(readOnly = true)                         // 只读提示：不设为可写、可做优化
```
| 隔离级别 | 脏读 | 不可重复读 | 幻读 |
|---|---|---|---|
| READ_UNCOMMITTED | 可能 | 可能 | 可能 |
| READ_COMMITTED | 防止 | 可能 | 可能 |
| REPEATABLE_READ | 防止 | 防止 | InnoDB 下基本防止（MVCC+间隙锁） |
| SERIALIZABLE | 防止 | 防止 | 防止 |
`readOnly=true` 只是语义/优化提示（Hibernate 可跳过脏检查、数据库可路由只读副本），**不是安全限制**，不能用来阻止写操作。

---

### Q5. 写出代理调用、事务边界和 self-invocation 失效的最小示例。

```java
@Service
public class DemoService {
    @Transactional
    public void outer() {
        this.inner();           // ❌ self-invocation：this 是目标对象不是代理，inner 的 @Transactional 失效
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { }
}

@Service
public class Caller {
    private final DemoService demo;   // 注入的是代理
    public void ok() { demo.inner(); } // ✅ 经过代理，REQUIRES_NEW 生效
}
```
失效原因与排查：Spring 事务靠代理实现，内部自调用不经过代理；其他失效场景：方法非 public、类不是 Spring Bean（自己 new）、异常被 catch 吞掉、数据库引擎不支持事务。修复：拆 Bean、自注入代理、或用 `TransactionTemplate` 编程式事务。

---

## External

### E1. 用 REQUIRES_NEW 设计审计日志。
需求：业务主事务即使回滚，审计/操作日志也要保留。
```java
@Transactional
public void business() {
    try { ... } catch (RuntimeException e) {
        auditLog.recordFail(...);   // 该方法 REQUIRES_NEW，独立事务提交，不受外层回滚影响
        throw e;
    }
}
// AuditLogService
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordFail(...) { auditRepository.save(...); }
```
注意 REQUIRES_NEW 会**挂起外层事务并占用第二个数据库连接**，高并发下可能耗尽连接池；审计也可用 MQ/异步落库替代。

### E2. 观察锁等待。
本项目提交用 `SELECT ... FOR UPDATE`（PESSIMISTIC_WRITE）。开两个事务：A 先 `findByIdForUpdate(1)` 不提交，B 再锁同一行时会阻塞，直到 A commit/rollback；超过 `innodb_lock_wait_timeout`（默认 50s）抛锁等待超时。可用 `SHOW ENGINE INNODB STATUS`、`information_schema.innodb_trx` 观察等待关系。结论：事务要尽量短，避免在事务内做网络/文件等慢操作以减少锁持有时间。
