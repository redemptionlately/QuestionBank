package com.allen.questionbank.practice;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "submission_item", uniqueConstraints = @UniqueConstraint(name = "uk_submission_question", columnNames = {"session_id", "question_version_id"}))
public class SubmissionItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "question_version_id", nullable = false) private Long questionVersionId;
    @Column(name = "answer_json", nullable = false, columnDefinition = "TEXT") private String answerJson;
    @Column(nullable = false) private int score;
    @Column(nullable = false) private boolean correct;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected SubmissionItem() {}
    public SubmissionItem(Long sessionId, Long questionVersionId, String answerJson) {
        this.sessionId = sessionId; this.questionVersionId = questionVersionId; this.answerJson = answerJson; this.createdAt = Instant.now();
    }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public Long getQuestionVersionId() { return questionVersionId; }
    public String getAnswerJson() { return answerJson; }
    public int getScore() { return score; }
    public boolean isCorrect() { return correct; }
    public void replaceAnswer(String answerJson) { this.answerJson = answerJson; }
    public void grade(int score, boolean correct) { this.score = score; this.correct = correct; }
}
