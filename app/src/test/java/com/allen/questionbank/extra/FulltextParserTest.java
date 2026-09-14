package com.allen.questionbank.extra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全文书分章解析的证据测试。
 *
 * <p>重点钉住「空正文章节」这条真实缺陷：抬头连着抬头（部/篇/章标题挨着）会产生
 * 只有标题没有正文的章节，阅读器点开是一片空白。规则是把它并入下一章正文首行
 * （2026-09-12：C语言手册 33 章里 15 章空、习题册 60 章里 24 章空）。
 */
class FulltextParserTest {

    private static String lines(String... xs) { return String.join("\n", xs); }

    @Test
    @DisplayName("抬头紧邻抬头不产生空章节，标题并入下一章正文")
    void noEmptyChapters() {
        String text = lines(
                "第一部分 基础",
                "第一章 绪论",
                "数据结构是研究数据元素之间关系的学科，本课程围绕线性表、树、图展开，"
                        + "并讨论各类排序与查找算法的效率与适用场景。",
                "第二章 线性表",
                "线性表是最常用的数据结构，可采用顺序存储或链式存储，"
                        + "顺序表随机访问快而插入删除慢，链表则相反。");

        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        assertTrue(cs.size() >= 2, "应切出多章：" + cs.size());
        for (FulltextParser.Chapter c : cs) {
            assertFalse(c.body().isBlank(), "不应出现空正文章节：" + c.title());
        }
        String firstBody = cs.get(0).body();
        assertTrue(firstBody.contains("第一章 绪论") || firstBody.contains("第一部分 基础"),
                "被合并的抬头应出现在正文里（信息不丢）：" + firstBody);
    }

    @Test
    @DisplayName("图片占位符 [IMG k] 原地保留，且被登记到 imageRefs")
    void imagePlaceholdersKept() {
        String text = lines(
                "第一章 概述",
                "[IMG 1]",
                "上图展示了系统的整体结构，包含输入、处理与输出三个部分，"
                        + "各部分之间通过数据流连接。");
        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        assertEquals(1, cs.size(), "应只有一章：" + cs.size());
        assertTrue(cs.get(0).body().contains("[IMG 1]"), "图占位应原地保留：" + cs.get(0).body());
        assertEquals(List.of(1), cs.get(0).imageRefs(), "图号应被登记：" + cs.get(0).imageRefs());
    }

    @Test
    @DisplayName("章节序号连续且从 0 起（供排序）")
    void sortNosAreSequential() {
        String text = lines(
                "第一章 甲",
                "这是第一章的正文内容，用于验证章节编号连续且从零开始编号。",
                "第二章 乙",
                "这是第二章的正文内容，同样是足够长的句子以便被识别为正文。",
                "第三章 丙",
                "这是第三章的正文内容，验证三章的序号依次为 0、1、2。");
        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        for (int i = 0; i < cs.size(); i++) {
            assertEquals(i, cs.get(i).sortNo(), "序号应连续：" + cs);
        }
    }

    @Test
    @DisplayName("空文本不炸且返回空列表")
    void emptyInputIsSafe() {
        assertTrue(FulltextParser.split("").isEmpty());
        assertTrue(FulltextParser.split(null).isEmpty());
        assertTrue(FulltextParser.split("   \n  \n ").isEmpty());
    }

    @Test
    @DisplayName("页脚 6 / 326 不进标题也不进正文")
    void pageFootersDropped() {
        String text = lines(
                "第一章 甲",
                "这是第一章的正文内容，用于验证页脚行会被丢弃不污染章节。",
                "6 / 326",
                "第二章 乙",
                "这是第二章的正文内容，同样是足够长的句子以便被识别为正文。",
                "7/327");
        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        assertEquals(2, cs.size(), "应只剩两章：" + cs.size());
        for (FulltextParser.Chapter c : cs) {
            assertFalse(c.body().contains("/ 326") || c.body().contains("/327"),
                    "正文不应含页脚：" + c.body());
        }
    }

    @Test
    @DisplayName("正文折行误命中节抬头：含分句标点的长行不开新节")
    void wrappedBodyLineNotAHeading() {
        String text = lines(
                "第八章 查找",
                "本章介绍查找的基本概念与静态查找表的实现。",
                "第二节介绍的查找方法主要针对顺序存储结构，而有些结构需要特殊处理。",
                "本节介绍三种查找方法利用树形结构来存储记录。");
        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        assertEquals(1, cs.size(), "折行正文不应开新节：" + cs.size());
        assertTrue(cs.get(0).body().contains("第二节介绍的查找方法"),
                "折行应留在正文里");
    }

    @Test
    @DisplayName("第X讲开新节：讲义型教材按讲分章")
    void jiangOpensSection() {
        String text = lines(
                "数据结构先行课",
                "第一讲 C语言入门",
                "本讲介绍 C 语言的基本概念与开发环境的搭建过程。",
                "第一节 基本语句",
                "表达式加分号可以构成语句，这是 C 语言最基本的语句形式。",
                "第二讲 C的数据类型",
                "本讲介绍基本数据类型的表示范围与转换规则。");

        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        assertEquals(4, cs.size(), "应切出前言/第一讲/第一节/第二讲四节：" + cs.size());
        assertEquals("前言", cs.get(0).title(), "首节应为前言：" + cs.get(0).title());
        assertTrue(cs.get(1).title().contains("第一讲"), "第二节应为第一讲：" + cs.get(1).title());
        assertTrue(cs.get(3).title().contains("第二讲"), "末节应为第二讲：" + cs.get(3).title());
    }

    @Test
    @DisplayName("卷首印刷目录丢弃：点线页码行不进前言正文")
    void printedTocInHeadMatterDropped() {
        String text = lines(
                "数据结构先行课",
                "目录",
                "第一讲 C语言入门 ............ 2",
                "第二节 基本语句 ............ 8",
                "第一讲 C语言入门",
                "本讲介绍 C 语言的基本概念与开发环境的搭建过程。");

        List<FulltextParser.Chapter> cs = FulltextParser.split(text);
        String head = cs.get(0).body();
        assertFalse(head.contains("...."), "前言不应含目录点线：" + head);
        assertTrue(cs.stream().anyMatch(c -> c.title().contains("第一讲")),
                "真正的第一讲抬头应成节");
    }
}
