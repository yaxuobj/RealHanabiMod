package com.realhanabimod.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * タイムラインに並ぶ要素。花火（FireworkEntry）か、待機時間（DelayEntry）のどちらか。
 */
public abstract class TimelineItem {

    public static final int TYPE_FIREWORK = 0;
    public static final int TYPE_DELAY = 1;

    /** タイムライン上での一意ID（GUIでの選択・編集対象特定に使用） */
    public long uid;

    public abstract int type();

    public abstract CompoundTag writeNbt();

    public abstract void writeBuf(FriendlyByteBuf buf);

    public static TimelineItem readNbt(CompoundTag tag) {
        int type = tag.getInt("Type");
        TimelineItem item = type == TYPE_FIREWORK ? new FireworkEntry() : new DelayEntry();
        item.readNbtInternal(tag);
        return item;
    }

    public static TimelineItem readBuf(FriendlyByteBuf buf) {
        int type = buf.readVarInt();
        TimelineItem item = type == TYPE_FIREWORK ? new FireworkEntry() : new DelayEntry();
        item.uid = buf.readLong();
        item.readBufInternal(buf);
        return item;
    }

    protected abstract void readNbtInternal(CompoundTag tag);

    protected abstract void readBufInternal(FriendlyByteBuf buf);
}
