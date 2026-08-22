package com.realhanabimod.network.packet;

import com.realhanabimod.data.HanabiShowData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PlayShowPacket {

    public final BlockPos pos;
    public final HanabiShowData data;

    public PlayShowPacket(BlockPos pos, HanabiShowData data) {
        this.pos = pos;
        this.data = data;
    }

    public static void encode(PlayShowPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        msg.data.writeBuf(buf);
    }

    public static PlayShowPacket decode(FriendlyByteBuf buf) {
        return new PlayShowPacket(buf.readBlockPos(), HanabiShowData.readBuf(buf));
    }

    public static void handle(PlayShowPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> startClient(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void startClient(PlayShowPacket msg) {
        com.realhanabimod.client.render.FireworkShowPlayer.startShow(msg.pos, msg.data);
    }
}
