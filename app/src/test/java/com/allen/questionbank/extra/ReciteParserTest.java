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
}
