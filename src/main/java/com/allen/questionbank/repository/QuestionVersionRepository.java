package com.allen.questionbank.repository;

import com.allen.questionbank.entity.QuestionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, Long> {
    List<QuestionVersion> findByPaperVersionIdOrderByQuestionNo(Long paperVersionId);
    Optional<QuestionVersion> findByPaperVersionIdAndQuestionNo(Long paperVersionId, int questionNo);
}
