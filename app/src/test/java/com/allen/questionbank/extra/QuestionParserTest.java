package com.allen.questionbank.extra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards for the chapter-compiled paper layout.
 * All CJK literals use unicode escapes so the file stays ASCII.
 */
class QuestionParserTest {

    // CJK building blocks (kept as escapes on purpose)
    private static final String ANS = "答案";
    private static final String EXP = "解析";
    private static final String COLON = "：";
    private static final String LP = "（";
    private static final String RP = "）";

    private static final String Q1 = String.join("\n",
            "1." + LP + "KP-one+2025.04" + RP,
            "stem one?",
            "A. 1140  B. 1144  C. 1156  D. 1160",
            ANS + COLON + "C",
            EXP + COLON + "reason one");

    private static final String TWO_QS = String.join("\n",
            "1." + LP + "KP-one+2025.04" + RP,
            "stem line one",
            "A. 1140  B. 1144  C. 1156  D. 1160",
            ANS + COLON + "C",
            EXP + COLON + "explanation one",
            "2.(KP-two+2024.10)",
            "stem line two",
            "A. opt1",
            "B. opt2",
            "C. opt3",
            "D. opt4",
            ANS + COLON + "B",
            EXP + COLON + "explanation two");

    @Test
    void inlineOptionsSplitIntoFour() {
        QuestionParser.ParseResult r = QuestionParser.parse(Q1);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals(List.of("1140", "1144", "1156", "1160"), q.options());
        assertEquals(List.of("1156"), q.correctAnswers());
        assertEquals("reason one", q.explanation());
    }

    @Test
    void fullwidthOptionMarkersStaySingleChoice() {
        // 223513 Q2/Q4 实锤：全角 ．/半角 ) 选项曾被漏掉，整题掉成 FILL。
        String text = String.join("\n",
                "2\uff0e\u4e0b\u5217\u5173\u4e8e\u7ebf\u6027\u8868\u7684\u53d9\u8ff0\u4e2d\uff0c\u9519\u8bef\u7684\u662f________\u3002",
                "A\uff0e\u94fe\u5f0f\u9002\u5408",
                "B\uff0e\u987a\u5e8f\u9002\u5408",
                "C\uff0e\u94fe\u5f0f\u8282\u7701",
                "D\uff0e\u987a\u5e8f\u5fc5\u8fde",
                ANS + COLON + "B\u3002",
                EXP + COLON + "B \u9519\u8bef");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("SINGLE", q.type());
        assertEquals(4, q.options().size());
        assertEquals(List.of("\u987a\u5e8f\u9002\u5408"), q.correctAnswers());
        assertEquals("B \u9519\u8bef", q.explanation());
    }

    @Test
    void halfParenOptionMarkersStaySingleChoice() {
        String text = String.join("\n",
                "4\uff0e\u9898\u5e72________\u3002",
                "A)\u9009\u9879\u4e00",
                "B)\u9009\u9879\u4e8c",
                ANS + COLON + "A",
                EXP + COLON + "e");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals("SINGLE", r.questions().get(0).type());
        assertEquals(2, r.questions().get(0).options().size());
    }

    @Test
    void kpHeaderExtractedAndPromptClean() {
        QuestionParser.ParseResult r = QuestionParser.parse(Q1);
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("KP-one", q.knowledgePoint());
        assertEquals("2025.04", q.sourceRef());
        assertEquals("stem one?", q.prompt());
        assertFalse(q.prompt().contains(LP));
    }

    @Test
    void bareNumberHeaderStillCollectsStem() {
        String text = String.join("\n",
                "1.",
                "stem?",
                "A. a",
                "B. b",
                ANS + COLON + "A",
                EXP + COLON + "e");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals("stem?", r.questions().get(0).prompt());
    }

    @Test
    void parseStemsKeepsExplanationWithOwner() {
        List<QuestionParser.StemQuestion> stems = QuestionParser.parseStems(TWO_QS);
        assertEquals(2, stems.size());
        assertEquals("stem line one", stems.get(0).prompt());
        assertEquals("explanation one", stems.get(0).explanation());
        assertEquals("KP-one", stems.get(0).knowledgePoint());
        assertEquals("stem line two", stems.get(1).prompt());
        assertEquals("explanation two", stems.get(1).explanation());
        assertFalse(stems.get(1).prompt().contains("explanation one"),
                "explanation of Q1 must not leak into Q2 prompt: " + stems.get(1).prompt());
    }

    @Test
    void inlineOptionsSingleSpaceSplit() {
        String text = String.join("\n",
                "1.",
                "stem?",
                "A. aa B. bb C. cc D. dd",
                ANS + COLON + "D",
                EXP + COLON + "e");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals(List.of("aa", "bb", "cc", "dd"), r.questions().get(0).options());
        assertEquals(List.of("dd"), r.questions().get(0).correctAnswers());
    }

    @Test
    void lonelyMarkerInBodyNotSplit() {
        String text = String.join("\n",
                "1.",
                "stem?",
                "A. vitamin B. daily",
                ANS + COLON + "A",
                EXP + COLON + "e");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals(List.of("vitamin B. daily"), r.questions().get(0).options());
    }

