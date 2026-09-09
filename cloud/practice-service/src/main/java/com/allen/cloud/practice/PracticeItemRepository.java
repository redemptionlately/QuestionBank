package com.allen.cloud.practice;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PracticeItemRepository extends JpaRepository<PracticeItem, Long> {

    List<PracticeItem> findBySessionIdOrderByOrdinalAsc(Long sessionId);
}
