# AGENTS.md — QuestionBank 项目代理规则

本文件是 **QuestionBank（题库 / 学习库系统）** 仓库的代理条目文件。任何在本仓库工作的
代理都必须先读本文件，再动手。

> 注意：`docs/AGENTS.md` 是另一套上下文（Allen 的 C++ / AI_Infra 学习方向教学规则），与本仓库
> 技术工作无关，两者并存、互不涉及。

---

## 0. 开工三步（2026-09-14 新增，违反视为未开工）

1. **上网搜索收集信息**：做任何任务前，先上网搜索收集相关信息——第三方库走 `context7`
   （先 `resolve-library-id` 再 `query-docs`），概念/做法/规范走 `websearch`/`webfetch`
   （如背诵卡规范对齐 SuperMemo 20 rules，证据见 `output/research-*.log`），批量抓取走
   `firecrawl`。**禁止凭记忆编造库 API 与版本行为**；记不清、不确定就去查，查完把结论落盘。
2. **研究设计计划**：基于搜集到的信息先研究再设计——多步骤任务写计划（`writing-plans` →
   `executing-plans`），2+ 独立子任务可并行（`dispatching-parallel-agents`），见 Bug 先加载
   `systematic-debugging` 再修。无计划不碰代码，无研究不改接口语义。
3. **证据闭环**：MCP/搜索结论落 `output/` 或在回复中引用来源；调用失败记日志换备用通道，不静默跳过。

---

## 1. 项目定位与目标

- Allen 自用**题库 + 学习库**：PDF / Word / 图片资料转可阅读**电子书**，嵌套分类目录，8 维度求职准备。
- 当前主线：资料入库呈现**三种电子书形态**（全文 / 背诵 / 试卷+图页）+ **嵌套子集目录**，用户可增删分类（§7）。
- 后端 Java 21 / Spring Boot 3.4，前端零构建 ES module。业务库 3306 `question_bank`，另有 3307 主库。

---

## 2. 技术栈与运行环境

- **后端**：Java 21（`C:\Program Files\Java\jdk-21.0.12`）、Spring Boot 3.4.5、Hibernate ORM 6.6、
  Maven 3.9.9（用 wrapper）。模块 `question-bank-m0`。
- **数据库**：MySQL 9.0.1，`127.0.0.1:3306` `question_bank`（用户 root，口令走环境变量 `${MYSQL_PASSWORD}`，本机值见 `scripts/.env.local`，**不入库**）；另有 3307 主库。
- **缓存/消息**：Redis 8.10.1（6379，后端 health 依赖它）；Kafka 4.0 KRaft 单机（9092）。
  `C:\Users\Allen\tools\kafka` 下的 java 进程是用户本机 Kafka，**绝不许杀**。
- **前端**：`frontend/` 零构建 ES module，`frontend/server.js` 在 `:3000` 反代 `:8080`。
- **环境限制**：Windows；**无 Docker**；WSL 被拦截；长驻进程只能以前台形态挂在后台任务里
  （任务不结束进程就活）。**本机无 tesseract/OCR** —— 图片只能渲染，不能识别。
- **进程极不稳定**：整机回收会杀全部后台进程；`Start-Process` 拉起的常被 `ChildProcess.kill`
  当场回收；长 sleep + 拉后台进程的组合同样被回收——命令拆小。可靠启动只有
  `javaw.exe -jar deploy/question-bank-app.jar` 直起或 `bash run-app-3306.sh`（慢启动 40s+）。
  **动手前永远先探活 8080**（`netstat` + `/actuator/health` + 登录），不要信 spawn 回执；
  8080 死亡是常态，连刷 401 先确认进程还在再怀疑逻辑。杀进程先认 PID
  （8080 共享、8081 并行会话、用户 Kafka 绝不动；`taskkill` 用单斜杠）。
- **多会话并行**：AGENTS4 等会话同时在本仓库/同一 MySQL 工作（它们提交走 git）。
  动手前看 `git log/status`、list `extra/`；`extra/` 是共享 WIP 区，改别人文件前读全文件
  （撞车先例：Category 实体双方各写一份、双 V7）。DB 曾被外部清空两次，删改数据前先备份
  （`.workbuddy/backups/`）。改共享前端文件前重读全文件，只做加法不动他人结构；
  **不评判对方设计方向**（默认主题/免登录这类产品决策只向用户汇报，不擅自回滚）。

---

## 3. 硬性红线（违反即回滚）

1. **不改原始后端主包**。新功能只进 `app/.../questionbank/extra/`（auto-scan：JPA 实体 +
   JpaRepository + `@Service`(`@Transactional`+`EntityManager`) + record DTO +
   带 `@PreAuthorize` 的 `@RestController`）。Flyway 用独立 `CREATE`/`ALTER`
   （H2 MODE=MySQL 拒绝多语句）。
2. **Flyway 开 + `ddl-auto: validate`** → 新实体逐字段 byte-match 迁移 DDL，否则启动失败。
   已应用迁移永不修改；新表开新版本号，动手前 `ls migration/` 取空闲号；
   checksum 漂移用官方 `ChecksumCalculator.calculate(FileSystemResource)` 重算再 UPDATE。
3. **构建用 `./scripts/mvn.sh`**（`mvnw` 在 Git Bash 下 `ClassNotFoundException`）。
   增量构建不清理已删文件（`app/target/classes/` 残留会打进 jar）→ 查 jar 用
   `unzip -l xxx.jar | grep migration`；运行中 jar 禁止 `cp` 覆盖（懒加载
   `NoClassDefFoundError`，复现器 `scripts/repro-jar-overwrite.py`）。
4. **业务启动用 `bash scripts/run-app-3306.sh`**（强制 8080，读 `scripts/.env.local`，**勿提交**）。
   产物 `deploy/question-bank-app.jar`；长跑验证用私有副本 `output/jars/qb-ui-dev.jar`
   （与部署路径解耦；运行中出现 logback `NoClassDefFoundError` + 请求挂起 = jar 被覆盖，换副本重启）。
5. **前端可改、后端只进 `extra/`，但都不入库**：`frontend/`、`extra/`、V5/V6 迁移常驻 gitignore；
   **日志/临时脚本/构建产物一律不入库**（`output/`、`*.log`、`*.jar` 等；已入库旧日志的改动不顺手提交）。
6. **严禁编造证据/指标**。每项修复附 `output/` 可复现证据；**30 分钟零问题监督窗口**为完成闸口。
7. **配置变更强约束**：CLI / 模型配置改动前先报告证据、等用户明确授权（AGENTS5 只评估不实现，越界先确认）。
8. **多会话协作**：禁同名文件/同版本号迁移并存；撞车以先到者设计合一
   （Category 用 join 表版 + order_idx，`book.category_id` 已废弃；提交走 git 的以对方为准）。

---

## 4. 目录与文件约定

```
app/src/main/java/com/allen/questionbank/
  extra/            ← 唯一允许新增后端代码的位置
  auth bank cache common event importjob ops practice redis/  ← 原始后端，勿改
app/src/main/resources/db/migration/   ← V1..Vn（新增表改这里）
frontend/js/        ← ES module（views/ 各路由，extra-api.js 为 API 客户端）
frontend/css/       ← tokens/base/components/pages/cc-ui/cc-glass/cinema/polish（就近加）
output/             ← 证据/脚本/截图（常驻冒烟 focus/cinema/dual-smoke、perf-probe、night-shot*.cjs 直接复用）
scripts/            ← mvn.sh / run-app-3306.sh / evidence 脚本
```

---

## 5. 构建 / 运行 / 验证

```bash
./scripts/mvn.sh package          # 产物 deploy/question-bank-app.jar
bash scripts/run-app-3306.sh     # 业务后端（读 scripts/.env.local）
cd frontend && node server.js     # :3000 反代 :8080
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"student","password":"student123"}'
# 管理员口令见 §11（admin/admin123 已 401）；答题/背诵 E2E 用 student（practice 整类 STUDENT，admin 会 403）
```

- **中文三件套**（踩过即记）：① MySQL 中文走 `--default-character-set=utf8mb4` + UTF-8 sql 文件，
  用 `cmd` 重定向落盘，**不走** PowerShell 管道/`Out-File`（GBK 吃字）；② 控制台只打 ASCII，
  详情写 UTF-8 文件再 Read 看；③ 新写文件的中文用 `\u` 转义，判据只认字节抽查（`ascii()`）、
  后端行为、浏览器渲染——Read 显示正常 ≠ 落盘正确。
- **PowerShell 坑**：无 `head`/`tail`；`\"` 不转义、`$var` 会展开——复杂命令先落脚本文件；
  `$env:MYSQL_PWD` 传密码；`$LASTEXITCODE` 管道后不可信；`bash` 必须用 Git Bash。
- **代理坑**：curl 加 `--noproxy '*'`；Playwright 启动加 `--no-proxy-server --proxy-bypass-list=*`，
  否则 localhost 走代理卡死/502（前端表现为“加载中”卡住）。
