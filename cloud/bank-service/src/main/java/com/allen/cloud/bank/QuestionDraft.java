package com.allen.cloud.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 草稿题。发布时会被"复制"成 paper_item，草稿本身不变。
 *
 * <p>为什么不直接让 paper_item 引用 question_draft：发布版本必须不可变。
 * 若引用草稿，教师改一道题就会把已发布的历史试卷改掉，学生拿到的历史成绩无法复现。
 */
@Entity
@Table(name = "question_draft")
public class QuestionDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(nullable = false, length = 500)
    private String prompt;

    @Column(nullable = false, length = 20)
    private String type;

    /** JSON 数组，如 ["A","B","C","D"] */
    @Column(name = "options_json", nullable = false, length = 500)
    private String optionsJson;

    @Column(nullable = false, length = 200)
    private String answer;

    protected QuestionDraft() {
    }

    public QuestionDraft(Long bankId, String prompt, String type, String optionsJson, String answer) {
        this.bankId = bankId;
        this.prompt = prompt;
        this.type = type;
        this.optionsJson = optionsJson;
        this.answer = answer;
    }

    public Long getId() {
        return id;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getType() {
        return type;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public String getAnswer() {
        return answer;
    }
}
