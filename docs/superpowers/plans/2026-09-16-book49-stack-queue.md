# book49 第三章栈和队列背诵书修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 book49（《数据结构与算法》(2024版)第三章 栈和队列 复习知识点考点汇总.pdf，DB 98 节）按 §7.7 背诵卡政策 + §7.9 渲染验收修到 `已检查2026-09-16`（逐题审 + 浏览器验全过）。

**Architecture:** 只读诊断先行（student/student123），修复草案先在 `output/` 生成新节 JSON 并过无损校验，ADMIN 的 PUT 全量换节最后执行，浏览器用常驻探针做 DOM 断言。全程不碰原始后端主包。

**Tech Stack:** Spring Boot 3.4 extra 接口、python 诊断脚本（UTF-8 落盘）、playwright-core + chrome-153 常驻 `output/*.cjs` 探针。

## Global Constraints

- 不改原始后端主包，新功能只进 `app/.../questionbank/extra/`。
- Flyway 开 + `ddl-auto: validate`，已应用迁移永不修改（本计划不涉及迁移）。
- 构建用 `./scripts/mvn.sh`；运行中 jar 禁止 `cp` 覆盖（本计划不构建）。
- `frontend/`、`extra/`、`output/`、`*.log`、`*.jar` 一律不入库；无明确提交指令不 commit，只证据落盘。
- 严禁编造证据/指标；每项修复附 `output/` 可复现证据；30 分钟零问题监督窗口为完成闸口。
- PUT 全量换节必须先过无损校验：新节拼接 ≡ 旧节拼接 + 源文恢复串，恢复串须在 `extracted_text` 逐字存在。
- 中文三件套：MySQL 中文走 `--default-character-set=utf8mb4` + UTF-8 sql 文件，用 `cmd` 重定向落盘，不走 PowerShell 管道；控制台只打 ASCII，详情写 UTF-8 文件再 Read；新写文件中文用 `\u` 转义优先，判据认字节抽查/后端行为/浏览器渲染。
- PowerShell 坑：无 `head`/`tail`；`curl` 会被劫持为 `Invoke-WebRequest`，一律用 `curl.exe`；复杂命令先落脚本文件；curl 加 `--noproxy "*"`。
- 浏览器验证走本机 `playwright-core` + `chrome-153` + `--no-sandbox --disable-gpu`，复用常驻 `output/*.cjs`；DOM 断言 > 截图；hash 路由不用 `waitForURL`；进站路径 `goto #/dashboard` → 点 `#welDirect` → 再改 hash。
- 背诵卡政策（§7.7）：一卡一问、标题独立可读（禁"它/上述"）、答案保持考试完整、总览卡刻意保留、缺答案明写不编、碎片标题必须并回父题、例题/练习/真题尾巴先查卷覆盖再决定去留。
- 渲染验收（§7.9）：代码块必须加 ` ``` ` fence；前言/适用人群/小结/说明类 → `kind=note` 非卡片；有文字层禁整页截图充数；答题页正确答案走块级 `mdLite`（本次不动 exam.js）。
- 沉淀接口（已按 extra 代码核实）：修单题 `PATCH /api/extra/maint/papers/{pid}/questions/{qid}`（`MaintenanceController.java:52`）、全量换节 `PUT /api/subjects/{sid}/books/{bid}/sections`（`SubjectController.java:147`，ADMIN）、质检门禁 `GET /api/ingest/papers/{paperId}/quality`（`IngestionController.java:155`）、读题 `GET /api/extra/questions?paperVersionId=`（`ExtraQuestionController.java:17-22`，STUDENT 可读）。

## 现状基线（已实测，证据在 `output/`）

- `output/_b49_sections.json`：98 节，`kind` 全 `RECITE`；`output/_b49_summary.txt` 全标题清单。
- `output/_b49_detail.json`：book49，PDF 17 页，`extractedText` 18409 字，节拼接 16066 字（ratio 0.874）；`figures=[]`，`images.count=17`。
- `output/_b49_issues.txt`：重名标题 5 组（操作受限×2/对比理解×2/选择题/填空题×4/简答题×4/编程题（专业）×4）；meta 命中 9 节（sortNo 0,1,2,91,92,93,94,95,97）；`FENCE_SORT=[]`（零 fence）；含 C 代码节 6 个（25/29/33/50/56/60，其中 33/50 为误命中 Queue 关键字，实码节为 25/29/56/60）。
- `output/_b49_sample.txt`：sortNo 4/8/25/29/30/31/32/34/38/56/60/64/65/68/84/91/92 正文抽样。
- 已沉淀写 book49 99 节，DB 实 98 节，差 1 节待解释（Task 2 覆盖）。

---

### Task 1: 尾巴题卷覆盖核查（只读）

**Files:**
- Create: `output/_tail49.py`
- Modify: none
- Test: `output/_b49_tail_coverage.txt`（人工判读：覆盖/未覆盖/部分覆盖）

**Interfaces:**
- Consumes: `output/_b49_sections.json`（sortNo 30-32/61-63/81-83/88-90 共 12 节正文）、`student` token（`output/_login.json` 已有）
- Produces: `output/_b49_tail_coverage.txt`（每节一行：`sortNo | 标题 | 卷覆盖结论 | 处置=保留总览卡/并回父题/删除`），供 Task 3 消费

- [ ] **Step 1: 写覆盖核查脚本**

```python
# output/_tail49.py
import urllib.request, json
tok = json.load(open('output/_login.json', encoding='utf-8'))['token']
h = {'Authorization': 'Bearer ' + tok}
def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=15).read().decode('utf-8')
secs = json.load(open('output/_b49_sections.json', encoding='utf-8'))
tails = [s for s in secs if s['sortNo'] in (30,31,32,61,62,63,81,82,83,88,89,90)]
qs = json.loads(get('http://127.0.0.1:8080/api/extra/questions?paperVersionId=223503'))
prompts = [q.get('prompt','') for q in qs]
with open('output/_b49_tail_coverage.txt', 'w', encoding='utf-8') as f:
    f.write('paper223503 count=' + str(len(qs)) + '\n')
    for s in tails:
        body = s.get('body','')
        keys = [k for k in ['\u987a\u5e8f\u6808', '\u94fe\u5f0f\u6808', '\u961f\u6ee1', '\u5047\u6ea2\u51fa', '\u5faa\u73af\u961f\u5217', '\u94fe\u5f0f\u961f\u5217', '\u5165\u6808', '\u51fa\u6808', '\u5165\u961f', '\u51fa\u961f'] if k in body]
        hit = sum(1 for p in prompts if any(k in p for k in keys))
        f.write('sortNo=%d title=%s keys=%s paper_hits=%d\n' % (s['sortNo'], s['title'], '/'.join(keys), hit))
    f.write('quality=' + get('http://127.0.0.1:8080/api/ingest/papers/223503/quality') + '\n')
