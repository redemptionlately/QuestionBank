# MustRemember

- `@Transactional` 由 Spring AOP 代理在目标方法外开启事务，正常返回提交，异常按规则回滚；同类 `self-invocation` 不经过代理
- 默认运行时异常和 Error 回滚；checked exception 默认不回滚，可用 `rollbackFor` 指定；传播级别决定加入现有事务或创建新事务
- READ_COMMITTED/REPEATABLE_READ/SERIALIZABLE 的可见性和锁范围不同，`readOnly=true` 是事务语义/优化提示而不是安全限制（本项目读写分离靠它路由从库）
- 事务服务的源码形态是（BankService.createDraft :44-62，真实代码）：
  ```java
  @Transactional
  public PaperVersion createDraft(AuthPrincipal user, Long bankId, String title,
                                  List<BankController.QuestionInput> inputs) {
      QuestionBank bank = banks.findById(bankId).orElseThrow(...);   // 归属校验
      int nextVersion = papers.findByBankIdOrderByVersionNoDesc(bankId).stream()
              .mapToInt(PaperVersion::getVersionNo).max().orElse(0) + 1;
      PaperVersion paper = papers.save(new PaperVersion(bankId, nextVersion, title.trim(), user.userId()));
      for (int i = 0; i < inputs.size(); i++) {
          validateQuestion(inputs.get(i));                           // 先校验，任一非法整体回滚
          questionRepository.save(new QuestionVersion(paper.getId(), i + 1, ...));
      }
      return paper;
  }
  ```
- 源码索引（MustRemember）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 44-62 行草稿事务、64-81 行发布事务（同事务 Outbox :75-78、afterCommit 淘汰 :87-98）；[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 56-89 行提交事务（FOR UPDATE 在 :61）

# MustUnderstand

- 事务边界必须覆盖一个业务不变量：草稿的 PaperVersion 与全部 QuestionVersion 同成败，提交的 Session 与 Item/错题同成败
- 网络、PDF 解析和长任务不应占用长数据库事务；异步任务用持久状态和 afterCommit 回调衔接两个事务（ImportJobService.submit 在事务提交后才触发 worker.process，:27）
- 事务五路径与传播行为都有默认运行的证据测试：TransactionFailureModeTest 5 法、TransactionPropagationIntegrationTest 2 法（REQUIRES_NEW 独立提交、代理存在性）
- 源码索引（MustUnderstand）：[BankService.java](../../app/src/main/java/com/allen/questionbank/bank/BankService.java) 第 44-62 行的事务覆盖范围；[PracticeService.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeService.java) 第 56-89 行的多表写入与回滚边界；[PracticeSessionRepository.java](../../app/src/main/java/com/allen/questionbank/practice/PracticeSessionRepository.java) 第 14-16 行 PESSIMISTIC_WRITE
