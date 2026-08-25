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

public class HanabiEditScreen extends Screen {

    private final HanabiListScreen parent;
    private final BlockPos pos;
    private final HanabiShowData snapshotBeforeEdit; // キャンセル時の復元用
    private final long entryUid;
    private FireworkEntry entry;

    private EditBox sizeBox, heightBox, explodeTimeBox, offsetXBox, offsetZBox;
    private EditBox curveXBox, curveZBox;
    private EditBox extraExplodeHeightBox;

    private Button gradientToggleButton;
    private Button secondaryColorLeftButton, secondaryColorRightButton;
    private Button misfireToggleButton;
    private Button curveToggleButton;
    private Button ballHiddenToggleButton;
    private Button tailOnlyToggleButton;
    private int secondarySwatchX; // グラデーション2色目のスウォッチ描画X座標（パネル幅に応じて可変）

    private static final int PANEL_MARGIN = 24;
    // デザイン/大きさ/高さ/爆発時間/オフセット/色/グラデーション/不発/カーブ/カーブ先XZ/玉の見え方(2つ並び)/消えてから+高さ
    private static final int NUM_ROWS = 12;
    private static final int BOTTOM_RESERVED = 40; // 保存・戻るボタン用に確保する下部の高さ
    private static final int LABEL_W = 110; // ラベルを入力ボックスの左に置くための固定幅
    private static final int PREFERRED_ROW_H_MIN = 18; // 読みやすさを優先したい最小の行の高さ
    private static final int PREFERRED_ROW_H_MAX = 26; // 間延びさせすぎないための最大の行の高さ

    // レイアウト計算結果（init と render の両方から参照するためフィールドに保持）
    private int optX, optY, rowH, panelX, panelY, panelW, panelH, fieldX, fieldW;
    // 行数が多い時にウィジェットの縦幅そのものを詰めて、はみ出し・重なりを防ぐ（rowHに追従する可変の高さ）
    private int fieldH;

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

        optX = panelX + 24;
        int topGap = 30;
        int maxContentH = height - PANEL_MARGIN * 2; // 画面に収まる最大の高さ
        int availableForRows = maxContentH - topGap - BOTTOM_RESERVED;
        // 1行あたりの高さは、基本的には読みやすさを優先して18〜26pxの範囲を使う。
        // ただし画面(GUIスケール)が小さくてその範囲では全NUM_ROWS行が入りきらない場合に限り、
        // 「はみ出し・重なりを起こさないこと」を優先して、実際に入りきる値まで詰める
        // （下限を固定してしまうと、画面が小さい時にその下限のせいでパネル外や保存ボタンに
        // 重なってしまう不具合が起きるため、その場合だけ例外的により小さい値へフォールバックする）。
        int idealRawRowH = availableForRows / NUM_ROWS;
        int preferredRowH = Math.max(PREFERRED_ROW_H_MIN, Math.min(PREFERRED_ROW_H_MAX, idealRawRowH));
        if (preferredRowH * NUM_ROWS <= availableForRows) {
            rowH = preferredRowH;
        } else {
            rowH = Math.max(1, idealRawRowH);
        }
        // パネルの高さは、上のrowHで実際に必要になる高さ(必ずmaxContentH以下になる)に合わせる。
        panelH = topGap + rowH * NUM_ROWS + BOTTOM_RESERVED;
        optY = panelY + topGap;

        fieldX = optX + LABEL_W;
        fieldW = Math.max(90, Math.min(150, panelW - LABEL_W - 60));

        // ウィジェット自体の縦幅もrowHより必ず小さく（最低でも1pxの隙間を空けて）追従させる。
        fieldH = Math.max(1, Math.min(18, rowH - 2));

        // 0. デザイン ← [名前] →
        addRenderableWidget(Button.builder(Component.literal("←"), b -> entry.designIndex--)
                .bounds(optX, rowY(0), 20, fieldH).build());
        addRenderableWidget(Button.builder(Component.literal("→"), b -> entry.designIndex++)
                .bounds(fieldX + fieldW - 20, rowY(0), 20, fieldH).build());

