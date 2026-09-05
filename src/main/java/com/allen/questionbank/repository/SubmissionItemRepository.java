package com.allen.questionbank.repository;

import com.allen.questionbank.entity.SubmissionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SubmissionItemRepository extends JpaRepository<SubmissionItem, Long> {
    Optional<SubmissionItem> findBySessionIdAndQuestionVersionId(Long sessionId, Long questionVersionId);
    List<SubmissionItem> findBySessionIdOrderByQuestionVersionId(Long sessionId);
}
