# Day57 System Design Interview · 题目与标准解答（Solutions）

> 系统设计面试框架，结合题库项目场景落地。

## Current

### Q1. 任选场景写完整系统设计（以“练习提交”为例，十要素齐全）。
1. **需求与边界**：学生对已发布试卷提交答案，要求判分确定、重复提交结果一致、错题归集；非目标：主观题、实时协同。
2. **规模假设**：10 万学生、2 小时窗口、峰值 3 倍均值（显式写出，见 Day44 计算）。
3. **API**：`POST /api/practices/{id}/submit`，头 `Idempotency-Key`，返回 SubmitResult(total/max/answers)。
4. **数据模型**：practice_session、submission_item、wrong_question、question_version（联合唯一键、@Version）。
5. **核心数据流**：过滤器认证→Service 行锁→判分→写 Item/Wrong/Session→提交。
6. **一致性与并发**：`SELECT…FOR UPDATE` 串行同会话；幂等键同 key 重放、异 key 409；唯一键兜底。
7. **缓存/队列**：已发布列表 cache-aside（可重建）；异步导入用持久任务表，不用长事务。
8. **失败恢复**：事务回滚无半提交；任务崩溃靠租约接管；DB 故障 500 + 客户端幂等重试。
9. **观测**：requestId、Counter/Timer（P95）、health、锁等待、错误率。
10. **容量与替代**：Little 定律估并发与连接；替代方案（乐观锁、通用幂等表、Redis）及取舍。

---

### Q2. 设计 offset 与 keyset 分页，写稳定排序、游标编码与增删边界。
**offset 分页**（适合小页数、管理后台）：
```
GET /api/papers/published?page=0&size=20&sort=publishedAt,desc
```
必须：排序加唯一 tiebreaker（`ORDER BY published_at DESC, id DESC`）保证翻页稳定；限制最大 size（如 100）；`LIMIT 20 OFFSET n`，深翻页时 offset 越大扫描越多。

**keyset/游标分页**（适合无限下拉、深分页）：
```
GET ...?cursor=<base64(lastPublishedAt,lastId)>&size=20
WHERE (published_at, id) < (:lastPublishedAt, :lastId)
ORDER BY published_at DESC, id DESC LIMIT 21   -- 多取一条判断是否有下一页
```
游标用不透明 base64（服务端解码，防篡改可签名），记录上一页最后一条的排序键。
**增删边界**：keyset 不会像 offset 那样因插入/删除导致跳条/重复（它基于值而非位置）；仍需 tiebreaker 处理排序键相同；删除当前游标指向的行时直接用其排序键继续即可。

---

### Q3. 用 STAR 讲一个已验证的 M0 难点，区分实现/测试/设计/未掌握。
以提交幂等为例：
- **S/T 背景目标**：网络重试可能让同一提交执行多次，导致重复判分、错题计数翻倍；
- **A 个人动作**：设计“幂等键 + `SELECT…FOR UPDATE` 行锁 + 结果 JSON 快照”，同 key 重放、异 key 409，并在提交事务内同写 Item/Wrong/Session；
- **R 可验证结果**：并发同 key 集成测试两次响应一致、wrongCount 不增长（指向测试方法与行号）；
- **边界**：已实现并测试的是单库行锁幂等；通用幂等表、TTL、PROCESSING 接管只是设计；Redis 分布式锁未掌握到实战，不写成经历。

---

## External

### E1. QPS×10、DB 故障、消息重复、缓存雪崩的演进顺序。
- **QPS×10**：先 profiling/压测定位瓶颈（SQL/锁/连接池）→ 加索引/优化事务、加缓存挡读 → 水平扩应用实例（无状态化、token 共享化）→ 读写分离/分库分表（最后手段）；
- **DB 故障**：健康探针摘流量 → 熔断/降级（读缓存兜底）→ 主从切换/重连 → 数据一致性校验与幂等补偿；
- **消息重复**：消费端业务唯一键去重（本就 at-least-once），死信兜底毒消息；
- **缓存雪崩**：TTL 随机抖动、分批预热、多级缓存、single-flight 防击穿、限流保护 DB。
顺序原则：先用最便宜、最不改变架构的手段，再考虑加组件。

### E2. API 版本兼容、错误响应、幂等键、安全审计字段。
- 版本：URI `/api/v1` 或 Header；新增可空字段向后兼容，破坏性变更升版本并并行过渡期；
- 错误：统一 ErrorResponse(code,message,requestId,timestamp)，HTTP 状态与业务码正交（参考 RFC 9457 Problem Details）；
- 幂等：写接口支持 Idempotency-Key，服务端绑定用户+资源+请求 hash；
- 审计：who(userId)、what(resource+action)、from→to、requestId、时间、结果，只追加不可改，敏感信息脱敏。

### E3. 30 分钟模拟面试并回填缺口。
按固定结构计时：3 分钟项目、10 分钟八股、12 分钟设计/手写、5 分钟反问；把卡壳点按八类（Java/Spring/MySQL/Redis/MQ/网络/算法/项目）记录，回填到对应 Day，排进 1/3/7/14 天复习。
