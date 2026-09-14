package com.allen.questionbank.extra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资料形态嗅探器证据测试：五类形态各给一份真实排版样本，断言「判得对」且
 * 「理由/打分可复核」——这是 /api/ingest/auto 全自动入库的地基，判错会直接导致
 * 书被切成废品，所以必须有可复现证据，不接受「跑通就行」。
 */
class SourceSnifferTest {

    private static String lines(String... xs) { return String.join("\n", xs); }

    /* ---------------- FULL_TEXT：教材长段落 + 章抬头 ---------------- */

    private static final String FULL_TEXT_SAMPLE = lines(
            "第一章 软件工程概述",
            "软件工程是一门研究用工程化方法构建和维护有效的、实用的和高质量的软件的学科，"
                    + "它涉及程序设计语言、数据库、软件开发工具、系统平台、标准、设计模式等多个方面的内容。",
            "本章首先介绍软件工程产生的原因，然后给出软件工程的定义、三要素以及软件生命周期的基本模型，"
                    + "为后续各章的学习奠定理论基础，也为读者建立完整的工程化思维框架。",
            "软件工程的三要素包括方法、工具和过程，方法是完成软件开发各项任务的技术手段，"
                    + "工具为方法提供自动或半自动的支撑环境，过程则把方法与工具结合起来以完成软件开发的目标。",
            "1.1 软件危机",
            "所谓软件危机是指在计算机软件的开发和维护过程中所遇到的一系列严重问题，"
                    + "这些问题不仅限于技术上无法按时交付，还包括成本超支、质量不稳定、维护困难等工程管理层面的矛盾。",
            "产生软件危机的原因可以归纳为两个方面：其一是软件本身的特点，软件是逻辑部件而不是物理部件，"
                    + "其规模庞大且复杂度呈指数级增长；其二是软件开发与维护的方法不正确，忽视需求分析、轻视文档与测试。",
            "1.2 软件工程的定义",
            "软件工程是指导计算机软件开发和维护的一门工程学科，采用工程的概念、原理、技术和方法来开发与维护软件，"
                    + "把经过时间考验而证明正确的管理技术和当前能够得到的最好的技术方法结合起来，以经济地开发出高质量的软件。",
            "第二章 软件生命周期",
            "软件生命周期是指软件产品从提出应用需求开始，经过开发、使用，直到最终退役的整个过程，"
                    + "通常划分为可行性研究、需求分析、概要设计、详细设计、编码、测试和维护等多个阶段。",
            "每个阶段都有明确的输入输出与里程碑产物，前一阶段的产物是后一阶段的输入，"
                    + "阶段之间的回溯与迭代在瀑布模型中代价极高，这也是后续迭代模型与增量模型产生的直接动因。",
            "维护阶段是软件生命周期中耗时最长、成本最高的阶段，通常占到软件总成本的六成以上，"
                    + "因此可维护性必须从需求分析与设计阶段就作为一项显式质量属性被设计和度量，而不是事后补救。",
            "附录 习题参考答案",
            "本章习题的参考答案统一给出，供读者在完成练习之后进行对照检查，"
                    + "建议先独立完成再对照答案，否则习题训练的效果会大打折扣，也无法真实反映掌握程度。");

    /* ---------------- RECITE：知识点短卡 ---------------- */

