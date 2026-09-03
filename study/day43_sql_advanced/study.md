# 必须会背会写

- 窗口函数保留原行并计算 `ROW_NUMBER/RANK/SUM OVER`，CTE 用 `WITH` 命名中间结果，`EXISTS` 表达关联记录存在性
- 慢查询分析同时看执行计划、实际扫描行数、锁等待、返回行数和数据分布；对索引列套函数或发生隐式类型转换可能失去索引访问
- InnoDB MVCC 通过隐藏版本和 undo 读取一致性视图；REPEATABLE READ、gap lock、next-key lock 和死锁检测影响并发写入
- Top-N per group 常用 `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` 再过滤 `rn <= N`
- Top-N SQL 形态是：
  ```sql
  WITH ranked AS (
      SELECT q.*, ROW_NUMBER() OVER (
          PARTITION BY paper_version_id ORDER BY question_no) rn
      FROM question_version q
  ) SELECT * FROM ranked WHERE rn <= 10;
  ```
- 外部源码索引（会背会写）：[MySQL window functions](https://dev.mysql.com/doc/refman/8.4/en/window-functions.html) 的 `ROW_NUMBER/RANK/SUM OVER`；[WITH CTE](https://dev.mysql.com/doc/refman/8.4/en/with.html)

# 必须理解

- SQL 优化顺序是确认语义、收集计划、定位瓶颈、单变量改写、再次验证；不能只因“加索引”就跳过语义和数据分布
- 死锁来自循环等待；统一锁顺序、缩短事务和有限重试比无限重试可靠，重试必须保证业务幂等
- 外部源码索引（必须理解）：[InnoDB locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html) 的 record/gap/next-key lock 与死锁检测
- 官方：[MySQL InnoDB](https://dev.mysql.com/doc/refman/8.4/en/innodb-storage-engine.html)、[Window Functions](https://dev.mysql.com/doc/refman/8.4/en/window-functions.html)
