package com.allen.questionbank.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wrong_question", uniqueConstraints = @UniqueConstraint(name = "uk_wrong_question", columnNames = {"student_id", "question_version_id"}))
public class WrongQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "student_id", nullable = false) private Long studentId;
    @Column(name = "question_version_id", nullable = false) private Long questionVersionId;
    @Column(name = "wrong_count", nullable = false) private int wrongCount;
    @Column(name = "last_wrong_at", nullable = false) private Instant lastWrongAt;

    protected WrongQuestion() {}
    public WrongQuestion(Long studentId, Long questionVersionId) {
        this.studentId = studentId; this.questionVersionId = questionVersionId; this.wrongCount = 1; this.lastWrongAt = Instant.now();
    }
    public void markWrong() { wrongCount++; lastWrongAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getStudentId() { return studentId; }
    public Long getQuestionVersionId() { return questionVersionId; }
    public int getWrongCount() { return wrongCount; }
    public Instant getLastWrongAt() { return lastWrongAt; }
}
