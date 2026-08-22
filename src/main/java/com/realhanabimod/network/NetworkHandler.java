package com.realhanabimod.network;

import com.realhanabimod.RealHanabiMod;
import com.realhanabimod.network.packet.OpenGuiPacket;
import com.realhanabimod.network.packet.PlayShowPacket;
import com.realhanabimod.network.packet.SaveDataPacket;
import com.realhanabimod.network.packet.StartShowPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RealHanabiMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int id = 0;

    private static int nextId() {
        return id++;
    }

    public static void register() {
        CHANNEL.registerMessage(nextId(), OpenGuiPacket.class,
                OpenGuiPacket::encode, OpenGuiPacket::decode, OpenGuiPacket::handle);

        CHANNEL.registerMessage(nextId(), SaveDataPacket.class,
                SaveDataPacket::encode, SaveDataPacket::decode, SaveDataPacket::handle);

        CHANNEL.registerMessage(nextId(), StartShowPacket.class,
                StartShowPacket::encode, StartShowPacket::decode, StartShowPacket::handle);

        CHANNEL.registerMessage(nextId(), PlayShowPacket.class,
                PlayShowPacket::encode, PlayShowPacket::decode, PlayShowPacket::handle);
    }
}
