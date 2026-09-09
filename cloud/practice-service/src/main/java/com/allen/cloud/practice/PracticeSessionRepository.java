package com.allen.cloud.practice;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeSessionRepository extends JpaRepository<PracticeSession, Long> {

    Optional<PracticeSession> findByClientToken(String clientToken);
}
