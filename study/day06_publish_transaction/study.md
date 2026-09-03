# 必须会背会写

- 发布状态机只有 `DRAFT -> PUBLISHED`；实体方法的核心形态是：
  ```java
  public void publish() {
      if (!"DRAFT".equals(status)) throw new IllegalStateException();
      status = "PUBLISHED";
      publishedAt = Instant.now();
  }
  ```
- 发布服务先检查 `paper -> bank -> owner` 关系、当前状态和题目非空，再在同一事务写入 `status=PUBLISHED` 与 `publishedAt`
- `@Transactional` 把校验、实体修改和持久化绑定到同一事务；未捕获运行时异常使事务回滚
- 源码索引（会背会写）：[BankService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/BankService.java) 第 55-66 行的发布方法；[PaperVersion.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 31 行的状态方法

# 必须理解

- 不可变版本使历史练习保持题面、答案和分值快照；重复发布返回已发布版本，其他状态返回冲突
- 事务提交前的写入对其他事务不可见；网络、文件和长耗时外部调用不应占用发布事务
- 源码索引（必须理解）：[BankService.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/BankService.java) 第 57-66 行的 owner/status/非空校验顺序；[PaperVersion.java](../../../../projects/java-question-bank-m0/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 14-17、31 行的状态与时间字段
