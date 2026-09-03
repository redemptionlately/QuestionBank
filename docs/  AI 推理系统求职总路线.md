# Java 后端主线 / C++ 系统备选求职路线

> 状态更新时间：2026-08-17（Asia/Shanghai）
>
> 目标窗口：2026 年 12 月至 2027 年 1 月实习；2027 年 3 月春招。
>
> 文档职责：这是求职方向、能力建设、项目收敛、投递和转向规则的**唯一长期入口**。
> 当前学习停点与每日进度见 `/home/allen/AI_Infra/CODEX_CONTEXT.md`；教学规则见
> `/home/allen/AI_Infra/AGENTS.md`；87 天课程目录见 `/home/allen/AI_Infra/PROGRESS.md`。

本文是基于当前项目事实做出的路线预测，不是实时招聘市场统计，也不承诺 Offer。没有真实
JD 样本、投递数据和面试反馈前，不给任何方向编造成功率。

## 1. 唯一方向结论

Allen 当前方向已由用户明确调整为：

```text
Java 后端
  -> 题库平台 M0 的业务闭环与数据库/测试证据
  -> TinyWebServer + TinyInfer 作为 C++ 差异化备选线
  -> AI_Infra 暂停后续 Day，不作为近期求职主资产
```

这不是对实时岗位数量或成功率的断言。用户选择 Java 主线以扩大可投范围；真实 JD、投递和面试
反馈仍是后续调整的依据。

| 层级 | 岗位池 | 作用 |
|---|---|---|
| 主投 | Java 后端 | 用题库平台 M0 建立 Spring Boot、MySQL、事务、鉴权、幂等、测试和部署证据 |
| 保留/备选 | Linux C++、C++ 后端、推理 Runtime | TinyWebServer + TinyInfer 收敛为可验证的 CPU 推理服务原型；只写用户能负责的模块 |
| 暂停 | AI_Infra 后续 Day、Agent/Harness、论文、完整生产级 LLM Serving | 不再占近期主时间；已完成 CUDA 实验保留为学习记录，不外推成求职能力 |

用户已明确不选择测试、运维和前端，不再用这些岗位作为默认兜底。Java 题库平台 M0 已获启动
授权，但当前只有蓝图，不等于项目已建仓、功能已接线或用户已经掌握。

### 当前执行依据与边界

- 用户明确希望 Java 优先；题库平台 M0 覆盖业务后端的核心训练点，且范围比完整 Agent 系统可控。
- TinyWebServer 与 TinyInfer 继续保留，避免放弃已经存在的 C++ 系统学习资产；但只在用户完成模块所有权 Gate 后才成为求职证据。
- AI_Infra 的工程证据可以保留为性能学习记录，但不再继续按 Day 扩展，也不能代替 Java 后端工程能力。
- 当前最大风险仍是把 AI 生成代码、蓝图或课程目录写成个人已完成项目；Java 与 C++ 两条线都必须以亲手修改、测试和解释为准。

## 2. 当前真实起点

状态定义沿用统一上下文：

```text
source_exists   源码或文档存在
wired           已列入构建并接到入口
verified        有可审计测试或原始日志
learned         用户能复述、亲手修改、测试并回答追问
```

前三项不能推出 `learned`。

| 资产 | 已证实工程状态 | 用户所有权 | 当前求职价值 | 不能声称 |
|---|---|---|---|---|
| TinyWebServer | `/infer`、有界队列、超时、429、Mock runner、可选 TinyInfer bridge 已接线；现有 build 上 CTest 155/155 | 尚未逐模块学习 | 高价值 C++ 网络/并发教材 | 自己实现完整 Reactor、高性能推理服务或生产 Serving |
| TinyInfer | `InferenceSession + GraphBuilder` 已接入，手工 Graph 可 `Compile -> Run`；现有 build 上 CTest 2/2 | 尚未逐模块学习 | 高价值 C++ Runtime/内存教材 | 自己实现完整 Runtime、ONNX importer、CUDA backend 或 LLM Runtime |
| AI_Infra | Day 11--Day 15 有各自工程证据；Day 16 未实现 | 不再作为近期主线 | 保留为学习记录 | 已具备 AI Infra 求职能力或完成后续课程 |
| 算法题 | 题面、答案册和 33 个 C++ 实现文件存在 | 每日一题尚未正式开始 | 未来面试基本盘 | 文件存在等于会做或能无提示实现 |
| Java | 有容器、JUC、JVM 学习材料、题库蓝图和 Agent 接入计划；M0 已获启动授权 | 还没有 Java 工程、构建或测试证据 | 当前主项目的规划入口 | 已有 Spring、数据库、Redis 或 Agent 工程经验 |

