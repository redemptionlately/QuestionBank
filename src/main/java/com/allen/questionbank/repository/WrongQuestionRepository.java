package com.allen.questionbank.repository;

import com.allen.questionbank.entity.WrongQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WrongQuestionRepository extends JpaRepository<WrongQuestion, Long> {
    Optional<WrongQuestion> findByStudentIdAndQuestionVersionId(Long studentId, Long questionVersionId);
    List<WrongQuestion> findByStudentIdOrderByLastWrongAtDesc(Long studentId);
}
