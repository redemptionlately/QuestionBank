package com.allen.questionbank.bank;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "paper_version", uniqueConstraints = @UniqueConstraint(name = "uk_paper_version_no", columnNames = {"bank_id", "version_no"}))
public class PaperVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "bank_id", nullable = false) private Long bankId;
    @Column(name = "version_no", nullable = false) private int versionNo;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, length = 20) private String status = "DRAFT";
    @Column(name = "created_by", nullable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;

    protected PaperVersion() {}
    public PaperVersion(Long bankId, int versionNo, String title, Long createdBy) {
        this.bankId = bankId; this.versionNo = versionNo; this.title = title; this.createdBy = createdBy; this.createdAt = Instant.now();
    }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getBankId() { return bankId; }
    public int getVersionNo() { return versionNo; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public void publish() { status = "PUBLISHED"; publishedAt = Instant.now(); }
}
