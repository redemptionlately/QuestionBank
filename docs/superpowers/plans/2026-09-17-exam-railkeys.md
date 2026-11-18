# 答题页图标轨 + 自定义快捷键 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 答题页右侧只留上/下两个纯图标统一按钮；练习与连读合成单图标留在左侧大纲；顶栏加快捷键图标开面板改上/下一题键位。

**Architecture:** exam.js 模板只加法替换旧导航（删 #sidePrev/#sideNext、#pageFnav、#examFnav 的上一题/下一题文字钮，换 #railPrev/#railNext）；统一 goNext/goPrev 按 scrollMode 分发 step 或整屏翻页；键位存 localStorage qb.keys，经 store.js keymapGet/keymapSet 读写，exam onKey 与 subjects 步进 j/k 共用；顶栏 #scBtn（icons.keyboard，仅答题路由可见）开 #scPanel 下拉改键。

**Tech Stack:** 零构建 ES module；零新依赖；现有 icons/ui/toast/modal-scrim 豁免与 composedPath 点外部关闭口径。

## Global Constraints

- 后端不动；frontend/ 常驻 gitignore：绝不 git add/commit frontend 文件；只 commit docs/。
- JS 改完 node --check（copy 成 .mjs）；改了内容的 JS bump ?v=（app.js 内 exam.js 后缀进一位；入口 index.html 的 script 禁带 ?v=）。
- i18n zh/en 同步（现 414/414，新增 8 key 后两边同数、零裸 key）。
- 无位移动画；大卡片无 backdrop-filter；样式进 polish.css 末尾 ASCII 注释；图标按钮 title/aria-label 用 i18n。
- 旧探针引用旧 ID 必同步改（probe-exam-infinite.cjs 的 #scrollSeg/#pageFnav/seg 文字断言）。
- 证据落 output/；30 分钟监督窗口过闸。

---

### Task 1: 实现（单个子代理一次做完，防同文件互覆盖）

**Files:**
- Modify: `frontend/js/views/exam.js`（模板：删 exam-side-nav 文字钮、#pageFnav、#examFnav 内翻题条，留标题/进度/大纲；modeSeg/scrollSeg 改单图标开关 #modeIcon/#scrollIcon；新增右侧轨 #examRail > #railPrev/#railNext；新增 goNext/goPrev；syncFnav 改 syncRail；onKey 读 keymap）
- Modify: `frontend/js/store.js`（加 keymapGet/keymapSet，prefs  backing key qb.keys，默认 {next:'j',prev:'k'}，大小写不敏感归一）
- Modify: `frontend/js/app.js`（顶栏 menuBtn 后加 #scBtn keyboard 图标 + #scPanel 下拉；仅 `#/exam` 或 `#/practice` 路由可见，对齐 topbarBack 显隐逻辑；Esc/点外部关，复用 composedPath + modal-scrim 豁免；?v= bump exam.js 后缀）
- Modify: `frontend/js/i18n.js`（zh/en 各加 8 key：sc.title 快捷键/Shortcut、sc.prev 上一题/Prev question、sc.next 下一题/Next question、sc.change 更改/Change、sc.press 请按键…/Press a key…、sc.reset 重置默认/Reset、sc.saved 已保存/Saved、sc.conflict 按键冲突/Key conflict）
- Modify: `frontend/css/polish.css`（#examRail：桌面 fixed 右 18px 垂直居中纵排 44px 图标钮；≤900px 改右下横排 bottom:76px right:12px 避开 #backToTop；#scPanel 顶栏下拉定位；.icon-only 无文字按钮态复用 .fnav-btn）
- Modify: `output/probe-exam-infinite.cjs`（旧断言改新 ID：seg 文字→#scrollIcon 开关态、#pageFnav→#examRail、pageflip 走 #railNext 整页模式）

**Interfaces:**
- Consumes: 现有 step(d)、scrollBy 整页翻、jumpAnchor、onScrollSync、paintChips、clearFeedback，签名不动。
- Produces: `goNext()`/`goPrev()`（infinite→step(±1)，page→整屏翻页后靠 onScrollSync 派生当前题）、`keymapGet()`/`keymapSet()`、面板 capture/reset。

- [ ] **Step 1: store.js keymap**

```js
const KEYS_STORE = 'qb.keys';
export function keymapGet() {
  try {
    const s = JSON.parse(localStorage.getItem(KEYS_STORE) || '{}');
    return { next: normKey(s.next) || 'j', prev: normKey(s.prev) || 'k' };
  } catch { return { next: 'j', prev: 'k' }; }
}
```

normKey：单字符转小写；Arrow* 原样；空/超长回 null。

- [ ] **Step 2: exam.js 模板+统一跳转+onKey**

rail 模板（题区兄弟节点，main column 内 fixed 由 CSS 定）：

```html
<div id="examRail" role="navigation">
  <button id="railPrev" class="fnav-btn icon-only" type="button"></button>
  <button id="railNext" class="fnav-btn icon-only primary" type="button"></button>
</div>
```

