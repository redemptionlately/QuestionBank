package com.allen.questionbank.controller;

import com.allen.questionbank.auth.ApiTokenFilter;
import com.allen.questionbank.common.CurrentUser;
import com.allen.questionbank.entity.*;
import com.allen.questionbank.service.BankService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
public class BankController {
    private final BankService service;

    public BankController(BankService service) { this.service = service; }

    @PostMapping("/admin/banks")
    @PreAuthorize("hasRole('ADMIN')")
    public BankResponse createBank(@Valid @RequestBody CreateBankRequest request) {
        QuestionBank bank = service.createBank(CurrentUser.require(), request.name(), request.description());
        return new BankResponse(bank.getId(), bank.getName(), bank.getDescription());
    }

    @PostMapping("/admin/banks/{bankId}/versions")
    @PreAuthorize("hasRole('ADMIN')")
    public PaperResponse createDraft(@PathVariable Long bankId, @Valid @RequestBody CreatePaperRequest request) {
        PaperVersion paper = service.createDraft(CurrentUser.require(), bankId, request.title(), request.questions());
        return toPaper(paper);
    }

    @PostMapping("/admin/versions/{paperId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public PaperResponse publish(@PathVariable Long paperId) {
        return toPaper(service.publish(CurrentUser.require(), paperId));
    }

    @GetMapping("/papers/published")
    @PreAuthorize("hasAnyRole('ADMIN','STUDENT')")
    public List<PaperResponse> published() { return service.published().stream().map(BankController::toPaper).toList(); }

    @GetMapping("/papers/{paperId}")
    @PreAuthorize("hasAnyRole('ADMIN','STUDENT')")
    public PaperDetailResponse detail(@PathVariable Long paperId) {
        PaperVersion paper = service.requirePublished(paperId);
        List<QuestionResponse> items = service.questions(paperId).stream()
                .map(q -> new QuestionResponse(q.getId(), q.getQuestionNo(), q.getPrompt(), q.getQuestionType(),
                        q.getOptionsJson(), q.getScore(), q.getExplanation())).toList();
        return new PaperDetailResponse(toPaper(paper), items);
    }

    private static PaperResponse toPaper(PaperVersion paper) {
        return new PaperResponse(paper.getId(), paper.getBankId(), paper.getVersionNo(), paper.getTitle(), paper.getStatus(), paper.getPublishedAt());
    }

    public record CreateBankRequest(@NotBlank @Size(max = 160) String name, @Size(max = 500) String description) {}
    public record CreatePaperRequest(@NotBlank @Size(max = 200) String title, @NotEmpty List<@Valid QuestionInput> questions) {}
    public record QuestionInput(@NotBlank String prompt, @NotNull QuestionType type, @NotEmpty List<@NotBlank String> options,
                                @NotEmpty List<@NotBlank String> correctAnswers, @Min(1) int score, String explanation) {}
    public record BankResponse(Long id, String name, String description) {}
    public record PaperResponse(Long id, Long bankId, int versionNo, String title, String status, Instant publishedAt) {}
    public record PaperDetailResponse(PaperResponse paper, List<QuestionResponse> questions) {}
    public record QuestionResponse(Long id, int questionNo, String prompt, QuestionType type, String optionsJson, int score, String explanation) {}
}
