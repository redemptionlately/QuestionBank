package com.allen.questionbank.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 幂等去重的查询入口：existsByEventKey 命中即说明这条消息消费过，直接跳过。 */
@Repository
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, Long> {
    boolean existsByEventKey(String eventKey);
}