TinyInfer/TinyWebServer 的测试使用已有 build，未在本次路线审计中从干净目录重新 configure
和 build。CTest 通过证明现有构建可运行，不证明用户掌握，也不证明所有可选集成路径通过。

### 当前竞争力诊断

- **优势**：方向已经能收束为网络服务、Runtime、GPU 性能三层；已有源码和真实实验可学习。
- **最大短板**：C++ 基础、模块所有权、算法和项目表达尚未形成稳定面试能力。
- **主要风险**：把未来计划写成当前能力、同时推进过多大功能、只打卡 87 天却没有可负责模块。
- **当前投递状态**：不应直接发送目标态简历；可以立即开始 JD 采样、岗位建档和面试题收集。

`/home/allen/AI_Infra/docs/RESUME.md` 是“核心路线完成后的目标态草稿”，不是当前可发送简历。
其中 CUDA/Triton、Paged KV Cache、Continuous Batching、FlashAttention 等内容必须通过真实
实现、验证和用户追问关后才能保留。正式投递前要从当前证据重新生成事实版简历。

## 3. 岗位能力模型

### 3.1 所有目标岗位共同的 P0 基础

| 能力 | 至少要达到的可验证表现 |
|---|---|
| Java/Spring | 能解释 Java 生命周期、集合、异常、并发基础；能用 Spring Boot 写清晰的 Controller/Service/Repository 边界并测试一个业务切片 |
| C++ | 能解释 RAII、对象生命周期、拷贝/移动、智能指针、容器失效、虚函数、多态和常见 UB；能独立写小模块 |
| 并发 | 能解释 mutex、condition_variable、atomic、生产者消费者、线程池、死锁与竞态；能定位一个真实并发问题 |
| Linux/网络 | 能说明 socket、TCP、HTTP、非阻塞 I/O、epoll/Reactor、连接生命周期、超时和背压 |
| 工程 | 能使用 CMake、Git、GTest/CTest、gdb/sanitizer、日志；能在新 build 目录复现构建和测试 |
| 算法 | 能独立完成常见数组、链表、栈队列、树、图、二分、堆、DFS/BFS、动态规划基础题并解释复杂度 |
| 系统基础 | 能回答进程/线程、虚拟内存、分页、缓存、系统调用、文件描述符、锁与调度的基础问题 |
| 数据基础 | 掌握 SQL、索引、事务、隔离级别和缓存基本问题；C++ 后端也不能完全跳过数据库 |
| 表达 | 能用 3 分钟和 15 分钟版本讲项目，并回答设计替代、失败案例、测试边界和性能限制 |

### 3.2 Java 后端 M0 加深项

重点是 `HTTP 请求 -> Controller -> Service -> 事务/Repository -> MySQL -> 响应` 的数据流，
以及角色鉴权、版本不可变、幂等提交、规则判分、错误契约、迁移和集成测试。题库平台 M0 是这一层
唯一的当前实现项目；不以 Agent、PDF 或 Redis 替代这些基本边界。

### 3.3 C++ 后端保留项

重点是 `请求 -> 事件 -> 连接 -> 解析 -> 队列/任务 -> 响应` 的完整数据流，以及资源所有权、
线程安全、错误处理和可观测性。TinyWebServer 是这一层的主要教材。

### 3.4 Runtime / 推理系统保留项

重点是 `Tensor -> Graph -> Rewrite -> MemoryPlanner -> Engine -> Operator` 的责任边界、对象
生命周期、算子正确性和内存复用。TinyInfer 是这一层的主要教材。

### 3.5 CUDA / AI Infra（暂停）

重点不是背名词，而是完成：

