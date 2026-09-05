package com.allen.questionbank.service;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.common.ApiException;
import com.allen.questionbank.entity.ImportJob;
import com.allen.questionbank.repository.ImportJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ImportJobService {
    private final ImportJobRepository jobs;
    private final ImportJobWorker worker;
    public ImportJobService(ImportJobRepository jobs, ImportJobWorker worker) { this.jobs = jobs; this.worker = worker; }

    @Transactional
    public ImportJob submit(ApiTokenFilter.AuthPrincipal user, String sourceName) {
        if (sourceName == null || sourceName.isBlank() || sourceName.length() > 200)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "文件名不合法");
        ImportJob job = jobs.save(new ImportJob(user.userId(), sourceName.trim()));
        Long jobId = job.getId();
        // Do not let the async worker race the INSERT commit. The worker is
        // scheduled only after the transaction becomes visible to other DB
        // connections.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { worker.process(jobId); }
        });
        return job;
    }

    @Transactional(readOnly = true)
    public ImportJob require(ApiTokenFilter.AuthPrincipal user, Long id) {
        return jobs.findByIdAndOwnerId(id, user.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "导入任务不存在"));
    }
}
