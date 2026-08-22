package com.realhanabimod.network.packet;

import com.realhanabimod.data.HanabiShowData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenGuiPacket {

    public final BlockPos pos;
    public final HanabiShowData data;

    public OpenGuiPacket(BlockPos pos, HanabiShowData data) {
        this.pos = pos;
        this.data = data;
    }

    public static void encode(OpenGuiPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        msg.data.writeBuf(buf);
    }

    public static OpenGuiPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        HanabiShowData data = HanabiShowData.readBuf(buf);
        return new OpenGuiPacket(pos, data);
    }

    public static void handle(OpenGuiPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> openScreen(msg));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void openScreen(OpenGuiPacket msg) {
        Minecraft.getInstance().setScreen(
                new com.realhanabimod.client.gui.HanabiListScreen(msg.pos, msg.data));
    }
}