```text
问题与预测 -> CPU/PyTorch reference -> Kernel -> correctness
-> 固定条件 Benchmark -> NCU/NSYS -> 解释与限制
```

该能力线当前暂停，不继续排入近期学习日。既有实验只用于未来复习正确性、测量和限制的表达方法；
不继续扩展到 Triton、Attention、KV Cache、Serving、分布式、MoE 或 CUTLASS。

## 4. 三仓库怎样收敛成求职资产

三个仓库保持独立 Git 历史和构建系统，不为了简历物理合仓。招聘叙事按岗位生成不同视图。

### 视图 A：C++ 后端简历

在用户通过所有权 Gate 后，可选择：

1. TinyWebServer：重点写亲自负责的事件循环、HTTP、连接、队列、线程池、背压或故障处理。
2. TinyInfer：重点写亲自负责的 Tensor/Graph/内存规划/Engine/CPU 算子模块。
3. AI_Infra：放入“性能实验/技术作品”，只挑 2～4 个真正能讲透的实验。

### 视图 B：C++ AI 平台 / 推理系统简历

在请求级集成真实通过后，可选择：

1. CPU Inference Serving Prototype：TinyWebServer + TinyInfer 作为一个系统项目、两个仓库。
2. CUDA Operator Experiments：AI_Infra 中 3～5 个经过筛选的实验，不必另建仓库。

### CPU Inference Serving Prototype 的最小可验证链路

```text
HTTP /infer
  -> 输入校验
  -> 有界队列 / 超时 / 429
  -> TinyInferRunner
  -> 已 Compile 的手工 CPU Graph
  -> request-level reference correctness
  -> JSON response
  -> 固定条件 latency/QPS/error/queue wait 记录
```

它在满足以下条件前仍只是目标设计：

- 用户已通过 HTTP/队列和 Tensor/Graph/Engine 至少各一段模块所有权验收。
- `TINY_INFER_INTEGRATION=ON` 的真实构建和测试通过，不使用 Mock 冒充集成。
- 输入、输出、非法输入、超时、429 和服务关闭行为有请求级测试。
- benchmark 客户端本身正确，成功数、失败数和 schema 一致，原始日志可复核。
- 用户能解释瓶颈、替代方案和尚未解决的问题。

### CUDA 实验集的收敛标准

不追求把 87 个 Day 全塞进简历。优先沉淀：

1. 一个融合实验：解释 Kernel launch 与中间 Global Memory 读写。
2. 一个访存/线程组织实验：解释映射、coalescing、边界和测量限制。
3. 一个 GEMM 实验：从 naive 到 tiling，解释 FLOPs、字节量和资源约束。
4. 一个 Attention/Softmax 实验：只有实现和 reference 真实通过后再加入。
5. 一个“优化未生效”的案例：展示诚实定位瓶颈，而不是只展示成功加速。

Day 11 当前可作为候选，但用户必须能重跑、改一个核心变量并解释 `5.1591x` 只属于固定
`4096x1024 FP32`、当前实现和 kernel-only CUDA Event 范围。

## 5. 模块所有权 Gate

AI 生成代码只有逐模块通过以下 Gate，才从“学习素材”变成用户可负责经历：

1. **复述**：不看源码，说清输入、输出、数据流、对象所有权和一个失败场景。
2. **预测**：修改前写出预期行为或性能变化，允许预测错误，但必须可证伪。
3. **亲手修改**：用户写核心状态转换、索引、循环、边界或修复；AI 可提供骨架和 Review。
4. **正确性**：用户亲自 build/run，覆盖正常、边界和失败输入，保存真实输出。
5. **解释**：说清测试证明了什么、不能证明什么、替代设计和限制。
6. **变式**：在少量提示下完成一个新边界、小功能或故障定位。
7. **复现**：在新 build 目录或按 README 重新执行关键路径，确认不是旧产物偶然通过。

通过哪个模块，只认领哪个模块；不能把参与局部修改写成“从零独立实现整个仓库”。

## 6. 当前执行顺序：Java M0 主线

日期是检查点，不是按日历伪造掌握。某个 Gate 未通过就缩小任务，而不是把计划勾成完成。