图标 innerHTML = icons.arrowLeft/arrowRight；title/aria 走 t('c.prevQ')/t('c.nextQ')（整页模式 title 切 c.prevPg/c.nextPg，随 setScrollMode 同步）。左栏 #modeIcon 显示 icons.book/play 二切（title exam.modeNormal/Drill），#scrollIcon 显示连读/整页两态图标（用 icons.list 做连读、icons.grid 做整页，title exam.scrollInfinite/Page）。onKey：INPUT/TEXTAREA/SELECT guard 保留；命中 keymap.next/prev 才 preventDefault+goNext/goPrev；旧硬编码 j/k 删除。

- [ ] **Step 3: app.js 顶栏按钮+面板**

```html
<button class="btn btn-ghost btn-sm btn-icon" id="scBtn" hidden title="...">${icons.keyboard}</button>
<div id="scPanel" hidden>两行（sc.prev/sc.next + 当前键 kbd + 更改钮）+ 重置行</div>
```

路由显隐：与 topbarBack 同函数里按 `#/exam`、`#/practice` 开头控制 hidden。更改流程：点更改→该行变 sc.press→下一次 keydown 捕获（Escape 取消）→冲突（与另一键相同）则 ui.err(t('sc.conflict'))→成功 keymapSet + ui.ok(t('sc.saved'))。点外部关闭：document click 用 composedPath 快照判 #scPanel/#scBtn 内，modal-scrim 豁免（对齐 subjects 抽屉语义）。

- [ ] **Step 4: subjects 步进键位统一**

subjects.js rdr-step 的 keydown j/k 改读 keymapGet（import store.js；已有 import { session, prefs } from '../store.js'，同行加 keymapGet）。

- [ ] **Step 5: 自检**

Run: 四个文件各 `copy X output\_chk_N.mjs && node --check output\_chk_N.mjs`，静默 PASS；i18n 两边计数相等且零缺口（沿用 parity 脚本口径）。

- [ ] **Step 6: 探针同步+跑**

改 probe-exam-infinite.cjs 旧 ID 断言为新结构后跑三探针（exam/text/recite）+ 回归烟；pageerrors/console 空；证据 `output/regress-railkeys-*.log`。

### Task 2: 收尾（?v= 已在 Task 1 Step 内做；本 Task 只做监督窗口）

- [ ] **Step 1: 30 分钟监督窗口**（60s health + 5min 冒烟，拆小前台 sleep，禁后台进程），0 fails 过闸，证据 `output/supervise-railkeys-*.log`。

### Task 3: 背诵步进合一（2026-09-17 用户追加：背诵侧也一样）

- [x] subjects.js：rdrStepHtml 加 iconOnly 参数（背诵纯图标，题库 rail 同款；全文/图页不动）；新增 rdrStepTitles/reciteStepGo/syncRdrStepTitles；背诵 rpg 翻页行删除，屏页 pill 并入 syncRdrStep；绑定改 reciteStepGo。键盘（转 click）与提纲（jumpReciteCard）零改动继承合一。全文侧不合并不动。
- [x] 探针同步 + 双绿：recite ALL_PASS 34/34，text ALL_PASS 23/23，node --check 静默；app.js subjects ?v=  bump；证据 `output/merge-recite-step-20260917.log`。
- [ ] 监督窗口见 Task 4（本次合一变更的独立过闸）。

### Task 4: 合一变更监督窗口

- [ ] **Step 1: 30 分钟监督窗口**（60s health + 5min 冒烟含 recite 合一断言，拆小前台 sleep，禁后台进程），0 fails 过闸，证据 `output/supervise-merge-recite-*.log`。

### Task 5: 分页整体删除（2026-09-17 用户：不分页了，直接去掉）

- [x] exam.js：删 scroll-seg 模板 + scrollMode/setScrollMode/applyScrollModeUI；rail 标题静态化；goNext/goPrev 只剩 step；哨兵守卫删除。练习 seg/右侧轨/快捷键面板保留。
- [x] subjects.js：删 bookScrollMode 全套 + 屏分页引擎整块（~155 行）+ paintTextPage/paintRecitePage；renderReciteBody 纯窗口；全文/背诵翻页行与绑定删除；gotoPara/jumpReciteCard/applyBFilter/editText/初始绘制去分页；`page`/`recitePage` 变量删除。图页查看器不动。
- [x] i18n：删 exam.page/prevPage/nextPage/scrollInfinite/scrollPage（中英同步，parity 不变）；c.prevPg（wrong.js）与 book.prev/next（图页）保留。
- [x] CSS：删 scroll-seg/#pageFnav/[data-bookmode]/[data-pagemode] 规则；.rdr-pagerow/.pg-btn（图页）与哨兵/轨/步进规则保留。
- [x] 探针同步 + 三绿：exam 25/25，text 19/19，recite 31/31；node --check 四文件静默；app.js ?v= 双 bump；证据 `output/drop-pagination-20260917.log`。
- [ ] 监督窗口见 Task 6。

### Task 6: 删除分页监督窗口

- [ ] **Step 1: 30 分钟监督窗口**（60s health + 5min 三探针轮询，拆小前台 sleep，禁后台进程），0 fails 过闸，证据 `output/supervise-drop-page-*.log`。