    @Test
    void kpHeaderSemicolonVariant() {
        String text = String.join("\n",
                "1." + LP + "KP-one；来自2025年10月自考卷" + RP + "stem one?",
                "A. a  B. b  C. c  D. d",
                ANS + COLON + "B",
                EXP + COLON + "e");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("KP-one", q.knowledgePoint());
        assertTrue(q.sourceRef().contains("2025"), q.sourceRef());
        assertEquals("stem one?", q.prompt());
        assertTrue(QuestionParser.displayKnowledgePoint(
                q.knowledgePoint(), q.sourceRef()).contains("2025年10月"));
    }

    @Test
    void wrappedLastOptionJoined() {
        String text = String.join("\n",
                "12.",
                "stem here?",
                "A. opt-a  B. opt-b  C. opt-c  D.",
                "opt-d tail",
                ANS + COLON + "D",
                EXP + COLON + "why d");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals(List.of("opt-a", "opt-b", "opt-c", "opt-d tail"),
                r.questions().get(0).options());
        assertEquals(List.of("opt-d tail"), r.questions().get(0).correctAnswers());
    }

    @Test
    void wrappedMidRunOptionJoined() {
        String text = String.join("\n",
                "17.",
                "stem here?",
                "A. a1  B. b1  C. c-first",
                "c-rest  D. d1",
                ANS + COLON + "C",
                EXP + COLON + "why");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals(List.of("a1", "b1", "c-firstc-rest", "d1"),
                r.questions().get(0).options());
        assertEquals(List.of("c-firstc-rest"), r.questions().get(0).correctAnswers());
    }

    @Test
    void twoPlusTwoLayoutSplit() {
        String text = String.join("\n",
                "8.",
                "stem here?",
                "A. opt-a B. opt-b",
                "C. opt-c D. opt-d",
                ANS + COLON + "A",
                EXP + COLON + "why a");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals(List.of("opt-a", "opt-b", "opt-c", "opt-d"),
                r.questions().get(0).options());
        assertEquals(List.of("opt-a"), r.questions().get(0).correctAnswers());
    }

    @Test
    void fillBlankSplitAndCounted() {
        String text = String.join("\n",
                "二、填空题",
                "1. 直接插入排序的核心操作是__和__。",
                "答案：比较；移动",
                "2. 冒泡排序是最好的排序",
                "答案：错");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("FILL_BLANK", q.type());
        assertEquals(List.of("比较", "移动"), q.correctAnswers());
        assertTrue(q.options().isEmpty());
    }

    @Test
    void fillBlankCountMismatchGoesUnparsed() {
        String text = String.join("\n",
                "二、填空题",
                "1. 操作是__和__。",
                "答案：比较");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertTrue(r.questions().isEmpty());
        assertEquals(1, r.unparsed().size());
    }

    @Test
    void shortAnswerMultiline() {
        String text = String.join("\n",
                "三、简答题",
                "1. 简述冒泡排序的核心思想。",
                "答案：相邻比较逆序交换；",
                "每一轮冒出最大元素。",
                "2. 单选题残留",
                "A. a",
                "B. b",
                "答案：A");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("SHORT_ANSWER", q.type());
        assertTrue(q.correctAnswers().get(0).contains("每一轮"));
    }

