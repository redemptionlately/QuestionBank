# Java 后端主线 / C++ 系统备选统一学习上下文

> 状态更新时间：2026-08-19（Asia/Shanghai）
>
> 用途：这是三仓库与算法学习的**唯一动态进度和 AI 交接入口**。新会话先读本文件，
> 再按“当前停点”读取必要源码、测试和日志。稳定协作规则见 `AGENTS.md`。

## 1. 当前方向与协作方式

- 当前主方向：Java 后端；先完成题库平台 M0 的最小业务闭环。
- 保留方向：TinyWebServer + TinyInfer 作为 C++ CPU 推理服务原型的学习线；只认领通过所有权 Gate 的模块。
- 暂停方向：AI_Infra 后续 Day 不再作为求职主资产或每日主块；已完成实验保留为学习证据，不删除、不外推。
- 长期求职方向、项目收敛、投递与转向规则统一见
  `/home/allen/AI_Infra/Phases_book/  AI 推理系统求职总路线.md`，不在本文件重复维护。
- 学习资产：`/home/allen/TinyWebServer`、`/home/allen/TinyInfer`、
  `/home/allen/AI_Infra` 和 `Phases_book` 算法题。
- 当前阶段：共写期。AI 负责计划、最小理论、骨架、Review 和调试；用户先写核心索引、
  状态转换或循环，亲自运行关键命令并解释输出。
- 当前日程：Java M0 是深度主块；C++ 线与算法保留小步学习。AI_Infra 暂停，不要求继续 Day 16。
- Java M0 工程已完成并通过 H2、真实 MySQL、HTTP E2E 和并发/回滚验收；用户学习所有权仍未验收。
  M1 PDF、M2 可靠性扩展和 Agent 接入仍暂停。

## 2. 状态判定规则

后续 AI 必须区分：

```text
source_exists      源码或文档存在
wired              已列入构建并接到入口
verified           本次或有路径可审计的真实测试/日志通过
learned            用户能复述、亲手修改、测试并回答追问
```

前三级都不能自动推出 `learned`。TinyInfer 和 TinyWebServer 主要由 AI 生成；当前仍是学习
代码库，不得直接写成用户从零设计和实现的完整项目。

证据优先级：用户当前终端输出 > 仓库原始日志 > 当前源码/测试/CMake > 本文件 > README、
路线图和旧对话。性能结果必须绑定环境、输入和命令，不能从一次测量推出普遍最优。

## 3. 当前环境快照

本次现场读取（2026-08-12）：

| 项目 | 当前值 |
|---|---|
| GPU | NVIDIA GeForce RTX 4060 Laptop GPU，8188 MiB VRAM/GDDR6 |
| Driver | 610.88 |
| CUDA Toolkit | 13.3，`nvcc V13.3.73` |
| CMake | 3.22.1 |
| G++ | 11.4.0 |
| AI_Infra branch/HEAD | `main` / `4b165f5` |

环境会变化。`nvidia-smi` 的 CUDA Version 是驱动兼容上限，不等于 Toolkit；旧 sandbox 的
socket/GPU/Git 失败不能推翻当前 WSL 的成功，反之亦然。

本次 TinyInfer/TinyWebServer 验证使用各自已有 `build/`，运行了 CTest 和 TinyInfer 示例，
没有重新 configure/build；因此证明“现有构建当前可运行”，不等于干净环境从源码重建通过。

AI_Infra 工作树当前不是干净状态，而且审计期间仍可能有外部编辑器或其他任务写入。后续 AI
必须重新运行 `git status --short` 获取实时清单，不得依赖本文枚举文件，也不得自动恢复、
覆盖或混入无关提交。

## 4. 四块进度总览

