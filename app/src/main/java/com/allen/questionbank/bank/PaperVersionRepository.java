package com.allen.questionbank.bank;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaperVersionRepository extends JpaRepository<PaperVersion, Long> {
    List<PaperVersion> findByBankIdOrderByVersionNoDesc(Long bankId);
    List<PaperVersion> findByStatusOrderByPublishedAtDesc(String status);
}
