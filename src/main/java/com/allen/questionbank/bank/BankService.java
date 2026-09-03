package com.allen.questionbank.bank;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.common.ApiException;
import com.allen.questionbank.common.ExpiringCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BankService {
    private final QuestionBankRepository banks;
    private final PaperVersionRepository papers;
    private final QuestionVersionRepository questions;
    private final ObjectMapper objectMapper;
    private final ExpiringCache<String, List<PaperVersion>> publishedCache;

    public BankService(QuestionBankRepository banks, PaperVersionRepository papers,
                       QuestionVersionRepository questions, ObjectMapper objectMapper,
                       ExpiringCache<String, List<PaperVersion>> publishedCache) {
        this.banks = banks; this.papers = papers; this.questions = questions; this.objectMapper = objectMapper;
        this.publishedCache = publishedCache;
    }

    @Transactional
    public QuestionBank createBank(ApiTokenFilter.AuthPrincipal user, String name, String description) {
        if (user == null || name == null || name.isBlank()) throw bad("题库名称不能为空");
        return banks.save(new QuestionBank(user.userId(), name.trim(), description));
    }

    @Transactional
    public PaperVersion createDraft(ApiTokenFilter.AuthPrincipal user, Long bankId, String title,
                                    List<BankController.QuestionInput> inputs) {
        QuestionBank bank = banks.findById(bankId).orElseThrow(() -> notFound("题库不存在"));
        if (!bank.getOwnerId().equals(user.userId())) throw forbidden();
        if (title == null || title.isBlank() || inputs == null || inputs.isEmpty()) {
            throw bad("试卷标题和至少一道题目不能为空");
        }
        int nextVersion = papers.findByBankIdOrderByVersionNoDesc(bankId).stream()
                .mapToInt(PaperVersion::getVersionNo).max().orElse(0) + 1;
        PaperVersion paper = papers.save(new PaperVersion(bankId, nextVersion, title.trim(), user.userId()));
        for (int index = 0; index < inputs.size(); index++) {
            BankController.QuestionInput input = inputs.get(index);
            validateQuestion(input);
            questions.save(new QuestionVersion(paper.getId(), index + 1, input.prompt(), input.type(),
                    json(input.options()), json(input.correctAnswers()), input.score(), input.explanation()));
        }
        return paper;
    }

    @Transactional
    public PaperVersion publish(ApiTokenFilter.AuthPrincipal user, Long paperId) {
        PaperVersion paper = papers.findById(paperId).orElseThrow(() -> notFound("试卷版本不存在"));
        QuestionBank bank = banks.findById(paper.getBankId()).orElseThrow(() -> notFound("题库不存在"));
        if (!bank.getOwnerId().equals(user.userId())) throw forbidden();
        if ("PUBLISHED".equals(paper.getStatus())) return paper;
        if (!"DRAFT".equals(paper.getStatus())) throw conflict("试卷版本状态不允许发布");
        if (questions.findByPaperVersionIdOrderByQuestionNo(paperId).isEmpty()) throw bad("试卷不能发布为空版本");
        paper.publish();
        PaperVersion result = papers.save(paper);
        publishedCache.evict("published");
        return result;
    }

    @Transactional(readOnly = true)
    public List<PaperVersion> published() {
        return publishedCache.getOrLoad("published", () -> papers.findByStatusOrderByPublishedAtDesc("PUBLISHED"));
    }

    @Transactional(readOnly = true)
    public PaperVersion requirePublished(Long paperId) {
        PaperVersion paper = papers.findById(paperId).orElseThrow(() -> notFound("试卷版本不存在"));
        if (!"PUBLISHED".equals(paper.getStatus())) throw conflict("试卷版本尚未发布");
        return paper;
    }

    @Transactional(readOnly = true)
    public List<QuestionVersion> questions(Long paperId) {
        return questions.findByPaperVersionIdOrderByQuestionNo(paperId);
    }

    private void validateQuestion(BankController.QuestionInput input) {
        if (input == null || input.prompt() == null || input.prompt().isBlank() || input.type() == null
                || input.options() == null || input.options().isEmpty() || input.correctAnswers() == null
                || input.correctAnswers().isEmpty() || input.score() <= 0) throw bad("题目字段不合法");
        if (!input.options().containsAll(input.correctAnswers())) throw bad("标准答案必须来自选项");
        if (input.options().stream().distinct().count() != input.options().size()) throw bad("选项不能重复");
        if (input.correctAnswers().stream().distinct().count() != input.correctAnswers().size()) throw bad("标准答案不能重复");
        if (input.type() == QuestionType.SINGLE && input.correctAnswers().size() != 1) throw bad("单选题只能有一个答案");
        if (input.type() == QuestionType.TRUE_FALSE && input.correctAnswers().size() != 1) throw bad("判断题只能有一个答案");
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw bad("题目答案格式不合法"); }
    }

    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message); }
    private ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", message); }
    private ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有操作该题库的权限"); }
}
