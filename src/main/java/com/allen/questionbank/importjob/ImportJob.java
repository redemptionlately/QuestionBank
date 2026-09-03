package com.allen.questionbank.importjob;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "import_job")
public class ImportJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "source_name", nullable = false, length = 200) private String sourceName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ImportJobStatus status;
    @Column(nullable = false) private int progress;
    @Column(length = 500) private String error;
    @Column(nullable = false) private int attempt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long entityVersion;

    protected ImportJob() {}
    public ImportJob(Long ownerId, String sourceName) {
        this.ownerId = ownerId; this.sourceName = sourceName; this.status = ImportJobStatus.RECEIVED;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); if (updatedAt == null) updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getSourceName() { return sourceName; }
    public ImportJobStatus getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getError() { return error; }
    public int getAttempt() { return attempt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void start() { status = ImportJobStatus.PROCESSING; attempt++; progress = 10; }
    public void succeed() { status = ImportJobStatus.SUCCEEDED; progress = 100; error = null; }
    public void fail(String message) { status = ImportJobStatus.FAILED; error = message; }
}
