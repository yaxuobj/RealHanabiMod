package com.realhanabimod.event;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 今後の拡張（例: クリエイティブ制限やクールダウン等）のための土台
 */
public class ForgeEventHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
    }
}
