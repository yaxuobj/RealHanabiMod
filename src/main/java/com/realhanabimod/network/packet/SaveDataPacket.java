package com.realhanabimod.network.packet;

import com.realhanabimod.blockentity.HanabiLauncherBlockEntity;
import com.realhanabimod.data.HanabiShowData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** GUIの「保存」を押した際、クライアントからサーバーへタイムラインを送信し、BlockEntityへ反映する。 */
public class SaveDataPacket {

    public final BlockPos pos;
    public final HanabiShowData data;

    public SaveDataPacket(BlockPos pos, HanabiShowData data) {
        this.pos = pos;
        this.data = data;
    }

    public static void encode(SaveDataPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        msg.data.writeBuf(buf);
    }

    public static SaveDataPacket decode(FriendlyByteBuf buf) {
        return new SaveDataPacket(buf.readBlockPos(), HanabiShowData.readBuf(buf));
    }

    public static void handle(SaveDataPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            Level level = player.level();
            if (!level.isLoaded(msg.pos)) return;
            BlockEntity be = level.getBlockEntity(msg.pos);
            if (be instanceof HanabiLauncherBlockEntity launcher) {
                launcher.setShowData(msg.data);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
