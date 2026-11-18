package com.allen.questionbank.extra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 背诵解析器的清理规则证据测试。
 *
 * <p>三条规则都是真实踩坑后才加的（2026-09-12 速记宝典/简答题集导入实测）：
 * <ol>
 *   <li>不去页眉 → 首页卡标题变成「速记宝典」、答案吞掉整页；</li>
 *   <li>不挡单字碎片 → 「含 :」被当成知识点标题立出空壳卡；</li>
 *   <li>正文行必须够长（&gt;24 字）或带句读，否则会被当成抬头——
 *       这是本解析器的既有语义，用它写测试时正文必须给足长度，
 *       否则所有行都成「无正文的孤儿」最后并成一张「前言」卡
 *       （2026-09-12 写测试时被这条语义坑过一次，特此钉住）。</li>
 * </ol>
 */
class ReciteParserTest {

    private static String lines(String... xs) { return String.join("\n", xs); }

    @Test
    @DisplayName("每页重复的页眉/页码会被丢弃，不再污染卡标题")
    void runningHeadsAreStripped() {
        String text = lines(
                "速记宝典", "1 / 9",
                "考点 1：简述软件危机",
                "软件危机是指在计算机软件的开发和维护过程中所遇到的一系列严重问题，包括成本超支与质量不可靠。",
                "速记宝典", "2 / 9",
                "考点 2：简述软件工程三要素",
                "软件工程三要素包括方法、工具和过程，三者协同支撑软件开发的工程化管理与质量保证。",
                "速记宝典", "3 / 9",
                "考点 3：简述软件生命周期",
                "软件生命周期是软件产品从提出需求开始直到最终退役的完整过程，包含需求分析与设计等阶段。",
                "速记宝典", "4 / 9");

        List<String> kept = ReciteParser.stripRunningHeads(text.split("\\R"));
        String joined = String.join("\n", kept);
        assertFalse(joined.contains("速记宝典"), "重复页眉应被去掉");
        assertFalse(joined.contains("1 / 9"), "页码应被去掉");
        assertTrue(joined.contains("考点 1：简述软件危机"), "正文必须保留：" + joined);

        ReciteParser.ReciteResult r = ReciteParser.parse(text);
        assertTrue(r.cards().size() >= 3, "应至少成 3 张卡：" + r.cards());
        assertTrue(r.cards().stream().noneMatch(c -> c.title().contains("速记宝典")),
                "不应存在以页眉为标题的卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("软件危机")),
                "考点卡必须还在：" + r.cards());
    }

    @Test
    @DisplayName("只丢「短且重复」的行，长正文即使重复也不误伤")
    void longRepeatedLinesAreKept() {
        String longLine = "这是一条很长的正文行用于验证不会被当成页眉丢掉因为它超过了二十个字符的长度限制";
        String text = lines(longLine, longLine, longLine, longLine);
        List<String> kept = ReciteParser.stripRunningHeads(text.split("\\R"));
        assertEquals(4, kept.size(), "长行重复不应被丢");
    }

