# 无限滚动 + 整页翻页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 题目页与电子书全文/背诵窗实现无限滚动与整页翻页双模式，上一题/下一题保留。

**Architecture:** 数据层零改；渲染层加 renderedCount 窗 + 末尾 sentinel，IntersectionObserver（rootMargin 800px）追加下一段 innerHTML，只增不删；跳转先补渲染再走现有锚点逻辑。

**Tech Stack:** 零构建 ES module 前端；IntersectionObserver；content-visibility（仅已有处，不动 exam .q-card 的 visible 结论）。

## Global Constraints

- 后端只进 app/.../questionbank/extra/，本次不碰后端。
- frontend/ 常驻 gitignore：改完不 git add、不 commit；只 commit docs/ 下的 spec/plan。
- JS 改完先 node --check（拷成 .mjs 再 check）；带 ?v= 的文件改内容必 bump 版本号。
- i18n zh/en 必须同步补 key（当前 412/412 零缺口，不得留裸 key）。
- 无位移动画；大卡片无 backdrop-filter；样式就近加。
- 每项修复附 output/ 可复现证据；30 分钟零问题监督窗口为完成闸口。
- exam 连续渲染架构改动已获用户 2026-09-17 批准。

---

### Task 1: 题目页 exam.js 无限追加 + 双模式

**Files:**
- Modify: `frontend/js/views/exam.js`（renderPage 改窗口追加、sentinel observer、模式 seg、整页按钮、ensureRendered）
- Modify: `frontend/js/i18n.js`（zh 区 exam.modeDrill 行下与 en 区同位置各加一行）
- Modify: `frontend/css/polish.css`（.exam-sentinel 高度 2px 占位，.scroll-seg 复用 .seg 样式）
- Test: `output/probe-exam-infinite.cjs`（常驻冒烟写法复用：goto #/dashboard → tap #welDirect → 跳 #/practice/xxx）

**Interfaces:**
- Consumes: 现有 `visList()`、`cardHTML(q)`、`bindList()`、`paintChips()`、`syncFnav()`、`jumpAnchor()`、`scrollToCard(qid)`，不动签名。
- Produces: `ensureExamRendered(qid)`（补渲染到含 qid 的段，无返回值）、`appendNextExam()`（追加一段）、`setScrollMode(m)`（'infinite'|'page'，写 localStorage qb.exam.scrollMode）。

- [ ] **Step 1: 加 i18n key（zh+en 同步）**

```js
// zh 区（exam.modeDrill 行下加）：
'exam.scrollInfinite': '连读', 'exam.scrollPage': '整页',
// en 区（exam.modeDrill 行下加）：
'exam.scrollInfinite': 'Infinite', 'exam.scrollPage': 'Page',
```

- [ ] **Step 2: node 语法自检 i18n.js**

Run: `copy frontend\js\i18n.js output\_chk_i18n.mjs && node --check output\_chk_i18n.mjs`
Expected: 无输出即 PASS（有错则修到静默）。

- [ ] **Step 3: exam.js 加渲染窗 + sentinel（最小实现，不动跳转）**

```js
const EXAM_STEP = 10;
let examRendered = 0, examObs = null;
function examSlice() { return visList().slice(0, examRendered); }
function appendNextExam() {
  const arr = visList();
  if (examRendered >= arr.length) return;
  examRendered = Math.min(arr.length, examRendered + EXAM_STEP);
  paintExamWindow();
}
function ensureExamRendered(qid) {
  const arr = visList();
  const i = arr.findIndex(q => q.id === qid);
  if (i >= 0 && i >= examRendered) { examRendered = i + 1; paintExamWindow(); }
}
```

paintExamWindow 复用现有 renderPage 的 body 拼接逻辑，但输入改为 examSlice()，末尾拼 `<div class="exam-sentinel" data-sentinel></div>`，然后 bindList/paintChips/syncFnav。筛选切换（mFilter 按钮/clearfilter）与 loadMastery 成功回调里加 `examRendered = 0; appendNextExam();` 重置。

- [ ] **Step 4: sentinel observer + 跳转前补渲染**

```js
function wireExamSentinel() {
  if (examObs) examObs.disconnect();
  examObs = new IntersectionObserver((ents) => {
    if (ents.some(e => e.isIntersecting)) appendNextExam();
  }, { root: null, rootMargin: '800px 0px' });
  const s = list.querySelector('[data-sentinel]');
  if (s) examObs.observe(s);
}
```

jumpTo/step/jumpToNextAfter 开头先算出目标 qid 再调 `ensureExamRendered(qid)`。hashchange 摘除时加 `examObs && examObs.disconnect()`。

- [ ] **Step 5: 模式 seg + 整页按钮（只加法）**

exam-side-mode 块内追加：

```html
<div class="seg seg-sm scroll-seg" id="scrollSeg">
  <button data-sm="infinite" type="button"></button>
  <button data-sm="page" type="button"></button>
</div>
```

