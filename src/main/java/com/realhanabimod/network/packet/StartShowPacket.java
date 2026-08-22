package com.realhanabimod.network.packet;

import com.realhanabimod.data.HanabiShowData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import com.realhanabimod.network.NetworkHandler;

import java.util.function.Supplier;

/** GUIの「開始」を押した際、サーバーに再生開始をリクエストする。サーバーは周囲全プレイヤーへ再生パケットを転送する。 */
public class StartShowPacket {

    public final BlockPos pos;
    public final HanabiShowData data;

    public StartShowPacket(BlockPos pos, HanabiShowData data) {
        this.pos = pos;
        this.data = data;
    }

    public static void encode(StartShowPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        msg.data.writeBuf(buf);
    }

    public static StartShowPacket decode(FriendlyByteBuf buf) {
        return new StartShowPacket(buf.readBlockPos(), HanabiShowData.readBuf(buf));
    }

    public static void handle(StartShowPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            if (!(player.level() instanceof ServerLevel level)) return;

            PlayShowPacket play = new PlayShowPacket(msg.pos, msg.data);
            // 同じディメンションにいる全プレイヤーに送信（遠くからでも見える特別仕様のため）
            NetworkHandler.CHANNEL.send(
                    PacketDistributor.DIMENSION.with(level::dimension), play);
        });
        ctx.get().setPacketHandled(true);
    }
}
