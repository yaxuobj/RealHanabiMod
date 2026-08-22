package com.realhanabimod.data;

/**
 * 花火の色プリセット一覧。
 * ← → ボタンでこの配列をインデックス送りする。
 * 値は 0xRRGGBB 形式。
 */
public class ColorPresets {

    public static final int[] COLORS = new int[] {
            0xFFFFFF, // 白
            0xFF3B30, // 赤
            0xFF9500, // 橙
            0xFFD60A, // 黄
            0x34C759, // 緑
            0x30D5C8, // 水色
            0x0A84FF, // 青
            0x5E5CE6, // 紫
            0xFF2D95, // ピンク
            0xFFD700, // 金
            0xC0C0C0, // 銀
            0x8B0000, // 深紅
    };

    public static final String[] NAMES = new String[] {
            "白", "赤", "橙", "黄", "緑", "水色", "青", "紫", "ピンク", "金", "銀", "深紅"
    };

    public static int get(int index) {
        int i = ((index % COLORS.length) + COLORS.length) % COLORS.length;
        return COLORS[i];
    }

    public static String getName(int index) {
        int i = ((index % NAMES.length) + NAMES.length) % NAMES.length;
        return NAMES[i];
    }
}
