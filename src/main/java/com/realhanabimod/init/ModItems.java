package com.realhanabimod.init;

import com.realhanabimod.RealHanabiMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> REGISTRY =
            DeferredRegister.create(ForgeRegistries.ITEMS, RealHanabiMod.MOD_ID);

    public static final RegistryObject<Item> HANABI_LAUNCHER = REGISTRY.register("hanabi_launcher",
            () -> new BlockItem(ModBlocks.HANABI_LAUNCHER.get(), new Item.Properties()));

    @Mod.EventBusSubscriber(modid = RealHanabiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class CreativeTabHandler {
        @SubscribeEvent
        public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
            if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS) {
                event.accept(HANABI_LAUNCHER);
            }
        }
    }
}
