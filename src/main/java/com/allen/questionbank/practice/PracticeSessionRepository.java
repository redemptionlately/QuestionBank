package com.allen.questionbank.practice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface PracticeSessionRepository extends JpaRepository<PracticeSession, Long> {
    List<PracticeSession> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PracticeSession p where p.id = :id")
    Optional<PracticeSession> findByIdForUpdate(@Param("id") Long id);
}