### Gate J0：范围与项目初始化

- 用户能复述 M0 的两个角色、核心资源、发布版本不可变规则、练习会话状态和重复提交边界。
- 确认 Java、构建工具、MySQL 与 Docker 现场环境后，创建独立 Java 项目和最小可重复构建。
- 不实现 PDF、Redis、对象存储、异步导入、Agent 或 Harness。

### Gate J1：最小业务闭环

- 实现登录/角色、题库草稿与发布、学生练习会话、保存答案、幂等提交、服务端确定性判分和错题记录。
- 每个垂直切片由用户写核心状态或数据访问代码，并有单元/集成测试和失败用例。
- MySQL 迁移与 Docker 启动成为可复现证据，不以本地手工建表代替。

### Gate J2：收敛为可投递项目证据

- 用户能讲清 Controller -> Service -> Repository -> MySQL 的数据流、事务范围、版本不可变和幂等策略。
- 从干净环境构建并运行测试；保存真实日志，准备 3 分钟和 15 分钟项目说明。
- 先依据真实 JD、构建/测试证据和学习所有权决定是否进入 M1/M2；Agent 始终在 M0--M2 后评估。

### C++ 保留线

- TinyWebServer 和 TinyInfer 每周只推进一个小模块所有权 Gate；优先形成未来 CPU 推理服务原型所需的 HTTP/Queue 与 Tensor/Graph/Engine 数据流理解。
- 不把两个仓库称为 LLM Serving；不与 Java M0 交叉集成，直到两边各自有可复现的核心闭环。

## 7. 每日与每周执行方式

当前遵循“Java 一个深度主块、C++ 与算法保留小步”的节奏：

| 学习块 | 每日最小动作 |
|---|---|
| Java 题库平台 M0 | 需求、数据流、项目骨架或一个业务垂直切片；一次只推进一个 Gate |
| TinyWebServer / TinyInfer | 每周选择其中一个追一段数据流、解释一个对象/状态，或完成一个很小的修改/测试 |
| AI_Infra | 暂停后续 Day；不安排新的 correctness、benchmark 或 profiler 工作 |
| 算法 | 一道新题的一个阶段，或按 1/3/7/14 天复习旧题 |

一天只有 Java M0 做较深实现，C++ 与算法只做可验收小步骤，避免三倍工作量和频繁开新主题。
如果精力不足，记录真实停点并顺延，不用补虚假的“完成”。

每周还要完成：

- 一次 C++/Linux/网络/操作系统专题复盘，内容来自本周源码和错题。
- 一次项目口述：3 分钟版本和追问版本。
- 一次仓库卫生检查：构建、测试、README 复现入口、日志和 Git 状态。
- 一次求职反馈检查：JD 高频要求、缺口和下周优先级。

## 8. 市场验证与投递闭环

路线不能只靠 AI 预测。每两周抽样一批符合地域、毕业时间和岗位类型的真实 JD，至少记录：

```text
公司 / 岗位 / 地点 / 实习或校招 / 学历与毕业要求
语言 / Linux / 网络 / 并发 / 数据库 / CUDA或框架
项目或实习要求 / 与当前匹配项 / 最大缺口 / 是否投递 / 结果
```

样本要覆盖主投、邻近主投和副投，不只看大厂，也不只看容易岗位。累计样本后按出现频率调整
学习优先级；在此之前不得笼统声称“C++ 一定比 Java 难找”或“AI Infra 一定没有机会”。

### 投递漏斗怎样诊断

达到阶段 2 后开始记录：合格投递数、简历回复、笔试、技术面、终面和 Offer。出现问题时按
漏斗定位，不立刻换语言：

| 观察 | 优先检查 |
|---|---|
| 有足够匹配 JD，但投递后几乎无回复 | 简历事实密度、关键词、学历/毕业条件、岗位匹配和投递渠道 |
| 有笔试但通过率低 | 算法、C++ 语法、操作系统、网络和时间管理 |
| 有技术面但项目追问失败 | 模块所有权、数据流、测试边界、失败案例和表达 |
| 基础题失败多 | 暂缓新项目功能，集中补对应错题并再次模拟 |
| 主投岗位样本确实过少 | 先扩到相邻 C++ 岗位、地域和公司规模，再讨论是否重启备选路线 |
| AI Infra 冲刺岗位无反馈 | 保留长期学习，降低投递占比，不否定 C++ 主线 |

