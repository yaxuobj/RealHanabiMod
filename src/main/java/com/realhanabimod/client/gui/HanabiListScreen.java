package com.realhanabimod.client.gui;

import com.realhanabimod.client.gui.widget.ScrollListWidget;
import com.realhanabimod.data.ColorPresets;
import com.realhanabimod.data.DelayEntry;
import com.realhanabimod.data.FireworkEntry;
import com.realhanabimod.data.HanabiShowData;
import com.realhanabimod.data.TimelineItem;
import com.realhanabimod.client.render.FireworkShapeManager;
import com.realhanabimod.network.NetworkHandler;
import com.realhanabimod.network.packet.SaveDataPacket;
import com.realhanabimod.network.packet.StartShowPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class HanabiListScreen extends Screen {

    private final BlockPos pos;
    public HanabiShowData data;

    private ScrollListWidget scrollList;
    private Long selectedFireworkUid = null;
    private Long editingDelayUid = null;
    private EditBox delayEditBox;

    private Button addButton;
    private Button startButton;
    private Button duplicateButton;
    private Button deleteButton;

    private static final int PANEL_MARGIN = 24;
    private static final int ROW_HEIGHT = 22;

    public HanabiListScreen(BlockPos pos, HanabiShowData data) {
        super(Component.translatable("gui.realhanabimod.list.title"));
        this.pos = pos;
        this.data = data;
    }

    @Override
    protected void init() {
        int panelX = PANEL_MARGIN;
        int panelY = PANEL_MARGIN;
        int panelW = width - PANEL_MARGIN * 2;
        int panelH = height - PANEL_MARGIN * 2;

        scrollList = new ScrollListWidget(panelX + 10, panelY + 10, panelW - 20, panelH - 60);
        rebuildRows();

        addButton = Button.builder(Component.translatable("gui.realhanabimod.add"), b -> onAdd())
                .bounds(width - 200, height - 34, 90, 20).build();

        startButton = Button.builder(Component.translatable("gui.realhanabimod.start"), b -> onStart())
                .bounds(width - 100, height - 34, 80, 20).build();
        startButton.setFGColor(0x55FF55);

        duplicateButton = Button.builder(Component.translatable("gui.realhanabimod.duplicate"), b -> onDuplicate())
                .bounds(20, height - 34, 90, 20).build();

        deleteButton = Button.builder(Component.translatable("gui.realhanabimod.delete"), b -> onDelete())
                .bounds(116, height - 34, 90, 20).build();

        updateButtonVisibility();

        this.addRenderableWidget(addButton);
        this.addRenderableWidget(startButton);
        this.addRenderableWidget(duplicateButton);
        this.addRenderableWidget(deleteButton);
    }

    private void updateButtonVisibility() {
        boolean empty = data.isEmpty();
        addButton.visible = true;
        addButton.active = true;
        startButton.visible = !empty;
        startButton.active = !empty;
        duplicateButton.visible = selectedFireworkUid != null;
        duplicateButton.active = selectedFireworkUid != null;
        deleteButton.visible = selectedFireworkUid != null;
        deleteButton.active = selectedFireworkUid != null;

        if (empty) {
            // 何もない状態: 画面中央に大きな追加ボタンのみ
            int w = 140, h = 24;
            addButton.setX(width / 2 - w / 2);
            addButton.setY(height / 2 - h / 2);
            addButton.setWidth(w);
        } else {
            // 開始ボタンの隣に追加ボタン（右下）
            addButton.setX(width - 200);
            addButton.setY(height - 34);
            addButton.setWidth(90);
        }
    }

    private void rebuildRows() {
        List<ScrollListWidget.Row> rows = new ArrayList<>();
        for (TimelineItem item : data.items) {
            if (item instanceof FireworkEntry fw) {
                rows.add(new FireworkRow(fw));
            } else if (item instanceof DelayEntry delay) {
                rows.add(new DelayRow(delay));
            }
        }
        scrollList.setRows(rows);
    }

    /** データを書き換えた直後に呼ぶ。サーバーへ同期し、リストを再構築する（スクロール位置は保持される）。 */
    public void syncAndRebuild() {
        NetworkHandler.CHANNEL.sendToServer(new SaveDataPacket(pos, data));
        rebuildRows();
        if (addButton != null) updateButtonVisibility();
    }

    private void onAdd() {
        HanabiShowData snapshot = data.copyAll();
        FireworkEntry entry = data.addFirework();
        this.minecraft.setScreen(new HanabiEditScreen(this, pos, snapshot, entry.uid));
    }

    private void onStart() {
        NetworkHandler.CHANNEL.sendToServer(new StartShowPacket(pos, data));
    }

    private void onDuplicate() {
        if (selectedFireworkUid == null) return;
        data.duplicate(selectedFireworkUid);
        selectedFireworkUid = null;
        syncAndRebuild();
    }

    private void onDelete() {
        if (selectedFireworkUid == null) return;
        data.remove(selectedFireworkUid);
        selectedFireworkUid = null;
        syncAndRebuild();
    }

    private void openEditFor(long uid) {
        HanabiShowData snapshot = data.copyAll();
        this.minecraft.setScreen(new HanabiEditScreen(this, pos, snapshot, uid));
    }

    private void openDelayEditor(DelayEntry delay, int rowX, int rowY, int rowWidth) {
        editingDelayUid = delay.uid;
        if (delayEditBox != null) this.removeWidget(delayEditBox);
        delayEditBox = new EditBox(this.font, width - 130, 20, 100, 18, Component.literal("seconds"));
        delayEditBox.setValue(String.valueOf(delay.seconds));
        delayEditBox.setResponder(s -> {
            try {
                float v = Float.parseFloat(s);
                delay.setSeconds(v);
            } catch (NumberFormatException ignored) {
            }
        });
        this.addRenderableWidget(delayEditBox);
        this.setFocused(delayEditBox);
    }

    private void closeDelayEditorAndSync() {
        if (delayEditBox != null) {
            this.removeWidget(delayEditBox);
            delayEditBox = null;
        }
        editingDelayUid = null;
        syncAndRebuild();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingDelayUid != null && keyCode == 257) { // Enter
            closeDelayEditorAndSync();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (editingDelayUid != null && delayEditBox != null) {
            boolean insideBox = delayEditBox.isMouseOver(mouseX, mouseY);
            if (!insideBox) {
                closeDelayEditorAndSync();
            }
        }
        boolean consumedByList = scrollList.mouseClicked(mouseX, mouseY, button);
        if (consumedByList) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (scrollList.mouseScrolled(mouseX, mouseY, delta)) return true;
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 黒い半透明の背景
        gfx.fill(0, 0, width, height, 0x99000000);

        int panelX = PANEL_MARGIN, panelY = PANEL_MARGIN;
        int panelW = width - PANEL_MARGIN * 2, panelH = height - PANEL_MARGIN * 2;
        gfx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xB0000000);
        drawBorder(gfx, panelX, panelY, panelW, panelH, 0xFFFFFFFF);

        scrollList.render(gfx, mouseX, mouseY);

        gfx.drawCenteredString(this.font, this.title, width / 2, panelY - 16, 0xFFFFFF);

        super.render(gfx, mouseX, mouseY, partialTick);
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

    // ================= 行の描画クラス =================

    private class FireworkRow implements ScrollListWidget.Row {
        private final FireworkEntry entry;

        FireworkRow(FireworkEntry entry) {
            this.entry = entry;
        }

        @Override
        public int height() {
            return ROW_HEIGHT;
        }

        @Override
        public void render(GuiGraphics gfx, int x, int y, int width, int mouseX, int mouseY) {
            boolean selected = entry.uid == (selectedFireworkUid == null ? -1 : selectedFireworkUid);
            int bg = selected ? 0x5555AAFF : (mouseY >= y && mouseY < y + height() && mouseX >= x && mouseX < x + width ? 0x40FFFFFF : 0x30FFFFFF);
            gfx.fill(x, y, x + width, y + height() - 2, bg);

            String colorName = ColorPresets.getName(entry.colors.get(0));
            String design = FireworkShapeManager.designName(entry.designIndex);
            String label = "花火: " + design + " / " + colorName + " / 高さ" + (int) entry.height
                    + (entry.misfire ? " / 不発" : "")
                    + (entry.curveEnabled ? " / カーブ" : "");
            gfx.drawString(HanabiListScreen.this.font, label, x + 6, y + 6, 0xFFFFFF);

            // 緑色の編集ボタン
            int btnW = 50, btnH = 16;
            int btnX = x + width - btnW - 6;
            int btnY = y + 3;
            boolean hoverBtn = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
            gfx.fill(btnX, btnY, btnX + btnW, btnY + btnH, hoverBtn ? 0xFF3FBF3F : 0xFF2E8B2E);
            gfx.drawCenteredString(HanabiListScreen.this.font, "編集", btnX + btnW / 2, btnY + 4, 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, double mouseX, double mouseY, int button) {
            int btnW = 50, btnH = 16;
            int btnX = x + width - btnW - 6;
            int btnY = y + 3;
            if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
                openEditFor(entry.uid);
                return true;
            }
            // 行本体クリック -> 選択トグル（複製ボタン表示）
            if (editingDelayUid != null) closeDelayEditorAndSync();
            selectedFireworkUid = (selectedFireworkUid != null && selectedFireworkUid == entry.uid) ? null : entry.uid;
            updateButtonVisibility();
            return true;
        }
    }

    private class DelayRow implements ScrollListWidget.Row {
        private final DelayEntry delay;

        DelayRow(DelayEntry delay) {
            this.delay = delay;
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public void render(GuiGraphics gfx, int x, int y, int width, int mouseX, int mouseY) {
            String label = "⏱ " + trimSeconds(delay.seconds) + "秒";
            gfx.drawCenteredString(HanabiListScreen.this.font, label, x + width / 2, y + 4, 0xAAAAAA);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, double mouseX, double mouseY, int button) {
            if (selectedFireworkUid != null) {
                selectedFireworkUid = null;
                updateButtonVisibility();
            }
            openDelayEditor(delay, x, y, width);
            return true;
        }

        private String trimSeconds(float v) {
            if (v == Math.floor(v)) return String.valueOf((int) v);
            return String.valueOf(v);
        }
    }
}