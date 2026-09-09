package com.allen.cloud.practice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 会话内的题目副本。
 *
 * <p>建会话时把标准答案一起存下来，之后判分是纯本地事务，不再依赖 bank-service。
 * 这样"题库服务不可用"只影响"开新会话"，不影响"提交已开会话"——
 * 熔断降级能真正生效，正是因为依赖被切成了一次性的快照拉取。
 */
@Entity
@Table(name = "practice_item")
public class PracticeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "correct_answer", nullable = false, length = 200)
    private String correctAnswer;

    @Column(name = "student_answer", length = 200)
    private String studentAnswer;

    @Column(nullable = false)
    private boolean correct;

    protected PracticeItem() {
    }

    public PracticeItem(Long sessionId, Long questionId, int ordinal, String correctAnswer) {
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.ordinal = ordinal;
        this.correctAnswer = correctAnswer;
        this.correct = false;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public String getStudentAnswer() {
        return studentAnswer;
    }

    public boolean isCorrect() {
        return correct;
    }

    public void grade(String answer) {
        this.studentAnswer = answer;
        // 判分规则必须与题库侧约定一致：去空格 + 忽略大小写。
        // 多选按排序后的字符串比较——顺序不同的等价答案必须规范化，否则同答案不同分。
        this.correct = normalize(answer).equals(normalize(correctAnswer));
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String[] parts = raw.replaceAll("\\s+", "").split(",");
        java.util.List<String> sorted = new java.util.ArrayList<>(java.util.Arrays.asList(parts));
        java.util.Collections.sort(sorted);
        return String.join(",", sorted).toLowerCase(java.util.Locale.ROOT);
    }
}
