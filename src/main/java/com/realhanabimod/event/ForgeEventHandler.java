package com.realhanabimod.event;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 花火打ち上げ機の右クリックは HanabiLauncherBlock#use 内で完結して処理しているため、
 * ここでは今後の拡張（例: クリエイティブ制限やクールダウン等）のための土台として用意している。
 */
public class ForgeEventHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // 現状は HanabiLauncherBlock#use が処理を担うため、ここでは何もしない。
        // 将来的に「クールダウン中は開けない」等のガードを入れたい場合はここに追加する。
    }
}
