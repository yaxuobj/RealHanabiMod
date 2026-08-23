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
 * curveEnabled: カーブ機能の有効/無効。trueの場合、玉は打ち上がりながら「発射地点(offsetX/Z)から
 *               curveOffsetX/Z ぶんだけ離れた位置」へ曲がっていき、その曲がった先の頂点で爆発する。
 *               misfireと組み合わせても良い（その場合はカーブした先で不発＝爆発せず消える）。
 *               falseの場合は今まで通り、offsetX/Zの真上にまっすぐ打ち上がる。
 *               カーブの曲がり方（イージング）は ease-in-out（序盤・終盤はゆっくり、中間で最も速く曲がる）。
 * curveOffsetX/Z : カーブが有効な場合、発射地点(offsetX/Z)からの移動量（相対値）。例えば offsetX=5 で
 *               curveOffsetX=3 なら、爆発地点のXは 5+3=8 になる。0のままなら該当軸は曲がらない。
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
    public boolean curveEnabled = false; // カーブ（発射地点から横にずれた位置で爆発させる）
    public float curveOffsetX = 0.0f; // カーブの移動量（発射地点からの相対X）
    public float curveOffsetZ = 0.0f; // カーブの移動量（発射地点からの相対Z）

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
        tag.putBoolean("CurveEnabled", curveEnabled);
        tag.putFloat("CurveX", curveOffsetX);
        tag.putFloat("CurveZ", curveOffsetZ);
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
        curveEnabled = tag.getBoolean("CurveEnabled");
        // 既存セーブ(カーブ機能がなかった頃)には CurveX/Z が存在しないので、その場合は
        // offsetX/Z(打ち上げ位置)と同じにしておく＝真上に打ち上がる従来通りの見た目になる。
        curveOffsetX = tag.contains("CurveX") ? tag.getFloat("CurveX") : offsetX;
        curveOffsetZ = tag.contains("CurveZ") ? tag.getFloat("CurveZ") : offsetZ;
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
        buf.writeBoolean(curveEnabled);
        buf.writeFloat(curveOffsetX);
        buf.writeFloat(curveOffsetZ);
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
        curveEnabled = buf.readBoolean();
        curveOffsetX = buf.readFloat();
        curveOffsetZ = buf.readFloat();
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
        e.curveEnabled = curveEnabled;
        e.curveOffsetX = curveOffsetX;
        e.curveOffsetZ = curveOffsetZ;
        return e;
    }
}