| 学习块 | 当前工程状态 | 当前学习状态 | 当前停点/下一步 |
|---|---|---|---|
| Java 题库平台 M0 | `/home/allen/projects/java-question-bank-m0` 已有 Java 21/Spring Boot 3.4.5、Maven Wrapper、Flyway V1/V2、登录/角色、版本发布、练习保存/判分、数据库行锁幂等、错题聚合、统一错误响应和 Compose；H2 7/7、MySQL 8.4、真实 HTTP E2E 和 EXPLAIN 通过 | 用户尚未完成源码复述、亲手修改和追问验收，不能记为 learned | 工程冻结；按 `/home/allen/study/java/m0/` 的 49 天路线完成所有权验收；Git 历史需用户执行 |
| TinyWebServer | `/infer`、有界队列、超时、429、Mock runner、可选 TinyInfer 集成已接线；当前 CTest 155/155 通过 | 用户尚未按模块复述、修改、测试，不能认领完整实现 | C++ 备选线：从 HTTP、Queue 或 EventLoop 选择一个模块完成所有权 Gate；不与 Java M0 同时做大改动 |
| TinyInfer | `InferenceSession + GraphBuilder` 已进 CMake 和 `main`，手工图 `Compile -> Run` 可执行；当前 CTest 2/2 通过 | 用户尚未学习 Session/Tensor/Graph 数据流，不能认领 Runtime | C++ 备选线：从 Tensor、Graph、MemoryPlanner 或 Engine 选择一个模块完成所有权 Gate |
| AI_Infra | Day 11--Day 15 有各自工程证据；Day 16 只有计划和局部理论学习，没有实现/测试 | CUDA 实验不等于可用于求职的独立能力 | 暂停后续 Day；仅在未来明确需要时复习既有实验，不继续扩展课程 |
| 算法题 | 题面、答案册及 33 个独立 C++ 文件存在 | 每日一题尚未正式开始 | 保留为 Java/C++ 共用面试基础；第 01 题从题面开始 |

## 5. 当前首要任务：Java 题库平台 M0 学习所有权 Gate

### 研究问题

在不引入 PDF、Redis、Agent 或微服务的前提下，先定义并实现一个可验证的 Java 业务闭环：管理员
创建/发布版本，学生创建练习、保存答案、幂等提交，服务端确定性判分并更新错题记录。

### 当前事实

- 用户已明确切换为 Java 主线，启动条件来自 `题库平台项目蓝图.md` 的“用户主动改变主方向”。
- M0 工程已存在并有可审计构建、迁移、测试、MySQL 和 HTTP 日志；工程验证不等于用户掌握。
- 当前先完成 M0 源码/数据流复述、亲手小改动、测试解释和变式；不进入 PDF、对象存储、Redis、异步导入、模型调用、Harness 或 Agent。

### 下一步（按顺序）

1. 用户按 day01-day14 复述 Java/Spring/MySQL 基础和 M0 数据流。
2. 用户亲手修改一个状态/校验/查询并补测试，运行 `./mvnw -B test`。
3. 用户解释回滚、数据库行锁、索引和真实 E2E 日志，再做一个变式。
4. 通过学习 Gate 后再依据真实 JD 决定是否进入 M1/M2；Agent 仍后置。

### 完成门槛

- M0 的范围、状态、不变量和失败边界可由用户复述；不把蓝图当成已实现功能。
- 工程证据已满足：项目初始化、数据库迁移、发布/提交事务边界、回滚测试、索引/EXPLAIN、并发幂等测试和真实 MySQL E2E 均有路径。
- 用户学习证据仍待完成：源码复述、亲手修改、测试解释、变式和追问通过后，才可记为 `learned`。
- 任何 Agent、模型或 PDF 功能均在 M0 后重新评估，不能借“AI”名称替代后端基本功。

## 6. AI_Infra 已有证据与边界

### Day 11 融合实验

- 报告：`day11_fusion_phase_a/docs/Phase_A_report.md`。
- 正确性：`day11_fusion_phase_a/output/correctness.log`，3 个 shape 均 PASS。
- Benchmark：`day11_fusion_phase_a/output/benchmark_5runs_20260812.log`，每个 variant 25 个
  CUDA Event 样本。
- 当前报告中位数：unfused `0.277873 ms`，fused `0.053861 ms`，当前固定条件比值
  `5.1591x`。
- NCU：`day11_fusion_phase_a/output/ncu/fused_basic.txt` 和 `unfused_basic.txt`。

这些数据只证明当前 GPU、FP32、`4096x1024`、当前实现和 kernel-only 测量范围内的结果。
不能拆分 launch 减少与中间 Global Memory 减少各自贡献，也不能外推真实 LLM、其他 shape、
dtype 或端到端延迟。NCU Duration 不能替代 CUDA Event sequence 中位数。

### Day 12 与 Day 13 已有证据

- Day 12：用户能写出并修正 1D、2D row-major 和 NCHW 索引；9 个 correctness case PASS。
- Day 13：三种 copy 地址模式正确性均通过；在固定配置下，5 次均值为 coalesced
  `0.025962 ms`、offset `0.026151 ms`、stride=8 `0.179884 ms`。两份 NCU basic 报告支持
  stride=8 更接近 DRAM 吞吐上限，但不提供精确 transaction 数。

