package com.realhanabimod.client.gui;

import com.realhanabimod.client.render.FireworkShapeManager;
import com.realhanabimod.data.ColorPresets;
import com.realhanabimod.data.FireworkEntry;
import com.realhanabimod.data.HanabiShowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class HanabiEditScreen extends Screen {

    private final HanabiListScreen parent;
    private final BlockPos pos;
    private final HanabiShowData snapshotBeforeEdit; // キャンセル時の復元用
    private final long entryUid;
    private FireworkEntry entry;

    private EditBox sizeBox, heightBox, explodeTimeBox, offsetXBox, offsetZBox;

    private Button gradientToggleButton;
    private Button secondaryColorLeftButton, secondaryColorRightButton;

    private static final int PANEL_MARGIN = 24;
    private static final int NUM_ROWS = 7; // デザイン/大きさ/高さ/爆発時間/オフセット/色/グラデーション
    private static final int BOTTOM_RESERVED = 40; // 保存・戻るボタン用に確保する下部の高さ
    private static final int LABEL_W = 110; // ラベルを入力ボックスの左に置くための固定幅

    // レイアウト計算結果（init と render の両方から参照するためフィールドに保持）
    private int optX, optY, rowH, panelX, panelY, panelW, panelH, fieldX, fieldW;

    public HanabiEditScreen(HanabiListScreen parent, BlockPos pos, HanabiShowData snapshotBeforeEdit, long entryUid) {
        super(Component.translatable("gui.realhanabimod.edit.title"));
        this.parent = parent;
        this.pos = pos;
        this.snapshotBeforeEdit = snapshotBeforeEdit;
        this.entryUid = entryUid;
    }

    @Override
    protected void init() {
        entry = (FireworkEntry) parent.data.get(entryUid);
        if (entry == null) {
            this.minecraft.setScreen(parent);
            return;
        }

        panelX = PANEL_MARGIN;
        panelY = PANEL_MARGIN;
        panelW = width - PANEL_MARGIN * 2;
        panelH = height - PANEL_MARGIN * 2;

        optX = panelX + 24;
        int topGap = 30;
        int available = Math.max(NUM_ROWS * 22, panelH - topGap - BOTTOM_RESERVED);
        // ラベルを横に置くので、1行あたり必要な高さは箱の高さ(18)+わずかな余白でよい。
        // それでも極端に画面が小さい場合に備えて 22〜32px の範囲でクランプする。
        rowH = Mth.clamp(available / NUM_ROWS, 22, 32);
        optY = panelY + topGap;

        fieldX = optX + LABEL_W;
        fieldW = Math.max(90, Math.min(150, panelW - LABEL_W - 60));

        // 0. デザイン ← [名前] →
        addRenderableWidget(Button.builder(Component.literal("←"), b -> entry.designIndex--)
                .bounds(optX, rowY(0), 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal("→"), b -> entry.designIndex++)
                .bounds(fieldX + fieldW - 20, rowY(0), 20, 18).build());

        // 1. 大きさ（ラベルは箱の左）
        sizeBox = new EditBox(font, fieldX, rowY(1), fieldW, 18, Component.literal("size"));
        sizeBox.setValue(trim(entry.size));
        sizeBox.setResponder(s -> setFloatSafe(v -> entry.size = Math.max(0.1f, v), s));
        addRenderableWidget(sizeBox);

        // 2. 高さ
        heightBox = new EditBox(font, fieldX, rowY(2), fieldW, 18, Component.literal("height"));
        heightBox.setValue(trim(entry.height));
        heightBox.setResponder(s -> setFloatSafe(v -> entry.height = Math.max(1f, v), s));
        addRenderableWidget(heightBox);

        // 3. 爆発までの時間（この時間と高さの比率で玉の上昇速度が決まる）
        explodeTimeBox = new EditBox(font, fieldX, rowY(3), fieldW, 18, Component.literal("explodeTime"));
        explodeTimeBox.setValue(trim(entry.explodeTime));
        explodeTimeBox.setResponder(s -> setFloatSafe(v -> entry.explodeTime = Math.max(0.2f, v), s));
        addRenderableWidget(explodeTimeBox);

        // 4. X,Z オフセット（ラベルの右に2つ並べる。全体幅は他の行の入力欄と揃える）
        int halfW = (fieldW - 8) / 2;
        offsetXBox = new EditBox(font, fieldX, rowY(4), halfW, 18, Component.literal("x"));
        offsetXBox.setValue(trim(entry.offsetX));
        offsetXBox.setResponder(s -> setFloatSafe(v -> entry.offsetX = v, s));
        addRenderableWidget(offsetXBox);

        offsetZBox = new EditBox(font, fieldX + halfW + 8, rowY(4), halfW, 18, Component.literal("z"));
        offsetZBox.setValue(trim(entry.offsetZ));
        offsetZBox.setResponder(s -> setFloatSafe(v -> entry.offsetZ = v, s));
        addRenderableWidget(offsetZBox);

        // 5. 色 ← [色見本] →（見本のすぐ横に色名を表示。間隔を詰めている）
        addRenderableWidget(Button.builder(Component.literal("←"), b -> {
            int cur = entry.colors.get(0);
            entry.colors.set(0, cur - 1);
        }).bounds(fieldX, rowY(5), 18, 18).build());

        addRenderableWidget(Button.builder(Component.literal("→"), b -> {
            int cur = entry.colors.get(0);
            entry.colors.set(0, cur + 1);
        }).bounds(fieldX + 40, rowY(5), 18, 18).build());

        // 6. グラデーション（専用行にして横はみ出しを防止）
        gradientToggleButton = Button.builder(Component.translatable("gui.realhanabimod.gradient.toggle"), b -> {
            if (entry.colors.size() > 1) {
                entry.colors.remove(entry.colors.size() - 1);
            } else {
                entry.colors.add(entry.colors.get(0) + 1);
            }
            updateGradientButtonsVisibility();
        }).bounds(fieldX, rowY(6), 130, 18).build();
        addRenderableWidget(gradientToggleButton);

        // グラデーションをオンにした時だけ表示される、右側の2つの矢印ボタン
        secondaryColorLeftButton = Button.builder(Component.literal("←"), b -> {
            if (entry.colors.size() > 1) {
                int cur = entry.colors.get(1);
                entry.colors.set(1, cur - 1);
            }
        }).bounds(fieldX + 138, rowY(6), 18, 18).build();
        addRenderableWidget(secondaryColorLeftButton);

        secondaryColorRightButton = Button.builder(Component.literal("→"), b -> {
            if (entry.colors.size() > 1) {
                int cur = entry.colors.get(1);
                entry.colors.set(1, cur + 1);
            }
        }).bounds(fieldX + 178, rowY(6), 18, 18).build();
        addRenderableWidget(secondaryColorRightButton);
        // ↑ 2つ目のスウォッチは render() 側で fieldX+158〜fieldX+176 に描画する。

        updateGradientButtonsVisibility();

        // 保存 / 保存せずに戻る
        addRenderableWidget(Button.builder(Component.translatable("gui.realhanabimod.save"), b -> onSave())
                .bounds(width - 170, height - 34, 75, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.realhanabimod.cancel"), b -> onCancel())
                .bounds(width - 90, height - 34, 70, 20).build());
    }

    private int rowY(int index) {
        return optY + rowH * index;
    }

    private void updateGradientButtonsVisibility() {
        boolean gradientOn = entry.colors.size() > 1;
        secondaryColorLeftButton.visible = gradientOn;
        secondaryColorLeftButton.active = gradientOn;
        secondaryColorRightButton.visible = gradientOn;
        secondaryColorRightButton.active = gradientOn;
    }

    private void setFloatSafe(java.util.function.Consumer<Float> setter, String s) {
        try {
            setter.accept(Float.parseFloat(s));
        } catch (NumberFormatException ignored) {
        }
    }

    private static String trim(float v) {
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.valueOf(v);
    }

    private void onSave() {
        parent.syncAndRebuild();
        this.minecraft.setScreen(parent);
    }

    private void onCancel() {
        parent.data.items.clear();
        parent.data.items.addAll(snapshotBeforeEdit.items);
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        gfx.fill(0, 0, width, height, 0x99000000);

        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xB0000000);
        drawBorder(gfx, panelX, panelY, panelW, panelH, 0xFFFFFFFF);

        gfx.drawCenteredString(this.font, this.title, width / 2, panelY - 16, 0xFFFFFF);

        if (entry != null) {
            gfx.drawCenteredString(font, FireworkShapeManager.designName(entry.designIndex),
                    fieldX + fieldW / 2, rowY(0) + 5, 0xFFFFFF);

            // ラベルは各入力ボックスの左、縦方向は中央揃え
            drawLabel(gfx, "大きさ", rowY(1));
            drawLabel(gfx, "高さ", rowY(2));
            drawLabel(gfx, "爆発まで(秒)", rowY(3));
            drawLabel(gfx, "X / Z", rowY(4));

            // メインの色スウォッチ（矢印のすぐ間、狭い間隔）＋色名
            int colorIdx = entry.colors.get(0);
            int rgb = ColorPresets.get(colorIdx);
            gfx.fill(fieldX + 20, rowY(5), fieldX + 38, rowY(5) + 18, 0xFF000000 | rgb);
            gfx.drawString(font, ColorPresets.getName(colorIdx), fieldX + 62, rowY(5) + 5, 0xFFFFFF);

            if (entry.colors.size() > 1) {
                int rgb2 = ColorPresets.get(entry.colors.get(1));
                gfx.fill(fieldX + 158, rowY(6), fieldX + 176, rowY(6) + 18, 0xFF000000 | rgb2);
            }
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private void drawLabel(GuiGraphics gfx, String text, int rowTopY) {
        // 入力ボックス(高さ18)と縦方向の中心を合わせて描画する
        gfx.drawString(font, text, optX, rowTopY + 5, 0xAAAAAA);
    }

    private static void drawBorder(GuiGraphics gfx, int x, int y, int w, int h, int color) {
        gfx.fill(x, y, x + w, y + 1, color);
        gfx.fill(x, y + h - 1, x + w, y + h, color);
        gfx.fill(x, y, x + 1, y + h, color);
        gfx.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}