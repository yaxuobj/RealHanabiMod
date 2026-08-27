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
import java.util.Collections;
import java.util.List;

public class HanabiListScreen extends Screen {

    private final BlockPos pos;
    public HanabiShowData data;

    private ScrollListWidget scrollList;
    private Long selectedUid = null; // 選択中のアイテム(花火 or タイマー)のuid
    private Long editingDelayUid = null;
    private EditBox delayEditBox;

    private Button addButton;
    private Button addTimerButton;
    private Button startButton;
    private Button duplicateButton;
    private Button deleteButton;
    private Button moveUpButton;
    private Button moveDownButton;

    private static final int PANEL_MARGIN = 24;
    // 花火の行の高さ。1画面に大量の項目を表示できるよう、以前(22px)より詰めている。
    private static final int ROW_HEIGHT = 15;

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

        int startX = panelX + 10;
        int rightEnd = width - panelX - 10;

        // --- 下段 (Y = height - 44): 追加や開始などの全体操作 ---

        startButton = Button.builder(Component.translatable("gui.realhanabimod.start"), b -> onStart())
                .bounds(rightEnd - 72, height - 44, 72, 20).build();
        startButton.setFGColor(0x55FF55);

        addButton = Button.builder(Component.translatable("gui.realhanabimod.add"), b -> onAdd())
                .bounds(rightEnd - 156, height - 44, 80, 20).build(); // 72 + 4 + 80 = 156

        // 花火とセットではなく、単独のタイマー(待機)だけをタイムラインに追加する
        addTimerButton = Button.builder(Component.translatable("gui.realhanabimod.add_timer"), b -> onAddTimer())
                .bounds(rightEnd - 240, height - 44, 80, 20).build(); // 156 + 4 + 80 = 240

        // --- 上段 (Y = height - 68): 選択アイテムの編集操作 ---

        // 選択中のアイテム(花火 or タイマー)を1つ上/下へ移動する（タイムライン全体の並びの中で入れ替える）
        moveUpButton = Button.builder(Component.literal("↑"), b -> onMoveUp())
                .bounds(startX, height - 68, 30, 20).build();
        moveDownButton = Button.builder(Component.literal("↓"), b -> onMoveDown())
                .bounds(startX + 34, height - 68, 30, 20).build();

        // 複製は花火専用（タイマー単体の複製は意味が薄いので選択中が花火の時だけ表示する）
        duplicateButton = Button.builder(Component.translatable("gui.realhanabimod.duplicate"), b -> onDuplicate())
                .bounds(startX + 68, height - 68, 70, 20).build();

        deleteButton = Button.builder(Component.translatable("gui.realhanabimod.delete"), b -> onDelete())
                .bounds(startX + 142, height - 68, 70, 20).build();

        updateButtonVisibility();