    @Test
    void inlineAnswerSplitInShortSection() {
        String text = String.join("\n",
                "三、简答题",
                "1. 简述栈的特点。答案：后进先出",
                "2. 简述队列的特点是什么？",
                "参考答案：分点作答");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(2, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals("SHORT_ANSWER", r.questions().get(0).type());
        assertEquals(List.of("后进先出"), r.questions().get(0).correctAnswers());
        assertEquals("简述栈的特点。", r.questions().get(0).prompt());
        assertEquals(List.of("分点作答"), r.questions().get(1).correctAnswers());
    }

    @Test
    void numberedAnswerTailStaysInAnswer() {
        String text = String.join("\n",
                "三、简答题",
                "1. 简述步骤。",
                "答案：",
                "1. 输入处理；",
                "2. 输出结果。");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        String a = r.questions().get(0).correctAnswers().get(0);
        assertTrue(a.contains("输出结果"), a);
    }

    @Test
    void bareAnswerLineContinues() {
        String text = String.join("\n",
                "三、简答题",
                "1. 简述栈的特点。",
                "答案：",
                "后进先出；",
                "应用广泛。",
                "解析：略");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("SHORT_ANSWER", q.type());
        assertTrue(q.correctAnswers().get(0).contains("应用广泛"), q.correctAnswers().get(0));
        assertEquals("略", q.explanation());
    }

    @Test
    void orphanTailGluedToShortAnswer() {
        String text = String.join("\n",
                "三、简答题",
                "1. 简述步骤。",
                "答案：",
                "1. 输入处理；",
                "2. 输出结果。",
                "2. 下一题是什么？",
                "答案：未知");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(2, r.questions().size(), "unparsed: " + r.unparsed());
        String a = r.questions().get(0).correctAnswers().get(0);
        assertTrue(a.contains("输出结果"), a);
    }

    @Test
    void realWorldShortTailsGlue() {
        String text = String.join("\n",
                "三、简答题",
                "4. 简述深度。",
                "答案与解析：",
                "广义表深度的定义：",
                "递归计算方法：",
                "1. 若广义表为空，返回深度1；",
                "2. 初始化最大深度；",
                "GL的深度计算：",
                "1. 子表的深度 = 2；",
                "2. 空表的深度 = 1；",
                "5. 下一题是什么？",
                "答案：未知");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(2, r.questions().size(), "unparsed: " + r.unparsed());
        String a = r.questions().get(0).correctAnswers().get(0);
        assertTrue(a.contains("空表的深度"), a);
    }

    @Test
    void fullwidthNumberNormalized() {
        assertEquals("12", QuestionParser.normNo("１２"));
    }

    @Test
    void displayKnowledgePointFormatsYearSuffix() {
        String s = QuestionParser.displayKnowledgePoint("KP", "2025.04");
        assertTrue(s.contains("2025"), s);
        assertTrue(s.contains("KP"), s);
    }

    @Test
    void choiceAnswerWithPeriodAndTrailingExplanation() {
        String text = String.join("\n",
                "一、单选题",
                "1. stem here?",
                "A. aa",
                "B. bb",
                ANS + COLON + "B\u3002",
                "why b is right",
                "2. next?",
                "A. x",
                "B. y",
                ANS + COLON + "A");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(2, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("SINGLE", q.type());
        assertEquals(List.of("bb"), q.correctAnswers());
        assertTrue(q.explanation() != null && q.explanation().contains("why b is right"),
                q.explanation());
    }

    @Test
    void choiceAnswerWeiForm() {
        String text = String.join("\n",
                "一、单选题",
                "1. stem here?",
                "A. aa",
                "B. bb",
                "C. cc",
                ANS + "为C\u3002",
                "why c");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals(List.of("cc"), r.questions().get(0).correctAnswers());
    }

    @Test
    void bracketRefStandaloneMarkerContinues() {
        String text = String.join("\n",
                "三、简答题",
                "1. 简述栈的特点。",
                "\u3010参考答案\u3011",
                "后进先出。",
                "2. 下一题？",
                "答案：未知");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(2, r.questions().size(), "unparsed: " + r.unparsed());
        assertTrue(r.questions().get(0).correctAnswers().get(0).contains("后进先出"));
    }

    @Test
    void fillAnswerTerminatedRoutesFollowingToExplanation() {
        String text = String.join("\n",
                "二、填空题",
                "1. 条件是__。",
                "答案：head==NULL\u3002",
                "解释行内容。",
                "2. 下一题__。",
                "答案：x");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(2, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals(List.of("head==NULL"), q.correctAnswers());
        assertTrue(q.explanation() != null && q.explanation().contains("解释行内容"),
                q.explanation());
    }

    @Test
    void fillSectionChoiceWithOptionsPromoted() {
        String text = String.join("\n",
                "二、填空题",
                "1. stem__。",
                "A. aa",
                "B. bb",
                ANS + COLON + "B\u3002",
                "why");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        assertEquals("SINGLE", r.questions().get(0).type());
        assertEquals(List.of("bb"), r.questions().get(0).correctAnswers());
    }

    @Test
    void unbalancedOptionGoesUnparsed() {
        String text = String.join("\n",
                "一、单选题",
                "1. stem?",
                "A. O(n",
                "B. O(log n)",
                ANS + COLON + "A");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertTrue(r.questions().isEmpty());
        assertEquals(1, r.unparsed().size());
    }

    @Test
    void romanStatementsStayInPrompt() {
        String text = String.join("\n",
                "一、单选题",
                "3. 下列关于二叉树的叙述中，正确的是__。",
                "I．单结点二叉树的度为0",
                "II．二叉树的度为2",
                "A. 仅I",
                "B. 仅II",
                ANS + COLON + "A",
                EXP + COLON + "why");
        QuestionParser.ParseResult r = QuestionParser.parse(text);
        assertEquals(1, r.questions().size(), "unparsed: " + r.unparsed());
        QuestionParser.ParsedQuestion q = r.questions().get(0);
        assertEquals("SINGLE", q.type());
        assertEquals(List.of("仅I", "仅II"), q.options());
        assertEquals(List.of("仅I"), q.correctAnswers());
        assertTrue(q.prompt().contains("单结点"), q.prompt());
    }

    @Test
    void taintedPromptDetector() {
        assertTrue(IngestionService.taintedPrompt("stem\n" + EXP + COLON + "leaked"));
        assertTrue(IngestionService.taintedPrompt("stem " + ANS + COLON + "B"));
        assertTrue(IngestionService.taintedPrompt("reason，故选B"));
        assertFalse(IngestionService.taintedPrompt("stem about functions"));
        assertFalse(IngestionService.taintedPrompt(null));
    }
}