- **浏览器验证**：`playwright-core` + `chrome-153` + `--no-sandbox --disable-gpu`；
  `agent-browser` daemon 常卡死则绕开；MCP 浏览器（chrome-devtools/playwright）在本机无 stable
  Chrome，一律用常驻 `output/*.cjs` 跑冒烟。**DOM 断言 > 截图**；`pageerror`/`console` 抓白屏；
  hash 路由不用 `waitForURL`；`nth()` 在异步列表 flaky。headless 是软件渲染，fps 只横向对比，
  同一探针连跑 2 次看 spread。
- **JS 改完先 `node --check`**（`.js` 拷成 `.mjs` 再 check；ESM 解析期错误致整站白屏，
  如 `exam.js` 重复 `let`）；带 `?v=` 的文件改内容必 bump 版本号，无版本号 JS 靠 `no-cache` 新鲜。
- **探针进站路径**（固定）：`goto #/dashboard` → tap `#welDirect` → 再改 hash
  （深链会被欢迎页拦截；桌面 context 无 `hasTouch`，`p.tap` 抛异常须用 `p.click`）。
- **测试账号**：不假设种子账号，单测注入 `PasswordEncoder` 自建、按 FK 序清理；
  Kafka 证据失败先查 9092；`general_log` 开完即关；jacoco 排除 WIP 写顶层
  （`com/allen/questionbank/extra/*`）；JSON 列用 `@JdbcTypeCode(SqlTypes.JSON)` 且不带 columnDefinition。

---

## 6. 前端约定

- **零构建**：改完刷新即生效。**i18n** `zh`/`en` 必须同步补 key，走 `t('key', {n:..})`；E2E 断言无裸 key。
- **数据口径**：题目正文 `q.prompt`（非 `stem`）；extra 题目 `xid = 1e9 + id` 命名空间；
  幂等键 = paperVersionId + 归一题干；答案块归属**前面**题干。
- **阅读器**：`.book-reader` 为书行兄弟节点（禁塞回行内）；`.reader-layout` = 188px 大纲 + 660px 主区；
  形态自动选 `text` / `img` / `recite`（tab 已取消）；背诵卡手风琴「查看答案 ⇄ 收起答案」。
  **服务端节为准**（2026-09-14 用户拍板）：`srvData` 非空一律渲染服务端节，无节才回退 kp-parse。
- **大纲三处**：科目页 `.subject-layout` = 232px `.side-tree` + 右栏（`--depth` 缩进）；
  阅读器 `.reader-layout`；答题页连续渲染无分页，左栏 `.exam-side`（236px + 894px），
  上下题只走 `sidePrev`/`sideNext`，返回走顶栏常驻按钮。分组策略：题型(≥2) → 真题年份 → 单组。
- **答题反馈**（2026-09-13/14）：答对 toast（`exam.rightToast`）后立跳下一题
  （`jumpToNextAfter`，选择/文本统一）；答错展开手动收起；未评三态不计 0；做完只挂横幅（无交卷）。
- **顶栏/侧栏**：无面包屑、无用户块；侧栏默认隐藏，左缘 `.rail-hot`（桌面 = 70px 图标轨的常驻入口，
  恒可见可点：`z-index:75` 压过 `.sidebar`，否则悬停召唤时被侧栏盖住点不动；锁态用 `clip-path`
  收成中段竖带保底）悬停召唤 `body.sidepeek` / 点击开 `.open` 抽屉（`.pinned` 钉住，
  Esc/点外部解除）；抽屉内点主题/退出**不关抽屉**（`composedPath()` + 进出时重查节点 + `modal-scrim` 豁免）。
  `≤860px` 走 `#menuBtn` 的 `.open` + scrim；桌面 `#menuBtn` 反而是 **zen 开关**
  （`body.rail-off` 收掉热区+侧栏、`data-sidelock=1` 收起科目左树，再点即退出——不是抽屉键）。
  深链靠 `pendingRoute` 在静默登录后恢复。
  **入口 script 禁带 `?v=`**（ESM 视作第二个模块 → 双实例 → shell 节点全变死节点，见 §12）。
- **样式**：就近加（`cc-ui.css`/`pages.css`），沿用 token；自有卡片独立类名，禁复用裸 `.card`；
  cinema 整文件 scoped `[data-theme="cinema"]`（`qb.theme.v2`，默认影院；`dark` 残留映射 `cinema`），
  共享精修放最后引入的 `polish.css`；`logo` 用 `fill="currentColor"`。
- **性能红线**：无颗粒层、无 `fixed` 背景、大卡片无 `backdrop-filter`、长列表
  `content-visibility: auto`、全站无位移动画、顶部内高光慎用；ticker 类节流（如 focus 500ms/5s）。
- **专注页**：纯前端，无倒计时只有正向计时；自建分类多计时并行 + 今日占比圆环；
  存储 `qb.focus.*`，分类色行内 `--cat` 变量。

---

## 7. 用户的功能要求（电子书 / 学习库）

资料入库形态（2026-09-14 修订——**禁把知识点伪造成带假选项的试卷**；禁编造答案/解析，缺失只报告；2026-09-16 用户拍板：**内容先与源文件逐句对齐（无损、可溯源），再按 §7.9 验格式**——`已检查` = 内容保真 + 格式全过，两者缺一不可）：

1. **可识别文字的 PDF** → **全文电子书**（章/节 + 图原位插入；无 OCR，扫不到字只放图页）。
2. **知识点集合** → **背诵电子书**（`book_section`：标题=问题，点击展开=答案；md 渲染 `mdLite/mdInline`，
   铅笔按钮 `PUT` 在线改正文/全量换节）。
3. **纯课本/扫描件** → **图页形态**（`images/{i}` 按页渲染；DOCX 按内嵌图张数翻页）。
4. **题库** → 原样成卷（不拆不编；缺答案打回不入库）；练习选即判分、做完自动收尾横幅，无评分交卷。
5. **嵌套子集目录**：category 自引用 `parent_id` + `book_category`/`paper_category`；
   新建/移动/删除走 `categoriesApi`。
6. **C:\Study 共 45 文件**：软件工程 12（真题对 2024/25-docx 纯图 → SCAN，2026-pdf → 卷 223520，
   教材习题 → 卷 203463）+ 数据结构 33（C 语言手册/大纲/历年真题/知识点手册/章节综述×8/
   章节配题×8/配套题库/章真题×8）。章书尾巴的练习+真题已并入对应章卷（每卷 ≈15 新真题 +
   ~20 练习 + ~100 历年，证据 batch4），**判重后再入库，禁重复造卷**。
7. **背诵卡修复口径**（2026-09-14，对齐 SuperMemo 20 rules，详见
   `output/research-recite-policy.log`）：一卡一问、标题独立可读（禁“它/上述”）、
   答案保持考试完整（简答多点不拆碎）、总览卡刻意保留（冗余欢迎）、缺答案明写不编、
   碎片标题（选边/开放地址法类）必须并回父题、例题/练习/真题尾巴先查卷覆盖再决定去留。
8. **专注页**：只正向计时，自建分类多并行 + 占比圆环。**主题**：白天 + 影院两套一键切
   （影院走深蓝单色 + 暖金，参考 `ryota-kk/personal-homepage` 电影感，不抄位移动画）。
