package com.allen.cloud.practice;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PracticeController {

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    public record CreateSessionRequest(Long paperId, String clientToken) {
    }

    public record SubmitRequest(Map<Long, String> answers) {
    }

    /**
     * 用户身份来自网关透传的 X-User-Id。
     * 服务不信任请求体里的 userId——如果允许客户端传 userId，
     * 任何人都能替别人交卷。这就是"边界鉴权 + 服务内授权"的分工。
     */
    @PostMapping("/api/practice/sessions")
    public PracticeService.SessionView create(@RequestBody CreateSessionRequest request,
                                              @RequestHeader("X-User-Id") Long userId) {
        String token = request.clientToken() == null || request.clientToken().isBlank()
                ? "auto:" + userId + ":" + request.paperId()
                : request.clientToken();
        return practiceService.createSession(request.paperId(), userId, token);
    }

    @PostMapping("/api/practice/sessions/{sessionId}/submit")
    public PracticeService.SubmitView submit(@PathVariable Long sessionId,
                                             @RequestBody SubmitRequest request) {
        return practiceService.submit(sessionId, request.answers());
    }

    @GetMapping("/api/practice/sessions/{sessionId}")
    public PracticeService.SessionView get(@PathVariable Long sessionId,
                                           @RequestHeader("X-User-Id") Long userId) {
        return practiceService.getSession(sessionId, userId);
    }
}
