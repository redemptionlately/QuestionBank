package com.allen.questionbank.extra;

import com.allen.questionbank.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards for reunion import validation (pure static, no Spring context).
 * All CJK literals use unicode escapes so the file stays ASCII.
 */
class ExtraQuestionValidatorTest {

    private static final String FILL_MARK = "\u586b\u7a7a\u9898"; // 填空题
    private static final String SHORT_MARK = "\u7b80\u7b54\u9898"; // 简答题

    private ExtraQuestionValidator.ValidatedItem validFill() {
        return ExtraQuestionValidator.validate(188437L, ExtraQuestionType.FILL_BLANK, 12,
                "12\u3001\u3010" + FILL_MARK + "\u3011\u7c7b\u662f______\u7684\u96c6\u5408\u3002",
                List.of("\u6570\u636e"), "\u8f6f\u4ef6=\u7a0b\u5e8f+\u6570\u636e", null, "book20#seg12");
    }

    @Test
    void validFillPassesAndSerializesAnswers() {
        ExtraQuestionValidator.ValidatedItem v = validFill();
        assertEquals("[\"\u6570\u636e\"]", v.answersJson());
        assertEquals(12, v.questionNo());
    }

    @Test
    void blankPromptRejected() {
        assertThrows(ApiException.class, () -> ExtraQuestionValidator.validate(
                188437L, ExtraQuestionType.FILL_BLANK, 12, "   ",
                List.of("x"), null, null, null));
    }

    @Test
    void emptyAnswersRejected() {
        assertThrows(ApiException.class, () -> ExtraQuestionValidator.validate(
                188437L, ExtraQuestionType.SHORT_ANSWER, 30,
                "30\u3001\u3010" + SHORT_MARK + "\u3011\u4ec0\u4e48\u662f\u8f6f\u4ef6?",
                List.of(), null, null, null));
    }

    @Test
    void choiceShapedPromptRejected() {
        assertThrows(ApiException.class, () -> ExtraQuestionValidator.validate(
                188437L, ExtraQuestionType.FILL_BLANK, 5,
                "5\u3001\u3010" + FILL_MARK + "\u3011xxx\nA: aaa\nB: bbb",
                List.of("aaa"), null, null, null));
    }

    @Test
    void missingMarkerRejected() {
        assertThrows(ApiException.class, () -> ExtraQuestionValidator.validate(
                188437L, ExtraQuestionType.FILL_BLANK, 5,
                "5\u3001xxx______yyy", List.of("zzz"), null, null, null));
    }

    @Test
    void overlongAnswerRejected() {
        assertThrows(ApiException.class, () -> ExtraQuestionValidator.validate(
                188437L, ExtraQuestionType.ESSAY, 33,
                "33\u3001\u3010\u8bba\u8ff0\u9898\u3011xxx______yyy",
                List.of("x".repeat(2001)), null, null, null));
    }

    @Test
    void normalizeStripsAllWhitespaceIncludingIdeographicSpace() {
        assertEquals(ExtraQuestionValidator.normalize("a b\u3000c\nd"),
                ExtraQuestionValidator.normalize("ab\u3000cd"));
    }
}
