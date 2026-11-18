# MustRemember

- `publish(user, paperId)`（BankService:64-81）的校验顺序是 `find paper(:66) -> find bank(:67) -> owner check(:68) -> 已发布幂等返回(:69) -> DRAFT 状态检查(:70) -> 题目非空(:71) -> paper.publish()(:72)`
- 不存在、无权、非法状态、空题目分别映射 404 NOT_FOUND、403 FORBIDDEN、409 STATE_CONFLICT、400 INVALID_INPUT；重复发布返回已经是 `PUBLISHED` 的同一版本（不刷新 publishedAt、不重复写 Outbox）
- `PaperVersion.publish()`（:31，单行）同时写入 `status=PUBLISHED` 和 `publishedAt=Instant.now()`，实体方法自身不做状态前置判断；发布后没有更新题面的 API，新题面必须创建新版本（versionNo=max+1）
- 发布同事务写 outbox_event（:75-78，kafka.enabled=false 时 no-op），缓存淘汰注册在 afterCommit 回调（evictPublishedAfterCommit :87-98），不在事务内直接 evict
- 实体状态方法的源码形态是：
  ```java
  public void publish() { status = "PUBLISHED"; publishedAt = Instant.now(); }
  ```
- 源码索引（MustRemember）：[PaperVersion.java](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 31 行的状态转换、:7 的 uk_paper_version_no；[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 64-81 行的发布入口

# MustUnderstand

- 不可变版本使历史练习成为可审计快照；版本 ID 是题面、答案、分值和发布状态的稳定引用（practice 以标量 paperVersionId 引用，不持有对象关联）
- 事务提交把状态和时间作为原子事实，读请求只能看到已提交版本；事务失败不能留下半发布状态；缓存淘汰必须等提交后，否则回滚会留下空缓存/旧值回填窗口
- 双层授权：Controller 方法级 @PreAuthorize 管角色（ADMIN），Service :68 管资源归属（owner），通过角色不等于拥有资源
- 源码索引（MustUnderstand）：[BankController.java](../../app/src/main/java/com/allen/questionbank/bank/BankController.java) 第 40-44 行的发布协议；[PaperVersion.java](../../app/src/main/java/com/allen/questionbank/bank/PaperVersion.java) 第 9-17 行的不可变快照字段；[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 87-98 行 afterCommit 淘汰
