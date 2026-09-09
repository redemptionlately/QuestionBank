package com.allen.questionbank.practice;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.auth.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('STUDENT')")
public class PracticeController {
    private final PracticeService service;
    /**
     * Redis 锁协调的提交路径（app.lock.backend=redis 时才有实例）。曾经开了开关
     * Controller 也不走它——组件存在但没接线，条件开关形同虚设。用 ObjectProvider
     * 注入：开关关闭时 bean 不存在，getIfAvailable 返回 null，回退直连 service；
     * 开启时 submit 必须走锁（锁在事务外获取、提交后释放，见 RedisGuardedPracticeSubmitter）。
     */
    private final RedisGuardedPracticeSubmitter guardedSubmitter;

    public PracticeController(PracticeService service,
                              ObjectProvider<RedisGuardedPracticeSubmitter> guardedSubmitter) {
        this.service = service;
        this.guardedSubmitter = guardedSubmitter.getIfAvailable();
    }

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
        ApiTokenFilter.AuthPrincipal user = CurrentUser.require();
        if (guardedSubmitter != null) {
            return guardedSubmitter.submit(user, sessionId, key);
        }
        return service.submit(user, sessionId, key);
    }

    @GetMapping("/practices/{sessionId}")
    public PracticeService.PracticeView view(@PathVariable Long sessionId) { return service.view(CurrentUser.require(), sessionId); }

    @GetMapping("/wrong-questions")
    public PracticeService.WrongBook wrongQuestions(
            @RequestParam(required = false) Integer wrongCountMin,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            Instant wrongSince,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.wrongBook(CurrentUser.require(), wrongCountMin, wrongSince, page, size);
    }

    public record CreatePracticeRequest(@NotNull Long paperVersionId) {}
    public record SaveAnswerRequest(@NotNull JsonNode answer) {}
    public record PracticeCreated(Long id, Long paperVersionId, PracticeStatus status, Instant createdAt) {}
    public record AnswerView(Long questionId, String answerJson) {}
}