不设虚假的固定投递数量保证。只有在岗位确实匹配、简历诚实且样本量足够后，漏斗数据才有
解释意义。

## 9. 投递 Gate 与简历规则

### 可以开始小范围验证性投递

- 至少一个 TinyWebServer 模块和一个 TinyInfer 模块通过所有权 Gate 的前 5 项。
- 至少一个 CUDA 实验能独立解释正确性、测量、结果和限制。
- 有事实版简历，删除所有未实现/未掌握能力。
- 能回答基础 C++ 生命周期、线程同步、TCP/HTTP、epoll、队列与复杂度问题。

### 可以开始广泛投递

- 至少一个 C++ 项目故事可从新 build 目录复现，测试说明清楚。
- 用户能在 15 分钟内完整讲清需求、架构、亲自负责部分、bug/瓶颈、验证和限制。
- 至少一个性能实验有原始数据和 profiler 解释；允许结果不理想。
- 已进行模拟面试并把答不清的内容从简历删除或补齐。

### 简历硬规则

- 简历只写 `learned` 且可审计的模块，不按仓库目录批量认领。
- AI 生成的原始实现必须说明个人真实修改与验证范围，不写“全部独立从零实现”。
- 不虚构 QPS、延迟、显存、TTFT/TPOT、NCU/NSYS 指标或提升倍数。
- RTX 4060 Laptop 使用 VRAM/GDDR6，不写 HBM。
- Mock backend 的 benchmark 不写成真实模型或 LLM Serving 结果。
- `PagedAttention` 主要解决 KV 分配、碎片和调度问题；是否提速必须实测。
- 目标态简历只作为验收清单，不得原样投递。

## 10. 哪些必须用户完成，哪些交给 AI

### 用户必须亲手完成

- 新 Kernel 的核心索引、边界、循环或状态转换。
- TinyWebServer/TinyInfer 中用于建立所有权的核心局部修改。
- build、correctness、benchmark、profiler 的关键命令和原始输出解释。
- 至少一个 bug、失败案例或性能瓶颈的假设与验证。
- 算法题的思路、伪代码和核心实现；AI 不先展示答案册。
- 主要里程碑的 Git status、staged diff 和提交过程。
- 项目口述、简历逐句负责和面试追问。

### AI 适合完成或协作

- 路线拆解、最小理论、接口和非核心骨架。
- 测试口径、参考实现框架、压测脚本和统计工具。
- 代码 Review、错误定位、Profiler 指标解释和报告整理。
- 基于真实源码/日志生成 README、图表和简历候选句。
- 模拟面试、红字批改、错题设计和路线复盘。

AI 可以提高速度，但不能替用户生成 `learned` 状态。工程通过而用户讲不清时，先补学习验收。

## 11. 风险控制与转向条件

### 风险 1：三线变成三个半成品

信号：连续两个学习周都只有新增文件，没有用户完成的修改、测试和复述。

处理：停止新功能，每条线各收一个最小所有权闭环；一天只设一个深度主块。

### 风险 2：87 天课程挤占求职基本盘

信号：CUDA 目录持续增加，但 C++、算法、网络和项目表达没有进步。

处理：优先保留 Day 12～25 的 CUDA/访存/GEMM基础和后续少量 Attention/Serving 代表实验；
Day 58～87 的分布式、MoE、CUTLASS 等长期内容可顺延，不阻塞投递。

### 风险 3：过早做 PagedAttention/Continuous Batching 大集成

信号：CPU 请求闭环、输入正确性、队列和 Graph 数据流还讲不清，就开始堆 LLM 名词。

处理：退回 HTTP -> queue -> TinyInfer CPU Graph -> response correctness。单层通过后再进入 KV
Block Manager 和调度器 toy model。

### 风险 4：学历或毕业要求影响筛选