        // 1. 大きさ（ラベルは箱の左）
        sizeBox = new EditBox(font, fieldX, rowY(1), fieldW, fieldH, Component.literal("size"));
        sizeBox.setValue(trim(entry.size));
        sizeBox.setResponder(s -> setFloatSafe(v -> entry.size = Math.max(0.1f, v), s));
        addRenderableWidget(sizeBox);

        // 2. 高さ
        heightBox = new EditBox(font, fieldX, rowY(2), fieldW, fieldH, Component.literal("height"));
        heightBox.setValue(trim(entry.height));
        heightBox.setResponder(s -> setFloatSafe(v -> entry.height = Math.max(1f, v), s));
        addRenderableWidget(heightBox);

        // 3. 爆発までの時間（この時間と高さの比率で玉の上昇速度が決まる）
        explodeTimeBox = new EditBox(font, fieldX, rowY(3), fieldW, fieldH, Component.literal("explodeTime"));
        explodeTimeBox.setValue(trim(entry.explodeTime));
        explodeTimeBox.setResponder(s -> setFloatSafe(v -> entry.explodeTime = Math.max(0.2f, v), s));
        addRenderableWidget(explodeTimeBox);

        // 4. X,Z オフセット（ラベルの右に2つ並べる。全体幅は他の行の入力欄と揃える）
        int halfW = (fieldW - 8) / 2;
        offsetXBox = new EditBox(font, fieldX, rowY(4), halfW, fieldH, Component.literal("x"));
        offsetXBox.setValue(trim(entry.offsetX));
        offsetXBox.setResponder(s -> setFloatSafe(v -> entry.offsetX = v, s));
        addRenderableWidget(offsetXBox);

        offsetZBox = new EditBox(font, fieldX + halfW + 8, rowY(4), halfW, fieldH, Component.literal("z"));
        offsetZBox.setValue(trim(entry.offsetZ));
        offsetZBox.setResponder(s -> setFloatSafe(v -> entry.offsetZ = v, s));
        addRenderableWidget(offsetZBox);

        // 5. 色 ← [色見本] →（見本のすぐ横に色名を表示。間隔を詰めている）
        addRenderableWidget(Button.builder(Component.literal("←"), b -> {
            int cur = entry.colors.get(0);
            entry.colors.set(0, cur - 1);
        }).bounds(fieldX, rowY(5), fieldH, fieldH).build());

        addRenderableWidget(Button.builder(Component.literal("→"), b -> {
            int cur = entry.colors.get(0);
            entry.colors.set(0, cur + 1);
        }).bounds(fieldX + 40, rowY(5), fieldH, fieldH).build());

        // 6. グラデーション（専用行にして横はみ出しを防止）
        int gradientBtnW = Math.min(130, fieldW);
        gradientToggleButton = Button.builder(Component.translatable("gui.realhanabimod.gradient.toggle"), b -> {
            if (entry.colors.size() > 1) {
                entry.colors.remove(entry.colors.size() - 1);
            } else {
                entry.colors.add(entry.colors.get(0) + 1);
            }
            updateGradientButtonsVisibility();
        }).bounds(fieldX, rowY(6), gradientBtnW, fieldH).build();
        addRenderableWidget(gradientToggleButton);

        // グラデーションをオンにした時だけ表示される、右側の2つの矢印ボタン + その間のスウォッチ
        // (パネルが狭い場合でも右端をはみ出さないよう、パネルの右端を上限にクランプする)
        int panelRightEdge = panelX + panelW - 24;
        int secondaryLeftX = Math.min(fieldX + 138, panelRightEdge - 56);
        int swatchX = secondaryLeftX + 20;
        int secondaryRightX = swatchX + 20;
        this.secondarySwatchX = swatchX;

        secondaryColorLeftButton = Button.builder(Component.literal("←"), b -> {
            if (entry.colors.size() > 1) {
                int cur = entry.colors.get(1);
                entry.colors.set(1, cur - 1);
            }
        }).bounds(secondaryLeftX, rowY(6), fieldH, fieldH).build();
        addRenderableWidget(secondaryColorLeftButton);