print('wrote coverage')
```

- [ ] **Step 2: 运行脚本**

Run: `python output/_tail49.py`
Expected: 输出 `wrote coverage`，生成 `output/_b49_tail_coverage.txt`（13 行：1 行卷计数 + 12 节）

- [ ] **Step 3: 人工判读并写处置结论**

Run: Read `output/_b49_tail_coverage.txt`，逐节判定：
- 尾巴节是考点罗列（非真题原文）且卷内有同考点真题 → 处置=保留为总览卡（改独立可读标题，如 `栈考点总览：选择/填空方向`），不删除
- 尾巴节与卷内真题逐字重复 → 处置=删除（卷已覆盖，禁重复造卡）
- 无命中 → 处置=保留并标注 `未在卷223503覆盖`

将结论追加写入 `output/_b49_tail_coverage.txt` 末尾（python 追加，UTF-8），格式 `DECISION sortNo=30 ...`。
Expected: 12 节每节都有 DECISION 行

- [ ] **Step 4: 证据落盘（不 commit）**

Run: `python -c "import json; d=open('output/_b49_tail_coverage.txt',encoding='utf-8').read(); assert d.count('DECISION')==12, d.count('DECISION'); print('decisions=12 ok')"`
Expected: `decisions=12 ok`

---

### Task 2: 源文对齐抽查 + 98/99 差一节解释

**Files:**
- Create: `output/_align49.py`
- Modify: none
- Test: `output/_b49_source_align.txt`

**Interfaces:**
- Consumes: `output/_b49_sections.json`、`output/_b49_detail.json`（`extractedText`）
- Produces: `output/_b49_source_align.txt`（逐项 `OK/缺失` + 98/99 解释），供 Task 3 消费（恢复串清单）

- [ ] **Step 1: 写对齐脚本**

```python
# output/_align49.py
import json
secs = json.load(open('output/_b49_sections.json', encoding='utf-8'))
det = json.load(open('output/_b49_detail.json', encoding='utf-8'))
ex = det.get('extractedText', '')
probes = {
  'code25_push': 'S.top = -1',
  'code25_maxsize': 'MAXSIZE',
  'code29_linkstack': 'LinkStack',
  'code56_front': 'Q.front',
  'code60_rear': 'Q.rear',
  'tbl64': '\u53d7\u9650\u7ebf\u6027\u8868',
  'app84_recursion': '\u9012\u5f52\u5de5\u4f5c\u6808',
  'app84_bracket': '\u62ec\u53f7\u5339\u914d',
  'tail32_sec2': '\u7b2c\u4e8c\u8282 \u961f\u5217',
}
with open('output/_b49_source_align.txt', 'w', encoding='utf-8') as f:
    f.write('extracted_chars=%d sec_count=%d\n' % (len(ex), len(secs)))
    for name, s in probes.items():
        f.write('%s | in_extracted=%s\n' % (name, s in ex))
    bodies = ''.join(s.get('title','') + s.get('body','') for s in secs)
    f.write('sec_chars=%d\n' % len(bodies))
    sortnos = sorted(s['sortNo'] for s in secs)
    f.write('sortno_min=%d max=%d contiguous=%s\n' % (sortnos[0], sortnos[-1], sortnos == list(range(sortnos[0], sortnos[-1]+1))))
    f.write('note99: chendian_says_99_vs_db_98\n')
