package com.allen.questionbank.importjob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 重试清扫：退避窗口、attempt 上限、FAILED→RECEIVED 翻转与 worker 交还。 */
@ExtendWith(MockitoExtension.class)
class ImportJobRetrySweeperTest {

    @Mock
    private ImportJobRepository jobs;
    @Mock
    private ImportJobWorker worker;
    @InjectMocks
    private ImportJobRetrySweeper sweeper;

    private ImportJob failedJob(int attempt, long secondsSinceUpdate) {
        ImportJob job = new ImportJob(1L, "paper.pdf");
        ReflectionTestUtils.setField(job, "id", 42L);
        for (int i = 0; i < attempt; i++) job.start();
        job.fail("boom #" + attempt);
        ReflectionTestUtils.setField(job, "updatedAt", Instant.now().minusSeconds(secondsSinceUpdate));
        return job;
    }

    @Test
    void failedJobPastBackoffWindowIsRequeued() {
        ImportJob job = failedJob(1, 3600); // 第 1 次失败，退避 30s 早已过
        when(jobs.findByStatusAndAttemptLessThan(ImportJobStatus.FAILED, ImportJobRetrySweeper.MAX_ATTEMPTS))
                .thenReturn(List.of(job));
        when(jobs.save(any(ImportJob.class))).thenAnswer(inv -> inv.getArgument(0));

        int requeued = sweeper.retryFailedJobs();

        assertEquals(1, requeued);
        assertEquals(ImportJobStatus.RECEIVED, job.getStatus());
        assertEquals("boom #1", job.getError(), "重试入队应保留失败原因");
        verify(jobs).save(job);
        verify(worker).process(42L);
    }

    @Test
    void failedJobInsideBackoffWindowIsSkipped() {
        ImportJob job = failedJob(1, 5); // 第 1 次失败仅 5s，退避窗口 30s 未过
        when(jobs.findByStatusAndAttemptLessThan(ImportJobStatus.FAILED, ImportJobRetrySweeper.MAX_ATTEMPTS))
                .thenReturn(List.of(job));

        int requeued = sweeper.retryFailedJobs();

        assertEquals(0, requeued);
        assertEquals(ImportJobStatus.FAILED, job.getStatus());
        verify(jobs, never()).save(any());
        verify(worker, never()).process(any());
    }

    @Test
    void secondFailureWaitsForExponentialBackoff() {
        ImportJob job = failedJob(2, 45); // 第 2 次失败，退避 60s 未过
        when(jobs.findByStatusAndAttemptLessThan(ImportJobStatus.FAILED, ImportJobRetrySweeper.MAX_ATTEMPTS))
                .thenReturn(List.of(job));

        assertEquals(0, sweeper.retryFailedJobs());
    }

    @Test
    void emptyCandidateListShortCircuitsWithoutSave() {
        when(jobs.findByStatusAndAttemptLessThan(ImportJobStatus.FAILED, ImportJobRetrySweeper.MAX_ATTEMPTS))
                .thenReturn(List.of());
        assertEquals(0, sweeper.retryFailedJobs());
        verify(jobs, never()).save(any());
        verify(worker, never()).process(any());
    }

    @Test
    void backoffGrowsExponentiallyAndCapsAtTenMinutes() {
        assertEquals(30, ImportJobRetrySweeper.backoffSeconds(1));
        assertEquals(60, ImportJobRetrySweeper.backoffSeconds(2));
        assertEquals(120, ImportJobRetrySweeper.backoffSeconds(3));
        assertEquals(600, ImportJobRetrySweeper.backoffSeconds(10), "异常大的 attempt 也应封顶");
        assertEquals(30, ImportJobRetrySweeper.backoffSeconds(0), "防御性下界");
    }
}
