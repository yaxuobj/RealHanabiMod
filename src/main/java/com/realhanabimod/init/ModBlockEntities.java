package com.realhanabimod.init;

import com.realhanabimod.RealHanabiMod;
import com.realhanabimod.blockentity.HanabiLauncherBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> REGISTRY =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, RealHanabiMod.MOD_ID);

    public static final RegistryObject<BlockEntityType<HanabiLauncherBlockEntity>> HANABI_LAUNCHER =
            REGISTRY.register("hanabi_launcher", () -> BlockEntityType.Builder.of(
                    HanabiLauncherBlockEntity::new, ModBlocks.HANABI_LAUNCHER.get()).build(null));
}
