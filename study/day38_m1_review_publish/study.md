# MustRemember

- 候选状态可为 `CREATED -> REVIEWING -> ACCEPTED/REJECTED`；正式版本可为 `DRAFT -> PUBLISHED -> ARCHIVED`
- 审核记录保存 reviewer、decision、reason、sourceVersion、createdAt；审核记录是追加事实而不是覆盖字段
- 撤回、归档、恢复通过状态或新版本表达，不能覆盖已发布题面和历史审核意见
- 状态转换表必须定义 `fromState + command + actor -> toState`；不存在的边返回 409，越权 actor 返回 403
- 外部源码索引（MustRemember）：[Spring StateMachine](https://docs.spring.io/spring-statemachine/docs/current/reference/) 的 state/event/transition 配置模型

# MustUnderstand

- 审核是状态机、权限和追加审计的组合；解析只产生候选，人工决定是否进入正式题库
- 并发审核需要 `entity_version` 检查或悲观行锁，旧页面不能覆盖新决定；冲突返回 409
- 外部源码索引（MustUnderstand）：[Spring StateMachine guards/actions](https://docs.spring.io/spring-statemachine/docs/current/reference/#sm-guards) 的守卫与副作用边界
- 官方：[Spring StateMachine](https://spring.io/projects/spring-statemachine)
