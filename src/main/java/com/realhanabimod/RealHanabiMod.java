package com.realhanabimod;

import com.realhanabimod.client.render.FireworkShowPlayer;
import com.realhanabimod.client.render.HanabiRenderer;
import com.realhanabimod.init.ModBlockEntities;
import com.realhanabimod.init.ModBlocks;
import com.realhanabimod.init.ModItems;
import com.realhanabimod.init.ModSounds;
import com.realhanabimod.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * RealHanabiMod
 * 花火（打ち上げ花火）を再現するMOD。
 * 1つの設置ブロック「花火打ち上げ機」から、色・大きさ・高さ・座標をカスタマイズした
 * 花火のタイムラインを作成し再生できる。
 */
@Mod(RealHanabiMod.MOD_ID)
public class RealHanabiMod {

    public static final String MOD_ID = "realhanabimod";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RealHanabiMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // レジストリ初期化
        ModBlocks.REGISTRY.register(modEventBus);
        ModItems.REGISTRY.register(modEventBus);
        ModBlockEntities.REGISTRY.register(modEventBus);
        ModSounds.REGISTRY.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        // イベントハンドラ登録（サーバー側：右クリックでGUIを開く等）
        MinecraftForge.EVENT_BUS.register(com.realhanabimod.event.ForgeEventHandler.class);

        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(HanabiRenderer.class);
            MinecraftForge.EVENT_BUS.register(FireworkShowPlayer.class);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(NetworkHandler::register);
    }
}
