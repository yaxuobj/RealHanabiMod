package com.realhanabimod.init;

import com.realhanabimod.RealHanabiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> REGISTRY =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, RealHanabiMod.MOD_ID);

    // 玉が打ち上がる音（fly.ogg）。一度だけ再生。
    public static final RegistryObject<SoundEvent> FLY = REGISTRY.register("fly",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(RealHanabiMod.MOD_ID, "fly")));

    // 爆発音（explode.ogg）。一度だけ再生。
    public static final RegistryObject<SoundEvent> EXPLODE = REGISTRY.register("explode",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(RealHanabiMod.MOD_ID, "explode")));
}
