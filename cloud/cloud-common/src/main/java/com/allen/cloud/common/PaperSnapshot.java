package com.allen.cloud.common;

import java.time.Instant;
import java.util.List;

/**
 * 试卷快照：bank-service 发布版本后对外提供的只读视图。
 *
 * <p>为什么是"快照"而不是让 practice-service 直接查 bank 的库：
 * 拆分的核心约束是数据所有权——practice 不能 JOIN bank 的表，否则一次加字段就要两个服务同时发版。
 * 所以 practice 在建会话时通过 Feign 拉一份不可变快照存进自己的库，
 * 之后判分完全本地完成，bank 挂了也不影响已开会话的提交（这正是熔断降级能生效的前提）。
 *
 * @param paperId 试卷（版本）ID
 * @param bankId 所属题库
 * @param version 版本号，发布后不可变
 * @param publishedAt 发布时间
 * @param items 题目项，顺序即出题顺序
 */
public record PaperSnapshot(Long paperId, Long bankId, int version, Instant publishedAt, List<Item> items) {

    /**
     * @param questionId 题目 ID
     * @param type 题型：SINGLE / MULTIPLE / TRUE_FALSE（与 app 侧 QuestionType 枚举同词表；
     *             bank-service 的 addDraft 白名单强制这三个值，快照里不会出现别的拼写）
     * @param prompt 题干
     * @param options 选项，判断题可为空列表
     * @param answer 标准答案；判分在 practice 侧完成，所以答案必须随快照下发
     */
    public record Item(Long questionId, String type, String prompt, List<String> options, String answer) {
    }
}
