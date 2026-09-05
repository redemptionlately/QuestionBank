package com.allen.questionbank.service;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.entity.*;
import com.allen.questionbank.repository.*;
import com.allen.questionbank.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class PracticeService {
    private final PracticeSessionRepository sessions;
    private final SubmissionItemRepository submissions;
    private final WrongQuestionRepository wrongQuestions;
    private final QuestionVersionRepository questions;
    private final BankService banks;
    private final ObjectMapper objectMapper;

    public PracticeService(PracticeSessionRepository sessions, SubmissionItemRepository submissions,
                           WrongQuestionRepository wrongQuestions, QuestionVersionRepository questions,
                           BankService banks, ObjectMapper objectMapper) {
        this.sessions = sessions; this.submissions = submissions; this.wrongQuestions = wrongQuestions;
        this.questions = questions; this.banks = banks; this.objectMapper = objectMapper;
    }

    @Transactional
    public PracticeSession create(ApiTokenFilter.AuthPrincipal user, Long paperId) {
        PaperVersion paper = banks.requirePublished(paperId);
        return sessions.save(new PracticeSession(user.userId(), paper.getId()));
    }

    @Transactional
    public SubmissionItem saveAnswer(ApiTokenFilter.AuthPrincipal user, Long sessionId, Long questionId, JsonNode answer) {
        PracticeSession session = requireSession(user, sessionId);
        if (session.getStatus() != PracticeStatus.IN_PROGRESS) throw conflict("练习已提交，答案不可修改");
        QuestionVersion question = requireQuestion(session, questionId);
        String normalized = normalize(answer);
        validateChoices(question, answer);
        SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, questionId)
                .map(existing -> { existing.replaceAnswer(normalized); return existing; })
                .orElseGet(() -> new SubmissionItem(sessionId, questionId, normalized));
        return submissions.save(item);
    }

    @Transactional
    public SubmitResult submit(ApiTokenFilter.AuthPrincipal user, Long sessionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "提交必须携带 Idempotency-Key");
        }
        PracticeSession session = sessions.findByIdForUpdate(sessionId).orElseThrow(() -> notFound("练习不存在"));
        if (!session.getStudentId().equals(user.userId())) throw forbidden();
        if (session.getStatus() == PracticeStatus.SUBMITTED) {
            if (!idempotencyKey.equals(session.getSubmissionKey())) throw conflict("该练习已经使用其他幂等键提交");
            return readResult(session);
        }
        List<QuestionVersion> paperQuestions = banks.questions(session.getPaperVersionId());
        int total = 0;
        List<GradedAnswer> graded = new ArrayList<>();
        for (QuestionVersion question : paperQuestions) {
            SubmissionItem item = submissions.findBySessionIdAndQuestionVersionId(sessionId, question.getId())
                    .orElseGet(() -> new SubmissionItem(sessionId, question.getId(), "[]"));
            boolean correct = canonical(item.getAnswerJson()).equals(canonical(question.getAnswerJson()));
            int score = correct ? question.getScore() : 0;
            item.grade(score, correct);
            submissions.save(item);
            if (!correct) {
                wrongQuestions.findByStudentIdAndQuestionVersionId(user.userId(), question.getId())
                        .ifPresentOrElse(WrongQuestion::markWrong,
                                () -> wrongQuestions.save(new WrongQuestion(user.userId(), question.getId())));
            }
            total += score;
            graded.add(new GradedAnswer(question.getId(), correct, score));
        }
        SubmitResult result = new SubmitResult(sessionId, total, paperQuestions.stream().mapToInt(QuestionVersion::getScore).sum(), graded);
        session.submit(idempotencyKey, total, write(result));
        sessions.save(session);
        return result;
    }

    @Transactional(readOnly = true)
    public PracticeView view(ApiTokenFilter.AuthPrincipal user, Long sessionId) {
        PracticeSession session = requireSession(user, sessionId);
        List<QuestionVersion> paperQuestions = banks.questions(session.getPaperVersionId());
        List<AnswerView> answers = submissions.findBySessionIdOrderByQuestionVersionId(sessionId).stream()
                .map(item -> new AnswerView(item.getQuestionVersionId(), item.getAnswerJson(), item.getScore(), item.isCorrect())).toList();
        return new PracticeView(session.getId(), session.getPaperVersionId(), session.getStatus(), session.getTotalScore(),
                paperQuestions.stream().map(q -> new PracticeQuestion(q.getId(), q.getQuestionNo(), q.getPrompt(), q.getQuestionType(), q.getOptionsJson(), q.getScore())).toList(), answers);
    }

    @Transactional(readOnly = true)
    public List<WrongQuestion> wrongQuestions(ApiTokenFilter.AuthPrincipal user) {
        return wrongQuestions.findByStudentIdOrderByLastWrongAtDesc(user.userId());
    }

    private PracticeSession requireSession(ApiTokenFilter.AuthPrincipal user, Long sessionId) {
        PracticeSession session = sessions.findById(sessionId).orElseThrow(() -> notFound("练习不存在"));
        if (!session.getStudentId().equals(user.userId())) throw forbidden();
        return session;
    }

    private QuestionVersion requireQuestion(PracticeSession session, Long questionId) {
        QuestionVersion question = questions.findById(questionId).orElseThrow(() -> notFound("题目不存在"));
        if (!question.getPaperVersionId().equals(session.getPaperVersionId())) throw conflict("题目不属于当前练习");
        return question;
    }

    private void validateChoices(QuestionVersion question, JsonNode answer) {
        if (!answer.isArray()) throw bad("答案必须是数组，例如 [\"A\"]");
        try {
            List<String> options = new ArrayList<>();
            objectMapper.readTree(question.getOptionsJson()).forEach(value -> options.add(value.asText()));
            for (JsonNode value : answer) if (!options.contains(value.asText())) throw bad("答案包含不属于题目的选项");
        } catch (JsonProcessingException exception) { throw bad("题目选项格式损坏"); }
    }

    private String normalize(JsonNode node) {
        try { return canonical(objectMapper.writeValueAsString(node)); }
        catch (JsonProcessingException exception) { throw bad("答案格式不合法"); }
    }

    private String canonical(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) return node.toString();
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.toString()));
            values.sort(Comparator.naturalOrder());
            return values.toString();
        } catch (JsonProcessingException exception) { throw bad("答案格式不合法"); }
    }

    private String write(SubmitResult result) {
        try { return objectMapper.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private SubmitResult readResult(PracticeSession session) {
        try { return objectMapper.readValue(session.getSubmissionResultJson(), SubmitResult.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message); }
    private ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", message); }
    private ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有访问该练习的权限"); }

    public record GradedAnswer(Long questionId, boolean correct, int score) {}
    public record SubmitResult(Long sessionId, int totalScore, int maxScore, List<GradedAnswer> answers) {}
    public record PracticeQuestion(Long id, int questionNo, String prompt, QuestionType type, String optionsJson, int score) {}
    public record AnswerView(Long questionId, String answerJson, int score, boolean correct) {}
    public record PracticeView(Long id, Long paperVersionId, PracticeStatus status, Integer totalScore,
                               List<PracticeQuestion> questions, List<AnswerView> answers) {}
}
