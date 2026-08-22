package com.realhanabimod.init;

import com.realhanabimod.RealHanabiMod;
import com.realhanabimod.block.HanabiLauncherBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {

    public static final DeferredRegister<Block> REGISTRY =
            DeferredRegister.create(ForgeRegistries.BLOCKS, RealHanabiMod.MOD_ID);

    public static final RegistryObject<Block> HANABI_LAUNCHER = REGISTRY.register("hanabi_launcher",
            () -> new HanabiLauncherBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0f, 6.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));
}
