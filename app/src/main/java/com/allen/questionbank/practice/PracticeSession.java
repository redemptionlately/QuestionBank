package com.allen.questionbank.practice;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "practice_session")
public class PracticeSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "student_id", nullable = false) private Long studentId;
    @Column(name = "paper_version_id", nullable = false) private Long paperVersionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PracticeStatus status = PracticeStatus.IN_PROGRESS;
    @Column(name = "total_score") private Integer totalScore;
    @Column(name = "submission_key", length = 100) private String submissionKey;
    @Column(name = "submission_result_json", columnDefinition = "TEXT") private String submissionResultJson;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Version private long entityVersion;

    protected PracticeSession() {}
    public PracticeSession(Long studentId, Long paperVersionId) {
        this.studentId = studentId; this.paperVersionId = paperVersionId; this.createdAt = Instant.now();
    }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getStudentId() { return studentId; }
    public Long getPaperVersionId() { return paperVersionId; }
    public PracticeStatus getStatus() { return status; }
    public Integer getTotalScore() { return totalScore; }
    public String getSubmissionKey() { return submissionKey; }
    public String getSubmissionResultJson() { return submissionResultJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void submit(String key, int score, String resultJson) {
        status = PracticeStatus.SUBMITTED; submissionKey = key; totalScore = score;
        submissionResultJson = resultJson; submittedAt = Instant.now();
    }
}
