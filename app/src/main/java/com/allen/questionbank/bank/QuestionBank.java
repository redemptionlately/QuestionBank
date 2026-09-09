package com.allen.questionbank.bank;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "question_bank")
public class QuestionBank {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(length = 500)
    private String description;
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected QuestionBank() {}
    public QuestionBank(Long ownerId, String name, String description) {
        this.ownerId = ownerId; this.name = name; this.description = description; this.createdAt = Instant.now();
    }
    @PrePersist void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
