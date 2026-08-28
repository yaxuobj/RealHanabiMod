package com.realhanabimod.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 花火のグラデーション1段分の設定。
 * <p>
 * color1 → color2 という2色の組（ColorPresetsのインデックス）を1つの単位として持つ。
 * FireworkEntry.gradients には、このColorGradientを1〜4個（MAX_GRADIENTS）並べて持たせることができ、
 * 「グラデーションを追加」ボタンを押すたびに新しいColorGradient（color1/color2の組）が1つ増える。
 * <p>
 * 色の変化タイミングは自動計算：火花1つ1つの寿命(maxLife)を、このリストの個数で均等に分割し、
 * 前半の区間から順にそれぞれのColorGradientのcolor1→color2へなめらかに変化しながら、
 * 区間の切り替わりごとに次のグラデーションのcolor1へパッと切り替わる（実際の花火の色変わり演出）。
 */
public class ColorGradient {

    public int color1;
    public int color2;

    public ColorGradient() {
        this(0, 0);
    }

    public ColorGradient(int color1, int color2) {
        this.color1 = color1;
        this.color2 = color2;
    }

    public ColorGradient copy() {
        return new ColorGradient(color1, color2);
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("C1", color1);
        tag.putInt("C2", color2);
        return tag;
    }

    public static ColorGradient readNbt(CompoundTag tag) {
        return new ColorGradient(tag.getInt("C1"), tag.getInt("C2"));
    }

    public void writeBuf(FriendlyByteBuf buf) {
        buf.writeVarInt(color1);
        buf.writeVarInt(color2);
    }

    public static ColorGradient readBuf(FriendlyByteBuf buf) {
        return new ColorGradient(buf.readVarInt(), buf.readVarInt());
    }
}