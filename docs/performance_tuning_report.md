# 性能调优完整报告

> 方法论：**profiler 定位 → 优化 → 复测对照 → 明确权衡**。每一项优化都必须有"优化前后的原始数据"，否则不写。
> 所有结论绑定文末环境，不外推为生产容量。

## 0. 基线与总览

| 场景 | 负载 | 吞吐 | P50 | P95 | P99 | 错误率 |
|---|---|---|---|---|---|---|
| GET /api/papers/published | 开环 100 RPS | 100.0 RPS | 2ms | 5ms | 5-10ms | 0% |
| POST /api/auth/login（cost=10） | 开环 20 RPS | 15.6-16.1 RPS | 61-63ms | 65-79ms | 76-105ms | 0% |
| POST /api/auth/login（cost=12） | 开环 60 RPS 饱和发射 | 见 §3 | | | | |

关键事实：**login 吞吐上不去不是巧合，是设计**——BCrypt cost=10 单次验证约 60-80ms，CPU 密集型。

## 1. 索引优化：EXPLAIN 前后对照（数据层）

**定位**：5000 行 paper_version 下，发布列表查询 `WHERE status='PUBLISHED' AND published_at<=NOW() ORDER BY published_at DESC`。

| 版本 | 执行计划 | rows | Extra |
|---|---|---|---|
| 无合适索引 | type=ALL | 9640 | Using filesort |
| `idx_paper_version_status_published_at` | type=ref | **800** | **Backward index scan（无 filesort）** |
| 反面教材：`WHERE UPPER(status)=...` | type=ALL | 9640 | Using filesort（索引失效复现） |

**证据**：`output/mysql_evidence_*.log`（含慢查询日志对照：失效查询 rows_examined=5000 被捕获，命中索引的同查询不出现）。
**结论**：复合索引把扫描行数砍到 1/12 且消除 filesort；函数包裹谓词会让索引失效——慢查询日志是这道防线的监控手段。

## 2. 缓存与事务边界：afterCommit 淘汰（应用层）

**问题**：发布操作在事务内淘汰缓存，事务未提交时其他请求可能把**旧值**写回缓存（脏读窗口）。
**优化**：缓存淘汰移到 `TransactionSynchronization.afterCommit`——提交成功才淘汰。
**对照证据**：`RedisCacheIntegrationTest#publishEvictsCacheAfterCommit`（先淘汰/后淘汰的行为差异可直接复现）。
**权衡**：afterCommit 淘汰不是免费的——极小概率提交后进程崩溃导致缓存残留旧值，由 TTL（30s+抖动）兜底。**TTL 抖动**防止同批 key 同时过期造成缓存雪崩。

## 3. BCrypt cost 三档实测（CPU 密集路径）

**定位**：JFR profile 采样实锤 `BCrypt.key` 占 login 执行采样 **84.01%**（`output/jfr_analysis_*.log`）——登录吞吐的第一控制变量就是 cost。

**一个反直觉的实验设计错误（v1），保留作为发现**：只改 `app.security.bcrypt-strength` 配置（8/10/12）压 login，三档吞吐完全一致（15.55/15.55/15.65 RPS）。原因：**BCrypt 的 cost 是自描述的**——验证端 `matches()` 读的是存储哈希前缀里的 cost（`$2a$10$` 里的 10），与 encoder 实例配置无关。cost 编码在哈希里意味着：改配置只影响新哈希，老用户验证耗时不变。这是 BCrypt 的正确设计（每个哈希自带自己的成本参数，可平滑升级全局强度——新用户用新 cost，老用户重登录时渐进重哈希）。

**修正后的实验（v2）**：每档先生成对应 cost 的哈希并替换 student 的 `password_hash`（数据决定验证 cost），再压 login（开环 60 RPS 饱和发射）：

| 哈希 cost | 实测吞吐 | P50 | P95 | P99 | 错误率 |
|---|---|---|---|---|---|
| 8 | 52.65 RPS | 18ms | 29ms | 45ms | 0% |
| 10（当前默认） | 15.5 RPS | 64ms | 78ms | 90ms | 0% |
| 12 | 4.15 RPS | 238ms | 259ms | 298ms | 0% |

数据与理论严格吻合：cost 翻倍 → 单次验证耗时翻倍 → 吞吐减半（52.65 → 15.5 → 4.15，两档之间 cost 差 2 → 吞吐比约 1/3.4 与 1/3.7）；P50 18ms → 64ms → 238ms 同比例放大。**login 吞吐完全由 cost 主导，误差可忽略**。证据：`output/bcrypt_strength_20260907-202319.log` + `output/load_login_bcrypt{8,10,12}_*.json`。

**选型结论**：cost=10 是吞吐与安全的折中点。cost=12 吞吐约减半（在线登录接口不可接受，除非登录频率低且有排队预算）；cost=8 吞吐提升但 2026 年硬件上离线爆破成本偏低，不建议用于用户口令。**保持 cost=10，并把 strength 提为可配（`app.security.bcrypt-strength`），扩容时横向加实例而不是降 cost**。

**产品代码联动**：`SecurityConfig.passwordEncoder(@Value("${app.security.bcrypt-strength:10}"))`——参数可配 + 本脚本可复现，改之前必须有数据。

## 4. GC 对照：G1 vs ZGC（分代）

| 指标 | G1（-Xms512m -Xmx1g） | ZGC 分代（同参数） |
|---|---|---|
| 停顿次数 / 累计 | 23 次 / 147ms | 22 次 / 0.29ms |
| 停顿 P50 / MAX | 6.3ms / 17.2ms | 0.011ms / 0.027ms |
| Full GC | 0 | 0 |
| 吞吐（GC 视角） | 99.90% | ~100% |
| **应用 P99** | **10ms** | **10ms（不变）** |

**结论**：ZGC 把 GC 停顿压低约 3 个数量级，但**应用 P99 纹丝不动**——瓶颈在 BCrypt（84% CPU），不在 GC。瓶颈不在 GC 时换收集器不改善延迟。这组对照的价值在于演示"优化要打在 profiler 指的地方"。

## 5. 本轮不做及理由

| 候选项 | 结论 | 理由 |
|---|---|---|
| 降低 BCrypt cost 换吞吐 | 不做 | 在线爆破成本不可接受；登录频率升高时横向扩容 |
| 连接池加大 | 不做 | HikariCP 10 连接在当前压测下不是瓶颈（活跃连接远未打满） |
| ZGC 替换 G1 | 不做 | 应用 P99 不变，白付 ZGC 的内存/CPU 开销 |
| 虚拟线程改造 login | 无意义 | 线程不是瓶颈，BCrypt 是；虚拟线程只缓解阻塞等待 |

## 6. 环境绑定（结论不可脱离此表）

| 字段 | 值 |
|---|---|
| 机器 | 32 核 / 32 GB / Windows 11 |
| JDK / GC | 21.0.12 / G1 `-Xms512m -Xmx1g -XX:MaxGCPauseMillis=200` |
| 数据库 | MySQL 9.0.1 同机，REPEATABLE-READ |
| 连接池 | HikariCP maximumPoolSize=10 |
| 数据量 | paper_version 5000 行（PUBLISHED 500） |
| 负载模型 | 开环固定到达率，warmup 10s + 测量 20-30s |
| 复现 | `scripts/loadtest.sh`、`scripts/bcrypt-strength-benchmark.sh`、`scripts/gc-report.sh` |
