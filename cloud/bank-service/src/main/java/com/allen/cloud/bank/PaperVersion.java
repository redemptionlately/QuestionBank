package com.allen.cloud.bank;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 一次发布 = 一个不可变版本。 */
@Entity
@Table(name = "paper_version")
public class PaperVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(nullable = false)
    private int version;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    protected PaperVersion() {
    }

    public PaperVersion(Long bankId, int version, int itemCount) {
        this.bankId = bankId;
        this.version = version;
        this.itemCount = itemCount;
        this.publishedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getBankId() {
        return bankId;
    }

    public int getVersion() {
        return version;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getItemCount() {
        return itemCount;
    }
}
