# book51 第五章树与二叉树背诵书重建 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 book51（《数据结构与算法》2024版第五章 树与二叉树 复习知识点考点汇总.pdf，DB 166 节）按 §7.7 背诵卡政策 + §7.9 渲染验收修到 `已检查2026-09-16`（逐题审 + 浏览器验全过）。

**Architecture:** 以 `book.extractedText` 为唯一真源重建节，按源文标题级切成 47 张卡；结构性空标题不造卡，代码块加 ` ```c ` fence。先在 `output/` 生成草案并过“源文行级覆盖 + 代码 fence + 无新增知识字符”闸门，再 ADMIN PUT 全量换节，最后浏览器 DOM 验收和 30 分钟监督。

**Tech Stack:** Spring Boot 3.4 extra 接口、Python 3（UTF-8 证据脚本）、playwright-core + chrome-153 常驻探针。

## Global Constraints

- 不改原始后端主包；本计划不改 Java 代码。
- `output/` 证据不入库，构建产物不入库，无明确提交指令不 commit。
- 前言/适用人群/章节核心保持 `RECITE`，由前端 `isMetaTitle` 渲染为 note；后端只接受 `CHAPTER/RECITE`。
- 每个新节标题独立可读；正文必须来自 `extractedText` 的连续片段，禁止补写、改写或编造答案。
- 代码块必须加 ` ```c ` fence，且代码行保持源文顺序。
- 结构性标题（如只有标题没有正文的“三、理解案例汇总”）不单独造卡，不视为知识内容。
- PUT 前必须先落盘 `output/_b51_rebuild.py`、`output/_b51_new_sections.json`、`output/_b51_lossless.txt`、`output/_b51_changes.txt`。
- 浏览器验收 A-F 全绿，30 分钟监督窗口零问题后才打标 `已检查2026-09-16`。

## 现状基线

- `output/_b51_sections.json`：166 节，全部 `RECITE`。
- `output/_b51_detail.json`：源文 947 行，21 页，图片 21 张，内嵌图 0。
- `output/_b51_subject.json`：关联卷 223516（58 题）与 223502（140 题）。
- `output/_b51_paper_223516_quality.json`：58 题，零问题。
- `output/_b51_paper_223502_quality.json`：140 题，零问题。
- `output/_research-book51-20260917.log`：SuperMemo 与 CommonMark 外部依据已落盘。

---

### Task 1: 生成源文级新节草案

**Files:**
- Create: `output/_b51_rebuild.py`
- Create: `output/_b51_new_sections.json`
- Create: `output/_b51_lossless.txt`
- Create: `output/_b51_changes.txt`

**Steps:**

- [x] 读取 `output/_b51_detail.json` 的 `extractedText`，按 1-based 行号切段。
- [x] 新节目标 47 张：前言、适用人群、章节核心、树基础、二叉树、遍历、构建、堆、优先队列、森林转换、哈夫曼树、考点总结。
- [x] 每节标题用独立可读问句；正文保留源文标题行和后续正文，不改知识字符。
- [x] 对 17 个代码行区间自动插入 ` ```c ` 和 ` ``` `，代码内容逐字保留。
- [x] 输出 `sections_new`、`sections_put`、`lossless`、`changes` 四个文件。

### Task 2: 无损校验闸门

**Files:**
- Test: `output/_b51_lossless.txt`

**Steps:**

- [x] 校验每个新节正文对应的源文区间不重叠。
- [x] 校验除 meta 前三节外，新节标题不参与源文行计数，正文行集合覆盖源文知识行。
- [x] 校验代码 fence 成对出现，且去掉 fence 后与源文代码行逐字一致。
- [x] 校验没有新增中文知识字符；只允许标题问句、` ```c `、` ``` ` 和空行。
- [x] `output/_b51_lossless.txt` 末行为 `LOSSLESS=PASS`。

### Task 3: ADMIN PUT 全量换节

**Files:**
- Modify: DB `book_section`（bookId=51）
- Create: `output/_b51_put_resp.json`
- Create: `output/_b51_sections_after.json`
- Create: `output/_b51_put_verify.txt`

**Steps:**

- [x] 用本机 ADMIN token 发起 `PUT /api/subjects/6/books/51/sections`。
- [x] 请求体为 `{"sections": [...]}`，来源 `output/_b51_new_sections.json`。
- [x] HTTP 为 200，返回节数与新节数一致。
- [x] 用 student token 重新 GET，写 `output/_b51_sections_after.json`。
- [x] 校验节数、标题、正文、kind 与草案全等，末行输出 `PUT_VERIFY=PASS`。

### Task 4: 浏览器 DOM 验收

**Files:**
- Create: `output/_b51_browser.cjs`
- Create: `output/_b51_browser.log`
- Create: `output/_b51_browser.json`

**Steps:**

- [x] 进站路径固定：`#/dashboard` → `#welDirect` → `#/subjects/6` → 打开 book51。
- [x] A：阅读器打开，节数与 DB 新节数一致，碎片标题清零。
- [x] B：任选一张卡验证手风琴打开/收起往返。
- [x] C：8 个代码节内存在 `pre`，共 14 个代码标签，无裸段落代码。
- [x] D：前言、适用人群、章节核心渲染为 note，不当问答卡。
- [x] E：正文无 `\uFFFD` 乱码。
- [x] F：`pageerrors=[]` 且 `httpErrors=[]`。
- [x] 输出 `BROWSER=6/6 PASS`。

### Task 5: 打标 + 监督窗口

**Files:**
- Modify: `C:\Study\数据结构与算法复习\数据结构与算法复习\章节拆分知识点综述\已沉淀.txt`
- Create: `output/_supervise51.py`
- Create: `output/_supervise51.log`
- Create: `output/_b51_done.txt`

**Steps:**

- [x] 将 book51 行更新为 `book51 47(166→47源文级重切) | 已检查2026-09-16`。
- [x] 30 分钟内每 5 分钟采样：8080 health、book51 节数、代码节数、note 数。
- [x] 终轮复跑浏览器探针，仍需 6/6 PASS。
- [x] 汇总 `output/_b51_done.txt`，列明源文对齐、PUT、浏览器、监督证据。
