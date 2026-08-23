package com.realhanabimod.client.render;

import com.realhanabimod.data.FireworkEntry;
import com.realhanabimod.data.HanabiShowData;
import com.realhanabimod.init.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * クライアント側で「開始」された花火ショーの進行を管理する。
 * サーバーから PlayShowPacket を受け取るたびに 1 ショーとして登録され、
 * 毎ティック時間を進めてスケジュール通りに花火を打ち上げる。
 * 音は視覚より 1 秒遅れて鳴る（実際の花火のように光→音の順）。
 */
public class FireworkShowPlayer {

    private static final List<ActiveShow> ACTIVE_SHOWS = new ArrayList<>();
    private static final List<FireworkVisual> VISUALS = new ArrayList<>();
    private static final List<QueuedSound> QUEUED_SOUNDS = new ArrayList<>();

    public static final float SOUND_DELAY_SECONDS = 1.0f;

    // 可視判定(壁越しに見えないか)を全花火まとめて毎tick行うと重いので、
    // 花火ごとに少しずつタイミングをずらしながら CHECK_PERIOD_TICKS に1回だけ計算する。
    private static final int CHECK_PERIOD_TICKS = 4; // 秒5回程度に間引く（描画は毎フレームだが判定は秒5回で十分）
    private static int globalTickCounter = 0;

    public static List<FireworkVisual> getVisuals() {
        return VISUALS;
    }

    public static void startShow(BlockPos pos, HanabiShowData data) {
        ActiveShow show = new ActiveShow();
        show.blockPos = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        show.schedule = data.buildSchedule();
        show.elapsed = 0f;
        ACTIVE_SHOWS.add(show);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getInstance().isPaused()) return;

        float dt = 1f / 20f;
        globalTickCounter++;

        // ショー進行：時間が来たら花火を発射（=FireworkVisual生成、上昇開始）
        Iterator<ActiveShow> showIt = ACTIVE_SHOWS.iterator();
        while (showIt.hasNext()) {
            ActiveShow show = showIt.next();
            float prev = show.elapsed;
            show.elapsed += dt;

            Iterator<HanabiShowData.Scheduled> schedIt = show.schedule.iterator();
            List<HanabiShowData.Scheduled> toLaunch = new ArrayList<>();
            for (HanabiShowData.Scheduled s : show.schedule) {
                if (s.timeSeconds() >= prev && s.timeSeconds() < show.elapsed) {
                    toLaunch.add(s);
                }
            }
            for (HanabiShowData.Scheduled s : toLaunch) {
                launchFirework(show.blockPos, s.firework());
            }

            boolean allLaunched = show.schedule.isEmpty()
                    || show.elapsed > show.schedule.get(show.schedule.size() - 1).timeSeconds() + 6f;
            if (allLaunched) showIt.remove();
        }

        // 個々の花火の見た目の進行
        Iterator<FireworkVisual> visIt = VISUALS.iterator();
        while (visIt.hasNext()) {
            FireworkVisual v = visIt.next();
            FireworkVisual.Phase before = v.phase;
            v.tick(dt);
            if (before == FireworkVisual.Phase.FUSE && v.phase == FireworkVisual.Phase.EXPLODED
                    && !v.entry.misfire) {
                // 不発の場合は爆発音を鳴らさない（玉が静かに消えるだけ）
                queueSound(ModSounds.EXPLODE.get(), v.apexPos, SOUND_DELAY_SECONDS);
            }
            // 柳の爆発から少し経った頃（火花が垂れ始めた頃）に「ザラザラ…」というクラックル音を1回だけ鳴らす。
            if (v.pollCrackleTrigger()) {
                queueSound(ModSounds.CRACKLE.get(), v.getSparkCentroid(), SOUND_DELAY_SECONDS);
            }
            updateVisibilityCache(v);
            if (v.removed) visIt.remove();
        }

        // 遅延サウンド再生
        Iterator<QueuedSound> soundIt = QUEUED_SOUNDS.iterator();
        while (soundIt.hasNext()) {
            QueuedSound qs = soundIt.next();
            qs.remaining -= dt;
            if (qs.remaining <= 0f) {
                playSoundNow(qs);
                soundIt.remove();
            }
        }
    }

    private static void launchFirework(Vec3 blockPos, FireworkEntry entry) {
        Vec3 launchPos = blockPos.add(entry.offsetX, 0.0, entry.offsetZ);
        FireworkVisual visual = new FireworkVisual(blockPos, entry);
        VISUALS.add(visual);
        queueSound(ModSounds.FLY.get(), launchPos, SOUND_DELAY_SECONDS);
    }

    /**
     * 壁越しに見えないようにするための可視判定を、火花1粒ずつではなく花火(爆発)1つにつき1点だけ、
     * かつ CHECK_PERIOD_TICKS に1回だけ計算してキャッシュする。
     * 花火ごとに identityHashCode でタイミングをずらしているので、同時に大量の花火が
     * 爆発していても判定処理が1tickに集中して重くなることを防いでいる。
     */
    private static void updateVisibilityCache(FireworkVisual v) {
        int offset = System.identityHashCode(v) % CHECK_PERIOD_TICKS;
        if ((globalTickCounter + offset) % CHECK_PERIOD_TICKS != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gameRenderer == null) return;
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 target = v.getVisibilityCheckPos();

        double distSq = camPos.distanceToSqr(target);
        if (distSq < 4.0) {
            v.cachedVisible = true;
            return;
        }
        ClipContext ctx = new ClipContext(camPos, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
        HitResult result = mc.level.clip(ctx);
        v.cachedVisible = result.getType() == HitResult.Type.MISS;
    }

    private static void queueSound(net.minecraft.sounds.SoundEvent event, Vec3 pos, float delay) {
        QueuedSound qs = new QueuedSound();
        qs.event = event;
        qs.pos = pos;
        qs.remaining = delay;
        QUEUED_SOUNDS.add(qs);
    }

    private static void playSoundNow(QueuedSound qs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.playLocalSound(qs.pos.x, qs.pos.y, qs.pos.z, qs.event, SoundSource.RECORDS,
                6.0f, 1.0f, false);
    }

    private static class ActiveShow {
        Vec3 blockPos;
        List<HanabiShowData.Scheduled> schedule;
        float elapsed;
    }

    private static class QueuedSound {
        net.minecraft.sounds.SoundEvent event;
        Vec3 pos;
        float remaining;
    }
}