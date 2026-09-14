# MustRemember

- 发布状态机只有 `DRAFT -> PUBLISHED`；实体方法只做状态迁移、不含状态守卫（守卫在 Service 层），真实形态是：
  ```java
  // PaperVersion.java:31
  public void publish() {
      status = "PUBLISHED";
      publishedAt = Instant.now();
  }
  ```
- 发布服务先检查 `paper -> bank -> owner` 关系、当前状态和题目非空，再在同一事务写入 `status=PUBLISHED`、`publishedAt` 与 Outbox 事件（PAPER_PUBLISHED）
- `@Transactional` 把校验、实体修改、Outbox 落库绑定到同一事务；未捕获运行时异常使事务回滚；缓存淘汰通过 TransactionSynchronization.afterCommit 推迟到提交成功之后执行（回滚则不淘汰）
- 源码索引（MustRemember）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 64-81 行的发布方法、第 87-98 行的 afterCommit 缓存淘汰；[PaperVersion.java](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 31 行的状态方法

# MustUnderstand

- 不可变版本使历史练习保持题面、答案和分值快照；重复发布返回已发布版本，其他状态返回冲突
- 事务提交前的写入对其他事务不可见；网络、文件和长耗时外部调用不应占用发布事务
- 源码索引（MustUnderstand）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 66-71 行的 owner/status/非空校验顺序、第 74-79 行的同事务 Outbox 与提交后淘汰；[PaperVersion.java](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 14-17 行的状态与时间字段、第 31 行的状态转换方法；提交后淘汰时序由 RedisCacheIntegrationTest.publishEvictsCacheOnlyAfterCommit 固化
