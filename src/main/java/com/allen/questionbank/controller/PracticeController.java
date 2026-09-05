package com.allen.questionbank.controller;

import com.allen.questionbank.common.CurrentUser;
import com.allen.questionbank.entity.PracticeSession;
import com.allen.questionbank.entity.PracticeStatus;
import com.allen.questionbank.entity.SubmissionItem;
import com.allen.questionbank.service.PracticeService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('STUDENT')")
public class PracticeController {
    private final PracticeService service;

    public PracticeController(PracticeService service) { this.service = service; }

    @PostMapping("/practices")
    public PracticeCreated create(@Valid @RequestBody CreatePracticeRequest request) {
        PracticeSession session = service.create(CurrentUser.require(), request.paperVersionId());
        return new PracticeCreated(session.getId(), session.getPaperVersionId(), session.getStatus(), session.getCreatedAt());
    }

    @PutMapping("/practices/{sessionId}/answers/{questionId}")
    public AnswerView save(@PathVariable Long sessionId, @PathVariable Long questionId, @Valid @RequestBody SaveAnswerRequest request) {
        SubmissionItem item = service.saveAnswer(CurrentUser.require(), sessionId, questionId, request.answer());
        return new AnswerView(item.getQuestionVersionId(), item.getAnswerJson());
    }

    @PostMapping("/practices/{sessionId}/submit")
    public PracticeService.SubmitResult submit(@PathVariable Long sessionId, @RequestHeader("Idempotency-Key") String key) {
        return service.submit(CurrentUser.require(), sessionId, key);
    }

    @GetMapping("/practices/{sessionId}")
    public PracticeService.PracticeView view(@PathVariable Long sessionId) { return service.view(CurrentUser.require(), sessionId); }

    @GetMapping("/wrong-questions")
    public List<WrongQuestionView> wrongQuestions() {
        return service.wrongQuestions(CurrentUser.require()).stream()
                .map(w -> new WrongQuestionView(w.getQuestionVersionId(), w.getWrongCount(), w.getLastWrongAt())).toList();
    }

    public record CreatePracticeRequest(@NotNull Long paperVersionId) {}
    public record SaveAnswerRequest(@NotNull JsonNode answer) {}
    public record PracticeCreated(Long id, Long paperVersionId, PracticeStatus status, Instant createdAt) {}
    public record AnswerView(Long questionId, String answerJson) {}
    public record WrongQuestionView(Long questionId, int wrongCount, Instant lastWrongAt) {}
}
