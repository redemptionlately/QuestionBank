package com.allen.questionbank.importjob;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ImportJobWorker {
    private final ImportJobRepository jobs;
    public ImportJobWorker(ImportJobRepository jobs) { this.jobs = jobs; }

    @Async("importTaskExecutor")
    @Transactional
    public void process(Long id) {
        ImportJob job = jobs.findById(id).orElse(null);
        if (job == null || job.getStatus() != ImportJobStatus.RECEIVED) return;
        try {
            job.start();
            // Parsing is intentionally represented by a bounded, deterministic
            // stage. Real PDF extraction can replace this worker later.
            if (job.getSourceName().isBlank()) throw new IllegalArgumentException("source name is blank");
            job.succeed();
        } catch (RuntimeException error) {
            job.fail(error.getMessage() == null ? "import failed" : error.getMessage());
        }
        jobs.save(job);
    }
}
