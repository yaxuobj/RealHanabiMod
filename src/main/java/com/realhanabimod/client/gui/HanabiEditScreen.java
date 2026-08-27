package com.realhanabimod.client.gui;

import com.realhanabimod.client.render.FireworkShapeManager;
import com.realhanabimod.data.ColorPresets;
import com.realhanabimod.data.FireworkEntry;
import com.realhanabimod.data.HanabiShowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

public class HanabiEditScreen extends Screen {

    private final HanabiListScreen parent;
    private final BlockPos pos;
    private final HanabiShowData snapshotBeforeEdit; // キャンセル時の復元用
    private final long entryUid;
    private FireworkEntry entry;

    private HanabiEditList list;

    private EditBox sizeBox, heightBox, explodeTimeBox, offsetXBox, offsetZBox;
    private EditBox curveXBox, curveZBox;
    private EditBox extraExplodeHeightBox;

    private Button gradientToggleButton;
    private Button secondaryColorLeftButton, secondaryColorRightButton;
    private Button misfireToggleButton;
    private Button curveToggleButton;
    private Button ballHiddenToggleButton;
    private Button tailOnlyToggleButton;

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

        int itemHeight = 26; // 行の高さ（ゆとりを持たせる）

        // ※注意: もしMinecraft 1.20.2以降を使用していてコンパイルエラーになる場合、
        // コンストラクタを (this.minecraft, this.width, this.height - 70, 30, itemHeight) のように修正してください。
        this.list = new HanabiEditList(this.minecraft, this.width, this.height, 30, this.height - 40, itemHeight);
        this.addRenderableWidget(this.list);

        // UIの中央を基準に配置位置を決定
        int fieldX = this.width / 2 - 20;
        int fieldW = 150; // ウィジェットの標準幅
        int fieldH = 20;  // ボタン・入力枠の高さを20pxに固定（潰れない）

        // 0. デザイン
        Button designLeft = Button.builder(Component.literal("←"), b -> entry.designIndex--)
                .bounds(fieldX, 0, 20, fieldH).build();
        Button designRight = Button.builder(Component.literal("→"), b -> entry.designIndex++)
                .bounds(fieldX + fieldW - 20, 0, 20, fieldH).build();
        this.list.add(new WidgetRow("デザイン", (gfx, top, left, w, h) -> {
            String name = FireworkShapeManager.designName(entry.designIndex);
            gfx.drawCenteredString(font, name, fieldX + fieldW / 2, top + (h - 9) / 2, 0xFFFFFF);
        }, designLeft, designRight));

        // 1. 大きさ
        sizeBox = new EditBox(font, fieldX, 0, fieldW, fieldH, Component.literal("size"));
        sizeBox.setValue(trim(entry.size));
        sizeBox.setResponder(s -> setFloatSafe(v -> entry.size = Math.max(0.1f, v), s));
        this.list.add(new WidgetRow("大きさ", null, sizeBox));

        // 2. 高さ
        heightBox = new EditBox(font, fieldX, 0, fieldW, fieldH, Component.literal("height"));
        heightBox.setValue(trim(entry.height));
        heightBox.setResponder(s -> setFloatSafe(v -> entry.height = Math.max(1f, v), s));
        this.list.add(new WidgetRow("高さ", null, heightBox));

        // 3. 爆発までの時間
        explodeTimeBox = new EditBox(font, fieldX, 0, fieldW, fieldH, Component.literal("explodeTime"));
        explodeTimeBox.setValue(trim(entry.explodeTime));
        explodeTimeBox.setResponder(s -> setFloatSafe(v -> entry.explodeTime = Math.max(0.2f, v), s));
        this.list.add(new WidgetRow("爆発まで(秒)", null, explodeTimeBox));

        // 4. X,Z オフセット
        int halfW = (fieldW - 8) / 2;
        offsetXBox = new EditBox(font, fieldX, 0, halfW, fieldH, Component.literal("x"));
        offsetXBox.setValue(trim(entry.offsetX));
        offsetXBox.setResponder(s -> setFloatSafe(v -> entry.offsetX = v, s));

        offsetZBox = new EditBox(font, fieldX + halfW + 8, 0, halfW, fieldH, Component.literal("z"));
        offsetZBox.setValue(trim(entry.offsetZ));
        offsetZBox.setResponder(s -> setFloatSafe(v -> entry.offsetZ = v, s));
        this.list.add(new WidgetRow("X / Z", null, offsetXBox, offsetZBox));

