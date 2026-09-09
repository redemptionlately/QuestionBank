package com.allen.cloud.bank;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperVersionRepository extends JpaRepository<PaperVersion, Long> {

    Optional<PaperVersion> findTopByBankIdOrderByVersionDesc(Long bankId);
}
