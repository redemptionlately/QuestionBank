package com.allen.questionbank.bank;

public enum QuestionType {
    SINGLE,
    MULTIPLE,
    TRUE_FALSE,
    /** 填空题：options 为空，correctAnswers 按空序存每空答案，顺序敏感判分。 */
    FILL_BLANK,
    /** 简答/算法题：options 为空，correctAnswers 存参考答案文本，严格比对判分并展示。 */
    SHORT_ANSWER
}
