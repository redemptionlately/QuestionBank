package com.allen.cloud.practice;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "practice_session")
public class PracticeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false)
    private Long paperId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int score;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    /**
     * 客户端幂等键。建会话这一步会被网关/客户端重试，
     * 没有它一次网络抖动就多开一个会话、多扣一次配额。
     */
    @Column(name = "client_token", nullable = false, unique = true, length = 120)
    private String clientToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PracticeSession() {
    }

    public PracticeSession(Long paperId, Long userId, String clientToken, int totalCount) {
        this.paperId = paperId;
        this.userId = userId;
        this.clientToken = clientToken;
        this.totalCount = totalCount;
        this.status = "ACTIVE";
        this.score = 0;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPaperId() {
        return paperId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public int getScore() {
        return score;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void finish(int score) {
        this.score = score;
        this.status = "SUBMITTED";
    }
}
