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
- **顶栏/侧栏**：无面包屑、无用户块；侧栏默认隐藏（`.rail-hot` 滑出，点住 `.pinned`，Esc/点外部解除）；
  `≤860px` 走 `.open` + scrim。深链靠 `pendingRoute` 在静默登录后恢复。
- **样式**：就近加（`cc-ui.css`/`pages.css`），沿用 token；自有卡片独立类名，禁复用裸 `.card`；
  cinema 整文件 scoped `[data-theme="cinema"]`（`qb.theme.v2`，默认影院；`dark` 残留映射 `cinema`），
  共享精修放最后引入的 `polish.css`；`logo` 用 `fill="currentColor"`。
- **性能红线**：无颗粒层、无 `fixed` 背景、大卡片无 `backdrop-filter`、长列表
  `content-visibility: auto`、全站无位移动画、顶部内高光慎用；ticker 类节流（如 focus 500ms/5s）。
- **专注页**：纯前端，无倒计时只有正向计时；自建分类多计时并行 + 今日占比圆环；
  存储 `qb.focus.*`，分类色行内 `--cat` 变量。

---

## 7. 用户的功能要求（电子书 / 学习库）

资料入库形态（2026-09-14 修订——**禁把知识点伪造成带假选项的试卷**；禁编造答案/解析，缺失只报告）：

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
- `.bat` 里 `curl -w` 的 `%` 写 `%%`，`--noproxy` 的 `*` 加引号；PowerShell 里 `cmd /c` 传参引号地狱一律改走 `.bat` 文件。

---

## 9. 完成标准

- 端到端测试 + 长时验证，摒弃“仅编译通过”；每项修复附 `output/` 可复现证据。
- **30 分钟零问题监督窗口**为完成闸口；审计/越权指控逐条代码核实，警惕前端缓存掩盖后端变更。

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
  43（大纲）/44（速记宝典，30→19 已修）/45（考前资料，全文 47 节）/46–53（章节综述）/
  54–61（章节配题，60 已修 40→23）；卷 223496–503（章真题 130–178Q）/223511（考前 21Q）/
  223512–519（配套习题 22–58Q）。`习题题库与解答.pdf` 判重已覆盖，不入库；
  `extra_question` 孤儿 188437:23 未动。
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
