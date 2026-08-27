package com.realhanabimod.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 1つの花火打ち上げ機が持つタイムライン全体。
 * 花火(FireworkEntry)と待機(DelayEntry)が自由な順番で並ぶ。
 * addFirework() は今まで通り花火+待機をセットで追加するが、addDelay() で待機だけを
 * 単独のタイマーとしてどこにでも追加できる。
 */
public class HanabiShowData {

    public final List<TimelineItem> items = new ArrayList<>();

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public FireworkEntry addFirework() {
        if (!items.isEmpty()) {
            DelayEntry delay = new DelayEntry();
            delay.uid = System.nanoTime();
            delay.seconds = 1.0f;
            items.add(delay);
        }
        FireworkEntry entry = new FireworkEntry();
        entry.uid = System.nanoTime() + 1;
        items.add(entry);
        return entry;
    }

    /** 花火とは独立して、単独のタイマー(待機)をタイムライン末尾に追加する。 */
    public DelayEntry addDelay() {
        DelayEntry delay = new DelayEntry();
        delay.uid = System.nanoTime();
        delay.seconds = 1.0f;
        items.add(delay);
        return delay;
    }

    /** 指定した花火を複製し、その直後（待機を挟んで）に挿入する。 */
    public FireworkEntry duplicate(long fireworkUid) {
        int idx = indexOf(fireworkUid);
        if (idx < 0) return null;
        FireworkEntry original = (FireworkEntry) items.get(idx);
        FireworkEntry copy = original.copy();

        DelayEntry delay = new DelayEntry();
        delay.uid = System.nanoTime();
        delay.seconds = 1.0f;

        items.add(idx + 1, delay);
        items.add(idx + 2, copy);
        return copy;
    }

    /**
     * 花火を削除する。花火に付随していた待機(前後どちらか片方)も一緒に削除して整合を保つ。
     * タイマー単体の削除には使わないこと（removeTimer を使う）。
     */
    public void remove(long uid) {
        int idx = indexOf(uid);
        if (idx < 0) return;
        items.remove(idx);
        // 前後どちらかの待機要素も片方消して整合を保つ
        if (idx < items.size() && items.get(idx) instanceof DelayEntry) {
            items.remove(idx);
        } else if (idx - 1 >= 0 && items.get(idx - 1) instanceof DelayEntry) {
            items.remove(idx - 1);
        }
    }

    /**
     * 単独のタイマー(待機)だけをそのまま削除する。remove() と違い、
     * 隣接する要素を巻き添えで消す後始末は行わない。
     */
    public void removeTimer(long uid) {
        int idx = indexOf(uid);
        if (idx < 0) return;
        items.remove(idx);
    }

    public int indexOf(long uid) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uid == uid) return i;
        }
        return -1;
    }

    public TimelineItem get(long uid) {
        int idx = indexOf(uid);
        return idx < 0 ? null : items.get(idx);
    }

    public HanabiShowData copyAll() {
        HanabiShowData data = new HanabiShowData();
        for (TimelineItem item : items) {
            data.items.add(TimelineItem.readNbt(item.writeNbt()));
        }
        return data;
    }

    /** 各花火が再生開始されるまでの経過秒数（0開始）とセットにして返す。 */
    public List<Scheduled> buildSchedule() {
        List<Scheduled> list = new ArrayList<>();
        float t = 0f;
        for (TimelineItem item : items) {
            if (item instanceof DelayEntry delay) {
                t += delay.seconds;
            } else if (item instanceof FireworkEntry fw) {
                list.add(new Scheduled(t, fw));
            }
        }
        return list;
    }

    public record Scheduled(float timeSeconds, FireworkEntry firework) {}

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (TimelineItem item : items) list.add(item.writeNbt());
        tag.put("Items", list);
        return tag;
    }

    public void readNbt(CompoundTag tag) {
        items.clear();
        ListTag list = tag.getList("Items", 10); // 10 = CompoundTag
        for (int i = 0; i < list.size(); i++) {
            items.add(TimelineItem.readNbt(list.getCompound(i)));
        }
    }

    public void writeBuf(FriendlyByteBuf buf) {
        buf.writeVarInt(items.size());
        for (TimelineItem item : items) item.writeBuf(buf);
    }

    public static HanabiShowData readBuf(FriendlyByteBuf buf) {
        HanabiShowData data = new HanabiShowData();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) data.items.add(TimelineItem.readBuf(buf));
        return data;
    }
}