        secondaryColorRightButton = Button.builder(Component.literal("→"), b -> {
            if (entry.colors.size() > 1) {
                int cur = entry.colors.get(1);
                entry.colors.set(1, cur + 1);
            }
        }).bounds(secondaryRightX, rowY(6), fieldH, fieldH).build();
        addRenderableWidget(secondaryColorRightButton);
        // ↑ 2つ目のスウォッチは render() 側で secondarySwatchX を使って描画する。

        updateGradientButtonsVisibility();

        // 7. 不発（玉だけ打ち上げて、頂点で爆発させない設定）
        misfireToggleButton = Button.builder(misfireLabel(), b -> {
            entry.misfire = !entry.misfire;
            misfireToggleButton.setMessage(misfireLabel());
        }).bounds(fieldX, rowY(7), Math.min(150, fieldW), fieldH).build();
        addRenderableWidget(misfireToggleButton);

        // 8. カーブ（玉が上昇しながら「発射地点(offsetX/Z)からcurveOffsetX/Zぶんだけ離れた位置」へ
        //    ease-in-outで曲がっていき、その曲がった先の頂点で爆発する。
        //    不発と組み合わせれば「カーブした先で不発」も可能）
        curveToggleButton = Button.builder(curveLabel(), b -> {
            entry.curveEnabled = !entry.curveEnabled;
            curveToggleButton.setMessage(curveLabel());
            updateCurveFieldsVisibility();
        }).bounds(fieldX, rowY(8), Math.min(150, fieldW), fieldH).build();
        addRenderableWidget(curveToggleButton);

        // 9. カーブ先 X / Z（カーブが有効な時だけ操作可能）
        int curveHalfW = (fieldW - 8) / 2;
        curveXBox = new EditBox(font, fieldX, rowY(9), curveHalfW, fieldH, Component.literal("curveX"));
        curveXBox.setValue(trim(entry.curveOffsetX));
        curveXBox.setResponder(s -> setFloatSafe(v -> entry.curveOffsetX = v, s));
        addRenderableWidget(curveXBox);

        curveZBox = new EditBox(font, fieldX + curveHalfW + 8, rowY(9), curveHalfW, fieldH, Component.literal("curveZ"));
        curveZBox.setValue(trim(entry.curveOffsetZ));
        curveZBox.setResponder(s -> setFloatSafe(v -> entry.curveOffsetZ = v, s));
        addRenderableWidget(curveZBox);

        updateCurveFieldsVisibility();

        // 10. 玉の見え方（玉を非表示 / 尾のみ表示）。横に並べて1行にまとめている
        int visHalfW = (fieldW - 8) / 2;
        ballHiddenToggleButton = Button.builder(ballHiddenLabel(), b -> {
            entry.ballHidden = !entry.ballHidden;
            ballHiddenToggleButton.setMessage(ballHiddenLabel());
            updateTailOnlyButtonState();
        }).bounds(fieldX, rowY(10), visHalfW, fieldH).build();
        addRenderableWidget(ballHiddenToggleButton);

        // 玉非表示がONの時は無効化（どのみち何も見えないため）
        tailOnlyToggleButton = Button.builder(tailOnlyLabel(), b -> {
            entry.tailOnly = !entry.tailOnly;
            tailOnlyToggleButton.setMessage(tailOnlyLabel());
        }).bounds(fieldX + visHalfW + 8, rowY(10), visHalfW, fieldH).build();
        addRenderableWidget(tailOnlyToggleButton);
        updateTailOnlyButtonState();

        // 11. 玉が消えてから、さらにどれだけ高く上昇してから爆発するか（0なら消えた高さ=爆発する高さ）
        extraExplodeHeightBox = new EditBox(font, fieldX, rowY(11), fieldW, fieldH, Component.literal("extraExplodeHeight"));
        extraExplodeHeightBox.setValue(trim(entry.extraExplodeHeight));
        extraExplodeHeightBox.setResponder(s -> setFloatSafe(v -> entry.extraExplodeHeight = Math.max(0f, v), s));
        addRenderableWidget(extraExplodeHeightBox);