### 仍未证实

- Day 15 及之后的课程内容不能按目录当成完成。
- Tensor Core、Triton、FlashAttention、PagedAttention、Continuous Batching、真实模型推理、
  CUDA backend 和分布式均没有当前完成证据。
- Day 1～10 的工程文件存在不等于用户当前都能独立复述；错题补强在
  `day12_thread_mapping/docs/Tests.md` 中继续进行。

完整 87 天课程目录见 `PROGRESS.md` 或 `docs/project_structure_manifest.csv`，不复制到本文件。

## 7. TinyInfer 当前真实状态

仓库：`/home/allen/TinyInfer`，branch `main`，现场 HEAD `827ed7a`。

### 已接线并现场验证

- `src/session/session.cpp` 已列入顶层 `CMakeLists.txt` 的 `tinyinfer_core`。
- `src/main.cpp` 使用 `GraphBuilder` 手工构建 Conv -> ReLU -> Pooling 图，调用
  `InferenceSession::Compile()` 和 `Run()`。
- 2026-08-12 现场运行 `ctest --test-dir build --output-on-failure`：2/2 CTest 通过，
  总耗时 28.74s。
- 现场运行 `./build/tiny_infer`：GraphRewriter 融合 Conv+ReLU，Engine 执行 2 个节点，
  输出 1 个 `4x4` Tensor。

### 能声称的工程范围

- 有 Tensor、Graph、GraphRewriter、MemoryPlanner、Engine、CPU/AVX2 算子、ThreadPool、
  Bundle Loader，以及手工 Graph 的最小 Session `Compile -> Run`。
- 当前 Session 证明的是手工图闭环，不是完整模型导入或完整 LLM Runtime。

### 不能声称/待核验

- 没有完整 ONNX importer；Bundle 中 ONNX payload 不等于已转成 TinyInfer Graph。
- 没有已接通 CUDA backend、动态/Continuous Batching、NCCL、真实 LLM Runtime。
- v2/INT8/crypto/Winograd/NEON 等目录存在不等于进核心 CMake、功能完整或有端到端测试。
- 用户尚未学习该仓库。下一学习顺序：`Tensor -> Graph -> GraphRewriter -> MemoryPlanner -> Engine`。

## 8. TinyWebServer 当前真实状态

仓库：`/home/allen/TinyWebServer`，branch `main`，现场 HEAD `2dbd4f9`。

### 已接线并现场验证

- `src/CMakeLists.txt` 构建 `infer_server`；`HttpInferServer` 提供 `/health`、`/infer`、404，
  队列满返回 429。
- `InferenceQueue` 有有界队列、worker、超时路径；`main_infer.cpp` 接收 worker 参数。
- `TINY_INFER_INTEGRATION=OFF` 时走 Mock；ON 时通过 `TINY_INFER_PATH` 链接 TinyInfer 静态库。
- 测试包含 `QueueFullReturns429` 和 `NotFoundForUnknownPath`。
- 2026-08-12 现场运行 `ctest --test-dir build --output-on-failure`：155/155 通过，
  总耗时 5.57s。
- CTest 通过 `ASAN_OPTIONS=detect_leaks=0` 运行，因此不是启用 LeakSanitizer 后的无泄漏证明。

### 已保存 benchmark 与限制

- 原始路径：`/home/allen/TinyWebServer/bench_logs/`。
- `results.csv` 同时混有旧 8 列格式和新并发 8 列格式，表头不一致；使用前必须先按原始
  日志和 schema 分开整理，不能直接整表统计。
- 旧 50 请求单连接记录：Mock workers 1/2/4 约 80 QPS；另有 TinyInfer workers=2 的
  96.3 QPS 行。它只代表当时条件，不说明 TinyInfer 普遍快 20%。
- 200 请求、并发 16 的 Mock 行是 1 成功/199 失败、约 2.1 QPS；它暴露当前 HTTP 路径问题，
  不能作为健康 serving 吞吐。
- 历史队列 microbenchmark 报告显示增加 worker 未带来提升；必须以对应原始输出复核后再引用。

### 不能声称/待核验

- `HttpInferServer` 明确是单线程 accept + 每连接阻塞处理，不是完整 Reactor LLM Serving。
- `/infer` 存在和可选 TinyInfer bridge 不等于当前 benchmark 真实使用 TinyInfer、真实模型或
  端到端 LLM 推理。