9. **渲染验收标准**（2026-09-16 用户拍板：不对字数，只验格式；md 全量渲染）：
   全文书标题必须分级（`#`章/`##`节/`###`小节，一/二/三/转 `###` 或粗体，禁一坨 `<p>`）；
   代码块必须加 ` ``` ` fence（否则 mdLite 按段落渲染）；行内公式 `$…$`/KaTeX 保持；
   真图（结构图/几何图/场景图/公式图）原位 `[IMG n]` 插入，**有文字层禁整页截图充数**
   （SCAN 只允许零文字层）；背诵卡答案走块级 `mdLite`、标题走行内；
   答题页“正确答案”走块级 `mdLite`（`exam.js ansLine`/复盘/`preview.js ansHTML`，2026-09-16 已改，
   `exam.js?v=20260922g`），判分仍用原始 `correctAnswers` 串；
   前言/适用人群/小结/说明类 → `kind=note` 非卡片（isMetaTitle），保留不考；
   卷答配对强制：真题卷 + 答案卷必须合为一卷（24/25 合卷先例），禁卷答分离多造卷。
   答案归位双禁（2026-09-17 用户原需求落字）：①答案禁进题干（题干/选项/答案/解析四件套，
   答案块归属**前面**题干，tainted 题门禁拦）；②答案有文字层必须提文字，禁存图片文件夹
   （SCAN 只收零文字层，有字就提字）；缺答案打回不入库，禁编造。
   插图判定三问（2026-09-16 用户拍板：理解意思举一反三，不死记科目）：①有文字层？
   有→提文字，**禁整页截图充数**（SCAN 只收零文字层）；②文字提完仍有图形信息丢不得
   （拓扑/几何形状/场景/符号排版——计算机结构图、数学几何图、英语场景图、物理受力/
   电路/光路/实验装置图、公式图，举例而已，换科目同理）？有→裁**最小包围**原位
   `[IMG n]` 插入，配替代文字说明；③纯文字推导/代码→fence/公式，不截图。
   真源修复：切碎的句子以 `book.extracted_text` 为准恢复（48：大O定义“表示T(n)…”、
   GCD 缺的步骤2/4，证据 `output/extract-probes*.log`），恢复串必须在源文逐字存在；
   源文自带笔误照录只报告（如 48 的 `3.Delete`）。
   渲染规范（2026-09-17 用户审图拍板，代码在 `frontend/js/markdown.js` + `subjects.js`）：
   连续普通行合并成一段（源码硬换行 artefact 还原，中日韩相接不加空格、ASCII 词间加空格；
   选项/序号/标题/代码行断开，`data-pi` 空锚保留跳转精度）；无围栏代码连续行自动收进
   `<pre>`（语言嗅探只打 `lang-c/lang-py/lang-sql` class，不高亮；`{`/`*`开头与 `(1)` 选项式
   已区分）；行尾反斜杠 artefact 全路径剥除（`FulltextParser.split` 入库侧亦剥，单测钉住）；
   公式优先 `$…$`/`$$…$$` 走 KaTeX（文本层有公式文字才转写，向量公式图无文字层则保留图片，
   转写须逐符号对原图核对）；`md-img` 原生直连会 401，落盘前改 `data-mdsrc` + 空 src，
   token-fetch 回填 blob（另有视口 IO 懒取 + `w=1200` 缩放）；全文窗无限滚动只增不减，
   大章节以滚动渐进加载为准验图（勿用“等全图 complete”断言 lazy 图）。

---

## 8. 已知坑（一行一条，踩过即记）

- Git Bash 并行 Edit 同文件互覆盖 → 同文件多改串行，每次 grep 复核。
- 真实 DB 证据测试不假设种子账号；Kafka 证据失败先查 9092；`general_log` 用完即关。
- Flyway 版本号动手前先 `ls` 取空闲；并行会话覆盖 jar → 长跑用私有副本（见 §5）。
- 增量构建残留、`ZipFile` 覆盖、checksum、jacoco、JSON 列映射——见 §3/§5，不复述。
- 改 extra 前读全文件（并行会话随时加东西；`categoryId` 空 getter 编红先例）。
- Playwright 严格模式：隐藏侧栏里的元素 `waitForSelector visible` 必超时——按 `offsetParent` 判可见行再点。
- 同一本书在目录树 + 已打开抽屉各有一行（`data-book` 会命中 2 个），点能打开阅读器的那行。
- `extracted_text` 存的是字面 `\n` 转义串（非真实换行），解析前先 `chr(92)+'n' → chr(10)` 还原。
- Write 落盘 CJK 曾出现 NUL 杂散字节——新脚本落盘后必跑“非 ASCII 字符普查”，`\u` 转义优先。
- Read/控制台 CJK 显示污染（GBK 往返）会改字形：book52 曾出现整段误读（线性表串进图结构）。
  判据：关键事实一律用脚本内断言（_token/行号/计数/子串存在_）钉死，不用肉眼读屏下结论；
  脚本断言通过=事实成立，读屏只做辅助定位。易错写法：`head`/`tail`不可用、`curl`劫持、
  反引号转义——见 §5。
- `.bat` 里 `curl -w` 的 `%` 写 `%%`，`--noproxy` 的 `*` 加引号；PowerShell 里 `cmd /c` 传参引号地狱一律改走 `.bat` 文件。

---

## 9. 完成标准

- 端到端测试 + 长时验证，摒弃“仅编译通过”；每项修复附 `output/` 可复现证据。
- **30 分钟零问题监督窗口**为完成闸口；审计/越权指控逐条代码核实，警惕前端缓存掩盖后端变更。
- **已检查定义**（2026-09-16 用户拍板：`C:\Study` 各文件夹 `已沉淀.txt` 逐行打标）：
   只有“逐题审 + 浏览器验”过的才标 `已检查2026-09-16`；仅门禁绿的标 `门禁绿2026-09-16(未逐题)`，
   两者不混。标完即落盘 UTF-8（python 写，回读校验无 NUL）。
   行首格式（2026-09-17 用户拍板）：各行行首换 `book{id}`/`paper{id}` 领头
   （如 `book108 | … | SCAN | …`），原 file-size 映射备份 `output/_sizemap_backup.txt`；
   无 id 行（DELETED/NOT-IMPORTED/注记行）保留原样。
- **每书四阶段**（每个源文件走一遍，有多少文件执行多少次）：①判定形态
  （全文/背诵/试卷/SCAN，§7.9 标准；卷答配对强制合一，禁卷答分离多造卷）
  ②走接口入库（§11 沉淀接口）③DB + 浏览器验收：标题分级（禁一坨 `<p>`，禁跳级）/
  代码 fence（禁裸段落）/ 图原位（有文字层禁截图充数）/ 问答归位（答案禁进题干）/
  目录可读 / 解析不缺 / 序言·适用人群·小结→note 非卡片
  ④不行重来→行了在 `已沉淀.txt` 打标（`已检查2026-09-16` = 逐题审 + 浏览器验全过；
  仅门禁绿标 `门禁绿2026-09-16(未逐题)`）。PUT 全量换节必须先过**无损校验**
  （新节拼接 ≡ 旧节拼接 + 源文恢复串，恢复串须在 `extracted_text` 逐字存在），
  证据落 `output/` 再落库。46/47/44/45 已按此走完。
10. **长驻进程拉起**（整机回收后）：`cmd /c output\_boot-all.bat`（Git Bash 脚本，一次拉
    redis→kafka→8080，日志 `output/_boot-{redis,kafka,app}.log`；setsid 缺失无碍，
    8080 约 60s 后 LISTEN，health UP 再加 30s 才可登录）。直起不用 `start "title"`
    （Win10 `start` 标题语法坑，用 bat 包一层）；`Start-Job`/`Start-Process` 拉的会被回收。

---

## 10. 安全与授权边界

- 不得擅自改 CLI / 模型配置；改前报告证据等明确授权（AGENTS5 只评估不实现）。
- 不编造引用、指标或提升倍数；未实现 / 未验证 / 未测量明确标记。

---

## 11. 数据现状与 extra 接口（2026-09-14 实测）

- 科目 5（软件工程）：书 92/100/101（全文）、102/104/106（背诵；93/105/103 判重已删，备份在
  `.workbuddy/backups/`）、107–110（24/25 真题答案 SCAN，已归 cat21 历年真题）；
  卷 203463（教材 93Q）/ 223520（2026 真题 33Q，kp 标签待回填）。
- 科目 6（数据结构）：书 38（C 先修）/39（C 模板 SCAN）/40/41/111（历年真题 SCAN，cat8）/
  43（大纲）/44（速记宝典，27 卡已逐句审 ✅09-16）/45（考前资料，全文 47 节已源比对 ✅09-16）/
  46（综述 112→102 已重建 ✅09-16）/47（综述 86→74 已重建 ✅09-16）/48–53（综述未审）/
  54–61（配题未审，60 已修 40→23）；卷 223496（已修 15 道归位 + 门禁绿，未全逐题）/
  223497–503（修了抽到的问题项，未全逐题）/223511（考前 21Q，已修 2 道）/
  223512–519（配套习题 22–58Q）。`习题题库与解答.pdf` 判重已覆盖，不入库；
  `extra_question` 孤儿 188437:23 未动。打回（源卷库内均无，不编）：503#147 括号匹配完整函数、
  500#84/#94 修改后代码示例（文字方案保留）。
- 账号：`admin` 口令见本机密码管理器（**不入库**，bcrypt cost 10）；E2E 学生侧 `student/student123`。
- 端口：preview/confirm 全套 `POST /api/ingest/{auto,single-file}/preview+confirm`、
  `{fulltext,recite,paper-pair,scan}/preview+confirm`（大文件 `?async=true` → `GET jobs/{jobId}`）；
  `POST /api/ingest/{questions,book,extract}`；质检门禁 `GET papers/{id}/quality`
  （total/noAnswer/tainted/dup + issues，零问题卷才放行）；
  `GET/PUT subjects/{sid}/books/{bid}/{sections,images}`；`GET/POST
  subjects/{sid}/categories/{tree,CRUD,assign/unassign,items}`；
  `GET extra/questions?paperVersionId=`（读）/`POST bulk` + `DELETE`（ADMIN）；
  `MaintenanceController`：`resplit/preview+confirm`、`POST papers/{pid}/questions/{qid}/split`、
  `QualityController`：`overview` + `papers/{id}/repair`。上传上限 50MB（`scripts/.env.local`）。
- **沉淀接口（供别的 AI agent 调用，路径已按 extra 代码核实 2026-09-16）**：
  修单题 `PATCH /api/extra/maint/papers/{pid}/questions/{qid}`、
  全量换节 `PUT /api/subjects/{sid}/books/{bid}/sections`（配无损校验）、
  质检门禁 `GET /api/ingest/papers/{paperId}/quality`（零问题才放行）。
  完工后写 `docs/` 接口文档，调用全程走这三个。
- 扫书证据：`output/sweep-batch*.log`、`pilot44-evidence.log`、`research-recite-policy.log`。

---

## 12. 台账（只记结论 + 证据指针）

- **合卷**（09-11）：选择与非选 reunion，`extra_question` 表 + `xid=1e9+id` 前端合流；
  幂等键 paperVersionId + 归一题干；答案块归前面题干。`?v=` 改内容必 bump。
- **试点 44**（09-13/14）：背诵书 30→19（PUT 全量换节，无损校验 + 浏览器 8/8/3）。
- **真源裁决**（09-14 用户拍板）：背诵书 `srvData` 非空一律渲染服务端节（subjects.js 一行）。
- **答题反馈**（09-13/14）：答对 toast + `jumpToNextAfter` 立跳（选择/文本统一）。
- **去重**（09-14）：93/105/103 判重删除（留 104/102/106），备份可回滚。
- **UI 冻结**（09-13）：dashboard 欢迎区、影院配色（深蓝单色+暖金，回滚 checkout 三个 css）、
  答题页连续渲染架构——改前必须用户批准。
- **左栏死锁根治**（09-15，用户报"左侧 UI 不显示 + 退出/主题找不到"）：根因四层 ——
  ① `qb.side.lock` 自菜单键改抽屉语义后再无写入方，读侧恒 `true` ⇒ `display:none` 热区 +
  悬停召唤被挡 + 科目页左树永久收起（三重死锁）；② `.rail-hot z-index:65 < .sidebar z-index:70`
  ⇒ 悬停召出侧栏后微光条被盖住，点击落到 `.content`；③ 入口 script 带 `?v=` ⇒ app.js 双实例，
  后注入 shell 把先注入的节点全变死节点（侧栏/主题/退出失联）；④ 侧栏内按钮 onclick 就地改写
  `innerHTML`（`themeBtn.innerHTML = icons.sun`）⇒ 事件冒泡时被点 SVG 已脱离 DOM，
  `sbEl.contains(e.target)` 恒 false，被判"点外部"当场关抽屉。
  修复：默认**开锁**（仅显式 `localStorage['qb.side.lock']='1'` 才锁，锁态仍有微光条保底入口）；
  热区 `z-index:75` + 锁态 `clip-path` 竖带；入口去 `?v=`（靠 server.js no-cache）；
  点击判定改 `composedPath()` + 点击时重查 `#sidebar` 活节点 + `modal-scrim` 豁免；
  `mountApp()` 加 `data-shell-mounted` 幂等栅栏（防双实例）；`#welcome` 深链修正
  （`pendingPath` 只收 `/` 开头路径、空路径不覆盖），否则直接开 `#welcome` 会停在空 outlet、
  热区 0×0 被误读成"没进站"。深链 `#/welcome` 3/3 可达，点「进入」后热区 10×900 可用（`output/_wel.log`）。
  桌面 `#menuBtn` 定案为 zen 开关（`body.rail-off`，studio.css §14）——左栏的**唯一常驻入口**
  是左缘微光条，zen 态属用户显式收起（再点恢复）。
  证据：`output/sidebar-diag.log`/`sidebar-diag-2.log`（根因取证 INSTANCES/CAUSE/RAILGEOM）、
  `output/sidebar-fix-probe-final.log`（A–F 全绿：悬停召唤、微光条点击开抽屉、抽屉内主题/退出/V2
  模态取消均保持开启、Esc 关、导航跳转关、D2 桌面科目页 `#/subjects/5` 左树 `treeW:264`
  可见、移动端零回归、`pageerrors=[]` `httpErrors=[]`）、`output/rail-hit-probe.log`
  （`z-index:75` 不抢左树点击：热区在科目页 `w:0`，行左缘 x=31 点击归 `side-tree`）、
  `output/i18n-parity.log`（顺带补齐 3 个既有缺口：en `exam.filterNone`/`exam.noQuestions`、
  zh `wrong.loadFail`，现 zh=412 / en=412 零缺口）、`output/supervise-final.log`
  （30 分钟窗口：60s 健康采样 + 每 5 分钟无头冒烟→0 fails 才算过闸）。
- **移动/平板全面检查**（09-15）：真实触摸（`touchscreen.tap`）三视口 phone 390×844 /
  tablet 820×1180 / land 1180×820 全绿：顶栏、侧栏抽屉（触摸开/主题不断抽/导航关）、
  科目页零溢出、右缘光条点按开抽屉、答题链（选中+命中+答案展开+答对 toast）、翻题条、
  FAB 回顶、三视口 scrollWidth==innerWidth、`pageerrors=[]`。
  修复 1 项：手机端大纲/文件夹抽屉关不掉（本体无关闭钮 + 右缘二次点按被抽屉 z-index
  覆盖 + 触屏无 Esc/hover-out）→ subjects.js `wireRdrGlobal()` 补触屏点抽屉外部关闭
  （对齐侧栏语义，桌面零改动，桌面回归 hover 开/离开收/点内容不误伤）。
  非问题定性：① 二次点热区不关=抽屉本体覆盖热区；② book 39（FULL_TEXT、`.doc` 原件）
  `images` 400 属设计内防御，前端 catch 兜底 count=0 零感知（`_q39c.out`）；③ 错题页空=无数据。
  遗留已裁决（09-15 用户拍板：**维持现状不扩容**）：`menuBtn` 34×25（×0.75≈26×19 设备 px）
  低于触控建议值，但用户实测可用，不再加 `pointer:coarse` 扩容（后续会话勿再提）。
  证据：`output/mobile-touch-{phone,tablet}.log`、`mobile-touch2-*.log`、`_touch-outside.cjs`、
  `_desktop-drawer.log`、`shots-m/touch*-*.png`。

---

- **格式优先纠偏**（09-16，用户批评“光对了字数、没验格式”）：`已检查` 重定义为格式验收
  （§7.9：标题分级/md fence/图原位/问答归位/目录可读/解析不缺/序言note/卷答合一），
  字数仅作映射参考（`file-size > mapping`）。软件工程 12 + 44/45/46/47 已按此口径打标 `已检查2026-09-16`；
  46（112→102）/47（86→74）系标题碎重建。未审队列：综述 48–53、配题 54–61、
  卷 223496–503/223511–519（门禁绿未全逐题）、书 38/39/40/41/43。
  证据：`output/research-format-20260916.log`（md 排版规范：单 H1/禁跳级/fence 小写语言/标题前后空行）。
- **图库悬空**（09-16）：图已存但正文零引用——`images` 计数 92:125 / 100:655 /
  101:290 / 45:326 / 38:52 / 43:26，但节正文 `[IMG]`/`images/` 引用仅 book100 一处
  （且 alt 空 + 图号处乱码待查）；背诵书 44–61 每书 8–29 张同样悬空。
  引用归位是全文书专项任务，排在背诵书问答修复之后，按插图三问逐图判定。
- **48 真源恢复**（09-16）：`extracted_text`（9846 字 > 节拼接 8839 字）含完整源句，
  切碎处（大O“表示T(n)…”、GCD 步骤2/4）从源文逐字恢复；源文亦带笔误（`3.Delete`）
  照录。方法：恢复串须在源文存在 + 新节拼接无损，见 `output/extract-probes*.log`。
- **书48 解析器修复**（09-17）：病灶两条——①`SECTION_HEAD` 正则定义后**零调用**（死代码），
  「第一节 基本概念和术语」被当正文并进「核心目标」卡答案；②编号术语定义行
  `4. 数据结构（Data Structure）：…简单说，` 整行成标题、折行续行「就是…」掉进答案
  （`NUM_HEAD` 无术语拆分；`isTerm` 上限 14 字且 TERM_BAD 含数字，编号行永远进不了术语卡）。
  修复：①启用 SECTION_HEAD 收紧为 `第X章/节/讲/部 + ≤28字短标题`（无句读，光杆也拦），
  有卡丢弃/无卡归前言，「一、」形式维持现状；②新增 `splitNumTerm`——冒号前 ≤24 字无句读
  术语拆「术语标题+完整定义」，题型词/答题标记/冒号收尾/带句读不拆（守门测试钉住）。
  测试：`ReciteParserTest` 13/13（新增 3 条：混节、编号术语、守门对照），全量 199 跑 0 挂
  （30 skip=需真实中间件的集成测试）。
  真实重解析书48 全文：80 卡（原 74），0 章节泄漏、0 半句标题、0 逗号收尾标题，
  术语卡（数据/数据元素/数据项/数据结构/集合/线性/树形/图形结构…）全部完整成卡。
  规范已沉淀 `docs/电子书入库接口规范.md` §1.2。
  证据：`output/_b48-reparse.txt`（重解析全卡）、`_b48-verify.txt`（断言核验）、
  `_b48samp*.txt`（源文逐字样本）、`_b48-text.txt`。书48 尚未回填 DB（需 PUT 全量换节
  过无损校验），未逐节核对+浏览器验收前不打「已检查」。
- **书92/100/101 黏行标题修复**（09-17，用户报「明明有知识点1但右侧大纲没有」）：
  病灶——PDF 抽取把「知识点N 标题 + 一、标题 + 正文首行」压进同一行；前端 `innerHead`
  （subjects.js:115）对 >40 字行拒绝当标题 ⇒ 黏正文的「知识点1」既不进大纲、
  短黏行则整行变 h3 吞正文。**属数据问题，前端判据正确**。
  修法（四步管道，复用 book92 既有 apply 模式）：快照 → 源文标题索引（含「一、」副标题
  归属）→ 仅插换行候选（逐节 `old.replace('\n','')==new.replace('\n','')` 无损断言）
  → 前置校验（源文/节未漂移）+ `.workbuddy/backups/` 备份 + svcagent PUT + 读回断言。
  book100：454/454 源文标题命中、黏行 128→0（44 节改 34）；book101：242/242、73→0
  （9 节改 7）；book92 由并行会话先修（134/134 浏览器绿）。浏览器实测（chrome-153
  headless，live 数据）：100 = 大纲 310 行/222 知识点条目/0 长 h3；101 = 270/252/0；
  均无 pageerror。源文自带重复标题（92 的 p50/p66/p89 三处）逐字核实照录不删。
  证据：`output/book{92,100,101}-heading-{snapshot,candidate,audit,applied}.json`、
  `output/bx-browser-{100,101}.json`、`output/_b92-residual.txt`、`_b92-srcdup.txt`；
   管道脚本 `output/bx-heading-{candidate,apply}.py`、`bx-browser.cjs`
   （apply 用 `output/_svcagent.json` 明文凭据已删除；svcagent 账号本体仍按 223503 台账待删）。

- **渲染六修**（09-17，用户审 b45 截图报 6 条：右斜杠/代码无 fence/公式图片/行长不齐/切章卡顿/全靠左）：
  根因——①入库把字面 `\n` 还原成真换行剩行尾 `\`（47 全书 222 处）；② `mdLite` 逐行 `<p>`
  + `\n→<br>` 把源码硬换行全变成视觉断行。修法（渲染侧，DB 不动；只动 `markdown.js` +
  `subjects.js` + `FulltextParser`）：连续普通行合并一段（选项/序号/标题/代码断开，
  `data-pi` 空锚保跳转）/ 无围栏代码自动收 `<pre>`（`lang-c/py/sql` class，只标记不高亮）/
  行尾 `\` 全路径剥除（入库侧 `split` 亦剥，`FulltextParserTest` 钉住 9/9）/ 公式 `$…$` 走
  KaTeX（b45 折半 ASL 图转写已逐符号对原图核对，PUT 46 原样+1 改）/ `md-img` 401-defer +
  视口懒取已有（并行会话）我方只补开书预热 + 跳转期哨兵暂停 + 跟踪 1.5s 冻结。
  跳转抖动 310px/CLS0.59 → spread0/CLS0（`output/_jitter*.log`，LayoutShift 实测）。
  版式判定：内容区已 1080 满幅（窄是抽屉 overlay），660/980 上限不动（CSS 另有会话在改，
  只 bump `?v=` 不动其规则）。证据：`output/_render_verify2.log`/`_scroll45b.log`/
  `_katex3.log`/`_mdtest_run.mjs`(15/15)/`tri-smoke` 全绿。
  教训：并行会话的 WIP（如 `mdImgSrc` 未完成的 helper 抽取）会直接打断整站渲染——改共享
  前端文件前重读 gated 区域，冒烟红先看是不是他人半成品（本次 restore 其调用点为可用态，
  等其 helper 落地再合）。

- **表格渲染 + book53 重建**（09-18，用户报「原文是表格的你也要在网页渲染成表格」并要求修复 b53）：
  ① **前端新增 markdown 表格**（`markdown.js` mdLite + `subjects.js` renderMdChunk 两处同口径，
  新导出 `isTableRow/isTableSep/splitRow/tableAlign`）：`| a | b |` + `| --- |` 分隔行 → 真
  `<table class="md-table">`（表头 `<th>`、对齐 class `al-*`、单元格走 mdInline、`\|` 转义支持）；
  `.md-table-wrap` 横向可滚不撑破正文列；`ANS_STOP` 把表格行列为结构边界，答案折叠**不吞**表格行。
  单测 `output/_mdt/test.mjs` **27/27**（外管/无管/对齐/转义管/`---` 仍 hr/围栏优先/单元格行内格式）。
  版本：`pages.css?v=20260925a`、`subjects.js?v=20260918tf1`。
  ② **book53 重建**：台账前标 `已检查2026-09-17` 经逐字符比对**不成立**——旧 110 节相对源文
  丢 **1204 字**（前言课程名/教材元数据、广义表示例「长度：4/3」、`销毁（DestroyArray…）`、
  next 表 `next[j]` 行、`Chunk/LString` 整块、多处句尾截断），三处 C 代码块缺大括号**不可编译**，
  标题 36 处截断 + 8 处整句。按源文逐行重建为 **21 卡**（一卡一问，book51 口径；PDF 文本流里
  排在正文之后的代码块归位到各自「C语言定义…」标签下加 ` ```c ` 围栏；三张被拍平成单元格行
  的表转 md 表格）。
  ③ 闸口：源文逐字符缺字 **0**（`output/_b53_rebuild2.py` 的 Counter 闸）、21 条恢复串
  **在 `extracted_text` 逐字存在**、PUT 读回 `CONTENT_MISMATCH=[]`、浏览器 **7/7**
  （3 表行列/代码 fence/meta note/零 pageerror/零 401/零 mojibake）。
  ④ 台账纠正：`book53 | 21(116→110→21源文级重建) | 已检查2026-09-18`。
  ⑤ 窄屏复验（phone 390 / tablet 820 / land 1180）：三视口表格均渲染成真 `<table>`、
  `.md-table-wrap` `overflow-x:auto` 自行滚动、`documentElement.scrollWidth==innerWidth`（滚动元素不溢出）、
  零 pageerror —— **4/4**（`output/_b53_mobile_tbl.log`）。注：`body.scrollWidth` 手机端恒
  >innerWidth（520），book52（无表格）同值，属既有基线**非本次引入**（`output/_ovfchk.cjs` 对照）。

- **题库 8 卷逐题四件套体检**（09-18，223496–223503 共 1184 题；门禁早已全绿，本次查**门禁查不到的两类**）：
  ① **选项被 PDF 硬换行截断 → 题永不可判分**（9 题：223497#12/15/26、223498#40、223500#17/22/36、
  223502#4/20）。病灶：长选项在源 PDF 折行，入库只留首行（选项截断），而 `answer_json` 保留折行
  `\n`；前端判分是 `options.indexOf(correctAnswer)`（`exam.js:98`）⇒ 字符串不等 ⇒ `hitOf` 恒空，
  **点对了也不判对**。修法：`PATCH correctAnswers` 去 `\n`（结果 = 源文逐字 = 已存选项文本；
  `ALL_VERIFIED=True`，9/9 回读可判分）。
  ② **题干被标签顶替**（223502#105）：`QuestionParser.java:135` 把 `解析：`/`解题思路：` 当解析起点，
  源文折行处的「解题思路」被切成了**题干**、真题干（`下列关于二叉树的说法正确的是（ ）`+A–D）整块丢失
  ⇒ 学生看到的问题就是「解题思路」四字。修法：PATCH prompt/options/correctAnswers/explanation/type
  （5 串均在该题源 PDF 逐字存在，源 = `章节拆分知识点综述/…第五章 树与二叉树…pdf` 的「典型例题」段）。
  DB 全域该标签仅此 1 处题干 + 1 处答案前缀（223502#106，源文自带，照录）。
  ③ **120 题 SHORT_ANSWER 无 explanation = 源文性质**：源卷简答题体例是「参考答案：」而无独立「解析」，
  `correctAnswers` 已含完整参考答案 ⇒ **照录不编**（§7），非缺陷。
  ④ 结果：门禁 `0 污染/0 缺答案/0 重题`，四件套 `ungradeable=0 / newline_answers=0`；浏览器 **4/4**
  （223502 q105 显示真题干+4 选项且判对、223497 q12 折行项判对、零 pageerror）。
  证据：`output/_wrap_sweep.py`→`_wrap_issues.txt`/`_wrap_verify.txt`、`_wrap_fix.py`→`_wrap_fix.txt`、
  `_paper_final_check.txt`、`_paper_triage.py`、`_exam502.cjs`→`_exam502.log`；
  备份 `.workbuddy/backups/qids_wrapfix_before_20260918.json`、`q105_1558_before_20260918.json`。
  教训：**质检门禁只查"答案缺失/题干污染/重题"，查不到"答案串与选项不逐字相等"** —— 折行 `\n`
  这类不可判分题全绿通过。门禁应补一条 `choice_answer_not_in_options`。

- **软件工程 4 卷体检 + kp 回填**（09-18，subject 5：223520/223521/223522 真题卷 + 203463 教材习题，共 193 题）：
  ① **全卷无知识点标签**（门禁 33/38/29 条 `KP_MISSING`，即台账 `[kp待回填]`）：按本项目
  《【精讲总结】13005软件工程》(book100) 的**章名**逐题回填（`第一章软件工程概述`…`第七章软件项目管理`）。
  归类规则（写进台账）：RUP/软件过程/生存周期/过程模型→第一章；CMMI/过程改善/团队结构→第七章；
  耦合内聚/DFD/详细设计→第二章；UML/面向对象→第三章；移动应用→第四章；测试→第五章；维护→第六章。
  结果：`kpMissing=0`，**223520/223521 issues 33/38→0**；223522 余 1 条为下文误报。
  ② **门禁误报 1 条**：223522#8「在C++中，对象的____称作成员函数」（正确答案 = `行为`）被判
  `PLACEHOLDER_ANSWER` —— `IngestionService:1244` 规则「答案 <25 字且以 为/有/如下/包括/： 结尾」
  把正确单概念答案 `行为` 误伤。全 12 卷扫描该规则**仅此 1 例**命中（223503#147 走「参考本章…」
  另一条规则，是真占位）。**数据无需改**，修的是门禁判据。
  ③ 203463 门禁本就全绿；四件套复核四卷均 `ungradeable=0 / newline_answers=0 / taintedPrompt=0`。
  ④ **遗留（未做）**：`exam.js:133 chapOf` 定义后**零调用**（死代码），答题页大纲按「题干【题型标】」
  分组 ⇒ kp 回填目前只满足门禁/质检口径，**章节分节在 UI 上看不到**；要在界面显示需前端补挂载。
  证据：`output/_sw_triage.txt`、`_sw_final.txt`、`_sw223520_kp.py`、`_sw_kp_fill.py`、
  `_sw_exam.cjs`→`_sw_exam.log`、`_sw_placeholder_scan.txt`；
  备份 `.workbuddy/backups/p{223520,223521,223522}_kp_before_20260918.json`。

- **配套习题 9 卷四件套复核**（09-18，papers 223511–223519 = 考前 21Q + 第一章~第八章配套习题 285Q，
  共 **306 题**；此前台账只写「修了抽到的问题项，未全逐题」）：逐题四件套结果 **不可判分 0 /
  答案含字面 `\n` 0 / 题干污染 0 / 缺答案 0**，九卷门禁本就 `kpMissing=0`。
  唯一疑似项经**溯源判非错**：223513#2 与 #4 题干同文（`下列关于线性表的叙述中，错误的是________。`）
  —— `习题题库与解答.pdf` 里该题干出现 **2 次**，选项与答案各不相同（源 2 = 链式/顺序存储题 → B；
  源 4 = 前驱/后继/空表题 → D），DB 逐字复现两题 ⇒ **照录**（§7 原样成卷）。
  147 题无 `explanation` 全为简答/填空 ⇒ 源只给「参考答案」无独立「解析」，照录不编（同前）。
  另注：该卷源标注 `答案：B。` 为**字母**，入库已正确解析成选项**文本**（前端判分口径所需），
  说明数据结构卷的字母→文本解析本身是好的 —— 上一轮 9 个不可判分题确系**折行 artefact** 而非字母问题。
  证据：`output/_px_triage.txt`、`_px513_src2.txt`、`_px_titles.txt`。

- **book38/39 渲染验收**（09-18，C语言手册【代码看】）：
  - **book38**（C语言基础-数据结构先修）：DB 里**无 `#` 标题、无 ` ``` ` 围栏**，但属**渲染侧兜底**、
    不是缺陷 —— `subjects.js renderMdChunk` 的 `innerHead` 标题提升 + `isCodeLine` 自动收 `<pre>`：
    DOM 实测 **h=19 / pre=86 / toc=32**；70 条 `![..](/api/.../figures/p/i)` **逐条 URL 全部 200**。
  - **页码 artefact**：DB 正文含 **52 行独立页码**（1..52，与 `pageCount=52` 一一对应），
    但 `subjects.js:645` 对每个节 body 先过 `cleanFullText`（剥封面块/目录点线/「4 / 125」式页码）
    ⇒ DOM 实测 `standalone-digits=0 / digit-before-heading=0 / ``` 泄漏=0`，**未污染渲染**。
    「extracted 有而节缺」521 字 = 目录 494 字（阅读器用自建大纲替代，非内容）+ 标题碎片 ⇒ **判非内容丢失**。
  - **book39**（C模板 `.doc`）：源文逐字**零缺**，`pre=100`，无图。它的 `/images` 与 `/figures`
    返 **400 属设计内防御**（`SubjectController:58/81` → `imageCount/figureList` 对 `.doc` 无页结构直接拒），
    前端 `.catch(() => ({count:0}))` 兜底、零用户可见影响 ⇒ **非缺陷**（存量文档已记，回归判据应排除）。
  - 判据口径：**全文书验收看「渲染后 DOM」而非 DB 标记**（§5「DOM 断言 > 截图」的延伸）——
    DB 无 `#`/无围栏 ≠ 渲染不对；同时**不得**用「等全图 complete」断言 lazy 图（book38 首屏只 10/70 加载属正常）。
  - 作业中 8080+3000 双双被整机回收杀掉一次，按 §5 重启（`bash scripts/run-app-3306.sh` + `cd frontend && node server.js`）。
  - 证据：`output/_c38_audit.txt`、`_c38_loss.txt`、`_c38_pageno.txt`、`_c38_verify.log`、
    `_c38_dom.json`、`_c38_ctx.json`。

- **book43 补节 + book40/41 SCAN 验收**（09-18，subject 6 收尾）：
  - **book43（自考大纲, SYLLABUS）原 0 节 ⇒ 一坨 `<p>`**：SYLLABUS 无节时 `subjects.js` 走 `paras`
    路径（`innerHead` 标题提升**只在有服务端节的分支里跑**）⇒ 实测 `h=0 / p=302`，源文自带的
    `I.课程性质与课程目标`、`一、课程性质和特点`、`第一章 绪论` 全渲染成平铺段落（§7.9 禁）。
    修法：**按源文自身标题行切 16 节**（大纲前言 / I./II./III. / 第一章~第八章 / IV./V./VI./VII.），
    段=extractedText 逐行原样（不重打任何字）。闸口：`body` 拼接 ≡ extractedText **逐字 15448=15448**。
    修后 DOM：**h=0→72、大纲行 16→32**，零 pageerror（`output/_b43_shot.png` 目视确认层级与排版）。
    要点：**SYLLABUS 这类「仅 extractedText、零节」的书是全文渲染的盲区** —— 补节即回到
    `chapterSections` 分支，标题分级/大纲/跳转全部生效。
  - **book40/41（真题 SCAN）判合规**：判据是**源 PDF 文字层是否为空**（§7.9「SCAN 只收零文字层」），
    **不是**看 DB 有没有文本。离线逐页量：2024年10月 5 页 ×3 图 / 2025年4月 6 页 ×3 图，
    **文字层 0 字**；本机无 OCR（§2）⇒ 只能图页，分类正确。`images.count=5/6` 与 `pageCount`
    一一对应，逐页 `/images/{i}` **全部 200**（114KB~245KB）。**注意 img 模式是分页的**：
    DOM 里同时只挂 1 张页图，**不能用「imgEls 数量」判断 SCAN 完整性**（我第一版探针因此误判 FAIL），
    要逐页拉 `/images/{i}`。
  - 证据：`output/_b43_sections.py`、`_b43_heads.txt`、`_b43_new_sections.json`、
    `_b43_put_verify.txt`、`_scan_src.txt`、`_scan_pagebytes.txt`、`_scan43_verify.log`、
    `_b43_shot.png`；备份 `.workbuddy/backups/book43_before_sections_20260918.json`。

- **软件工程 6 本书复验**（09-18，subject 5：书 92/100/101/102/104/106；旧标记 `已检查2026-09-16`
  是**弱标准**，本次按 §7.9 格式 + 源文保真 + 浏览器全套复算）：
  - **格式/渲染（DOM 实测）**：92 `toc352/h198/p752`、100 `toc310/h448/p780`、101 `toc270/h421/p334`；
    102/104/106（背诵书）`cards=23/27/116`。**六本零 pageerror、零独立页码污染**。
  - **book101 只 9 节却 `h=421`**：有服务端节时 `subjects.js` 对**每行**跑 `innerHead` 提升
    （`paras.push(innerHead(p) + p)`）⇒ 不缺分级。**与 book43 的 0 节情形不同** —— 判断「会不会
    一坨 `<p>`」要看**有没有节**，不是看节数多少。
  - **保真**（extracted vs 节拼接）：92 `body/ext=0.977`、100 `1.055`、101 `1.014` —— 缺口主要是
    **目录/点线/装订线**（阅读器用自建大纲替代，非内容）；104/106 含标题后 `del_chars=98/340`
    且 **≥12 字的丢块 = 0**（仅碎片）。**单看 body 会误报**——标题/正文切分正是 b53 的同款陷阱，
    背诵书必须比 `title+body`。
  - **图**：`md-img` 是 `src="data:,"` + `data-mdsrc` **延迟取**，实测 figures 请求 32 次**全 200**、
    且有 1 张 `1425×1037` 的 blob 原图落地（`_s5_b92_shot.png`）⇒ 正常。
    ⚠ **不能用 `imgEls`/`imgLoaded` 数量判图**（无限滚动 + 延迟取，视口外恒 `src="data:,"`，
    我第一版探针因此误报 `0/88`）—— 要「滚到图再量 `naturalWidth`」或直接拉 `/figures/…`。
  - book102 `extractedText` 为空（纯节书），无源文可比。
  - 证据：`output/_s5_audit.log`、`_s5_fidelity.txt`、`_s5_recite_fidelity.txt`、`_s5_b92_shot.png`、
    `_s5_img.cjs`。
  证据：`output/_AUDIT_b53_*.{txt,json,png}`（源↔DB 逐字符比对/丢块/表图）、`_b53_new2_dump.txt`、
  `_b53_browser2.log`、`_b53_table_shot.png`；备份
  `.workbuddy/backups/book53_sections_before_rebuild_20260918.json`。
   教训：**「旧节拼接 ≡ 新节拼接」的无损闸门不够**（旧 `LOSSLESS=PASS` 仍丢 1204 字）——
   PUT 前必须与**源文**比对（字符计数 + 恢复串回源校验），不是只与旧版比对。
   标题政策综合（09-18 方法仲裁沉淀）：标题允许编辑，但须同时满足——①原标题是裸/碎片
   （小白案例×N/逻辑/单字等，逐字照录违反 §7.7 独立可读）；②新标题与源文锚定文本共享
   bigram/关键词（程序断言，不过即逐条核查）；③正文走源文 Counter 闸（缺字零容忍）。
   纯 “源文无此字句即编造” 的判据只适用于正文/答案/代码，不适用于标题（否则 §7.7 无法执行）。

- **答题页跳转抖动修复**（09-18，用户报「下一题和题号跳转会快速抖动和模糊」）：两层根因，
  均有探针实锤。① 远跳时 `ensureExamRendered` 只补渲染到目标卡 ⇒ 目标卡是文档最后一张，
  首滚被 maxScroll clamp 贴底 ⇒ 哨兵逐段追加（每段 ~716px）+ 跟踪器逐段追 ⇒ 阶梯瞬跳。
  修法：目标之后再预渲染一段 `EXAM_STEP`（`i+1+EXAM_STEP`，纯追加不重建）。
  ② **本机 Chrome 153 的 `window.scrollBy(字典)` 无论 `behavior:'auto'/'instant'` 都走线性
  滚动动画**（同步只前进 ~667px，~150ms 爬完；四组对照 `output/_scroll_state.log`：
  a/b 走动画、`scrollTop` 赋值与 `scrollTo(x,y)` 同步落位 landSync=true）——跟踪器每帧
  重发 scrollBy = 每发打断重启动画 ⇒ 210ms 内 10 次补滚、5 次 ~716px 阶梯
  （`output/_jump_probe.log`/.before.json），即「快速抖动+模糊」。修法：`place()` 改
  `scrollingElement.scrollTop` 直赋。修后同探针：railNext/chip **corrections=0、CLS=0、
  无 pageerror**，远跳一帧落位（y=438→4019 精确到锚点）、stable 67.6ms。
  版本 `exam.js?v=20260923d`（⚠ 探针曾被自己写的错误标记串误判「旧代码」——grep 串
  必须与源码字面一致）。证据：`output/_jump_probe*.json|log|cjs`、`_scroll_state.log|cjs`。

- **门禁两处修复**（09-18，`IngestionService.java`）：
  - **`ungradeable` 字段 + `CHOICE_ANSWER_NOT_IN_OPTIONS` 检查**（封堵 ingest 侧盲区）：
    选择/判断题 `answers ⊄ options` 即计数 + 报告（最多 50 条 issues）。
    修后 21 卷复扫 **ungradeable=0**，223522#8 误报消失（只 223503#147 真占位）。
    **注**：PATCH 维护接口**本就有**「答案不在选项中，拒绝写入」守卫（`MaintenanceService`
    lines 204-240）——那 9 个不可判分题是走 ingest 进来的（入库侧没这检查），现在双层都齐。
    （⚠ 补记：PATCH 400 = 守卫生效，非故障；新闸口**无法用 PATCH 做注入试验**，要证明
    `CHOICE_ANSWER_NOT_IN_OPTIONS` 会报警需 DB 级注入+备份恢复，本次未做。）
  - **placeholder 判据收紧**（`IngestionService:1244`）：原判据「<25字且以 有|为|如下|包括|： 结尾」
    把合法短答案「行为」误伤（223522#8）⇒ 移除 有|为；全 12 卷回扫该规则命中 1→0，
    真占位（223503#147）走「参考本章…」分支不受影响。
  - 证据：`output/_sw_final.txt`、`_sw_placeholder_scan.txt`、`_paper_final_check.txt`；
    备份 `.workbuddy/backups/p{223520,223521,223522}_kp_before_20260918.json`。
  - 版本：`question-bank-m0-0.1.0-SNAPSHOT.jar`（built & deployed 09-18）。

- **kp 挂到答题页 UI**（09-18，`exam.js`）：`exam.js:133 chapOf` 定义后**零调用**（死代码），
  答题页大纲按「题干【题型标】/系统 type」分组（717 行）⇒ kp 回填只满足门禁/质检口径，
  章节分组在 UI 上看不到。接线（chapOf → 大纲分组 + 主区组头），SW 三卷（223520/21/22,
  kp 覆盖 100%、5-7 个章节标签）→ 章节分组可见；DS 真题卷 kp 是细粒度知识点（无章标记）
  → 保持题型分组不变；223511 kp 是「第X节」→ 不参与章节分（会并组）。

- **全功能 sweep + 522#6 裁决**（09-18）：29 书节数全对上标记（DB 实测），21 卷门禁 20 全绿。
  ① **223522#6（状态机单选只有 A/D 两项）判照录**：源 `全国 2025年4月…真题.docx`
  是 12 张零文字层扫描图，逐页目检 Q9 纸面就只有 A/D（直跳 Q10），B/C 纸面缺失 ⇒
  禁编造，P1 `CHOICE_OPTION_INCOMPLETE` 报警保留，判分不受影响（答案 A 在选项内，
  浏览器点选判对 PASS）。证据 `_522src_img{1,2,3}.png`（Q9 在 img2 尾/img3 头）、
  `_exam522q6.cjs`→`_exam522q6.log`。② **book48 重名**：两张 `核心考点`（#3 数据/ADT
  vs #40 算法策略）→ PUT 改 `核心考点·数据与抽象数据类型`/`核心考点·算法设计策略`
  （读回全等，verify48 68/68 PASS）。③ **标记过期三处**：46（112→102）、47（86→74）、
  102（53→23，库内 23 卡全干净、09-18 复验 DOM=23，标错）已更正。④ 后端只读+守卫 sweep：
  student/admin 两角色、`extra/questions` 空是传了 paperId 而非 versionId（考试走
  ingest 路径 21 题正常）、`qc/overview` 正路是 `/api/extra/qc/overview`（p0 全零；
  513 的 P1 重复题干是已备案照录对；p2 全是 EXPLANATION_EMPTY/HARD_WRAP 类已知软项）。
  ⑤ cinema/focus 旧冒烟 FAIL 系探针过期（按登录页+light 默认写，现免登录+cinema 默认），
  现行 `_uiprobe.cjs`（主题循环/侧栏/专注/科目树）+ `_smoke_full_20260918.cjs` 全绿。
  证据：`output/_apisweep*.txt`、`_quality21.txt`、`_qcp1.txt`、`_522q6.txt`、
  `_put48.py`、`_spot48.txt`、`_uiprobe.cjs→_uiprobe.log`；备份
  `.workbuddy/backups/book48_sections_before_dedup_20260918.json`。

- **答题页「下一题」抖动 + 模糊：根因与修复**（09-19，用户："会剧烈抖动并且模糊好几秒，
  体验极其不好"）。根因是**两套滚动引擎互打**：`scroll-stage.js` 猴补丁了 `window.scrollBy`
  （大位移/同帧叠加起分段 + 接力 rAF，最长 14 帧≈233ms + GIVE_UP 500ms）；而 exam 落点
  `place()` 用 `scrollTop` 直赋、**不触发**模块的 `invalidate()`（模块只在 `scrollTo` 里作废），
  于是接力按"调用方安静 50ms 就补残留量"把视口推离目标、exam 的 `track()`（最长 120 帧≈2s）
  又纠回来——打到接力超时为止，叠加 1.6s 闪光 + 图片解码 = 好几秒。另 `clearFeedback` 的
  塌陷补偿是全链路最后一个字典形 `scrollBy`（Chrome 153 下必走线性动画，09-18 根因二同机制），
  每次点选项/翻题都触发。修法：exam 内程序滚动统一走 `hardScrollTo(y)` =
  `window.scrollTo(0, y)`（被补丁版先 invalidate 作废残留接力/计划、再原生瞬间到位；模块缺席时
  就是原生 `scrollTo`，与直赋同义；本站无全局 smooth）。动两处：`clearFeedback` 补偿、
  `place()`；阅读器/`scroll-stage.js` 本体未动。版本 `exam.js?v=20260923f`（`app.js` import 行
  已 bump；`node --check` 过）。**验证**（`_relaykill.cjs`）：先起 4000px 大分段（接力存活
  rem=3333 实测），立刻点 `#sideNext` → 落位 lateMove=0/reversals=0、接力死透（pending=null）、
  inv 日志 8×scrollTo，errors=[]，**PASS**。回归：`_nextjump2.cjs`（223502 全窗邻跳干净）、
  `_smoke_full_20260918.cjs` 全绿、`_exam522q6.cjs` 判分 PASS。⚠ 诚实标注：headless 下邻跳在修前
  也测不出抖动（lateMove=0），多秒级症状本机复现不了 ⇒ 修的是**可证明的互打机制**，观感需用户
  在真机确认。证据：`output/_relaykill.cjs→_relaykill.log`、`_nextjump*.cjs`。
  并行注记：另一会话随后在 exam.js 上加了 `landTo` 自带分帧引擎（复用本 `hardScrollTo`，
  `?v=` 现为其 `20260919d`，覆盖我方 `...23f`——内容含双方改动，缓存新鲜有效，不回 bump；
  合并态已重验 `_relaykill` + 全冒烟全绿）。

- **答题页「下一题 / 点题号」抖动 + 模糊：根因与修复**（09-19，用户："会剧烈抖动并且模糊好几秒，
  体验极其不好"）。根因是**单帧传送任意距离**：exam 落点走 `hardScrollTo` = `window.scrollTo`，
  而 `js/scroll-stage.js` 的 P1 分段只挂在 `scrollBy` 上，对 `scrollTo` 是 `invalidate()` + 原生
  瞬间到位 ⇒ 分段在答题页等于被关掉。实测对照（`_stage_ab.cjs`）：`scrollBy({top:3000})` → 4 帧
  × 750px；`scrollTo(0, y+3000)` → 1 帧 × 3000px。于是单帧 3581px（`_jump_probe.log` 的 J2 chip）
  = 一帧新暴露 516 万 px：Chrome 先顶低分辨率贴图（**模糊**），随后几帧补齐、叠上落点微调
  （**抖动**）；补齐期 track 最多 120 帧 ≈2s 每帧写滚动，每写一次都作废栅格缓存 ⇒ **好几秒**。
  修法：exam 落点改**自带分帧**（`landTo`，每帧新暴露 ≤0.85 屏、上限 60 帧），每步仍走 `scrollTo`
  ⇒ 那边接力永远无待办，两引擎不再互打。帧数封顶由 20 放宽到 60 是实测定的：压到 20 时 223502
  末题（42063px）被迫 2132px/帧、单帧 56ms（≈18fps）= 肉眼卡顿；≤0.85 屏/帧时稳定 17.5ms/帧。
  **验证**（`_land_verify.cjs`：桌面 + 移动 × 223520/223502）：相邻下一步仍 1 帧 × 438px（零新增
  延迟）；中距 2308px → 4 帧 × 577px；远跳 9214px → 13 帧 × 709px；极远 42063px → 56 帧 × 762px
  （≤cap 765，1.3s）；连点 10 次反转 = 0 / slack = 0；落点 top = 12（移动端 54 = 顶栏下沿 + 4）
  **全部 PASS**、errors = []。同点对照 `_jump_probe.cjs`：单帧最大 **3581 → 717**。带图卡
  （223520 qid 2152，2 张图）11 帧 × ≤764、400ms 后零位移（无连环纠偏）。**顺带证伪两条**：
  ① `_nextjump*.cjs` 点的是 `#sideNext`，该按钮前端已不存在 ⇒ 那三份"全部干净"是空点假阴性
  （selector 已改 `#railNext`）；② 另一条"无糊帧"结论靠软件光栅 screencast，证明不了真机不糊。
  同时删掉 `exam.js` 注释块内误粘的 `let i = cursor;` 死代码。⚠ 真机低分辨率贴图在本机
  （无头 / 软件光栅）测不出 ⇒ 观感需用户在自己机器上确认。旁证：跳转期 layout-shift = 0
  （`_cls_probe.cjs`）；`_exam502.cjs` 4/4。`tools/ui-smoke.cjs` 仍挂在"打开登录页"（探针过期，
  同上条，与本次无关）。证据：`output/_stage_ab.cjs`、`_land_verify.cjs→_land_verify.log`、
  `_imgjump.cjs`、`_cls_probe.cjs`、`_jump_probe.cjs`。

- **阅读器「下一题」极其微小超快频率抖动 + 模糊很久：根因与修复**（09-19，用户截图位置
  29/134 软件工程【电子教辅】）。与答题页是**同一族但不同源**：阅读器这道落点 `rdrPlaceTo`
  有三条驱动在抢同一根滚动轴 —— ① 程序滚动走 `scrollBy` ⇒ 激活 scroll-stage 的 P1 分段 + 接力；
  ② 本函数自己的 `track()`（≤300 帧）；③ 另一条 `settle()`（≤5000ms / ≤380 帧）。更致命的是
  旧 `track()` 写成 `if (imgPending || |偏移|>=0.5) place()` —— 正文图没有宽高占位（markdown.js
  只给 loading=lazy）⇒ "先稳后长"是常态，每帧目标都在变 ⇒ **每帧推几 px**：幅值极小 + 60Hz +
  持续到图全解完 = 用户读到的"极其微小超快频率抖动很久"；每写一次都作废栅格缓存 ⇒ 同一窗口内
  低分辨率贴图一直换不回来 = "模糊很久"。
  修法（`subjects.js` `rdrPlaceTo` 重写，与上条答题页落点引擎同口径）：① 程序滚动改 `to()`
  瞬时到位（不再 `scrollBy` ⇒ 接力永不激活）；② 自己分帧（每帧 ≤0.85 屏，上限 60 帧）；
  ③ **解码期 / 高度未变时不写滚动**，只写"真偏了"（≥0.5px）；④ 只留一条有界收敛环（落地 4s
  保险丝 + 落定后 2.5s）；⑤ 加"高度稳定 2 帧才写"（上限 400ms），避免量到两次生长之间的
  过渡布局；⑥ **本书实例守卫**：`reader` 是每本书各自的闭包常量、`setOpen(false)` 只把
  `reader.hidden` 置真，切书后上一本的环还活着、而它按选择器找的"自己的块"已隐藏（rect 全 0
  ⇒ 目标恒等于"当前位置−12"）⇒ 每帧把 window 往上拽 12px，与新书落点互抢（实测"同一本书连点
  16 步 0 反转" vs "先全文再背诵"同一会话就冒出反转 2 次、残移 932px）。顺手删掉 `data-pagemode`
  内滚死分支（09-17 分页移除后再无任何地方设置它）。
  验证：`_rdr_writes.cjs` 单书 16 步**每步 1 次写入、反转 0**（末尾 4 次是末卡贴底的空转写入，
  已由 clampHits 3 次即停兜住）；`_rdr_jitter.cjs` 30→31 步的 ±11px 迟到振荡（反转 1、slack 20）
  → **反转 0、slack 0**；`_rdr_verify.cjs` 全文 12 步失败 0 且收住 21ms、用户滚轮接管 PASS；
  `_tblreg.cjs` 4/4、`_exam502.cjs` 4/4、零 pageerror。
  ⚠ 残留（**不是滚动引擎的问题，是阅读器渲染路径**）：部分步进要等点击后 1.5~3s 才落位 ——
  `gotoPara` 的 `paint()` / `jumpReciteCard` 的 `paintReciteWindow()` 整窗重建 + 图片无宽高占位；
  期间**不抖**（无写入）但响应迟。另有极少数步在一次内容生长后补一次校正（≤512px）。
  证据：`output/_rdr_jitter.cjs→_rdr_jitter.log`、`_rdr_writes.cjs`、`_rdr_verify.cjs→_rdr_verify.log`、
  `_rdr_merge.cjs`、`_stage_ab.cjs`。

---

## 13. MCP 与 Skills（命中即主动调用，无需请示）

| MCP | 时机 |
|-----|------|
| `context7` | 查任何库/框架/SDK/CLI 文档（先 resolve 再 query，禁编造） |
| `filesystem`/`github`/`linear`/`memory` | 文件读写 / 远程仓库PR Issue / 需求跟踪 / 跨会话记忆 |
| `postgres`/`supabase`/`supabase-api` | Supabase/Postgres 查询迁移分支函数 |
| `sentry` | 线上错误：`search_sentry_tools` → `execute_sentry_tool` |
| `playwright`/`puppeteer`/`chrome-devtools`/`camoufox` | 浏览器验证走本机 `playwright-core` + `chrome-153` 常驻通道 |

| 场景 | Skill |
|------|-------|
| 创造性工作前 | `brainstorming` |
| 多步任务 | `writing-plans` → `executing-plans` |
| 2+ 独立子任务 | `dispatching-parallel-agents` |
| Bug/测试失败 | `systematic-debugging` |
| UI 设计判断 | `frontend-design-direction` |
| 论文/综述/白皮书/抓取/画布 | `research-paper`/`arxiv`/`firecrawl`/`tabbit` |

纪律：主动调用；结果落 `output/` 或引用来源；失败记日志换备用通道；不得违反 §3 红线。
凭据（`opencode.json` 配好，严禁打印/入库）：`GITHUB_PERSONAL_ACCESS_TOKEN` /
`LINEAR_API_KEY` / `SENTRY_ACCESS_TOKEN` / `DATABASE_URL`。