    private static final String RECITE_SAMPLE = lines(
            "【软件危机】是指在计算机软件的开发和维护过程中所遇到的一系列严重问题",
            "【软件工程】是指导计算机软件开发和维护的一门工程学科",
            "【软件生命周期】软件产品从提出需求到最终退役的整个过程",
            "【瀑布模型】将软件开发过程划分为若干阶段且阶段间严格顺序执行",
            "【快速原型模型】快速建立可运行的原型以获取用户真实需求",
            "【增量模型】把软件产品作为一系列增量构件分批设计开发和交付",
            "【螺旋模型】结合瀑布模型与原型模型并引入风险分析",
            "【模块化】把软件划分成独立命名且可独立访问的模块",
            "【内聚】模块内部各元素之间联系紧密程度的度量",
            "【耦合】模块之间相互依赖程度的度量",
            "数据：是对客观事物的符号表示",
            "软件：程序、数据及相关文档的完整集合",
            "文档：记录开发过程与结果的书面材料",
            "模块：具有独立功能且可单独命名的程序单位",
            "什么是软件危机？",
            "软件危机是指在软件开发和维护中遇到的严重问题",
            "什么是软件工程三要素？",
            "方法、工具和过程三者合称软件工程三要素",
            "为什么要进行需求分析？",
            "需求分析决定软件做什么，是后续设计与测试的依据",
            "知识点1 软件的定义",
            "知识点2 软件的特点",
            "知识点3 软件的分类");

    /* ---------------- SYLLABUS：考试大纲（四级考核目标） ---------------- */

    private static final String SYLLABUS_SAMPLE = lines(
            "数据结构与算法 自学考试大纲",
            "I. 课程性质与课程目标",
            "一、课程性质和特点",
            "“数据结构与算法”是高等教育自学考试计算机类专业的一门专业课。",
            "II. 考核目标",
            "本大纲在考核目标中，按照识记、领会、简单应用和综合应用四个层次规定其应达到的能力层次要求。",
            "识记（I）：要求考生能够识别和记忆有关知识点的概念性内容。",
            "领会（II）：要求考生在识记的基础上，能够领悟各知识点的内涵和外延。",
            "简单应用（III）：要求考生运用少量知识点，分析和解决一般的应用问题。",
            "综合应用（IV）：要求考生综合运用多个知识点，分析解决较复杂的应用问题。",
            "III. 课程内容与考核要求",
            "第一章 绪论",
            "一、学习目的与要求",
            "本章的学习目的是让考生了解数据结构在计算机应用领域中的作用。",
            "二、考核知识点与考核要求",
            "1. 基本概念和术语",
            "识记：数据；数据元素；数据项；数据结构；逻辑结构；存储结构。",
            "领会：数据项之间的关系；逻辑结构与存储结构之间的关系。",
            "简单应用：定义抽象数据类型。");

    /* ---------------- PAPER：真题卷（题号 + 选项） ---------------- */

    private static final String PAPER_SAMPLE = lines(
            "2025年4月高等教育自学考试全国统一命题考试",
            "软件工程 试卷",
            "一、单项选择题：本大题共20小题，每小题1分，共20分。",
            "1. 下列关于软件的叙述中，正确的是",
            "A. 软件就是程序",
            "B. 软件是程序、数据及相关文档的集合",
            "C. 软件就是数据",
            "D. 软件就是文档",
            "2. 软件工程三要素是指",
            "A. 方法、工具和过程",
            "B. 方法、数据和过程",
            "C. 工具、数据和人员",
            "D. 过程、人员和硬件",
            "3. 下列模型中强调风险分析的是",
            "A. 瀑布模型",
            "B. 快速原型模型",
            "C. 螺旋模型",
            "D. 增量模型",
            "4. 软件生命周期中花费最多的阶段是",
            "A. 需求分析",
            "B. 概要设计",
            "C. 编码实现",
            "D. 运行维护",
            "5. 需求分析阶段的主要产物是",
            "A. 可行性研究报告",
            "B. 需求规格说明书",
            "C. 概要设计说明书",
            "D. 测试计划",
            "二、填空题：本大题共10空，每空1分，共10分。",
            "参考答案：1.B 2.A 3.C 4.D 5.B",
            "解析：软件是程序、数据和文档的完整集合，这是软件的基本概念。");

    /* ---------------- SCAN：无文本层 ---------------- */

    private static final String SCAN_SAMPLE = "第 1 页\n第 2 页\n第 3 页";

