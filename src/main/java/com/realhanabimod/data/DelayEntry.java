package com.realhanabimod.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
待機時間
 */
public class DelayEntry extends TimelineItem {

    public static final float MIN_SECONDS = 0.1f;

    public float seconds = 1.0f;

    @Override
    public int type() {
        return TYPE_DELAY;
    }

    public void setSeconds(float value) {
        this.seconds = Math.max(MIN_SECONDS, value);
    }

    @Override
    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Type", TYPE_DELAY);
        tag.putLong("Uid", uid);
        tag.putFloat("Seconds", seconds);
        return tag;
    }

    @Override
    protected void readNbtInternal(CompoundTag tag) {
        uid = tag.getLong("Uid");
        seconds = Math.max(MIN_SECONDS, tag.getFloat("Seconds"));
    }

    @Override
    public void writeBuf(FriendlyByteBuf buf) {
        buf.writeVarInt(TYPE_DELAY);
        buf.writeLong(uid);
        buf.writeFloat(seconds);
    }

    @Override
    protected void readBufInternal(FriendlyByteBuf buf) {
        seconds = Math.max(MIN_SECONDS, buf.readFloat());
    }
}
