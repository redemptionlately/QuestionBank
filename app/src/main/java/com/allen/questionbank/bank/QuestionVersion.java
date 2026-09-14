package com.allen.questionbank.bank;

import jakarta.persistence.*;

@Entity
@Table(name = "question_version", uniqueConstraints = @UniqueConstraint(name = "uk_question_version_no", columnNames = {"paper_version_id", "question_no"}))
public class QuestionVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "paper_version_id", nullable = false) private Long paperVersionId;
    @Column(name = "question_no", nullable = false) private int questionNo;
    @Column(nullable = false, columnDefinition = "TEXT") private String prompt;
    @Enumerated(EnumType.STRING) @Column(name = "question_type", nullable = false, length = 20) private QuestionType questionType;
    @Column(name = "options_json", nullable = false, columnDefinition = "TEXT") private String optionsJson;
    @Column(name = "answer_json", nullable = false, columnDefinition = "TEXT") private String answerJson;
    @Column(nullable = false) private int score;
    @Column(columnDefinition = "TEXT") private String explanation;
    /** 知识点标签（V6 入库扩展，可空兼容老数据；原生 SQL 回填，不走构造器）。 */
    @Column(name = "knowledge_point", length = 200) private String knowledgePoint;

    protected QuestionVersion() {}
    public QuestionVersion(Long paperVersionId, int questionNo, String prompt, QuestionType type,
                           String optionsJson, String answerJson, int score, String explanation) {
        this.paperVersionId = paperVersionId; this.questionNo = questionNo; this.prompt = prompt;
        this.questionType = type; this.optionsJson = optionsJson; this.answerJson = answerJson;
        this.score = score; this.explanation = explanation;
    }
    public Long getId() { return id; }
    public Long getPaperVersionId() { return paperVersionId; }
    public int getQuestionNo() { return questionNo; }
    public String getPrompt() { return prompt; }
    public QuestionType getQuestionType() { return questionType; }
    public String getOptionsJson() { return optionsJson; }
    public String getAnswerJson() { return answerJson; }
    public int getScore() { return score; }
    public String getExplanation() { return explanation; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }
}
