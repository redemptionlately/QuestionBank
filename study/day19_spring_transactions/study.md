# MustRemember

- `@Transactional` 由 Spring AOP 代理在目标方法外开启事务，正常返回提交，异常按规则回滚；同类 `self-invocation` 不经过代理
- 默认运行时异常和 Error 回滚；checked exception 默认不回滚，可用 `rollbackFor` 指定；传播级别决定加入现有事务或创建新事务
- READ_COMMITTED/REPEATABLE_READ/SERIALIZABLE 的可见性和锁范围不同，`readOnly=true` 是事务语义/优化提示而不是安全限制
- 事务服务的源码形态是：
  ```java
  @Transactional
  public PaperVersion createDraft(AuthPrincipal user, Long bankId,
                                  String title, List<QuestionInput> inputs) {
      PaperVersion paper = papers.save(new PaperVersion(bankId, 1, title, user.userId()));
  for (QuestionInput input : inputs) questionRepository.save(toEntity(paper, input));
      return paper;
  }
  ```
- 源码索引（MustRemember）：[BankService.java](../../src/main/java/com/allen/questionbank/service/BankService.java) 第 38-55 行的草稿事务；[PracticeService.java](../../src/main/java/com/allen/questionbank/service/PracticeService.java) 第 53-85 行的提交事务

# MustUnderstand

- 事务边界必须覆盖一个业务不变量：草稿的 PaperVersion 与全部 QuestionVersion 同成败，提交的 Session 与 Item/错题同成败
- 网络、PDF 解析和长任务不应占用长数据库事务；异步任务用持久状态和租约衔接两个事务
- 源码索引（MustUnderstand）：[BankService.java](../../src/main/java/com/allen/questionbank/service/BankService.java) 第 38-55 行的事务覆盖范围；[PracticeService.java](../../src/main/java/com/allen/questionbank/service/PracticeService.java) 第 53-85 行的多表写入与回滚边界