- README/路线图性能数字不是现场实测证据。
- 用户尚未学习该仓库。下一学习顺序：`EventLoop -> Queue -> Connection -> HTTP -> response`。

## 9. 每日算法题状态

题源：

```text
/home/allen/AI_Infra/Phases_book/Algorithm.md
/home/allen/AI_Infra/Phases_book/Algorithm_solutions.md
/home/allen/AI_Infra/Phases_book/Algorithm_solutions/
```

- 共有 33 个独立 C++ 实现文件；现有 README 只证明曾用 C++17 编译，不替代题面样例和
  用户掌握验证。
- 当前题：01 接雨水 II（LeetCode 407），状态 L0，尚未正式读题。
- 新题先读 `Algorithm.md` 题面，用户先给暴力思路/伪代码/实现；不先展示答案册。
- 每题记录：题号、方法、测试、卡点、L0-L5、复习日期。默认复习第 1、3、7、14 天。

## 10. Java 题库平台主线状态

- 总体蓝图：`Phases_book/题库平台项目蓝图.md`。
- Agent 专项：`Phases_book/Java_Agent学习与接入计划.md`。
- M0 当前为 `verified` 工程状态，但不是 `learned`：项目代码、测试和日志已通过，用户尚未完成所有权验收。
- 固定顺序：M0～M2 普通后端与可靠性 -> DirectModelAdapter Baseline -> 受限 Harness PoC ->
  Java JSON-RPC 子进程适配 -> 同条件 A/B -> 决定保留或回退。
- Agent 不拥有题库、提交和正式分数；Java/MySQL 是业务事实来源，人工复核是正式结果入口。
- Java M0 是当前深度主块；先按 `/home/allen/study/java/m0/` 的 49 天路线完成 Java 基础、Spring、SQL、JUC/JVM、M0 和面试表达，不能跳到 Harness 接线。

## 11. 新 AI 恢复步骤

1. 完整读取本文件；读取 `AGENTS.md` 获取稳定教学和文件安全规则。
2. 执行三个仓库的 `git status --short`，不还原任何现有改动。
3. 只读取当前四块下一步需要的源码、测试和小型日志；先 `rg`，不全文吞 README/长日志。
4. 一次只教学一个小目标；等用户回答或贴输出后再切块。
5. 每日结束更新下方记录与四块总览；工程状态和学习状态分开。

### 上下文维护规则

- 每次先更新“四块进度总览”和“当前首要任务”，再追加当天记录。
- 只保留最近 7 个学习日的详细记录；更早内容压缩为阶段摘要，详细证据仍留在各 Day 的
  `next_day.md`、报告、日志和 Git 历史中。
- 环境、HEAD、测试数量或入口变化时，用现场结果替换旧快照，不并排堆叠多个过期版本。
- 不把完整源码、完整 87 天清单、长 CSV、长 CTest/NCU 输出复制进本文件，只写路径和摘要。

## 12. 每日记录

### 2026-08-15 / Java Agent 规划优化

- Java：优化题库蓝图，新增 Agent 专项学习与接入计划；明确 Baseline-first、受限工具、Java
  子进程 JSON-RPC、A/B 评测和回退 Gate。仅完成规划，未创建工程、实现或运行测试。
- 主线：C++/TinyInfer/TinyWebServer/AI_Infra/算法停点不因本次文档规划而改变；Java 实现继续
  暂停。

### 2026-08-19 / Java M0 学习卡与源码索引重写

- `/home/allen/study/java/m0/` 的 49 个 `study.md` 已改为具体知识正文：M0 前 28 天包含真实项目 Controller、Service、Repository、SQL、事务、锁和测试代码骨架；后 21 天包含 Redis、任务、限流、指标、JVM/JUC、SQL、系统设计和算法代码/公式。
- 所有项目源码链接改为相对于 study 文件的路径，已检查可解析到 `/home/allen/projects/java-question-bank-m0`；算法索引改为实际存在的 `Phases_book/algorithm/` 路径。
- 49 个 `tests.md` 的上半部分补齐为对应知识的代码、原理和边界验收题；study 只放知识，任务只放 tests。
- 每个 `tests.md` 上半部分另有源码索引验收：按当天“会背会写”索引逐项定位文件、类/方法或 SQL 对象和行号，独立写出并解释输入、输出和边界。
- 新增 `/home/allen/study/java/m0/README.md` 作为教师总说明，固定五阶段能力目标、源码索引格式、所有权阶梯和求职出口；不替代每天的两个学习文件。