教育背景必须如实填写。哪些岗位存在硬筛选要用 JD 和投递数据验证，不能靠焦虑猜测。若证据
显示某类岗位普遍不匹配，优先扩大公司规模、岗位邻域、城市、日常实习、社招实习和内推渠道，
同时增强可复现作品；不伪造学历，也不把 Java 项目目录当作已具备 Java 工程能力。

### 风险 5：Java M0 未形成可投递证据

若四周后仍未形成可构建、可测试的 M0 垂直切片，先根据真实 JD、学习卡点和工程证据缩小范围，
不靠追加 Agent 或新技术掩盖基本闭环缺失。调整顺序是：

```text
修复或缩小 M0 业务范围
-> 补 Java/Spring/MySQL/测试的直接缺口
-> 收集并复核 Java JD 关键词
-> 再决定继续 M0、进入 M1/M2 或调整 Java/C++ 时间比例
```

不要因为一次拒绝、一道不会的题或一天焦虑就更换主线。

## 12. 当前明确暂停和禁止事项

- Java 题库平台只允许实施 M0；M1 PDF、M2 可靠性扩展和 Agent 接入在 M0 证据前禁止启动。
- AI_Infra 后续 Day 暂停；不新增 CUDA/Kernel 工程或把既有课程目录包装为求职项目。
- 不在 CPU serving 闭环前实现完整 LLM Serving、PagedAttention 大集成或分布式系统。
- 不在真实 A/B/C 对照完成前把论文当成求职主资产。
- 不把 README、计划、测试文件存在或 AI 口头结论当作完成证据。
- 不等待 87 天全部结束才做 JD 审计、简历复盘和投递准备。
- 不用“成神”“降维打击”“极致压榨”等无法验证的词描述路线和项目。

## 13. 最近的可执行顺序

当前按下面顺序前进：

1. 完成 Java 题库平台 M0 的需求边界、角色、资源、不变量和失败边界复述。
2. 审计 Java、构建工具、MySQL 与 Docker 环境，创建独立项目骨架并保存首次构建证据。
3. 以一个业务垂直切片开始：先测试，再扩展登录、发布、练习、幂等提交、判分和错题闭环。
4. 同步建立第一批 Java JD 样本，验证岗位关键词和硬条件。
5. 每周保留一个 TinyWebServer 或 TinyInfer 的模块所有权小步骤；AI_Infra 不继续排程。

每次路线复盘只回答四个问题：

```text
我新增了什么 learned 能力？
有什么源码、测试和日志证据？
真实 JD/投递反馈显示最大缺口是什么？
下一个最小、可验收、最能提高机会的动作是什么？
```

## 14. 证据与文档边界

- 动态学习状态：`/home/allen/AI_Infra/CODEX_CONTEXT.md`。
- 稳定教学和文件安全规则：`/home/allen/AI_Infra/AGENTS.md`。
- AI Infra 课程地图：`/home/allen/AI_Infra/PROGRESS.md`。
- 目标态而非当前可投简历：`/home/allen/AI_Infra/docs/RESUME.md`。
- Java 备选设计：`/home/allen/AI_Infra/Phases_book/题库平台项目蓝图.md`。
- Java Agent 学习与接入：`/home/allen/AI_Infra/Phases_book/Java_Agent学习与接入计划.md`。
- Day 11 融合报告与原始数据：
  `/home/allen/AI_Infra/day11_fusion_phase_a/docs/Phase_A_report.md`、
  `/home/allen/AI_Infra/day11_fusion_phase_a/output/benchmark_5runs_20260812.log`。
- TinyInfer Session：`/home/allen/TinyInfer/src/session/session.h`、
  `/home/allen/TinyInfer/src/session/session.cpp`、`/home/allen/TinyInfer/src/main.cpp`。
- TinyWebServer 推理入口：`/home/allen/TinyWebServer/src/main_infer.cpp`、
  `/home/allen/TinyWebServer/include/HttpInferServer.hpp`、
  `/home/allen/TinyWebServer/include/InferenceQueue.hpp`。

本文不保存每天的完成情况，也不把未来路线写成当前事实。市场结论只在有真实 JD 和投递样本
后更新；项目状态只在有源码、构建、测试、日志和用户学习验收后更新。
