package com.allen.cloud.practice;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.allen.cloud.common.PaperSnapshot;

@Service
public class PracticeService {

    private final PracticeSessionRepository sessions;
    private final PracticeItemRepository items;
    private final BankSnapshotGateway bankGateway;

    public PracticeService(PracticeSessionRepository sessions, PracticeItemRepository items,
                           BankSnapshotGateway bankGateway) {
        this.sessions = sessions;
        this.items = items;
        this.bankGateway = bankGateway;
    }

    public record SessionView(Long sessionId, Long paperId, String status, List<Long> questionIds) {
    }

    public record SubmitView(Long sessionId, int score, int total, List<ItemResult> details) {
    }

    public record ItemResult(Long questionId, boolean correct, String correctAnswer) {
    }

    /**
     * 建会话：先按 clientToken 幂等查，再拉快照。
     *
     * <p>顺序不能反。若先拉快照再查幂等，重试的第二次请求会白白多打一次 bank-service，
     * 下游压力随重试次数放大——这正是网关限流 + 调用方重试最容易制造雪崩的地方。
     */
    @Transactional
    public SessionView createSession(Long paperId, Long userId, String clientToken) {
        return sessions.findByClientToken(clientToken)
                .map(existing -> new SessionView(existing.getId(), existing.getPaperId(), existing.getStatus(),
                        items.findBySessionIdOrderByOrdinalAsc(existing.getId()).stream()
                                .map(PracticeItem::getQuestionId).toList()))
                .orElseGet(() -> {
                    PaperSnapshot snapshot = bankGateway.fetchSnapshot(paperId);
                    PracticeSession session = sessions.save(
                            new PracticeSession(paperId, userId, clientToken, snapshot.items().size()));
                    int ordinal = 0;
                    for (PaperSnapshot.Item item : snapshot.items()) {
                        items.save(new PracticeItem(session.getId(), item.questionId(), ordinal++,
                                normalizeAnswer(item.answer())));
                    }
                    return new SessionView(session.getId(), paperId, session.getStatus(),
                            snapshot.items().stream().map(PaperSnapshot.Item::questionId).toList());
                });
    }

    /**
     * 提交判分。幂等：已提交的会话再次提交直接返回上次结果，不重复计分。
     *
     * <p>判分完全在本库完成（建会话时已把标准答案固化到 practice_item），
     * 所以这一步不依赖 bank-service——下游挂了，已开会话照样能交卷。
     */
    @Transactional
    public SubmitView submit(Long sessionId, Map<Long, String> answers) {
        PracticeSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        List<PracticeItem> practiceItems = items.findBySessionIdOrderByOrdinalAsc(sessionId);

        if ("SUBMITTED".equals(session.getStatus())) {
            return toView(session, practiceItems);
        }

        int score = 0;
        for (PracticeItem item : practiceItems) {
            item.grade(answers.get(item.getQuestionId()));
            items.save(item);
            if (item.isCorrect()) {
                score++;
            }
        }
        session.finish(score);
        sessions.save(session);
        return toView(session, practiceItems);
    }

    @Transactional(readOnly = true)
    public SessionView getSession(Long sessionId, Long userId) {
        PracticeSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        // 水平越权防护：不是自己的会话直接 403，而不是返回空对象让调用方猜
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查看自己的会话");
        }
        return new SessionView(session.getId(), session.getPaperId(), session.getStatus(),
                items.findBySessionIdOrderByOrdinalAsc(sessionId).stream()
                        .map(PracticeItem::getQuestionId).toList());
    }

    private SubmitView toView(PracticeSession session, List<PracticeItem> practiceItems) {
        return new SubmitView(session.getId(), session.getScore(), practiceItems.size(),
                practiceItems.stream()
                        .map(i -> new ItemResult(i.getQuestionId(), i.isCorrect(), i.getCorrectAnswer()))
                        .toList());
    }

    /**
     * 入库前先把标准答案规范化，和 {@link PracticeItem#grade} 用同一套规则。
     * 否则"答案 A,B" 与 "B,A" 会被判成不同答案。
     */
    private static String normalizeAnswer(String raw) {
        if (raw == null) {
            return "";
        }
        return java.util.Arrays.stream(raw.replaceAll("\\s+", "").split(","))
                .sorted()
                .collect(Collectors.joining(","))
                .toLowerCase(java.util.Locale.ROOT);
    }
}