### 2026-08-18 / Java M0 工程、证据与求职学习目录完成

- Java M0：工程位于 `/home/allen/projects/java-question-bank-m0`；新增 V2 查询索引、数据库悲观行锁幂等、事务回滚测试、同 key 并发测试和索引存在性测试；README 已写验证命令与边界。
- 证据：`./mvnw -B test`，7 tests / 0 failures / 0 errors；`./mvnw -B -DskipTests package` 成功，产物为 `target/question-bank-m0-0.1.0-SNAPSHOT.jar`。
- 运行修复：Docker daemon 已使用 `127.0.0.1:7897` 代理成功拉取 `mysql:8.4`；损坏的自动卷保留未删除，Compose 固定使用新健康卷 `question-bank-m0_question_bank_mysql`，宿主机绑定 `127.0.0.1:3307 -> 3306`。
- 真实证据：MySQL 容器 `healthy`；Flyway V1/V2 成功；`output/mysql_explain_20260818.log` 显示查询使用 `idx_paper_version_status_published_at`；`output/real_mysql_m0_verification_20260818.log` 保存健康、登录、发布、提交和同 key 重试结果。
- 学习目录：`/home/allen/study/java/m0/` 已建立 49 天、98 个 Markdown 文件；每个 `study.md` 和 `tests.md` 都严格为两部分。目录内容是学习路线，不是掌握证明。
- 限制：项目当前不是 Git 仓库，未执行初始化或提交；Git 历史需用户按仓库规则执行。M0 工程 verified，用户 learned 待验收。

### 2026-08-17 / Java M0 主线切换

- 用户明确将 Java 后端设为当前主线，先完成题库平台 M0；该方向变更来自用户决定，不把它误写成已有 JD 或投递数据证明的成功率结论。
- M0 初始仍是规划 Gate；当前工程状态以后续 2026-08-18 记录为准。M1 PDF、M2 可靠性扩展、Agent/Harness 全部暂停。
- TinyWebServer + TinyInfer 保留为 C++ 差异化学习线，目标是未来的 CPU 推理服务原型；用户需先通过各自一个模块所有权 Gate，不能称为 LLM Serving。
- AI_Infra：暂停 Day 16 及之后内容；Day 16 只完成动态 Shared Memory 与 Occupancy 的局部理论学习和计划，没有工程完成证据。

### 2026-08-16 / Day 14 Shared Memory Tile、Padding 与 XOR Swizzle

- AI_Infra 工程：完成 naive、Shared Memory unpadded、padding、XOR swizzle 四个 transpose variant。`3x5`、`33x35`、`1024x1024` 共 12/12 correctness PASS，原始日志为 `day14_shared_swizzle/output/correctness_all_variants_20260816.log`。
- 测量：RTX 4060 Laptop GPU / Driver 610.88 / CUDA 13.3，FP32 `4096x4096`、`32x32` block、warmup=10、iterations=100、5 次外部重复。CUDA Event 均值：naive `4.027230 ms`、unpadded `1.768743 ms`、padded `1.043411 ms`、XOR `1.077757 ms`。
- 边界：以上只是当前 GPU/shape/实现的 kernel-only 结果；有效带宽为算法字节派生值，未运行 NCU，不声称精确 bank-conflict transaction 数。
- 学习状态：用户尚不能独立解释 bank 映射；下次先完成 `tx * 32 + ty`、stride=33 与 XOR 读取的手算，不将本次工程完成记为用户掌握。

### 2026-08-12 / 合并与审计

- TinyWebServer：现场 CTest 155/155 通过；源码已接 `/infer`/429/可选 bridge；学习尚未开始。
- TinyInfer：现场 CTest 2/2 通过，`tiny_infer` 手工图 Session 运行成功；学习尚未开始。
- AI_Infra：Day 11 证据已确认；Day 12 `main.cu` 仍是 TODO，今天继续线程映射。
- 算法题：第 01 题排队，L0；Day 12 完成后从题面开始。
- 下一步：继续 Day 12 的 1D/2D 手算，不开始三仓库实现或算法答案。

### 每日更新模板

```text
### YYYY-MM-DD
- TinyWebServer：完成/证据/学习状态/卡点/明日最小下一步
- TinyInfer：完成/证据/学习状态/卡点/明日最小下一步
- AI_Infra：完成/证据/学习状态/卡点/明日最小下一步
- 算法题：题号/方法/测试/L0-L5/卡点/复习日期
```