    @Test
    @DisplayName("单字碎片「含 :」不立卡，正常术语仍立卡")
    void singleCharTermIsNotACard() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "含 :",
                "这是上一张卡答案的续行内容，长度足够以便被识别为正文而不是新的抬头。",
                "软件工程：应用计算机科学理论与工程管理原则和方法实现满足用户要求的软件产品的工程学科"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().replace(":", "").replace("：", "").trim().length() < 2),
                "单字碎片不应成为卡标题：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("软件工程")),
                "正常术语仍应立卡：" + r.cards());
    }

    @Test
    @DisplayName("无正文的短行不产出空卡，最终并入前言（既有语义，钉住）")
    void shortLinesWithNoBodyCollapseIntoPrologue() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "软件危机", "软件工程"));
        assertTrue(r.cards().stream().noneMatch(c -> c.body() == null || c.body().isBlank()),
                "不应产出空卡：" + r.cards());
        assertTrue(r.cards().size() <= 1,
                "无正文时应收敛成至多一张前言卡：" + r.cards());
    }

    @Test
    @DisplayName("行内挤在一起的「考点 N：」会被切开，卡片边界不丢")
    void inlinePointMarksAreSplitOut() {
        String squeezed = "上一句的结尾内容，与本条无关但被折行挤在了一起。考点 7：简述需求主要分为哪两类?"
                + "\n(1)需求主要分为功能需求和非功能需求两大类，前者规约系统必须执行的功能。";
        List<String> split = ReciteParser.splitPointMarks(List.of(squeezed.split("\\n")));
        assertEquals(3, split.size(), "第一行应被切成 2 行，共 3 行：" + split);
        assertTrue(split.get(0).endsWith("。"), "前缀应留在上一行：" + split);
        assertTrue(split.get(1).startsWith("考点 7："), "第二行应是考点 7 抬头：" + split);
        assertTrue(split.get(2).startsWith("(1)需求"), "答案行应原样保留：" + split);

        ReciteParser.ReciteResult r = ReciteParser.parse(squeezed);
        assertTrue(r.cards().stream().anyMatch(c -> c.title().startsWith("考点 7：")),
                "考点 7 应成为独立卡的标题：" + r.cards());
        assertTrue(r.cards().stream().noneMatch(c -> c.body() == null || c.body().isBlank()),
                "不应产出空卡：" + r.cards());
    }

    @Test
    @DisplayName("超 24 字的「考点 N：」抬头仍立卡（长度放宽规则）")
    void longPointHeadIsStillACard() {
        String longHead = "考点 7：简述需求主要分为哪两类?各自主要包含的内容是什么?";
        assertTrue(longHead.length() > 24, "该抬头确实超过 24 字：" + longHead.length());
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                longHead,
                "(1)需求主要分为功能需求和非功能需求两大类，前者规约系统必须执行的功能，后者约束性能与质量。",
                "考点 8：简述结构化方法的基本思想。",
                "结构化方法强调自顶向下逐步求精，把复杂问题分解为若干个可独立解决的小问题。"));
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("考点 7")),
                "长考点抬头应立卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("考点 8")),
                "后续考点也应立卡：" + r.cards());
    }

    @Test
    @DisplayName("卷首前言 + 方括号卡：前言单独成卡，知识点卡不空")
    void prologueAndBracketCards() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "本书面向自学考试考生，内容覆盖全部考点，建议配合历年真题一起使用以提高通过率。",
                "【软件危机】是指计算机软件开发和维护过程中所遇到的一系列严重问题，包含成本与质量两方面。",
                "【软件工程】是指导计算机软件开发和维护的一门工程学科，强调用工程化方法组织软件开发。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.body() == null || c.body().isBlank()),
                "不应产出空卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("前言")),
                "卷首应落成前言卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("软件危机")),
                "方括号卡应正常成卡：" + r.cards());
    }

    @Test
    @DisplayName("C++ 行注释 // 与列表横杠不立卡，并入当前答案（pilot46 实锤）")
    void commentAndDashLeadsAreNotCards() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "堆排序：利用堆结构实现的选择排序算法，能够保证对数级别的时间复杂度。",
                "// 第二步：交换堆顶和最后一个元素",
                "交换堆顶最大值和当前堆的最后一个元素，然后将堆的大小缩小一格继续处理。",
                "- 主关键字：能唯一标识一个记录的关键字，用于区分不同学生的身份信息。",
                "记录/元素：排序的基本单位，包含一个或多个数据项用于参与排序比较。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().contains("//")),
                "// 注释行不应成为卡标题：" + r.cards());
        assertTrue(r.cards().stream().noneMatch(c -> c.title().contains("主关键字")),
                "横杠子项不应立卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("记录/元素")),
                "含 / 的正常术语仍应立卡：" + r.cards());
    }

    @Test
    @DisplayName("标题前导 NBSP 被清理，不留隐形空格（pilot46 实锤）")
    void nbspPrefixedTitleIsCleaned() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "堆排序：利用堆结构实现的选择排序算法，能够保证对数级别的时间复杂度。",
                " // 第三步：调整剩余元素为大根堆",
                "将剩余元素重新调整为大根堆结构，堆的大小已经缩小了一格需要继续处理。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().startsWith(" ") || c.title().contains("//")),
                "标题不应带前导空白或 //：" + r.cards());
    }

    @Test
    @DisplayName("适用对象/核心说明归前言，不立说明文卡（pilot46 实锤）")
    void metaHeadsGoToPrologue() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "适用对象：小白学生入门理解，专业学生掌握考点内容以应对自学考试。",
                "核心说明：内部排序指数据全部存放在内存中进行的排序过程，是本章核心。",
                "排序：将一组无序的记录序列调整为有序的记录序列的过程，需要掌握基本概念。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().equals("适用对象") || c.title().equals("核心说明")),
                "说明文不应立卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("前言")),
                "应有前言卡收纳说明文：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("排序")),
                "真卡应保留：" + r.cards());
    }

    @Test
    @DisplayName("纯章节抬头（第X节）不混进上一卡的答案（书48 实锤 2026-09-17）")
    void sectionHeadDoesNotLeakIntoBody() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "核心目标：理解数据结构与算法的基本概念，掌握算法分析方法，了解基础算法设计策略，建立“问题-",
                "数据结构-算法”的解题思维",
                "第一节 基本概念和术语",
                "核心考点：数据、数据元素、数据项、数据结构、逻辑结构与物理结构的定义及区别；抽象数据类型",
                "（ADT）的概念及描述方法。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.body().contains("第一节")),
                "章节抬头不得混进任何卡的答案：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("核心目标")
                        && c.body().contains("解题思维") && !c.body().contains("第一节")),
                "核心目标卡的答案应保持完整：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("核心考点")),
                "下一节的核心考点仍应正常成卡：" + r.cards());
    }

    @Test
    @DisplayName("编号术语定义拆成「术语标题+完整定义」，不再半句入标题（书48 实锤 2026-09-17）")
    void numberedTermDefinitionStaysWhole() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "核心考点：数据、数据元素、数据项、数据结构、逻辑结构与物理结构的定义及区别；抽象数据类型",
                "（ADT）的概念及描述方法。",
                "1. 数据（Data）：计算机可以处理的各种符号的集合，是程序的操作对象。",
                "通俗理解：就像厨房食材（蔬菜、肉类），是烹饪（程序运行）的基础原料。",
                "2. 数据元素（Data Element）：数据的基本单位，是计算机在一次操作中处理的最小独立单位。",
                "4. 数据结构（Data Structure）：相互之间存在一种或多种特定关系的数据元素的集合。简单说，",
                "就是“数据元素的组织方式”。",
                "通俗理解：食材的摆放和搭配方式（比如把蔬菜分类放在冰箱抽屉，肉类放在冷冻层；或者按“主",
                "菜+配菜”的逻辑搭配）。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().contains("简单说，")),
                "折行半句不得成为卡标题：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("数据结构（Data Structure）")),
                "4 号编号术语应立术语卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("数据（Data）")),
                "1 号编号术语也应立术语卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("数据元素（Data Element）")),
                "2 号编号术语也应立术语卡：" + r.cards());
        String dsBody = r.cards().stream().filter(c -> c.title().equals("数据结构（Data Structure）"))
                .findFirst().orElseThrow().body();
        assertTrue(dsBody.contains("相互之间存在一种或多种特定关系的数据元素的集合。简单说，")
                        && dsBody.contains("就是“数据元素的组织方式”。"),
                "两行定义必须完整落在答案里：" + dsBody);
        assertTrue(dsBody.contains("通俗理解"),
                "通俗理解子标签应并入本卡答案：" + dsBody);
    }

    @Test
    @DisplayName("短代码/公式行不得丢（书48 实锤 2026-09-17：def sum_n(n): / while low <= high: 曾被当排版碎片丢）")
    void shortCodeAndFormulaLinesSurvive() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "算法分析的目标：评价算法效率，比较不同算法优劣，为选择算法提供量化依据。",
                "例：Python 实现求 1 到 n 的和：",
                "def sum_n(n):",
                "sum = 0",
                "for i in range(1, n+1):",
                "sum += i",
                "return sum",
                "二分查找核心逻辑：",
                "while low <= high:",
                "else: high = mid - 1",
                "空间复杂度结论：",
                "S(n) = O(1)（与n无关）。"));
        String all = r.cards().stream().map(c -> c.title() + "\n" + c.body()).reduce("", (a, b) -> a + "\n" + b);
        for (String frag : new String[]{"def sum_n(n):", "for i in range(1, n+1):", "sum += i",
                "while low <= high:", "else: high = mid - 1", "S(n) = O(1)（与n无关）。"}) {
            assertTrue(all.contains(frag), "短代码/公式行不得丢：" + frag + "\n" + all);
        }
        assertTrue(r.cards().stream().anyMatch(c -> c.title().startsWith("算法分析的目标")),
                "真卡不受影响：" + r.cards());
    }

    @Test
    @DisplayName("泛化标签（核心思想/小白实例/专业实例）不立同名卡，并回父题（书48 实锤 2026-09-17）")
    void genericLabelsJoinParentCard() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "1. 枚举法（Enumeration）—— 暴力搜索，逐个尝试",
                "核心思想：将问题的所有可能解一一列举出来，判断是否符合条件，符合则保留。",
                "小白实例：求1到100之间的所有偶数，判断每个数能否被2整除后保留。",
                "2. 递推法（Recurrence）—— 从已知推未知，逐步递推",
                "核心思想：利用问题的递推关系，从初始条件出发逐步计算出第n项的解。",
                "专业实例：计算阶乘 n! = n × (n-1)!，初始条件 0! = 1（递推公式明确）。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().equals("核心思想") || c.title().equals("小白实例")
                        || c.title().equals("专业实例")),
                "泛化标签不得立同名卡：" + r.cards());
        String enumBody = r.cards().stream().filter(c -> c.title().contains("枚举法"))
                .findFirst().orElseThrow().body();
        assertTrue(enumBody.contains("核心思想：将问题的所有可能解") && enumBody.contains("小白实例：求1到100"),
                "核心思想/小白实例应并回枚举法卡答案：" + enumBody);
        String recBody = r.cards().stream().filter(c -> c.title().contains("递推法"))
                .findFirst().orElseThrow().body();
        assertTrue(recBody.contains("核心思想：利用问题的递推关系") && recBody.contains("专业实例：计算阶乘"),
                "核心思想/专业实例应并回递推法卡答案：" + recBody);
    }

    @Test
    @DisplayName("小节抬头「（一）…」/「一、…」立卡，泛化标签归其答案（书48 实锤 2026-09-17）")
    void subSectionHeadBecomesCard() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "（一）时间复杂度（Time Complexity）",
                "1. 定义：算法执行过程中基本运算的次数与问题规模n的关系，记为T(n) = O(f(n))。",
                "2. 核心步骤（小白必学计算方法）：",
                "Step1：确定问题规模n。",
                "（二）空间复杂度（Space Complexity）",
                "1. 定义：算法执行过程中额外占用的内存空间与问题规模n的关系。",
                "（三）专业进阶：最坏、最好、平均时间复杂度"));
        java.util.List<String> titles = r.cards().stream().map(ReciteParser.Card::title).toList();
        assertTrue(titles.contains("（一）时间复杂度（Time Complexity）"), "（一）抬头应立卡：" + titles);
        assertTrue(titles.contains("（二）空间复杂度（Space Complexity）"), "（二）抬头应立卡：" + titles);
        assertFalse(titles.contains("定义"), "泛化标签「定义」不得单独立卡：" + titles);
        ReciteParser.Card tc = r.cards().stream()
                .filter(c -> c.title().equals("（一）时间复杂度（Time Complexity）"))
                .findFirst().orElseThrow();
        assertTrue(tc.body().contains("T(n) = O(f(n))") && tc.body().contains("Step1：确定问题规模n。"),
                "定义/步骤应归小节卡的答案：" + tc.body());
        assertFalse(titles.contains("最坏、最好、平均时间复杂度"),
                "带冒号的子标题不立卡（是卡内小标题）：" + titles);
    }

    @Test
    @DisplayName("「实例N：…」一例一卡，标题独立可读（书48 实锤 2026-09-17）")
    void instanceHeadBecomesCard() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "3. 实例分析（小白入门+专业深化）：",
                "实例1：求1到n的和（简单循环）",
                "伪代码：",
                "sum = 0  // 1次（常数项，忽略）",
                "执行次数：T(n) = 1 + n + 1 = n + 2",
                "实例3：常数阶（无循环，直接操作）",
                "时间复杂度：O(1)（常数阶）"));
        java.util.List<String> titles = r.cards().stream().map(ReciteParser.Card::title).toList();
        assertTrue(titles.contains("实例1：求1到n的和（简单循环）"), "实例抬头应立卡：" + titles);
        assertTrue(titles.contains("实例3：常数阶（无循环，直接操作）"),
                "括号内有逗号的实例抬头也应立卡：" + titles);
        ReciteParser.Card i1 = r.cards().stream().filter(c -> c.title().startsWith("实例1")).findFirst().orElseThrow();
        assertTrue(i1.body().contains("sum = 0  // 1次（常数项，忽略）")
                        && i1.body().contains("执行次数：T(n) = 1 + n + 1 = n + 2"),
                "实例的伪代码与执行次数应归其答案：" + i1.body());
    }

    @Test
    @DisplayName("编号+冒号收尾的子标题立卡，引子句仍归正文（书48 实锤 2026-09-17）")
    void numberedColonHeadingBecomesCard() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "4. 常见时间复杂度等级（从快到慢排序，核心考点）：",
                "O(1) < O(log n) < O(n) < O(n²) < O(2ⁿ) < O(n!)",
                "1. 各阶段的基本目标为：",
                "掌握基本概念与算法分析。"));
        assertTrue(r.cards().stream().anyMatch(c -> c.title().equals("常见时间复杂度等级（从快到慢排序，核心考点）")),
                "编号+冒号收尾的短名词短语应立卡：" + r.cards());
        ReciteParser.Card rank = r.cards().stream()
                .filter(c -> c.title().contains("常见时间复杂度等级")).findFirst().orElseThrow();
        assertTrue(rank.body().contains("O(1) < O(log n)"), "其下清单应归该卡答案：" + rank.body());
        assertTrue(r.cards().stream().noneMatch(c -> c.title().contains("基本目标")),
                "「…为：」引子句不得立卡（守门对照）：" + r.cards());
    }

    @Test
    @DisplayName("重复主题标签不另立卡，也不跨卡搬运——留在当前卡（书48 实锤 2026-09-17）")
    void repeatedTopicLabelStaysInCurrentCard() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "6. 动态规划（Dynamic Programming, DP）—— 记忆子问题解，避免重复计算",
                "核心思想：将大问题分解为重叠的子问题，保存子问题解避免重复计算。",
                "二、专业进阶：各策略的核心区别（考点）",
                "3. 分治法 vs 动态规划：",
                "分治法：子问题独立（无重叠），合并子问题解；",
                "动态规划：子问题重叠，保存子问题解避免重复计算。"));
        assertTrue(r.cards().stream().noneMatch(c -> c.title().equals("动态规划")),
                "短主题词是已有卡标题的子串时不得另立重复卡：" + r.cards());
        ReciteParser.Card cmp = r.cards().stream()
                .filter(c -> c.body().contains("分治法：子问题独立（无重叠），合并子问题解；"))
                .findFirst().orElseThrow();
        assertTrue(cmp.body().contains("动态规划：子问题重叠，保存子问题解避免重复计算。"),
                "同一对比块的两行必须落在同一张卡（不跨卡搬运）：" + cmp.body());
        String dp = r.cards().stream().filter(c -> c.title().contains("动态规划（Dynamic"))
                .findFirst().orElseThrow().body();
        assertFalse(dp.contains("动态规划：子问题重叠"),
                "后文对比块的行不得被搬回远处的同名主题卡：" + dp);
    }

    @Test
    @DisplayName("「N. 主题名——描述句」标题只留主题名，描述句落答案（书48 实锤 2026-09-17）")
    void dashDescriptionMovesToBody() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "1. 枚举法（Enumeration）—— 暴力搜索，逐个尝试",
                "核心思想：将问题的所有可能解一一列举出来，判断是否符合条件，符合则保留。"));
        ReciteParser.Card c = r.cards().stream().filter(x -> x.title().contains("枚举法"))
                .findFirst().orElseThrow();
        assertEquals("枚举法（Enumeration）", c.title(), "标题只保留「——」前的主题名：" + r.cards());
        assertTrue(c.body().contains("—— 暴力搜索，逐个尝试"),
                "「——」后的描述句不得丢，应落到答案：" + c.body());
    }

    @Test
    @DisplayName("带句读/引子句/题型词的编号行维持旧行为，不被误拆（守门对照）")
    void numberedNonTermLinesKeepOldBehaviour() {
        ReciteParser.ReciteResult r = ReciteParser.parse(lines(
                "考点 1：简述顺序栈的进栈过程",
                "进栈时先判断栈是否还有剩余空间，如果栈已满则报出上溢错误，否则把新元素存入栈顶位置。",
                "2. 核心步骤（小白必学计算方法）：",
                "Step1：确定问题规模n，例如排序问题中n是待排序元素个数，求和问题中n是相加的数字个数。",
                "3. 单选题：下列说法正确的是哪一个选项，请结合定义判断后作答并说明理由。"));
        /* 引子句（冒号收尾）与题型词抬头不拆卡：不得出现以「核心步骤」为标题的卡 */
        assertTrue(r.cards().stream().noneMatch(c -> c.title().contains("核心步骤")),
                "冒号收尾的引子句不得立卡：" + r.cards());
        assertTrue(r.cards().stream().noneMatch(c -> c.title().startsWith("单选题")),
                "题型词抬头不得被拆成术语卡：" + r.cards());
        assertTrue(r.cards().stream().anyMatch(c -> c.title().contains("考点 1")),
                "考点卡不受影响：" + r.cards());
    }
}
