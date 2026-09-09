package com.allen.questionbank.importjob;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    Optional<ImportJob> findByIdAndOwnerId(Long id, Long ownerId);

    /** 重试清扫候选：FAILED 且未耗尽尝试次数（退避窗口在内存里按 attempt 过滤）。 */
    List<ImportJob> findByStatusAndAttemptLessThan(ImportJobStatus status, int attempt);
}
