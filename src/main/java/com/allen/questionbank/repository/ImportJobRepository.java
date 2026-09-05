package com.allen.questionbank.repository;

import com.allen.questionbank.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    Optional<ImportJob> findByIdAndOwnerId(Long id, Long ownerId);
}