        // 5. 色
        Button colorLeft = Button.builder(Component.literal("←"), b -> {
            int cur = entry.colors.get(0);
            entry.colors.set(0, cur - 1);
        }).bounds(fieldX, 0, 20, fieldH).build();
        Button colorRight = Button.builder(Component.literal("→"), b -> {
            int cur = entry.colors.get(0);
            entry.colors.set(0, cur + 1);
        }).bounds(fieldX + 40, 0, 20, fieldH).build();

        this.list.add(new WidgetRow("色", (gfx, top, left, w, h) -> {
            int colorIdx = entry.colors.get(0);
            int rgb = ColorPresets.get(colorIdx);
            gfx.fill(fieldX + 22, top + (h - 18) / 2, fieldX + 38, top + (h - 18) / 2 + 18, 0xFF000000 | rgb);
            gfx.drawString(font, ColorPresets.getName(colorIdx), fieldX + 65, top + (h - 9) / 2, 0xFFFFFF);
        }, colorLeft, colorRight));

        // 6. グラデーション
        int gradientBtnW = 100;
        gradientToggleButton = Button.builder(Component.translatable("gui.realhanabimod.gradient.toggle"), b -> {
            if (entry.colors.size() > 1) {
                entry.colors.remove(entry.colors.size() - 1);
            } else {
                entry.colors.add(entry.colors.get(0) + 1);
            }
            updateGradientButtonsVisibility();
        }).bounds(fieldX, 0, gradientBtnW, fieldH).build();

        int secLeftX = fieldX + gradientBtnW + 5;
        int secRightX = secLeftX + 40;

        secondaryColorLeftButton = Button.builder(Component.literal("←"), b -> {
            if (entry.colors.size() > 1) {
                int cur = entry.colors.get(1);
                entry.colors.set(1, cur - 1);
            }
        }).bounds(secLeftX, 0, 20, fieldH).build();

        secondaryColorRightButton = Button.builder(Component.literal("→"), b -> {
            if (entry.colors.size() > 1) {
                int cur = entry.colors.get(1);
                entry.colors.set(1, cur + 1);
            }
        }).bounds(secRightX, 0, 20, fieldH).build();

        this.list.add(new WidgetRow("グラデーション", (gfx, top, left, w, h) -> {
            if (entry.colors.size() > 1) {
                int rgb2 = ColorPresets.get(entry.colors.get(1));
                gfx.fill(secLeftX + 22, top + (h - 18) / 2, secLeftX + 38, top + (h - 18) / 2 + 18, 0xFF000000 | rgb2);
            }
        }, gradientToggleButton, secondaryColorLeftButton, secondaryColorRightButton));

        updateGradientButtonsVisibility();

        // 7. 不発
        misfireToggleButton = Button.builder(misfireLabel(), b -> {
            entry.misfire = !entry.misfire;
            misfireToggleButton.setMessage(misfireLabel());
        }).bounds(fieldX, 0, fieldW, fieldH).build();
        this.list.add(new WidgetRow("不発", null, misfireToggleButton));

        // 8. カーブ
        curveToggleButton = Button.builder(curveLabel(), b -> {
            entry.curveEnabled = !entry.curveEnabled;
            curveToggleButton.setMessage(curveLabel());
            updateCurveFieldsVisibility();
        }).bounds(fieldX, 0, fieldW, fieldH).build();
        this.list.add(new WidgetRow("カーブ", null, curveToggleButton));

        // 9. カーブ先 X / Z
        curveXBox = new EditBox(font, fieldX, 0, halfW, fieldH, Component.literal("curveX"));
        curveXBox.setValue(trim(entry.curveOffsetX));
        curveXBox.setResponder(s -> setFloatSafe(v -> entry.curveOffsetX = v, s));

        curveZBox = new EditBox(font, fieldX + halfW + 8, 0, halfW, fieldH, Component.literal("curveZ"));
        curveZBox.setValue(trim(entry.curveOffsetZ));
        curveZBox.setResponder(s -> setFloatSafe(v -> entry.curveOffsetZ = v, s));
        this.list.add(new WidgetRow("カーブ移動量 X/Z", null, curveXBox, curveZBox));

        updateCurveFieldsVisibility();

        // 10. 玉の見え方
        ballHiddenToggleButton = Button.builder(ballHiddenLabel(), b -> {
            entry.ballHidden = !entry.ballHidden;
            ballHiddenToggleButton.setMessage(ballHiddenLabel());
            updateTailOnlyButtonState();
        }).bounds(fieldX, 0, halfW, fieldH).build();

