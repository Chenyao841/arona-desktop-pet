package com.cy.qq;

/**
 * 一次「想好了要说什么」的结果（P4：回复节奏改造后，思考与发送被拆开）。
 *
 * <p>为什么要拆：现在要求「不管想得多快，都要等固定时间再发」——所以
 * {@code QqChatService.think()} 只负责问模型拿到文本，真正发出去由
 * {@link QqReplyQueue} 在到点后调用 {@code QqChatService.deliver()}。
 */
public class QqOutcome {

    /** 要发出去的文本（已兜底，不会为空） */
    public final String text;
    /** 情绪词（挑表情包用，可为 null） */
    public final String emotion;
    /** true = 这是本地固定回复（额度用完/空@），不发表情包 */
    public final boolean localOnly;

    public QqOutcome(String text, String emotion) {
        this(text, emotion, false);
    }

    public QqOutcome(String text, String emotion, boolean localOnly) {
        this.text = text;
        this.emotion = emotion;
        this.localOnly = localOnly;
    }
}