    @Test
    @DisplayName("教材长段落 + 章抬头 → FULL_TEXT")
    void detectsFullText() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(FULL_TEXT_SAMPLE);
        assertEquals(SourceSniffer.FULL_TEXT, r.kind());
        assertTrue(r.decisive(), "教材样本应高置信：" + r.scores());
        assertTrue(r.signals().get("chapterHeads") >= 3,
                "章抬头应被识别：" + r.signals());
        assertTrue(r.scores().get(SourceSniffer.FULL_TEXT)
                        > r.scores().get(SourceSniffer.RECITE),
                "全文分应高于背诵分：" + r.scores());
    }

    @Test
    @DisplayName("知识点短卡密集 → RECITE")
    void detectsRecite() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(RECITE_SAMPLE);
        assertEquals(SourceSniffer.RECITE, r.kind());
        assertTrue(r.decisive(), "背诵册样本应高置信：" + r.scores());
        int cards = r.signals().get("bracketHeads")
                + r.signals().get("pointHeads");
        assertTrue(cards >= 10, "方括号/知识点抬头应被识别：" + r.signals());
        assertTrue(r.scores().get(SourceSniffer.RECITE)
                        > r.scores().get(SourceSniffer.PAPER),
                "背诵分应高于试卷分：" + r.scores());
    }

    @Test
    @DisplayName("考试大纲：四级考核目标 → SYLLABUS")
    void detectsSyllabus() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(
                new SourceSniffer.Input(SYLLABUS_SAMPLE, 26, "数据结构与算法考试大纲.pdf", true));
        assertEquals(SourceSniffer.SYLLABUS, r.kind(),
                "大纲应判为 SYLLABUS，而非被术语定义信号带偏成 RECITE：" + r.scores());
        assertTrue(r.decisive(), "大纲样本应高置信：" + r.confidence());
        assertTrue(r.signals().get("levelHits") >= 3,
                "应识别出至少 3 级考核目标：" + r.signals());
    }

    @Test
    @DisplayName("题号 + 选项 + 题型标签 → PAPER")
    void detectsPaper() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(PAPER_SAMPLE);
        assertEquals(SourceSniffer.PAPER, r.kind());
        assertTrue(r.decisive(), "试卷样本应高置信：" + r.scores());
        assertTrue(r.signals().get("optionLines") >= 12,
                "选项行应被识别：" + r.signals());
        assertTrue(r.signals().get("stemLines") >= 5,
                "题号行应被识别：" + r.signals());
        assertTrue(r.scores().get(SourceSniffer.PAPER)
                        > r.scores().get(SourceSniffer.FULL_TEXT),
                "试卷分应高于全文分：" + r.scores());
    }

    @Test
    @DisplayName("不可抽取（扫描件）→ SCAN")
    void detectsScanWhenNotExtractable() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(
                new SourceSniffer.Input(SCAN_SAMPLE, 12, "扫描卷.pdf", false));
        assertEquals(SourceSniffer.SCAN, r.kind());
        assertTrue(r.confidence() >= 0.9, "不可抽取应几乎确定：" + r.confidence());
        assertTrue(r.reasons().stream().anyMatch(x -> x.contains("无法抽取")));
    }

    @Test
    @DisplayName("页数多但文本极稀薄 → SCAN（每页字符数门禁）")
    void detectsScanWhenTextTooThin() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(
                new SourceSniffer.Input(SCAN_SAMPLE, 40, "扫描卷.pdf", true));
        assertEquals(SourceSniffer.SCAN, r.kind());
        assertTrue(r.reasons().stream().anyMatch(x -> x.contains("文本层过少")),
                "理由应说明是文本层过少：" + r.reasons());
    }

    @Test
    @DisplayName("空文本 → SCAN，且永不抛异常")
    void emptyTextIsScan() {
        assertEquals(SourceSniffer.SCAN, SourceSniffer.sniff("").kind());
        assertEquals(SourceSniffer.SCAN,
                SourceSniffer.sniff(new SourceSniffer.Input(null, 5, "x.pdf", true)).kind());
    }

    @Test
    @DisplayName("纯简答题集：有题号无选项 → 判 PAPER 但解析不出题，须能降级到 RECITE")
    void demoteWhenPaperYieldsNothing() {
        String shortAnswerBook = lines(
                "1. 简述软件危机的表现",
                "软件开发成本和进度估计不准确，交付的软件质量不可靠，软件难以维护。",
                "2. 简述软件工程三要素",
                "方法、工具和过程，三者共同构成软件工程的基本框架。",
                "3. 什么是软件生命周期",
                "软件产品从提出需求到退役的整个过程，包括需求、设计、编码、测试和维护。",
                "4. 简述需求分析的任务",
                "确定软件必须做什么，给出需求规格说明书并验证其正确性。",
                "5. 简述模块化设计的原则",
                "高内聚低耦合，模块规模适中，接口清晰且信息隐藏。");
        SourceSniffer.SniffResult first = SourceSniffer.sniff(shortAnswerBook);
        assertEquals(SourceSniffer.PAPER, first.kind(), "纯题号文本首判应为 PAPER");

        SourceSniffer.SniffResult demoted = SourceSniffer.demote(first, SourceSniffer.PAPER);
        assertNotEquals(SourceSniffer.PAPER, demoted.kind(), "降级后不应还是 PAPER");
        assertEquals(SourceSniffer.RECITE, demoted.kind(), "应降级为背诵卡形态");
        assertTrue(demoted.reasons().stream().anyMatch(x -> x.contains("降级")),
                "理由须说明发生了降级：" + demoted.reasons());
    }

    @Test
    @DisplayName("知识点手册（只有「知识点N」抬头）不会被误判成扫描件")
    void pointHeadedBookIsNotScan() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 24; i++) {
            sb.append("知识点").append(i).append(" 软件工程核心概念之").append(i).append('\n');
            sb.append("这是第 ").append(i)
                    .append(" 个知识点的正文说明，用于验证嗅探器不会把它当成没有结构的扫描件。\n");
        }
        SourceSniffer.SniffResult r = SourceSniffer.sniff(sb.toString());
        assertNotEquals(SourceSniffer.SCAN, r.kind(), "知识点手册不应判为扫描件：" + r.scores());
        assertTrue(r.signals().get("pointHeads") >= 20,
                "知识点抬头须被计数：" + r.signals());
    }

    @Test
    @DisplayName("四种形态的评分与理由全部可复核（供 agent 事后审计）")
    void exposesAllScoresAndSignals() {
        SourceSniffer.SniffResult r = SourceSniffer.sniff(PAPER_SAMPLE);
        Map<String, Double> s = r.scores();
        assertTrue(s.containsKey(SourceSniffer.FULL_TEXT));
        assertTrue(s.containsKey(SourceSniffer.RECITE));
        assertTrue(s.containsKey(SourceSniffer.PAPER));
        assertTrue(s.containsKey(SourceSniffer.SCAN));
        for (Double v : s.values()) {
            assertNotNull(v);
            assertTrue(v >= 0 && v <= 100, "分值应在 0~100：" + s);
        }
        assertFalse(r.reasons().isEmpty());
        List<String> reasons = r.reasons();
        assertTrue(reasons.stream().anyMatch(x -> x.contains("平均行长")));
    }

    @Test
    @DisplayName("判定互不串味：全文样本不会被判成背诵，背诵样本不会被判成试卷")
    void noCrossContamination() {
        assertEquals(SourceSniffer.FULL_TEXT, SourceSniffer.sniff(FULL_TEXT_SAMPLE).kind());
        assertEquals(SourceSniffer.RECITE, SourceSniffer.sniff(RECITE_SAMPLE).kind());
        assertEquals(SourceSniffer.PAPER, SourceSniffer.sniff(PAPER_SAMPLE).kind());
    }
}