print('wrote align')
```

- [ ] **Step 2: 运行并判读**

Run: `python output/_align49.py`，再 Read `output/_b49_source_align.txt`
Expected: 9 个探针全部 `in_extracted=True`（若有 False，该节正文疑似编造或转写失真，必须在 Task 3 前逐字核对源 PDF，不得直接采用）；`contiguous` 若为 False，把缺失的 sortNo 记为"解析漏节"，即 98/99 差一的解释；若为 True，则差一是已沉淀笔误，Task 6 打标时以 DB 98 为准并注明

- [ ] **Step 3: 代码顺序错乱取证（sortNo 25/60）**

`output/_b49_sample.txt` 已显示 sortNo25 的 `Push` 代码排在 `#define/typedef` 之前、sortNo60 的 `typedef/struct` 被挤进 `DeQueue` 中间。若 Step 2 探针全过，在 `output/_b49_source_align.txt` 末尾追加三行（python 追加 UTF-8）：`REORDER sortNo=25 ...`、`REORDER sortNo=60 ...`，写明源文中的正确顺序（以 `extractedText` 出現先后为准，起止锚点各 8 字），Task 3 按此恢复顺序。
Expected: 追加行数 ≥ 2

---

### Task 3: 生成新节 JSON 草案 + 无损校验（不写库）

**Files:**
- Create: `output/_build49.py`、`output/_b49_new_sections.json`、`output/_b49_lossless.txt`、`output/_b49_changes.txt`
- Modify: none（不调 PUT）
- Test: 无损校验断言全绿

**Interfaces:**
- Consumes: `output/_b49_sections.json`、`output/_b49_tail_coverage.txt`（Task 1 DECISION）、`output/_b49_source_align.txt`（Task 2 恢复串+REORDER）
- Produces: `output/_b49_new_sections.json`（`[{title, body, kind, sortNo}]`，PUT 请求体 `{"sections": [...]}` 的 sections 部分）、`output/_b49_lossless.txt`（校验结果）、`output/_b49_changes.txt`（逐节变更表），供 Task 4 消费

修复规则（逐条落地 §7.7 + §7.9，不编造答案/解析）：

