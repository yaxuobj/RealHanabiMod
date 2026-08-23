package com.realhanabimod.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 花火1発分の設定。
 * designIndex : 形（打ち上げ方）の種類。FireworkShapeManager のインデックスに対応。
 * size        : 大きさ（爆発の広がり半径倍率）
 * height      : 打ち上がる高さ（ブロックからの相対Y）
 * offsetX/Z   : ブロックからのXZ位置ズレ
 * colors      : 色。1つなら単色、2つ以上ならグラデーション。
 * misfire     : 不発フラグ。trueの場合、玉は打ち上がるが頂点で爆発せず、火花も音も出さずにそのまま消える。
 */
public class FireworkEntry extends TimelineItem {

    public int designIndex = 0;
    public float size = 1.0f;
    public float height = 20.0f;
    public float explodeTime = 1.4f; // 打ち上げてから爆発するまでの秒数。高さとの比率で玉の上昇速度が決まる。
    public float offsetX = 0.0f;
    public float offsetZ = 0.0f;
    public List<Integer> colors = new ArrayList<>(List.of(0)); // ColorPresetsのインデックス列
    public boolean misfire = false; // 不発（玉だけ打ち上がり、爆発しない）

    @Override
    public int type() {
        return TYPE_FIREWORK;
    }

    @Override
    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Type", TYPE_FIREWORK);
        tag.putLong("Uid", uid);
        tag.putInt("Design", designIndex);
        tag.putFloat("Size", size);
        tag.putFloat("Height", height);
        tag.putFloat("ExplodeTime", explodeTime);
        tag.putFloat("OffX", offsetX);
        tag.putFloat("OffZ", offsetZ);
        ListTag colorList = new ListTag();
        for (int c : colors) colorList.add(IntTag.valueOf(c));
        tag.put("Colors", colorList);
        tag.putBoolean("Misfire", misfire);
        return tag;
    }

    @Override
    protected void readNbtInternal(CompoundTag tag) {
        uid = tag.getLong("Uid");
        designIndex = tag.getInt("Design");
        size = tag.getFloat("Size");
        height = tag.getFloat("Height");
        explodeTime = tag.contains("ExplodeTime") ? Math.max(0.2f, tag.getFloat("ExplodeTime")) : 1.4f;
        offsetX = tag.getFloat("OffX");
        offsetZ = tag.getFloat("OffZ");
        colors.clear();
        ListTag colorList = tag.getList("Colors", 3); // 3 = IntTag
        for (int i = 0; i < colorList.size(); i++) {
            colors.add(((IntTag) colorList.get(i)).getAsInt());
        }
        if (colors.isEmpty()) colors.add(0);
        misfire = tag.getBoolean("Misfire");
    }

    @Override
    public void writeBuf(FriendlyByteBuf buf) {
        buf.writeVarInt(TYPE_FIREWORK);
        buf.writeLong(uid);
        buf.writeVarInt(designIndex);
        buf.writeFloat(size);
        buf.writeFloat(height);
        buf.writeFloat(explodeTime);
        buf.writeFloat(offsetX);
        buf.writeFloat(offsetZ);
        buf.writeVarInt(colors.size());
        for (int c : colors) buf.writeVarInt(c);
        buf.writeBoolean(misfire);
    }

    @Override
    protected void readBufInternal(FriendlyByteBuf buf) {
        designIndex = buf.readVarInt();
        size = buf.readFloat();
        height = buf.readFloat();
        explodeTime = Math.max(0.2f, buf.readFloat());
        offsetX = buf.readFloat();
        offsetZ = buf.readFloat();
        int n = buf.readVarInt();
        colors.clear();
        for (int i = 0; i < n; i++) colors.add(buf.readVarInt());
        misfire = buf.readBoolean();
    }

    public FireworkEntry copy() {
        FireworkEntry e = new FireworkEntry();
        e.uid = System.nanoTime();
        e.designIndex = designIndex;
        e.size = size;
        e.height = height;
        e.explodeTime = explodeTime;
        e.offsetX = offsetX;
        e.offsetZ = offsetZ;
        e.colors = new ArrayList<>(colors);
        e.misfire = misfire;
        return e;
    }
}