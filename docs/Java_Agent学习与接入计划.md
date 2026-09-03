# Java 题库平台 Agent 学习与接入计划

> 状态更新时间：2026-08-19（Asia/Shanghai）
>
> 当前状态：Java 题库平台 M0 工程已完成并通过真实验证，但用户学习所有权尚未验收；Agent 学习和实现仍未启动。本文件只负责 Agent 专项学习、接入顺序和验收；当前来源见 `/home/allen/AI_Infra/Phases_book/Java_Agent学习与接入计划.md`。

## 1. 目标与边界

目标不是在 Java 中重写 DeepSeek Harness，而是通过一个可替换的外部依赖，学习并证明：

```text
持久异步任务 -> 受控 Agent 调用 -> 结构化结果校验
-> 人工复核 -> 审计与评测 -> 故障恢复
```

完成后，用户应能解释并亲手验证：

- 为什么业务事实和最终分数必须留在 Java/MySQL，而不是 Agent session 中。
- 普通模型调用、单 Agent 多步工具调用和多 Agent 的适用边界。
- Java 如何管理 Harness 子进程、逐行 JSON-RPC、超时、退出和重启。
- 为什么模型输出、工具参数和 PDF 文本都属于不可信输入。
- 如何用冻结评测集比较直接调用与 Harness，而不是只展示一次成功对话。

本计划不包含：完整复刻 Cordis、修改 Harness 核心循环、多 Agent 协作、模型微调、向量数据库、
让 Agent 使用 Bash/任意文件写入，或让模型直接决定正式分数。

## 2. 启动 Gate

只读源码学习可以作为限时专题进行。A3 Direct Baseline 要先满足第 1、2 项；A4 及之后的
Harness 学习实现必须同时满足以下全部条件：

1. Java 项目 M0～M2 已形成业务闭环、持久任务和恢复证据。
2. 主观题辅助反馈有明确需求，且不阻塞客观题确定性判分。
3. 已有直接模型调用 Baseline，能够测量有效输出、延迟、成本和人工复核结果。
4. 真实 JD、投递反馈或项目差异化目标支持继续投入。
5. 写明时间预算、停止条件，以及它替代当前哪项学习时间。

看到 Agent 热点、框架目录很多或单次 Demo 成功都不构成启动证据。

## 3. 目标架构

```text
Spring Boot API
  -> grading_job / agent_run in MySQL
  -> bounded grading worker
  -> GradingAgentPort
       |-> DirectModelAdapter      HTTP structured-output Baseline
       `-> HarnessProcessAdapter   long-lived child process
               -> line-delimited JSON-RPC over stdin/stdout
               -> restricted Harness runtime
               -> read-only domain tools + submit_suggestion
  -> Java schema and business validation
  -> grading_suggestion
  -> human review
  -> accepted / overridden / rejected
