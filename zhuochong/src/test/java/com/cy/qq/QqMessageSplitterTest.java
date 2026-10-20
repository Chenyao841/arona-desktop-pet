package com.cy.qq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分段规则单测（P0 的核心可测逻辑）：按 {@code \n\n} 分段 → 按句切 → ≤300 字 → 条数上限。
 * 不启 Spring 上下文，跑得很快。
 */
class QqMessageSplitterTest {

    @Test
    void 短句不切只发一条() {
        List<String> chunks = QqMessageSplitter.split("老师早上好呀～", 300, 6);
        assertEquals(1, chunks.size());
        assertEquals("老师早上好呀～", chunks.get(0));
    }

    @Test
    void 空行分段按空行拆开() {
        List<String> chunks = QqMessageSplitter.split("第一段。\n\n第二段。", 300, 6);
        assertEquals(2, chunks.size());
        assertEquals("第一段。", chunks.get(0));
        assertEquals("第二段。", chunks.get(1));
    }

    @Test
    void 同段内多个短句合并成一条() {
        List<String> chunks = QqMessageSplitter.split("我在呢。老师有事吗？", 300, 6);
        assertEquals(1, chunks.size());
        assertEquals("我在呢。老师有事吗？", chunks.get(0));
    }

    @Test
    void 每一条都不超过上限() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("这是第").append(i).append("句话，用来凑长度。");
        List<String> chunks = QqMessageSplitter.split(sb.toString(), 300, 0);
        assertFalse(chunks.isEmpty());
        for (String c : chunks) {
            assertTrue(c.length() <= 300, "超长：" + c.length());
        }
    }

    @Test
    void 超长无标点也能硬切() {
        String s = "啊".repeat(1000);
        List<String> chunks = QqMessageSplitter.split(s, 300, 0);
        assertEquals(4, chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= 300);
        }
    }

    @Test
    void 条数超过上限会截断并补省略号() {
        List<String> chunks = QqMessageSplitter.split("一。\n\n二。\n\n三。\n\n四。", 300, 2);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(1).endsWith("…"));
    }

    @Test
    void 字面反斜杠n还原成换行() {
        List<String> chunks = QqMessageSplitter.split("你好\\n\\n再见", 300, 6);
        assertEquals(2, chunks.size());
    }

    @Test
    void 多行报表保留换行不被粘连() {
        String report = "【运行状态】\n连接：在线\n今日消耗：1925 tokens\n每日上限：不限";
        List<String> chunks = QqMessageSplitter.split(report, 300, 6);
        assertEquals(1, chunks.size(), "短报表应合成一条消息");
        assertTrue(chunks.get(0).contains("\n"), "换行必须保留：管理报表一行一条，粘成一坨没法看");
        assertEquals(4, chunks.get(0).split("\n").length);
    }

    @Test
    void 空文本返回空列表() {
        assertTrue(QqMessageSplitter.split(null, 300, 6).isEmpty());
        assertTrue(QqMessageSplitter.split("   ", 300, 6).isEmpty());
    }
}
