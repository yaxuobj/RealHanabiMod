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

    // 花火・爆発系の音が聞こえる最大距離（ブロック単位）。
    public static final float FIREWORK_SOUND_RANGE = 2000.0f;
    // 発射音（打ち上げ機のドンという音）は少し控えめな範囲にする。
    public static final float LAUNCH_SOUND_RANGE = 700.0f;

    // 玉が打ち上がる音（fly.ogg）。一度だけ再生。
    // createFixedRangeEvent を使うことで、音量に関わらず range で指定した距離まで
    // 徐々に小さくなりながら聞こえ、それを超えると聞こえなくなる（距離減衰）。
    public static final RegistryObject<SoundEvent> FLY = REGISTRY.register("fly",
            () -> SoundEvent.createFixedRangeEvent(new ResourceLocation(RealHanabiMod.MOD_ID, "fly"), FIREWORK_SOUND_RANGE));

    // 爆発音（explode.ogg）。一度だけ再生。
    public static final RegistryObject<SoundEvent> EXPLODE = REGISTRY.register("explode",
            () -> SoundEvent.createFixedRangeEvent(new ResourceLocation(RealHanabiMod.MOD_ID, "explode"), FIREWORK_SOUND_RANGE));

    // 柳の終盤に鳴る「ザラザラ…」というクラックル音（crackle.ogg）。一度だけ再生。
    public static final RegistryObject<SoundEvent> CRACKLE = REGISTRY.register("crackle",
            () -> SoundEvent.createFixedRangeEvent(new ResourceLocation(RealHanabiMod.MOD_ID, "crackle"), FIREWORK_SOUND_RANGE));

    // 発射音
    public static final RegistryObject<SoundEvent> LAUNCH = REGISTRY.register("launch",
            () -> SoundEvent.createFixedRangeEvent(new ResourceLocation(RealHanabiMod.MOD_ID, "launch"), LAUNCH_SOUND_RANGE));
}