        this.addRenderableWidget(addButton);
        this.addRenderableWidget(addTimerButton);
        this.addRenderableWidget(startButton);
        this.addRenderableWidget(moveUpButton);
        this.addRenderableWidget(moveDownButton);
        this.addRenderableWidget(duplicateButton);
        this.addRenderableWidget(deleteButton);
    }

    private void updateButtonVisibility() {
        boolean empty = data.isEmpty();
        TimelineItem selectedItem = selectedUid == null ? null : data.get(selectedUid);
        boolean hasSelection = selectedItem != null;
        boolean selectedIsFirework = selectedItem instanceof FireworkEntry;

        addButton.visible = true;
        addButton.active = true;
        // 何もない状態でまずタイマーだけ置く運用は想定していないので、花火が1つ以上ある時だけ出す
        addTimerButton.visible = !empty;
        addTimerButton.active = !empty;
        startButton.visible = !empty;
        startButton.active = !empty;
        duplicateButton.visible = selectedIsFirework;
        duplicateButton.active = selectedIsFirework;
        deleteButton.visible = hasSelection;
        deleteButton.active = hasSelection;

        moveUpButton.visible = hasSelection;
        moveDownButton.visible = hasSelection;
        if (hasSelection) {
            int idx = data.indexOf(selectedUid);
            // 一番上/一番下にある時はそれ以上動かせないので押せなくする
            moveUpButton.active = idx > 0;
            moveDownButton.active = idx >= 0 && idx < data.items.size() - 1;
        } else {
            moveUpButton.active = false;
            moveDownButton.active = false;
        }

        if (empty) {
            // 何もない状態: 画面中央に大きな追加ボタンのみ
            int w = 140, h = 24;
            addButton.setX(width / 2 - w / 2);
            addButton.setY(height / 2 - h / 2);
            addButton.setWidth(w);
        } else {
            // 下段の中央右寄り（通常時の配置へ戻す）
            int rightEnd = width - PANEL_MARGIN - 10;
            addButton.setX(rightEnd - 156);
            addButton.setY(height - 44);
            addButton.setWidth(80);
        }
    }

    private void rebuildRows() {
        List<ScrollListWidget.Row> rows = new ArrayList<>();
        for (int i = 0; i < data.items.size(); i++) {
            TimelineItem item = data.items.get(i);
            if (item instanceof FireworkEntry fw) {
                // 並び替え後も一目で位置がわかるよう、タイムライン全体での順番(1始まり)を渡す
                rows.add(new FireworkRow(fw, i + 1));
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

    private void onAddTimer() {
        // 花火とは独立して、単独のタイマーをタイムライン末尾に追加する。
        // 追加直後は選択状態にしておき、そのまま「上へ」で好きな位置へ動かしやすくする。
        DelayEntry delay = data.addDelay();
        selectedUid = delay.uid;
        syncAndRebuild();
    }

    private void onStart() {
        NetworkHandler.CHANNEL.sendToServer(new StartShowPacket(pos, data));
    }

    private void onDuplicate() {
        if (selectedUid == null) return;
        TimelineItem item = data.get(selectedUid);
        if (!(item instanceof FireworkEntry)) return; // タイマーの複製は不可
        data.duplicate(selectedUid);
        selectedUid = null;
        syncAndRebuild();
    }

    private void onDelete() {
        if (selectedUid == null) return;
        TimelineItem item = data.get(selectedUid);
        if (item instanceof DelayEntry) {
            // タイマー単体の削除。花火用の「隣の待機も一緒に消す」後始末は不要なので専用メソッドを使う
            data.removeTimer(selectedUid);
        } else {
            data.remove(selectedUid);
        }
        selectedUid = null;
        syncAndRebuild();
    }

    private void onMoveUp() {
        if (selectedUid == null) return;
        int idx = data.indexOf(selectedUid);
        if (idx <= 0) return;
        Collections.swap(data.items, idx, idx - 1);
        syncAndRebuild();
    }

    private void onMoveDown() {
        if (selectedUid == null) return;
        int idx = data.indexOf(selectedUid);
        if (idx < 0 || idx >= data.items.size() - 1) return;
        Collections.swap(data.items, idx, idx + 1);
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
        private final int orderNumber; // タイムライン全体での表示順(1始まり)。並び替え時に位置で迷わないようにするための番号。

        FireworkRow(FireworkEntry entry, int orderNumber) {
            this.entry = entry;
            this.orderNumber = orderNumber;
        }

        @Override
        public int height() {
            return ROW_HEIGHT;
        }

        @Override
        public void render(GuiGraphics gfx, int x, int y, int width, int mouseX, int mouseY) {
            boolean selected = selectedUid != null && selectedUid == entry.uid;
            int bg = selected ? 0x5555AAFF : (mouseY >= y && mouseY < y + height() && mouseX >= x && mouseX < x + width ? 0x40FFFFFF : 0x30FFFFFF);
            gfx.fill(x, y, x + width, y + height() - 1, bg);

            int textY = y + Math.max(1, (height() - 8) / 2);

            // 左端に順番の数字（並び替え後も現在の位置がひと目でわかるように）
            String numberLabel = orderNumber + ".";
            gfx.drawString(HanabiListScreen.this.font, numberLabel, x + 4, textY, 0xFFD37F);
            int numberW = HanabiListScreen.this.font.width(numberLabel);

            String colorName = ColorPresets.getName(entry.colors.get(0));
            String design = FireworkShapeManager.designName(entry.designIndex);
            String label = design + " / " + colorName + " / 高さ" + (int) entry.height
                    + (entry.misfire ? " / 不発" : "")
                    + (entry.curveEnabled ? " / カーブ" : "")
                    + (entry.ballHidden ? " / 玉非表示" : "")
                    + (!entry.ballHidden && entry.tailOnly ? " / 尾のみ" : "")
                    + (entry.extraExplodeHeight > 0.001f ? " / 消灯後+" + (int) entry.extraExplodeHeight : "");
            gfx.drawString(HanabiListScreen.this.font, label, x + numberW + 10, textY, 0xFFFFFF);

            // 緑色の編集ボタン（行を詰めた分、ボタンも小さくする）
            int btnW = 40, btnH = Math.min(12, Math.max(9, height() - 2));
            int btnX = x + width - btnW - 4;
            int btnY = y + Math.max(1, (height() - btnH) / 2);
            boolean hoverBtn = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
            gfx.fill(btnX, btnY, btnX + btnW, btnY + btnH, hoverBtn ? 0xFF3FBF3F : 0xFF2E8B2E);
            gfx.drawCenteredString(HanabiListScreen.this.font, "編集", btnX + btnW / 2, btnY + Math.max(0, (btnH - 8) / 2), 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, double mouseX, double mouseY, int button) {
            int btnW = 40, btnH = Math.min(12, Math.max(9, height() - 2));
            int btnX = x + width - btnW - 4;
            int btnY = y + Math.max(1, (height() - btnH) / 2);
            if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
                openEditFor(entry.uid);
                return true;
            }
            // 行本体クリック -> 選択トグル（複製・削除・上へ/下へボタン表示）
            if (editingDelayUid != null) closeDelayEditorAndSync();
            selectedUid = (selectedUid != null && selectedUid == entry.uid) ? null : entry.uid;
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
            return ROW_HEIGHT;
        }

        @Override
        public void render(GuiGraphics gfx, int x, int y, int width, int mouseX, int mouseY) {
            boolean selected = selectedUid != null && selectedUid == delay.uid;
            int bg = selected ? 0x5555AAFF : (mouseY >= y && mouseY < y + height() && mouseX >= x && mouseX < x + width ? 0x40FFFFFF : 0x2AFFFFFF);
            gfx.fill(x, y, x + width, y + height() - 1, bg);

            int textY = y + Math.max(1, (height() - 8) / 2);
            String label = "⏱ " + trimSeconds(delay.seconds) + "秒";
            gfx.drawString(HanabiListScreen.this.font, label, x + 8, textY, 0xCCCCCC);

            // 花火行と同じ配置の編集ボタン。ここを押した時だけ秒数の編集欄を開く（行クリックは選択のみ）
            int btnW = 40, btnH = Math.min(12, Math.max(9, height() - 2));
            int btnX = x + width - btnW - 4;
            int btnY = y + Math.max(1, (height() - btnH) / 2);
            boolean hoverBtn = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
            gfx.fill(btnX, btnY, btnX + btnW, btnY + btnH, hoverBtn ? 0xFF3FBF3F : 0xFF2E8B2E);
            gfx.drawCenteredString(HanabiListScreen.this.font, "編集", btnX + btnW / 2, btnY + Math.max(0, (btnH - 8) / 2), 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, double mouseX, double mouseY, int button) {
            int btnW = 40, btnH = Math.min(12, Math.max(9, height() - 2));
            int btnX = x + width - btnW - 4;
            int btnY = y + Math.max(1, (height() - btnH) / 2);
            if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
                openDelayEditor(delay, x, y, width);
                return true;
            }
            // 行本体クリック -> 選択トグル（削除・上へ/下へボタン表示。花火と違い複製は出さない）
            if (editingDelayUid != null) closeDelayEditorAndSync();
            selectedUid = (selectedUid != null && selectedUid == delay.uid) ? null : delay.uid;
            updateButtonVisibility();
            return true;
        }

        private String trimSeconds(float v) {
            if (v == Math.floor(v)) return String.valueOf((int) v);
            return String.valueOf(v);
        }
    }
}