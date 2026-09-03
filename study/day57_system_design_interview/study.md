# 必须会背会写

- 系统设计回答顺序是需求与边界、规模假设、API/数据模型、核心数据流、一致性与并发、缓存/队列、失败恢复、观测、容量和替代方案。
- 分页优先使用稳定排序和游标（keyset）避免深 offset；API 版本、错误 code、幂等键和兼容字段要先定义再实现。
- SLI 是测量值，SLO 是目标，SLA 是对外承诺；容量估算至少考虑峰值 QPS、请求大小、数据增长、连接数、线程数和故障余量。
- 业务一致性分为强一致事务、读己之写、最终一致和可接受丢失；必须说明事实来源、重复请求、乱序事件和补偿方式。
- 简历项目表达必须使用 STAR：背景/目标、个人动作、可验证结果、失败边界；结果只能引用真实测试和日志，不能编造性能数字。
- 外部源码索引（会背会写）：[Google SRE SLO](https://sre.google/sre-book/service-level-objectives/)、[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457)、[Martin Fowler Keyset Pagination](https://www.martinfowler.com/eaaDev/Range.html)

# 必须理解

- 高可用、低延迟、强一致、低成本之间存在取舍；面试中应先澄清优先级，再选择单体、模块化单体或拆分服务。
- 缓存、队列、重试、限流和熔断都可能改变请求顺序和重复次数；系统设计必须写出每个组件的事实、容量、故障和恢复边界。
- “完成 49/57 天”不是求职能力证明；只有用户能逐段解释、亲手修改、运行测试和回答变式，项目模块才可写入简历。
- 外部源码索引（必须理解）：[Designing Data-Intensive Applications notes](https://dataintensive.net/)、[OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
