package com.allen.questionbank.practice;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * MyBatis 动态 SQL 接管的错题本读查询：多条件筛选 + 分页 + 聚合统计。
 *
 * <p>与 JPA 的分工：JPA 管实体写入与简单等值查询，MyBatis 管复杂动态读查询。
 * 这类"筛选条件全部可选 + 动态组装 + 聚合"的 SQL，XML 动态 SQL 比 JPA Specification
 * 更直观、产出的 SQL 更可预测（EXPLAIN 调优时看得到完整语句）。</p>
 */
@Mapper
public interface WrongQuestionQueryMapper {

    /** 错题分页查询：studentId 必填（资源归属），wrongCountMin / wrongSince 可选筛选。 */
    List<WrongQuestionRow> search(@Param("studentId") Long studentId,
                                  @Param("wrongCountMin") Integer wrongCountMin,
                                  @Param("wrongSince") Instant wrongSince,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    /** 聚合统计：与 search 用同一组筛选条件，一次返回错题数 / 累计错误次数 / 单题最大错误次数。 */
    WrongQuestionSummary summarize(@Param("studentId") Long studentId,
                                   @Param("wrongCountMin") Integer wrongCountMin,
                                   @Param("wrongSince") Instant wrongSince);

    /** 只读行视图（可序列化：二级缓存 readOnly=false 时命中会做序列化拷贝）。 */
    class WrongQuestionRow implements Serializable {
        private Long questionVersionId;
        private int wrongCount;
        private Instant lastWrongAt;

        public Long getQuestionVersionId() { return questionVersionId; }
        public void setQuestionVersionId(Long questionVersionId) { this.questionVersionId = questionVersionId; }
        public int getWrongCount() { return wrongCount; }
        public void setWrongCount(int wrongCount) { this.wrongCount = wrongCount; }
        public Instant getLastWrongAt() { return lastWrongAt; }
        public void setLastWrongAt(Instant lastWrongAt) { this.lastWrongAt = lastWrongAt; }
    }

    /** 聚合结果（可序列化原因同上）。 */
    class WrongQuestionSummary implements Serializable {
        private long totalQuestions;
        private long totalWrongCount;
        private int maxWrongCount;

        public long getTotalQuestions() { return totalQuestions; }
        public void setTotalQuestions(long totalQuestions) { this.totalQuestions = totalQuestions; }
        public long getTotalWrongCount() { return totalWrongCount; }
        public void setTotalWrongCount(long totalWrongCount) { this.totalWrongCount = totalWrongCount; }
        public int getMaxWrongCount() { return maxWrongCount; }
        public void setMaxWrongCount(int maxWrongCount) { this.maxWrongCount = maxWrongCount; }
    }
}
