package com.allen.cloud.bank;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperItemRepository extends JpaRepository<PaperItem, Long> {

    List<PaperItem> findByPaperVersionIdOrderByOrdinalAsc(Long paperVersionId);
}