        tailOnlyToggleButton = Button.builder(tailOnlyLabel(), b -> {
            entry.tailOnly = !entry.tailOnly;
            tailOnlyToggleButton.setMessage(tailOnlyLabel());
        }).bounds(fieldX + halfW + 8, 0, halfW, fieldH).build();
        this.list.add(new WidgetRow("玉の見え方", null, ballHiddenToggleButton, tailOnlyToggleButton));

        updateTailOnlyButtonState();

        // 11. 消灯後＋高さ
        extraExplodeHeightBox = new EditBox(font, fieldX, 0, fieldW, fieldH, Component.literal("extraExplodeHeight"));
        extraExplodeHeightBox.setValue(trim(entry.extraExplodeHeight));
        extraExplodeHeightBox.setResponder(s -> setFloatSafe(v -> entry.extraExplodeHeight = Math.max(0f, v), s));
        this.list.add(new WidgetRow("消灯後+高さ", null, extraExplodeHeightBox));


        // --- 保存 / キャンセルボタン（スクロールリストの外側に固定配置） ---
        int bottomBtnY = this.height - 30;
        addRenderableWidget(Button.builder(Component.translatable("gui.realhanabimod.save"), b -> onSave())
                .bounds(this.width / 2 - 80, bottomBtnY, 75, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.realhanabimod.cancel"), b -> onCancel())
                .bounds(this.width / 2 + 5, bottomBtnY, 75, 20).build());
    }

    private Component misfireLabel() {
        return Component.translatable(entry.misfire ? "gui.realhanabimod.misfire.on" : "gui.realhanabimod.misfire.off");
    }

    private Component curveLabel() {
        return Component.translatable(entry.curveEnabled ? "gui.realhanabimod.curve.on" : "gui.realhanabimod.curve.off");
    }

    private Component ballHiddenLabel() {
        return Component.translatable(entry.ballHidden ? "gui.realhanabimod.ballhidden.on" : "gui.realhanabimod.ballhidden.off");
    }

    private Component tailOnlyLabel() {
        return Component.translatable(entry.tailOnly ? "gui.realhanabimod.tailonly.on" : "gui.realhanabimod.tailonly.off");
    }

    private void updateCurveFieldsVisibility() {
        boolean on = entry.curveEnabled;
        curveXBox.visible = on;
        curveXBox.active = on;
        curveZBox.visible = on;
        curveZBox.active = on;
    }

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
        // 背景
        gfx.fill(0, 0, width, height, 0x99000000);
        // タイトル
        gfx.drawCenteredString(this.font, this.title, width / 2, 10, 0xFFFFFF);

        // リスト本体やボタンの描画
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =================================================================================
    // スクロールリストと行を管理する内部クラス
    // =================================================================================

    private class HanabiEditList extends ContainerObjectSelectionList<WidgetRow> {
        public HanabiEditList(net.minecraft.client.Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);

            // 土の背景テクスチャを無効化し、半透明のクールな見た目を維持する（バージョンによってはメソッド名が異なる場合があります）
            this.setRenderBackground(false);
            this.setRenderTopAndBottom(false);
        }

        public void add(WidgetRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return 320; // リストの最大幅
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + 160;
        }
    }

    private class WidgetRow extends ContainerObjectSelectionList.Entry<WidgetRow> {
        private final String label;
        private final CustomRender customRender;
        private final List<AbstractWidget> widgets;

        public WidgetRow(String label, CustomRender customRender, AbstractWidget... widgets) {
            this.label = label;
            this.customRender = customRender;
            this.widgets = List.of(widgets);
        }

        @Override
        public void render(GuiGraphics gfx, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            // 左側にラベルを描画
            if (label != null) {
                gfx.drawString(font, label, left + 20, top + (height - font.lineHeight) / 2, 0xAAAAAA);
            }

            // 色見本や文字などの特別描画
            if (customRender != null) {
                customRender.render(gfx, top, left, width, height);
            }

            // ウィジェットを描画（Y座標をスクロールに合わせて更新）
            for (AbstractWidget widget : widgets) {
                widget.setY(top + (height - widget.getHeight()) / 2);
                widget.render(gfx, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }

    private interface CustomRender {
        void render(GuiGraphics gfx, int top, int left, int width, int height);
    }
}