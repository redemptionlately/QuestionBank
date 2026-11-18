# book53 第四章数组、广义表和串背诵书重建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 book53（《数据结构与算法》2024版第四章 数组、广义表和串 复习知识点考点汇总.pdf，DB 原始 116 节）按 §7.7 背诵卡政策 + §7.9 渲染验收修到 `已检查2026-09-17`（逐题审 + 浏览器验全过）。

**Architecture:** 以共享记忆中已确认的 110 节无损闸门为主线；另一并行会话曾写入 21 节版，本计划先备份该现状，再恢复并落库 110 节版。全程不碰原始后端主包，浏览器用常驻探针做 DOM 断言。

**Tech Stack:** Spring Boot 3.4 extra 接口、Python 3（UTF-8 证据脚本）、playwright-core + chrome-153 常驻探针。

## Global Constraints

- 不改原始后端主包；本计划不改 Java 代码。
- `output/` 证据不入库，构建产物不入库，无明确提交指令不 commit。
- 前言/适用人群/章节核心保持 `RECITE`，由前端 `isMetaTitle` 渲染为 note；后端只接受 `CHAPTER/RECITE`。
- 每个新节标题独立可读；正文必须来自 `extractedText` 的既有片段或已验证恢复串，禁止补写、改写或编造答案。
- 代码块必须加 ` ```c ` fence，且代码行保持源文顺序。
- 结构性标题（如只有标题没有正文的“三、理解案例汇总”）不单独造卡，不视为知识内容。
- PUT 前必须先落盘 `output/_b53_new_sections.json`、`output/_b53_lossless.txt`、`output/_b53_changes.txt`。
- 浏览器验收 A-F 全绿，30 分钟监督窗口零问题后才打标 `已检查2026-09-17`。

## 现状基线

- `output/_b53_sections.json`：原始 116 节，全部 `RECITE`。
- `output/_b53_state.json`：110 节重建草案，含 18 组移动、2 张新卡、1 条恢复串、2 次合并。
- `output/_gate53.py`：最终无损闸门，输出 110 节、3 个代码卡、标题唯一且语义重叠全过。
- `output/_b53_new_sections.json`：110 节 PUT 体。
- `.workbuddy/memory/2026-09-18.md`：共享记忆确认当前任务为“116 节 → 110 张背诵卡”。
- 并行冲突：另一会话曾将 DB 写成 21 节版；本计划以 110 节版为准，先备份 21 节现状再恢复 110 节版。

---

### Task 1: 运行最终无损闸门

**Files:**
- Create: `output/_b53_new_sections.json`
- Create: `output/_b53_put_body.json`
- Create: `output/_b53_lossless.txt`
- Create: `output/_b53_changes.txt`

**Steps:**

- [x] 复用 `output/_b53_state.json`，不重跑生成脚本，避免引入新偏差。
- [x] 执行 `python output/_gate53.py`。
- [x] 断言输出末行为 `GATE53 ALL PASS`。
- [x] 断言 `output/_b53_lossless.txt` 末行为 `LOSSLESS=PASS`。
- [x] 断言新节数为 110，标题唯一且裸碎片标题清零。

### Task 2: 备份并行现状并恢复 110 节版

**Files:**
- Create: `.workbuddy/backups/book53_sections_21_before_restore.json`
- Create: `.workbuddy/backups/book53_sections_116_original.json`
- Modify: DB `book_section`（bookId=53）
- Create: `output/_b53_put_resp.json`
- Create: `output/_b53_sections_after.json`
- Create: `output/_b53_put_verify.txt`

**Steps:**

- [ ] 备份当前 DB 21 节到 `.workbuddy/backups/book53_sections_21_before_restore.json`。
- [ ] 备份原始 116 节基线到 `.workbuddy/backups/book53_sections_116_original.json`。
- [ ] 用 ADMIN token 发起 `PUT /api/subjects/6/books/53/sections`，请求体来自 `output/_b53_put_body.json`。
- [ ] HTTP 必须为 200，返回节数为 110。
- [ ] 用 student token 重新 GET，写 `output/_b53_sections_after.json`。
- [ ] 校验节数、标题、正文、kind 与草案全等，末行输出 `PUT_VERIFY=PASS`。

### Task 3: 浏览器 DOM 验收

**Files:**
- Create: `output/_b53_browser.cjs`
- Create: `output/_b53_browser.log`
- Create: `output/_b53_browser.json`

**Steps:**

- [ ] 进站路径固定：`#/dashboard` → `#welDirect` → `#/subjects/6` → 打开 book53。
- [ ] A：阅读器打开，节数与 DB 新节数一致，碎片标题清零。
- [ ] B：任选一张卡验证手风琴打开/收起往返。
- [ ] C：至少 3 个代码节内存在 `pre`，且无裸段落代码。
- [ ] D：前言、适用人群、章节核心渲染为 note，不当问答卡。
- [ ] E：正文无 `\uFFFD` 乱码。
- [ ] F：`pageerrors=[]` 且 `httpErrors=[]`。
- [ ] 输出 `BROWSER=6/6 PASS`。

### Task 4: 打标 + 监督窗口

**Files:**
- Modify: `C:\Study\数据结构与算法复习\数据结构与算法复习\章节拆分知识点综述\已沉淀.txt`
- Create: `output/_supervise53.py`
- Create: `output/_supervise53.log`
- Create: `output/_b53_done.txt`

**Steps:**

- [ ] 将 book53 行更新为 `book53 110(116→110源文级重切) | 已检查2026-09-17`。
- [ ] 30 分钟内每 5 分钟采样：8080 health、book53 节数、代码节数、note 数。
- [ ] 终轮复跑浏览器探针，仍需 6/6 PASS。
- [ ] 汇总 `output/_b53_done.txt`，列明源文对齐、PUT、浏览器、监督证据。