        // 保存 / 保存せずに戻る（画面全体ではなく、実際のパネルの下端を基準に配置する）
        int bottomBtnY = panelY + panelH - 34;
        addRenderableWidget(Button.builder(Component.translatable("gui.realhanabimod.save"), b -> onSave())
                .bounds(panelX + panelW - 170, bottomBtnY, 75, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.realhanabimod.cancel"), b -> onCancel())
                .bounds(panelX + panelW - 90, bottomBtnY, 70, 20).build());
    }

    private int rowY(int index) {
        return optY + rowH * index;
    }

    private Component misfireLabel() {
        return Component.translatable(entry.misfire
                ? "gui.realhanabimod.misfire.on"
                : "gui.realhanabimod.misfire.off");
    }

    private Component curveLabel() {
        return Component.translatable(entry.curveEnabled
                ? "gui.realhanabimod.curve.on"
                : "gui.realhanabimod.curve.off");
    }

    private Component ballHiddenLabel() {
        return Component.translatable(entry.ballHidden
                ? "gui.realhanabimod.ballhidden.on"
                : "gui.realhanabimod.ballhidden.off");
    }

    private Component tailOnlyLabel() {
        return Component.translatable(entry.tailOnly
                ? "gui.realhanabimod.tailonly.on"
                : "gui.realhanabimod.tailonly.off");
    }

    private void updateCurveFieldsVisibility() {
        boolean on = entry.curveEnabled;
        curveXBox.visible = on;
        curveXBox.active = on;
        curveZBox.visible = on;
        curveZBox.active = on;
    }

    /** 玉非表示(ballHidden)がONの間は「尾のみ」設定は意味を持たない（どのみち何も見えない）ため、操作不可にする。 */
    private void updateTailOnlyButtonState() {
        boolean relevant = !entry.ballHidden;
        tailOnlyToggleButton.active = relevant;
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
                    fieldX + fieldW / 2, rowY(0) + labelYOffset(), 0xFFFFFF);

            // ラベルは各入力ボックスの左、縦方向は中央揃え
            drawLabel(gfx, "大きさ", rowY(1));
            drawLabel(gfx, "高さ", rowY(2));
            drawLabel(gfx, "爆発まで(秒)", rowY(3));
            drawLabel(gfx, "X / Z", rowY(4));
            drawLabel(gfx, "不発", rowY(7));
            drawLabel(gfx, "カーブ移動量 X/Z", rowY(9));
            drawLabel(gfx, "玉の見え方", rowY(10));
            drawLabel(gfx, "消灯後+高さ", rowY(11));

            // メインの色スウォッチ（矢印のすぐ間、狭い間隔）＋色名
            int colorIdx = entry.colors.get(0);
            int rgb = ColorPresets.get(colorIdx);
            gfx.fill(fieldX + 20, rowY(5), fieldX + 38, rowY(5) + fieldH, 0xFF000000 | rgb);
            gfx.drawString(font, ColorPresets.getName(colorIdx), fieldX + 62, rowY(5) + labelYOffset(), 0xFFFFFF);

            if (entry.colors.size() > 1) {
                int rgb2 = ColorPresets.get(entry.colors.get(1));
                gfx.fill(secondarySwatchX, rowY(6), secondarySwatchX + 18, rowY(6) + fieldH, 0xFF000000 | rgb2);
            }
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private void drawLabel(GuiGraphics gfx, String text, int rowTopY) {
        // 入力ボックス(高さfieldH、行数が多い時は縮む)と縦方向の中心を合わせて描画する
        gfx.drawString(font, text, optX, rowTopY + labelYOffset(), 0xAAAAAA);
    }

    /** 現在のfieldH(ウィジェットの縦幅)に対して、フォント(高さ約9px)を縦方向中央に揃えるためのオフセット。 */
    private int labelYOffset() {
        return Math.max(1, (fieldH - 8) / 2);
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