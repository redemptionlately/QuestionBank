package com.allen.questionbank.bank;

import java.time.Instant;

/** 已发布试卷列表响应。独立成顶层 record，便于被 Redis 缓存层直接序列化。 */
public record PaperResponse(Long id, Long bankId, int versionNo, String title, String status, Instant publishedAt) {
}