按钮文案用 `t('exam.scrollInfinite')` / `t('exam.scrollPage')`。整页按钮挂在 exam-floatnav 旁（新 div#pageFnav，两按钮 data-pagejump="-1/1"）：`window.scrollBy({top: d * innerHeight * 0.92})` 后用现有 onScrollSync 派生当前题（无需手算 cursor）。默认 infinite，localStorage 读写 try/catch。

- [ ] **Step 6: 自检 + 探针**

Run: `copy frontend\js\views\exam.js output\_chk_exam.mjs && node --check output\_chk_exam.mjs`
Expected: 静默 PASS。
探针断言：卷内卡数随滚追加增长；跳未渲染题号可达且顶对齐（jumpAnchor 口径）；整页按钮翻动后当前题变化；pageerrors 为空。证据落 `output/probe-exam-infinite.log`。

- [ ] **Step 7: 留痕（不 commit frontend）**

Run: `git status --short`
Expected: frontend/ 改动不出现在待提交里（gitignore 常驻）；如出现则停手检查。

### Task 2: 电子书全文窗 chunks 改追加

**Files:**
- Modify: `frontend/js/views/subjects.js`（text paint 改窗口、sentinel、ensureChunkRendered、gotoPara 补渲染）
- Test: `output/probe-book-text-infinite.cjs`

**Interfaces:**
- Consumes: Task 1 的 `t('exam.scrollInfinite')` key（复用，不另加）；现有 `chunks`、`chunkOf(pi)`、`renderMdChunk(chunk)`、`paint()`、`bindFollow()`、`rdrPlaceTo(el)`、`syncRdrStep()`。
- Produces: `ensureChunkRendered(pi)`、`appendNextChunk()`；阅读器工具条模式 seg 读写 `qb.book.scrollMode`。

- [ ] **Step 1: text 窗渲染窗（首屏 2 块，步长 2）**

```js
const TEXT_FIRST = 2, TEXT_STEP = 2;
let textRendered = 0;
```

paint() 改为渲染 `chunks.slice(0, textRendered)` 拼接（每块仍走 renderMdChunk，data-pi 不变），末尾拼 sentinel。切换书/切模式时 `textRendered = 0` 重置。gotoPara 开头加 `ensureChunkRendered(pi)`。

- [ ] **Step 2: sentinel + followObs 全量重建**

追加后对新全量 `[data-pi]` 重建 observe（段数少，全量重建简单可靠）。

- [ ] **Step 3: 自检 + 探针**

Run: `copy frontend\js\views\subjects.js output\_chk_subj.mjs && node --check output\_chk_subj.mjs`
Expected: 静默 PASS。
探针断言：全文窗滚动追加块数增长；大纲点未渲染节可达；整页模式按 chunk 翻；pageerrors 为空。证据 `output/probe-book-text-infinite.log`。

### Task 3: 背诵窗 reciteCards 改追加

**Files:**
- Modify: `frontend/js/views/subjects.js`（paintRecite 改窗口、RECITE_PS 改步长语义）
- Test: `output/probe-book-recite-infinite.cjs`

**Interfaces:**
- Consumes: 现有 `reciteCards`、`rdrStepCards`、`noteHtml()`、`bindKpToggle/bindKpEdit/bindKpTitle/bindBseg`、`jumpReciteCard(k)`、`syncRdrStep(preferK)`。
- Produces: `ensureCardRendered(k)`、`appendNextCards()`；RECITE_PS=8 保持数值，仅语义从页大小改步长，首屏 12 卡。

- [ ] **Step 1: paintRecite 窗口化**

```js
const RECITE_FIRST = 12; // RECITE_PS=8 不变，作步长
let reciteRendered = 0;
```

slice 改为 `reciteCards.slice(0, reciteRendered)`；组头 lastHead 逻辑保持；翻页行 pager（data-rpg）在无限模式隐藏、整页模式保留。bFilter 切换时重置 reciteRendered。

- [ ] **Step 2: 跳转补渲染 + 步进兼容**

jumpReciteCard 开头加 `ensureCardRendered(k)`；rdrStepMove 不变（目标天然在 rdrStepCards 内，ensure 兜底）。

- [ ] **Step 3: 自检 + 探针**

Run: 同 Task 2 Step 3（同一文件，一次 check 覆盖）。
探针断言：背诵窗滚动追加卡数增长；大纲点未渲染卡可达且展开正常；掌握度三态/答案补丁不受影响；pageerrors 为空。证据 `output/probe-book-recite-infinite.log`。

### Task 4: 收尾（版本号 + 全量回归 + 监督窗口）

- [ ] **Step 1: bump ?v=**

改了内容的 JS（exam.js、subjects.js、i18n.js 若被入口引用带参）bump ?v= 后缀；入口 script 本体禁带 ?v=（沿用左栏死锁根治结论）。

- [ ] **Step 2: 全量冒烟**

复用常驻 `output/` 冒烟（focus/cinema/dual-smoke 至少跑 exam + subjects 两条），DOM 断言为主；`node --check` 全过；i18n zh/en 计数仍 412+/412+ 且零缺口。

- [ ] **Step 3: 30 分钟零问题监督窗口**

60s 健康采样 + 每 5 分钟无头冒烟，0 fails 才算过闸；证据落 `output/supervise-infinite-*.log`。
