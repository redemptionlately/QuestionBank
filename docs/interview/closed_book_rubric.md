# 闭卷卷判卷要点

> 每题按三层给分：**是什么（1 分）→ 为什么（1 分）→ 项目里怎样 / 反例（1 分）**。
> 只答出第一层不算掌握，能举出反例或落到本项目代码才算满分 3 分。

## Q1 · PECS（Day50）
- `List<? extends Number>` 只能安全**读**为 Number，不能 `add`（除 `null`）：编译器不知道实际元素是 Integer 还是 Double。
- `List<? super Integer>` 只能安全**写** Integer，读出来只能当 Object。
- PECS：Producer extends（你从它取值），Consumer super（你往它放值）。
- 追问：为什么 `Collections.copy(dest, src)` 的签名是 `copy(List<? super T>, List<? extends T>)`？

## Q2 · self-invocation（Day51）
- `@Transactional` 靠 AOP 代理织入；`this.inner()` 是对象内部调用，不经过代理 → 事务/缓存/异步注解全部失效。
- 修复：① 拆到另一个 bean；② 注入自身代理（`@Lazy` 自注入）；③ `AopContext.currentProxy()`（需 `exposeProxy=true`）。
- 代价：拆分类变多；自注入有循环依赖心智负担；`AopContext` 侵入业务代码且耦合 Spring。
- 项目位置：`BankService` 的 `publish()` 若被同类方法直调就会丢事务；缓存淘汰必须放在 `afterCommit`，已在 `evictPublishedAfterCommit()` 实现。

## Q3 · RC / RR 与行锁（Day52）
- RC：每条语句生成新快照，同一事务两次读可能不同（不可重复读）。
- RR：事务首次读生成快照，两次读一致。
- `SELECT ... FOR UPDATE` 加排他行锁；RR 下还加 gap / next-key 锁防幻读；**事务提交或回滚时释放**，不是语句结束。
- 追问：为什么本项目提交幂等用行锁 + 唯一键双保险？（见 Q10）

## Q4 · cache-aside 写顺序与 Outbox（Day53）
- 推荐：**先更新数据库，再删缓存**。
- 反例（先删缓存再更库）：删完缓存后、更新库前有并发读 → 读到旧值并回写缓存 → 缓存长期脏。
- 先更库再删缓存的残留风险：缓存恰好过期 + 并发读旧值回写，概率低，可用延迟双删 / 订阅 binlog 兜底。
- Outbox：业务数据与待发消息在**同一个数据库事务**里落库，再由投递器异步发送，解决「更新 DB 与发消息」的原子性，避免丢消息或重复消费。

## Q5 · JWT 校验顺序与 IDOR（Day54）
- 顺序：取 header 校验 Bearer → 解析三段结构 → **校验签名（算法白名单，禁 `none` 与 alg 混淆）** → 校验 `exp/nbf/iat` → 校验 `iss/aud` → 查 `jti` 撤销名单 → 加载主体与权限。
- 签名必须在过期校验之前；算法必须由服务端固定，不能信 JWT 头里的 `alg`。
- IDOR 修在**资源归属校验层**（service 内），不是网关。
- 项目位置：`BankService.createDraft/publish` 的 `bank.getOwnerId().equals(user.userId())`；`PracticeService` 的 session 归属校验。

## Q6 · 超时分层排查（Day55）
- DNS：`dig +short host` / `nslookup host`
- TCP：`curl -w '%{time_connect} %{time_starttransfer} %{time_total}\n' -o /dev/null -s URL`；`ss -antp | grep :443`
- TLS：`openssl s_client -connect host:443`；`curl -v` 看握手
- 服务端：`top -H -p <pid>`；`jstack <pid>`；`jstat -gcutil <pid> 1000`
- 数据库：`SHOW PROCESSLIST`；`EXPLAIN`；`SHOW ENGINE INNODB STATUS`；`information_schema.innodb_trx`
- 关键：不要把这五段延迟全算到应用头上。

## Q7 · CPU 飙高证据链（Day56）
1. `top` 定位进程 → `top -H -p <pid>` 找高 CPU 线程
2. 线程 nid 转十六进制 → `jstack <pid>` 搜 nid → 拿到业务栈
3. 或 `jcmd <pid> JFR.start duration=60s settings=profile` → JMC / 火焰图看热点方法
4. `jstat -gcutil` 先排除是不是 GC 线程吃 CPU
5. 项目证据：`output/load_*.jfr` 就是这条链的产物（本机 `jcmd` attach 被拒，改用 `-XX:StartFlightRecording`）。

## Q8 · keyset 分页（Day57）
- `LIMIT 100000, 20` 需要扫描并丢弃前 100000 行，代价随 offset 线性增长。
- keyset：`WHERE (published_at, id) < (?, ?) ORDER BY published_at DESC, id DESC LIMIT 20`。
- 必须加唯一 tiebreaker（id），否则排序不唯一会**漏行或重复**。
- 代价：不能跳页，只能上一页/下一页。

## Q9 · 重试放大（Day58）
- 两层重试是**乘法**：网关 3 次 × 服务间 3 次 = 下游 9 倍压力，故障时会把下游打穿。
- 控制：只在最外层重试；重试预算（retry budget，按成功率限制重试占比）；指数退避 + jitter；幂等键；熔断/隔离。
- 追问：重试的前提是什么？（接口幂等，否则会重复下单）

## Q10 · Redis 锁为何还要唯一键（Day59）
- 失效时序：A 获锁 → A 发生 GC 停顿 / 主从切换丢锁 → 锁过期 → B 获锁 → A 恢复后继续写 → A、B 同时写入。
- 唯一键是**数据库层的最后一道防线**：即使锁失效，也不会产生重复数据。
- fencing token：锁服务返回单调递增 token，存储层拒绝旧 token 的写入。
- 项目位置：`uk_submission_question (session_id, question_version_id)` + `findByIdForUpdate` 行锁就是这套双保险。

## Q11 · text / keyword 与 search_after（Day60）
- `text`：分词，用于全文检索；聚合排序需要 fielddata，代价高。
- `keyword`：不分词，整体作为一个 term，用于精确匹配、聚合、排序。
- `from/size` 深分页：每个分片都要算 `from+size` 条，协调节点汇总 `(from+size) × 分片数`，默认上限 10000。
- `search_after`：用上一页的排序值做游标，无 offset 开销；要求排序含唯一字段，且不支持跳页。
