package com.realhanabimod.client.gui.widget;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 汎用スクロールリスト。
 * 重要: 同じインスタンスを使い続け、rows を差し替える(setRows)だけならスクロール位置(scrollOffset)は
 * 自動的に保持される。何かボタン操作でデータが変わってリストを作り直す際も、この仕組みにより
 * 「押した瞬間に一番上へ戻る」問題が起きない（＝スクロール位置のsave/loadを毎回自前でやる必要がない）。
 */
public class ScrollListWidget {

    public interface Row {
        int height();

        /** x,y はこの行の左上の絶対スクリーン座標。width は行の幅。 */
        void render(GuiGraphics gfx, int x, int y, int width, int mouseX, int mouseY);

        /** クリックを消費したら true を返す。 */
        boolean mouseClicked(int x, int y, int width, double mouseX, double mouseY, int button);
    }

    private final int x, y, width, height;
    private List<Row> rows = new ArrayList<>();
    private int scrollOffset = 0; // ピクセル単位。意図的にリセットしない。

    public ScrollListWidget(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** rows を差し替える。scrollOffset は保持し、範囲外にならないようクランプするのみ。 */
    public void setRows(List<Row> newRows) {
        this.rows = newRows;
        clampScroll();
    }

    private int totalContentHeight() {
        int h = 0;
        for (Row r : rows) h += r.height();
        return h;
    }

    private void clampScroll() {
        int max = Math.max(0, totalContentHeight() - height);
        if (scrollOffset > max) scrollOffset = max;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    public void render(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.fill(x, y, x + width, y + height, 0x66000000);
        gfx.enableScissor(x, y, x + width, y + height);

        int cursorY = y - scrollOffset;
        for (Row row : rows) {
            int rh = row.height();
            if (cursorY + rh >= y && cursorY <= y + height) {
                row.render(gfx, x, cursorY, width, mouseX, mouseY);
            }
            cursorY += rh;
        }
        gfx.disableScissor();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) return false;

        int cursorY = y - scrollOffset;
        for (Row row : rows) {
            int rh = row.height();
            if (mouseY >= cursorY && mouseY < cursorY + rh) {
                return row.mouseClicked(x, cursorY, width, mouseX, mouseY, button);
            }
            cursorY += rh;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) return false;
        scrollOffset -= (int) (delta * 16);
        clampScroll();
        return true;
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(int offset) {
        this.scrollOffset = offset;
        clampScroll();
    }
}
