# MustRemember

- `publish(user, paperId)` 的校验顺序是 `find paper -> find bank -> owner check -> status check -> question non-empty -> paper.publish()`
- 不存在、无权、非法状态、空题目分别映射 404、403、409、400；重复发布返回已经是 `PUBLISHED` 的同一版本
- `PaperVersion.publish()` 同时写入 `status=PUBLISHED` 和 `publishedAt=Instant.now()`；发布后没有更新题面的 API，新题面必须创建新版本
- 实体状态方法的源码形态是：
  ```java
  public void publish() {
      if (!"DRAFT".equals(status)) throw new IllegalStateException();
      status = "PUBLISHED";
      publishedAt = Instant.now();
  }
  ```
- 源码索引（MustRemember）：[PaperVersion.java](../../src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 31 行的状态转换；[BankService.java](../../src/main/java/com/allen/questionbank/bank/BankService.java) 第 55-67 行的发布入口

# MustUnderstand

- 不可变版本使历史练习成为可审计快照；版本 ID 是题面、答案、分值和发布状态的稳定引用
- 事务提交把状态和时间作为原子事实，读请求只能看到已提交版本；事务失败不能留下半发布状态
- 源码索引（MustUnderstand）：[BankController.java](../../src/main/java/com/allen/questionbank/bank/BankController.java) 第 34-38 行的发布协议；[PaperVersion.java](../../src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 11-17 行的不可变快照字段