```

### 3.1 数据所有权

| 数据 | 权威来源 | Agent 可否修改 |
|---|---|---|
| 用户、角色、题库权限 | Java/MySQL | 否 |
| 已发布题目、答案、评分点 | Java/MySQL 版本记录 | 否，只能按授权读取 |
| 学生提交 | Java/MySQL | 否，只能读取当前任务快照 |
| Agent session/event | Harness 持久层 | 可追加，但不是业务事实 |
| 模型建议 | Java/MySQL `grading_suggestion` | 只能生成候选 |
| 正式分数与复核结论 | Java/MySQL 人工/规则流程 | 否 |

### 3.2 Java 端口

领域层只依赖 `GradingAgentPort` 概念接口，不依赖 DeepSeek、Cordis、JSON-RPC 或 Node 类型。
请求至少携带任务 id、题目/提交/评分规则版本、Prompt 版本和幂等标识；结果至少携带建议分、
评分点引用、理由、不确定性说明、模型/适配器版本和原始运行引用。

适配器负责外部协议，应用服务负责授权、任务状态和事务，领域规则负责判断结果是否可进入人工
复核。外部调用不得位于长数据库事务中。

## 4. 学习阶段

每阶段都按“问题 -> 预测 -> 最小阅读/实现 -> 测试 -> 解释 -> 记录”完成。源码存在或 AI
讲过不算通过。

| 阶段 | 核心问题 | 用户交付 | 通过证据 |
|---|---|---|---|
| A0 术语与数据流 | turn、step、tool、session 各负责什么 | 一张请求数据流和口头复述 | 能指出持久事件与实时事件 |
| A1 Tool 与 Session | 工具为何需要前后置策略，历史如何恢复 | 跟踪一个完整 tool call | 能解释参数校验、结果记录和重放边界 |
| A2 协议与生命周期 | Java 怎样可靠驱动 Harness 进程 | JSON-RPC 帧与进程状态草图 | 能解释 stdout、stderr、idle、退出和超时 |
| A3 直接调用 Baseline | 不用 Agent 能否完成任务 | 结构化模型适配器和冻结样本 | Mock/契约测试及首轮评测原始记录 |
| A4 Harness 独立 PoC | 多步工具是否解决 Baseline 缺口 | 最小 runtime 与受限工具 | Keyless replay；实机测试单独记录 |
| A5 Java 进程适配器 | 预发布协议如何隔离 | `HarnessProcessAdapter` | 假进程契约测试、崩溃/超时测试 |
| A6 业务接线 | 怎样保证重复执行不重复发布 | job/run/suggestion 状态闭环 | 重试、重启、幂等和人工复核 E2E |
| A7 A/B 评测 | Harness 的收益是否大于复杂度 | 同条件 Baseline/Harness 报告 | 质量、延迟、成本和故障对照 |
| A8 加固与表达 | 能否在新环境复现并讲清取舍 | 文档、指标、故障复盘 | 干净启动、测试日志和面试追问 |

### A0：Agent loop 基础

只读以下最小范围：

1. `deepseek-harness/docs/architecture.md` 的 Cordis、Core packages、Turn flow、Session log。
2. `deepseek-harness/docs/tool-execution-pipeline.md` 的工具执行顺序。
3. `deepseek-harness/docs/subsystems/session.md` 的持久事件与投影。

用户要能从一条 prompt 讲到 `assistant/message`、`tool/call`、`tool/result` 和下一 step，并说明
Java 业务任务状态为什么不能直接等同于 Agent turn 状态。

### A1：能力插件与最小权限

阅读 tool registry、文件系统策略、sandbox 和 compaction 的职责，不进入 Web UI。手工设计本项目
第一版工具：

```text
get_grading_context(run_id) -> 只返回已授权、已版本化的题目/提交/评分点
submit_grading_suggestion(run_id, suggestion) -> 只提交候选，不发布正式分数
```

第一版不提供 Bash、任意 URL、任意 SQL、文件写入、题库修改和用户管理工具。工具参数必须由
Java 再校验，`run_id` 不能让模型越权读取其他用户任务。

### A2：JSON-RPC 与进程生命周期

阅读 `packages/sdk/protocol`、`packages/sdk/server` 和 `packages/sdk/client`。用户先手写并解释：

- `initialize`、`session/prompt`、`session.event`、`session.status`、`shutdown` 的最小帧。
- stdout 只能传协议帧，诊断日志走 stderr。
- 当前协议没有逐提示结果、取消和逐会话关闭时，Java 如何超时止损。

第一版设计固定为“一个长期运行 Harness 进程、同一时刻一个在途业务任务”。Java 收到对应
session 的 `idle` 后结束活动区间；超时、协议损坏或进程退出时，终止并重建该进程，将业务任务
转为有限重试或人工处理。只有并发数据证明单进程串行不足时，才设计有界进程池。

### A3：直接模型调用 Baseline

先实现不带 Agent loop 的 `DirectModelAdapter`：固定 Prompt 版本、JSON Schema、超时、最大输出、
有限重试和审计字段。CI 使用 Mock server，不需要真实密钥；实机评测另存原始输出且不得提交
凭据。

这一阶段用于回答：任务是否其实只需要一次结构化调用。若 Baseline 已满足质量、审计和可靠性
要求，Harness 可以只保留为学习材料，不进入生产路径。

### A4：Harness 独立 PoC

在不连接 Java 的情况下组装最小 runtime，只加载模型、session、compaction、两个受限工具和
必要日志。先使用 keyless Mock/replay 验证工具序列和输出，再在有凭据环境运行少量冻结样本。

通过条件不是“能回答”，而是：工具只能访问指定 run、所有模型可见输入可从 session 重建、
无效参数被拒绝、失败能形成明确状态，且没有意外加载 Bash/文件写入能力。

### A5：Java Harness 适配器

用户亲手实现并测试：

1. `ProcessBuilder` 启动、环境变量白名单和工作目录。
2. stdin 逐行写入、stdout 逐行解析、stderr 有界保留。
3. 请求 id、session id、事件路由和单在途约束。
4. 启动握手、正常 shutdown、EOF、超时和强制终止阶梯。
5. 子进程退出后的资源回收、状态上报和下一任务重建。

先用可控假进程做协议契约测试，再运行真实 Harness。不得把真实 API key 写入命令、日志、测试
fixture 或 Git。

### A6：业务接线

Agent worker 领取 `grading_job` 租约后创建 `agent_run`，提交事务后再调用外部进程。返回内容先
做协议、JSON Schema、字段范围、评分点引用和业务版本校验，再写 `grading_suggestion` 并转入
`REVIEW_REQUIRED`。任何失败都不能改动正式成绩。

人工接受、覆盖和拒绝均追加审计记录；重复回调、worker 重启和租约回收不能产生两个生效建议
或绕过复核。

### A7：同条件 A/B 评测

冻结相同模型、题目、评分点、学生答案、Prompt 信息、超时和最大输出，对比：

| 维度 | DirectModelAdapter | HarnessProcessAdapter |
|---|---|---|
| 输出有效性 | Schema 通过与失败类型 | Schema 通过、工具失败与循环失败 |
| 质量 | 与人工结论的差异 | 与人工结论的差异 |
| 可追溯性 | 请求/响应记录 | Session、工具参数和结果事件 |
| 延迟 | 模型调用端到端 | 进程队列、step、工具和模型端到端 |
| 成本 | token/请求 | token/step、compaction 和工具上下文 |
| 运维 | HTTP 依赖故障 | 额外包含子进程和协议故障 |

仅在 Harness 明确解决多步取数、工具审计或上下文管理问题，且新增复杂度可测试、可运维时保留。
结果相当或更差时，回退 DirectModelAdapter 是合格结论，不是失败。

### A8：加固与面试表达

完成安全测试、故障注入、指标、运行手册和一份决策记录。用户需要能分别用 3 分钟和 15 分钟
讲清：为什么先做 Baseline、为什么采用子进程隔离、协议限制如何影响并发、Agent 如何降级、
评测结果支持了什么，以及哪些结论不能外推。

## 5. 必测故障

- 模型超时、限流、无效 JSON、字段越界和超长输出。
- Harness 启动失败、握手失败、运行中退出、永远不进入 idle。
- stdout 出现非法帧、响应 id 不匹配、重复事件、stderr 持续输出。
- 工具请求不存在的 run、越权 run、已过期版本或未允许的能力。
- Java worker 在模型完成后、建议落库前退出。
- 相同业务任务被重复领取、重复运行或人工重复提交复核。
- 模型/Prompt/Harness 版本变化后旧运行仍可审计，但不静默复用旧评测结论。

每个故障记录：触发方法、预期状态、实际数据库状态、进程是否回收、是否可重试、是否产生重复
副作用，以及用户能否看到可解释状态。

## 6. 证据与指标

### 工程证据

- Java 单元、数据库、协议契约、恢复和 E2E 测试日志。
- Harness keyless replay、最小配置和工具能力清单。
- 实机模型测试的命令、环境、模型/Prompt/适配器版本与原始结果。
- A/B 决策记录和一次故障复盘；不能只保留最终截图。

### 指标口径

- 系统：任务排队、运行、端到端时间，成功/重试/失败，子进程重启数。
- 协议：非法帧、超时、非预期退出、session 到 idle 时间。
- 模型：输入/输出 token、调用次数、成本、Schema 有效率。
- 质量：评分点引用有效性、与人工差异、接受/覆盖/拒绝比例。

系统吞吐不能替代批改质量，人工接受率也不能自动等于准确率。数据量不足时只报告当前样本。

## 7. 求职 Gate

只有同时满足以下条件，才把 Agent 接入写入简历：

1. 用户能独立画出 Java job 到 Harness session 再到人工复核的数据流。
2. 能解释直接调用与 Harness 的 A/B 结果及最终选型。
3. 至少亲手定位并修复一个协议、进程或幂等故障。
4. 测试和原始评测可在新环境复现，版本和限制记录完整。
5. 表述为“辅助建议系统”，不声称模型自动准确判分或生产级 Agent 平台。

对普通 Java 后端岗位，重点讲持久任务、事务、幂等、进程管理、故障恢复和可观测性；对 Java
AI 平台岗位，再展开 tool pipeline、session event、compaction、权限和评测。

## 8. 暂停与回退条件

- Java M0～M2 尚未完成，却连续投入 Agent UI、插件数量或多 Agent。
- Harness 预发布协议变化导致维护持续超过业务学习收益。
- 四周内没有形成新的 `learned` 能力、运行证据或目标 JD 匹配。
- A/B 结果不能证明 Harness 相对直接调用解决了具体问题。

触发时保留适配器和评测证据，业务路径回退 `DirectModelAdapter`，不影响题库核心功能。

## 9. 真正启动时的第一步

当前不执行。启动后先完成 A0，只提交一张数据流图和以下四个回答，不写 Java/Harness 源码：

```text
一个 turn 为什么可能包含多个 step？
tool/call 和 tool/result 为什么要进入 session log？
Java grading_job 与 Harness session 的权威状态分别是什么？
当前 SDK 没有逐提示取消时，第一版怎样限制故障范围？
```

通过 Review 后再进入 A1，不批量跳到接入实现。

## 10. 本地学习入口

- `/home/allen/deepseek-harness/docs/architecture.md`
- `/home/allen/deepseek-harness/docs/tool-execution-pipeline.md`
- `/home/allen/deepseek-harness/docs/subsystems/session.md`
- `/home/allen/deepseek-harness/packages/sdk/protocol/README.zh.md`
- `/home/allen/deepseek-harness/packages/sdk/server/README.zh.md`
- `/home/allen/deepseek-harness/packages/sdk/client/README.zh.md`
- `/home/allen/deepseek-harness/examples/jsonrpc-agent/README.zh.md`
