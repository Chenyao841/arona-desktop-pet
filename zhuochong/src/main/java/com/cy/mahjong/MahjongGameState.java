package com.cy.mahjong;

import java.util.ArrayList;
import java.util.List;

/**
 * 麻将对局状态机（纯逻辑，无 I/O）：维护排序手牌 / 摸牌 / 副露，
 * 支持 摸牌、出牌、吃/碰回报 后的纯计算更新（零截图）。
 * 屏幕槽位约定：槽 0..hand.size()-1 = 排序手牌（左对齐），槽 hand.size() = 摸牌位。
 */
public class MahjongGameState {

    /** 排序后的手牌（不含摸牌、不含副露），万→筒→条→字 */
    public final List<Integer> hand = new ArrayList<>();
    /** 当前摸到的牌（null=未摸/已打出） */
    public Integer drawn = null;
    /** 副露组：每组 3-4 张 */
    public final List<int[]> melds = new ArrayList<>();
    /** 是否已立直（立直后不再操作，仅等胡） */
    public boolean riichi = false;

    /** 初始化（13 张手牌 + 摸牌） */
    public void init(List<Integer> hand13, Integer drawnTile) {
        hand.clear();
        if (hand13 != null) hand.addAll(hand13);
        drawn = drawnTile;
        melds.clear();
        riichi = false;
        sortHand();
    }

    /** 摸牌（进张） */
    public void draw(Integer tile) {
        if (drawn != null) throw new IllegalStateException("已有摸牌未处理，不能重复摸牌");
        if (tile == null) throw new IllegalArgumentException("摸牌不能为空");
        drawn = tile;
    }

    /** 出牌：移除该牌并返回其屏幕槽位序号（0=最左；摸牌位=hand.size()）。
     *  注意：打出任意一张后，摸牌（若有）会并入排序手牌，摸牌槽清空。 */
    public int discard(Integer tile) {
        if (tile == null) throw new IllegalArgumentException("出牌不能为空");
        Integer merge = drawn; // 摸牌最终并入
        if (drawn != null && drawn.equals(tile)) {
            drawn = null;
            return hand.size(); // 摸牌位
        }
        int idx = hand.indexOf(tile);
        if (idx < 0) throw new IllegalStateException("手牌中没有 " + MahjongPluginService.TILE_NAMES[tile] + " 可出");
        hand.remove(idx);
        if (merge != null) {
            hand.add(merge);
            drawn = null;
        }
        sortHand();
        return idx;
    }

    /** 吃回报："吃 四条 五条 六条" / "四条 五条 吃 六条"：从手牌移除搭子两张，副露 +1 */
    public void recordChi(Integer a, Integer b, Integer eaten) {
        int ia = hand.indexOf(a);
        if (ia < 0) throw new IllegalStateException("吃回报错误：手牌无 " + MahjongPluginService.TILE_NAMES[a]);
        hand.remove(ia);
        int ib = hand.indexOf(b);
        if (ib < 0) throw new IllegalStateException("吃回报错误：手牌无 " + MahjongPluginService.TILE_NAMES[b]);
        hand.remove(ib);
        melds.add(new int[]{a, b, eaten});
        sortHand();
    }

    /** 碰回报：手牌移除两张 + 副露 */
    public void recordPon(Integer tile) {
        int ia = hand.indexOf(tile);
        if (ia < 0) throw new IllegalStateException("碰回报错误：手牌无 " + MahjongPluginService.TILE_NAMES[tile]);
        hand.remove(ia);
        int ib = hand.indexOf(tile);
        if (ib < 0) throw new IllegalStateException("碰回报错误：手牌不足两张 " + MahjongPluginService.TILE_NAMES[tile]);
        hand.remove(ib);
        melds.add(new int[]{tile, tile, tile});
        sortHand();
    }

    /** 杠回报：手牌移除三张 + 副露（4张） */
    public void recordKan(Integer tile) {
        for (int i = 0; i < 3; i++) {
            int ia = hand.indexOf(tile);
            if (ia < 0) throw new IllegalStateException("杠回报错误：手牌不足三张 " + MahjongPluginService.TILE_NAMES[tile]);
            hand.remove(ia);
        }
        melds.add(new int[]{tile, tile, tile, tile});
        sortHand();
    }

    public int handSize() { return hand.size(); }
    public int meldCount() { return melds.size(); }
    /** 含摸牌的总张数（14=出牌前） */
    public int totalTiles() { return hand.size() + (drawn != null ? 1 : 0); }

    public void sortHand() {
        hand.removeIf(x -> x == null || x < 0 || x >= 34); // 防御：清掉非法标签
        hand.sort((x, y) -> Integer.compare(MahjongPluginService.sortKey(x), MahjongPluginService.sortKey(y)));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("手牌[");
        for (Integer i : hand) sb.append(MahjongPluginService.TILE_NAMES[i]).append(" ");
        sb.append("]");
        if (drawn != null) sb.append(" 摸牌=").append(MahjongPluginService.TILE_NAMES[drawn]);
        if (!melds.isEmpty()) {
            sb.append(" 副露");
            for (int[] m : melds) {
                sb.append("(");
                for (int t : m) sb.append(MahjongPluginService.TILE_NAMES[t]);
                sb.append(")");
            }
        }
        if (riichi) sb.append(" 已立直");
        return sb.toString();
    }
}
