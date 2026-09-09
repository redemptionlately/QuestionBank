package com.allen.cloud.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 发布时固化的题目副本，随版本不可变。 */
@Entity
@Table(name = "paper_item")
public class PaperItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_version_id", nullable = false)
    private Long paperVersionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false, length = 500)
    private String prompt;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "options_json", nullable = false, length = 500)
    private String optionsJson;

    @Column(nullable = false, length = 200)
    private String answer;

    @Column(nullable = false)
    private int ordinal;

    protected PaperItem() {
    }

    public PaperItem(Long paperVersionId, Long questionId, String prompt, String type,
                     String optionsJson, String answer, int ordinal) {
        this.paperVersionId = paperVersionId;
        this.questionId = questionId;
        this.prompt = prompt;
        this.type = type;
        this.optionsJson = optionsJson;
        this.answer = answer;
        this.ordinal = ordinal;
    }

    public Long getQuestionId() {
        return questionId;
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
