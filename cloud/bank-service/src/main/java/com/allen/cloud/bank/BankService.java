package com.allen.cloud.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.allen.cloud.common.PaperSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class BankService {

    private final QuestionBankRepository banks;
    private final QuestionDraftRepository drafts;
    private final PaperVersionRepository paperVersions;
    private final PaperItemRepository paperItems;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 题型白名单：与判分侧词表（app 的 QuestionType / PaperSnapshot 注释）保持一致，拼错直接 400 */
    private static final Set<String> ALLOWED_TYPES = Set.of("SINGLE", "MULTIPLE", "TRUE_FALSE");

    public BankService(QuestionBankRepository banks, QuestionDraftRepository drafts,
                       PaperVersionRepository paperVersions, PaperItemRepository paperItems,
                       OutboxEventRepository outbox) {
        this.banks = banks;
        this.drafts = drafts;
        this.paperVersions = paperVersions;
        this.paperItems = paperItems;
        this.outbox = outbox;
    }

    @Transactional
    public QuestionBank createBank(String name, Long ownerId) {
        // 名称是唯一业务入参：空名会让列表接口出现无法辨识的行，在入口拒绝而不是入库后脏数据
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "题库名称不能为空");
        }
        return banks.save(new QuestionBank(name, ownerId));
    }

    @Transactional
    public QuestionDraft addDraft(Long bankId, String prompt, String type, List<String> options, String answer) {
        requireBank(bankId);
        if (type == null || !ALLOWED_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "题型必须是 SINGLE/MULTIPLE/TRUE_FALSE 之一");
        }
        try {
            return drafts.save(new QuestionDraft(bankId, prompt, type, objectMapper.writeValueAsString(options), answer));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选项序列化失败");
        }
    }

    /**
     * 发布新版本。
     *
     * <p>并发控制不靠"查 max(version) 再 +1"——两个请求同时查到同一个 max 就会写重。
     * 真正的兜底是 (bank_id, version) 唯一约束：写重时数据库抛
     * {@link DataIntegrityViolationException}，这里转成 409 让调用方重试，
     * 而不是让两个版本悄悄共用同一个版本号。
     */
    @Transactional
    public PaperVersion publish(Long bankId) {
        requireBank(bankId);
        int nextVersion = paperVersions.findTopByBankIdOrderByVersionDesc(bankId)
                .map(p -> p.getVersion() + 1)
                .orElse(1);
        List<QuestionDraft> draftsOfBank = drafts.findByBankIdOrderById(bankId);

        PaperVersion version;
        try {
            version = paperVersions.saveAndFlush(new PaperVersion(bankId, nextVersion, draftsOfBank.size()));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一题库正在并发发布，请重试");
        }

        int ordinal = 0;
        for (QuestionDraft draft : draftsOfBank) {
            paperItems.save(new PaperItem(version.getId(), draft.getId(), draft.getPrompt(), draft.getType(),
                    draft.getOptionsJson(), draft.getAnswer(), ordinal++));
        }

        // 与上面的业务写入同属一个本地事务：要么都成功，要么一起回滚
        String payload = "{\"bankId\":" + bankId + ",\"paperId\":" + version.getId()
                + ",\"version\":" + version.getVersion() + ",\"itemCount\":" + draftsOfBank.size() + "}";
        outbox.save(new OutboxEvent("paper-published:" + bankId + ":" + version.getId(), "PAPER_PUBLISHED", payload));
        return version;
    }

    @Transactional(readOnly = true)
    public PaperSnapshot snapshot(Long paperId) {
        PaperVersion version = paperVersions.findById(paperId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "试卷版本不存在"));
        List<PaperItem> items = paperItems.findByPaperVersionIdOrderByOrdinalAsc(paperId);
        List<PaperSnapshot.Item> snapshotItems = new ArrayList<>(items.size());
        for (PaperItem item : items) {
            snapshotItems.add(new PaperSnapshot.Item(item.getQuestionId(), item.getType(), item.getPrompt(),
                    parseOptions(item.getOptionsJson()), item.getAnswer()));
        }
        return new PaperSnapshot(version.getId(), version.getBankId(), version.getVersion(),
                version.getPublishedAt(), snapshotItems);
    }

    public List<QuestionBank> listBanks() {
        return banks.findAll();
    }

    /** 供 Controller 做所有权校验：按 id 取题库，不存在按 404（不向无权者泄露存在性） */
    @Transactional(readOnly = true)
    public QuestionBank getBank(Long bankId) {
        return banks.findById(bankId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "题库不存在"));
    }

    private void requireBank(Long bankId) {
        if (!banks.existsById(bankId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题库不存在");
        }
    }

    private List<String> parseOptions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
