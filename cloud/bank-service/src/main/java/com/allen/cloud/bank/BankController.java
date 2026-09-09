package com.allen.cloud.bank;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.allen.cloud.common.PaperSnapshot;

@RestController
public class BankController {

    private static final Logger log = LoggerFactory.getLogger(BankController.class);

    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    public record CreateBankRequest(String name) {
    }

    public record DraftRequest(String prompt, String type, List<String> options, String answer) {
    }

    public record BankResponse(Long id, String name, Long ownerId) {
    }

    public record PublishResponse(Long paperId, int version, int itemCount) {
    }

    /**
     * 角色由网关验签后透传（X-User-Role）。这里再做一次判断，不把授权完全外包给网关——
     * 网关是边界，服务自己是最后一道防线：内网直连可以绕过网关，X-User-Role 头本身
     * 也可以伪造，服务端必须独立校验。审计修复：addQuestion/publish/snapshot 原先
     * 漏了这层校验，任何持 token 的学生都能加题、发布任意题库、拉到含答案的快照。
     */
    private static void requireTeacher(String role) {
        if (role == null || !(role.equals("TEACHER") || role.equals("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅教师可执行该操作");
        }
    }

    /** 所有权校验：非 ADMIN 只能操作自己的题库。题库不存在按 404 抛出，不向无权者泄露存在性。 */
    private void requireOwnership(String role, Long userId, Long bankId) {
        if ("ADMIN".equals(role)) {
            return;
        }
        QuestionBank bank = bankService.getBank(bankId);
        if (!bank.getOwnerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能操作自己的题库");
        }
    }

    @PostMapping("/api/banks")
    public ResponseEntity<BankResponse> createBank(@RequestBody CreateBankRequest request,
                                                  @RequestHeader("X-User-Id") Long userId,
                                                  @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireTeacher(role);
        QuestionBank bank = bankService.createBank(request.name(), userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new BankResponse(bank.getId(), bank.getName(), bank.getOwnerId()));
    }

    @PostMapping("/api/banks/{bankId}/questions")
    public ResponseEntity<Void> addQuestion(@PathVariable Long bankId, @RequestBody DraftRequest request,
                                            @RequestHeader("X-User-Id") Long userId,
                                            @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireTeacher(role);
        requireOwnership(role, userId, bankId);
        bankService.addDraft(bankId, request.prompt(), request.type(),
                request.options() == null ? List.of() : request.options(), request.answer());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/api/banks/{bankId}/publish")
    public PublishResponse publish(@PathVariable Long bankId,
                                   @RequestHeader("X-User-Id") Long userId,
                                   @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireTeacher(role);
        requireOwnership(role, userId, bankId);
        PaperVersion version = bankService.publish(bankId);
        return new PublishResponse(version.getId(), version.getVersion(), version.getItemCount());
    }

    @GetMapping("/api/banks")
    public List<BankResponse> list() {
        return bankService.listBanks().stream()
                .map(b -> new BankResponse(b.getId(), b.getName(), b.getOwnerId()))
                .toList();
    }

    /**
     * 公开快照接口只对教师/管理员开放：快照里带标准答案（判分在 practice 侧完成，
     * 答案必须随快照下发，见 PaperSnapshot 的注释），但学生侧的取题路径是 practice
     * 建会话时走 /internal 快照（服务间 Feign 调用），不该存在从公网直接拿答案的通道。
     * 审计修复：原先学生 token 可直接调本接口把整卷答案拉走。
     */
    @GetMapping("/api/banks/papers/{paperId}/snapshot")
    public PaperSnapshot snapshot(@PathVariable Long paperId,
                                  @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireTeacher(role);
        return bankService.snapshot(paperId);
    }

    /** 供 practice-service 通过 Feign 调用的内部接口 */
    @GetMapping("/internal/papers/{paperId}/snapshot")
    public PaperSnapshot internalSnapshot(@PathVariable Long paperId) {
        PaperSnapshot snapshot = bankService.snapshot(paperId);
        // 日志-链路关联锚点：请求路径的 INFO 日志（带 MDC traceId/spanId），证据脚本
        // 用它做「日志行 ↔ collector 中 span」互查。OutboxRelay 在调度线程无请求上下文，
        // 不能当请求路径锚点（run3 实测调度线程日志抢先命中导致误抓）。
        log.info("[snapshot] paperId={} items={}", paperId, snapshot.items().size());
        return snapshot;
    }
}