1. `kind` 修正：sortNo 0（前言）、1（适用人群）、2（章节核心）、91（应用场景对照）、92/93/94（小结×3）、95（关键考点梳理）、97（学习建议）→ `kind=note`；其余问答卡保持 `RECITE`。
2. 碎片标题并回父题/改独立可读：`操作受限`(4)→`栈的操作受限：仅栈顶可入出`并入栈定义卡组；`操作受限`(34)→`队列的操作受限：队尾入队头出`；`对比理解`(8)→并入栈逻辑结构卡；`对比理解`(38)→并入队列逻辑结构卡；`数组下标`(68)→`共享栈的数组下标与栈底位置`并入共享栈卡组；`双端受限`(65)→并入 sortNo64 对比总览卡；`队满条件`(53)→`循环队列的队满条件`；`双向电梯的乘客进出`(76)→`双端队列：双向电梯类比`。
3. 重名考试方向卡加前缀：4 组 `选择题/填空题` → `栈-选择/填空方向`、`队列-选择/填空方向`、`扩展结构-选择/填空方向`、`应用-选择/填空方向`；`简答题`/`编程题（专业）` 同理加 `栈-/队列-/扩展-/应用-` 前缀。
4. 代码加 fence 并恢复顺序：sortNo 25/29/56/60 正文中的 C 代码段用 ` ```c ` fence 包裹（Task 2 REORDER 顺序恢复后：`#define/typedef` 在前，`Init/Push/Pop/GetTop` 在后）；`小白案例` 叙述留在 fence 外。判分/渲染只认 fence，不改代码字符本身（源文笔误照录只报告）。
5. 问答归位：sortNo32 body 尾部的 `第二节 队列` 及快递盒表格移回所属队列操作卡（sortNo47-50 组）；sortNo60 body 尾部的取号机表格移回队列操作卡；移动前后逐字一致。
6. 对比表合并：sortNo64 + 65 合为一张总览卡 `栈与队列对比总览：规则/特征/指针/关键问题/场景`（表格用 `|` markdown 表，答案保持完整不拆碎）。
7. 尾巴 12 节按 Task 1 DECISION 执行（保留总览卡/删除），删除节的字符必须在卷内逐字存在才删，否则保留。
8. 缺答案明写不编：若某卡答案在源文无对应，body 末尾加 `（源文未给答案，待补）`，不编造。

- [ ] **Step 1: 写构建脚本并运行**

Run: `python output/_build49.py`
Expected: 生成三个文件；控制台只打 ASCII（如 `sections_new=.. wrote ok`），中文详情全在文件中

- [ ] **Step 2: 无损校验（闸口，不过不进 Task 4）**

校验式（脚本内断言，失败即停）：
- `新节标题+正文拼接` ≡ `旧节标题+正文拼接` + 恢复串集合（恢复串= Task 2 REORDER 调序产生的纯位置移动零字符 + kind 改动零字符 + fence 新增字符集合 + 标题改写差值；其中 fence/标题/kind 改动允许，**正文知识字符只允许 Task 2 恢复串清单内的增补**，其余一字不差）
- 每个恢复串在 `extractedText` 逐字存在（`in` 检查）
- `output/_b49_lossless.txt` 末行必须为 `LOSSLESS=PASS`

Run: `python -c "print(open('output/_b49_lossless.txt',encoding='utf-8').read().splitlines()[-1])"`
Expected: `LOSSLESS=PASS`

- [ ] **Step 3: 变更表复核**

Read `output/_b49_changes.txt`，确认：9 节 kind→note、碎片标题 0 残留（`操作受限`/`对比理解`/`数组下标`/`双端受限` 裸标题计数=0）、fence 覆盖 25/29/56/60、64+65 已合并、32/60 窜题已归位。
Expected: 任一项不符回 Step 1 改脚本重跑，不进 Task 4

---

### Task 4: PUT 全量换节（需 ADMIN 授权，闸口）

**Files:**
- Modify: DB（`book_section`，bookId=49，经 `PUT /api/subjects/6/books/49/sections`）
- Test: GET 复核节数 + 抽样正文一致

**Interfaces:**
- Consumes: `output/_b49_new_sections.json`（Task 3）、ADMIN token（用户授权后提供，或有口令会话执行）
- Produces: DB 新节 + `output/_b49_put_verify.txt`

- [ ] **Step 1: 等用户明确授权 ADMIN 操作**（未授权不得执行 Step 2；本计划不存储口令）

- [ ] **Step 2: 执行 PUT（ADMIN）**

Run（Git Bash 或 python，密码不落盘不进 output）：
`curl.exe -s --noproxy "*" -X PUT http://127.0.0.1:8080/api/subjects/6/books/49/sections -H "Content-Type: application/json" -H "Authorization: Bearer <ADMIN_TOKEN>" --data-binary @output/_b49_put_body.json`
其中 `output/_b49_put_body.json` 由 Task 3 产物包一层 `{"sections": [...]}` 生成（python 生成，UTF-8）。
Expected: HTTP 200 且返回数组长度 = 新节数

- [ ] **Step 3: GET 复核**

Run: student token 重新 GET sections，写 `output/_b49_sections_after.json`，断言节数/title/kind 与 Task 3 产物一致，写 `output/_b49_put_verify.txt`（末行 `PUT_VERIFY=PASS`）。
Expected: `PUT_VERIFY=PASS`

---

