# Day35 M2 Failure Injection · 题目与标准解答（Solutions）

> 依据：`study.md`（Resilience4j：timeout/retry/circuit breaker/bulkhead/fallback）。

## Current

### Q1. 按源码索引定位并默写、口述 I/O 边界。
对照本项目真实组件：`docker compose stop mysql` 制造依赖故障；`/actuator/health` 观测依赖探针；业务接口经 `GlobalExceptionHandler` 兜底为 500；恢复后重新观察。

---

### Q2. 停止 MySQL 验证 health/API/恢复并保存证据。
实验步骤（有明确停止条件与回滚）：
1. 基线：health UP、接口 200，截图/日志留存；
2. 注入：`docker compose stop mysql`；
3. 观测：health 中 db 指示器 DOWN；业务接口查询时连接失败 → 500 `INTERNAL_ERROR`（不泄露连接串/密码/SQL）；
4. 恢复：`docker compose start mysql`，等待 healthy，接口恢复 200；
5. 全程 `tee` 保存时间线日志作为证据。
停止条件：出现非预期错误格式或进程崩溃即停止；回滚方式：重新启动数据库。

---

### Q3. 写出超时、重试、熔断、降级、隔离舱触发条件与不可重试错误。
| 模式 | 触发条件 | 作用 |
|---|---|---|
| Timeout 超时 | 等待超过预算（且 < 上游 SLA） | 快速失败，释放线程，避免无限挂起 |
| Retry 重试 | 瞬态错误（连接瞬断、限时不可用、5xx 可恢复） | 掩盖偶发抖动 |
| Circuit Breaker 熔断 | 滑动窗口失败率/慢调用率超阈值 | 短路保护下游，给它恢复时间 |
| Fallback 降级 | 被熔断/超时/失败后 | 返回有限能力（缓存值、默认值、友好提示） |
| Bulkhead 隔离舱 | 线程/信号量占满 | 把故障限制在某一依赖，不拖垮全局线程池 |

**不可重试错误**：权限不足(403)、参数错误(400)、唯一键冲突(409)、数据损坏/格式非法——重试结果不会改变，只会放大流量。指数退避 `delay=min(max,base*2^n)+jitter`，限制最大次数，避免多层重试相乘形成重试风暴。

---

### Q4. 画出 CLOSED→OPEN→HALF_OPEN 熔断状态机。
```
            失败率/慢调用率 ≥ 阈值
 CLOSED ───────────────────────────▶ OPEN（直接拒绝调用，走 fallback，启动冷却计时）
   ▲                                   │ 冷却时间结束，放少量探测请求
   │ 探测成功（达到成功阈值）            ▼
   └────────────────────────────── HALF_OPEN（试探下游是否恢复）
   ▲                                   │ 探测再次失败
   └───────────────────────────────────┘ 回到 OPEN（重新冷却）
```
- CLOSED：正常放行并统计；OPEN：快速失败不调用下游；HALF_OPEN：有限探测，成功则关闭熔断、失败则重新打开。Resilience4j 用滑动窗口统计 failure rate / slow call rate，配置 minimum-number-of-calls、wait-duration、permitted-calls-in-half-open。

---

### Q5. 为数据库不可用写出健康/API/恢复/数据一致性四层断言。
1. **进程存活层**：JVM 进程仍在、端口仍监听（进程没死）；
2. **健康探针层**：`/actuator/health` 为 DOWN，且 db 组件标记 DOWN（探针能感知依赖）；
3. **业务 API 层**：依赖 DB 的接口返回 500 `INTERNAL_ERROR`，错误体含 requestId/timestamp、无敏感信息；不依赖 DB 的路径（若有缓存）可降级；
4. **数据一致性层**：故障期间未提交的事务全部回滚、无半写入；恢复后已提交数据完好，幂等重放结果与故障前一致（数据库恢复 ≠ 故障期间请求成功，失败请求需由客户端按幂等键重试）。

---

## External

### E1. 模拟连接池耗尽。
把 HikariCP `maximumPoolSize` 调小并让查询慢/持锁，制造连接被占满：后续请求在 `connectionTimeout` 后快速失败而非无限等待，观察线程是否被级联拖垮、隔离舱是否把影响限制在该依赖。验证 bulkhead/超时的价值，并确认错误仍是统一 500 契约。

### E2. 写故障复盘（最小闭环）。
按六段式：**现象**（什么时间、哪些接口、什么表现）→ **复现条件**（如何稳定复现）→ **根因**（追到代码/配置/资源层）→ **修复**（具体改动）→ **回归证据**（测试/压测/注入验证结果）→ **残余限制**（还有什么没覆盖、监控如何补）。故障注入是验证“已设计的状态转换与恢复路径”，不是随机制造事故，每个实验都要有停止条件与回滚。