### Task 5: 浏览器验收（DOM 断言）

**Files:**
- Create: `output/_b49_browser.cjs`（复用常驻探针结构）、`output/_b49_browser.log`
- Modify: none

**Interfaces:**
- Consumes: Task 4 后的 DB（`srvData` 非空 → 渲染服务端节）、`output/_b49_new_sections.json`（期望标题清单）
- Produces: `output/_b49_browser.log`（A–F 全绿才算过）

- [ ] **Step 1: 写探针（进站路径固定，不得深链直闯）**

探针流程：`goto #/dashboard` → 点 `#welDirect` → `goto #/subjects/6` → 打开 book49 → 逐项断言：
- A 服务端节渲染：节数 = Task 4 新节数
- B 手风琴：点第一张问答卡 `查看答案` ⇄ `收起答案` 往返
- C 代码 fence：4 个代码节内 `pre > code` 存在且无裸段落代码
- D note 非卡片：前言/适用人群/小结节无手风琴按钮
- E 标题无裸 key、无乱码节（`�` 计数=0）
- F `pageerrors=[]` 且 `httpErrors=[]`
Run: `node output/_b49_browser.cjs`
Expected: `output/_b49_browser.log` 末行 `BROWSER=6/6 PASS`

- [ ] **Step 2: 回归抽查（桌面 1280 + 手机 390×844 各一遍关键链即可）**

手机端用触屏点抽屉外部关闭语义（subjects.js `wireRdrGlobal`），确认大纲抽屉可关。
Expected: log 内 `mobileDrawer=ok`

---

### Task 6: 已沉淀打标 + 证据汇总 + 监督窗口

**Files:**
- Modify: `C:\Study\数据结构与算法复习\数据结构与算法复习\章节拆分知识点综述\已沉淀.txt`（python UTF-8 写，回读校验无 NUL）
- Create: `output/_b49_done.txt`
- Test: 30 分钟零问题监督窗口

**Interfaces:**
- Consumes: Task 4/5 产物
- Produces: 已沉淀行更新 + `output/_b49_done.txt`

- [ ] **Step 1: 打标（仅当 Task 2–5 全绿）**

book49 行改为：`530106 | 《数据结构与算法》（2024版）第三章 栈和队列 复习知识点考点汇总.pdf | book49 <新节数>(98→<新节数>重切) | 已检查2026-09-16`
- 仅门禁绿未逐题的情况标 `门禁绿2026-09-16(未逐题)`，两者不混（本计划目标是 `已检查`）。
- python 写回读校验无 NUL（`ascii()` 抽查 + 无 `\x00`）。

- [ ] **Step 2: 证据汇总**

`output/_b49_done.txt` 列出：`_b49_sections.json`、`_b49_detail.json`、`_b49_issues.txt`、`_b49_sample.txt`、`_b49_tail_coverage.txt`、`_b49_source_align.txt`、`_b49_new_sections.json`、`_b49_lossless.txt`、`_b49_changes.txt`、`_b49_put_verify.txt`、`_b49_browser.log` 共 11 件。
Expected: 11 件齐全

- [ ] **Step 3: 30 分钟零问题监督窗口**

60s 健康采样 + 每 5 分钟无头冒烟，0 fails 才算过闸（参照 `output/supervise-final.log` 口径）。

## Self-Review

1. Spec coverage：§7.2 背诵形态判定（Task 0 已定 RECITE，不转卷/SCAN）→ Task 1 尾巴查卷；§7.7 七条 → Task 3 规则 1–8 全覆盖；§7.9 fence/note/问答归位 → Task 3 规则 1/4/5/6 + Task 5 C/D；无损校验 → Task 3 Step 2；三沉淀接口 → Task 1/4（PATCH 修单题本次若无单题可修则不调用，不硬凑）；图库（figures=[] 无悬空图，images=17 页码数，矢量示意图无法裁切→ Task 6 done 件中如实报告，不截图充数）。
2. Placeholder scan：无 TBD/TODO/"类似 Task N"；所有脚本含完整代码；所有命令为本机可运行形态（`curl.exe` + `--noproxy "*"` + python）。
3. Type consistency：`sections` 全用 `{title, body, kind, sortNo}`；kind 取值 `RECITE/note` 与 `SubjectService.SectionInput` 一致（Task 3 脚本内校验非法 kind 即抛错）；paperVersionId 全用 `223503`（栈和队列卷，`已沉淀.txt` 题库节）；bookId=49、subjectId=6 全文统一。
