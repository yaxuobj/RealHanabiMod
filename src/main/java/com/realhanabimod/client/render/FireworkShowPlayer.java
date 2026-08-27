package com.realhanabimod.client.render;

import com.realhanabimod.data.FireworkEntry;
import com.realhanabimod.data.HanabiShowData;
import com.realhanabimod.init.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
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

    // 各サウンドの基準音量。距離による減衰・可聴範囲は ModSounds 側の
    // createFixedRangeEvent(range) で設定済みなので、ここでは「近くで聞いたときの
    // 音の大きさ」だけを調整する。
    private static final float BASE_VOLUME = 6.0f;
    private static final float FLY_VOLUME = BASE_VOLUME;
    private static final float EXPLODE_VOLUME = BASE_VOLUME * 1.5f;   // 爆発音は150%（1.5倍）
    private static final float CRACKLE_VOLUME = BASE_VOLUME;
    private static final float LAUNCH_VOLUME = BASE_VOLUME * 0.6f;    // 発射音は60%

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
                queueSound(ModSounds.EXPLODE.get(), v.apexPos, SOUND_DELAY_SECONDS, EXPLODE_VOLUME);
            }
            // 柳の爆発から少し経った頃（火花が垂れ始めた頃）に「ザラザラ…」というクラックル音を1回だけ鳴らす。
            if (v.pollCrackleTrigger()) {
                queueSound(ModSounds.CRACKLE.get(), v.getSparkCentroid(), SOUND_DELAY_SECONDS, CRACKLE_VOLUME);
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
        // 打ち上げ機から玉が発射される瞬間の音（ドン、という発射音）
        queueSound(ModSounds.LAUNCH.get(), launchPos, SOUND_DELAY_SECONDS, LAUNCH_VOLUME);
        queueSound(ModSounds.FLY.get(), launchPos, SOUND_DELAY_SECONDS, FLY_VOLUME);
    }

    /**
     * 以前はここで「爆発1つにつき代表点1つだけ」へレイキャストし、ブロックに当たったら
     * 爆発全体（火花・尾すべて）を丸ごと非表示にしていた。しかしこれだと、現実の花火のように
     * 「ブロックの隙間から一部だけ見える」という見え方ができず、代表点がブロックの陰に
     * 入った瞬間に爆発全体が"パッ"と消えてしまう不自然な挙動になっていた。
     * <p>
     * ブロックによる遮蔽は、実際に描画する側（HanabiRenderer / SmokeRenderer）で
     * GPUの深度テスト（本物のピクセル単位の判定）を有効にすることで正しく・部分的に
     * 表現できるようになったため、ここでのレイキャストによる丸ごと非表示化は行わない。
     * cachedVisible フィールド自体は他のコードから参照され続けているため残してあるが、
     * 現在は常に true を返すだけの軽い処理になっている。
     */
    private static void updateVisibilityCache(FireworkVisual v) {
        v.cachedVisible = true;
    }

    private static void queueSound(net.minecraft.sounds.SoundEvent event, Vec3 pos, float delay, float volume) {
        QueuedSound qs = new QueuedSound();
        qs.event = event;
        qs.pos = pos;
        qs.remaining = delay;
        qs.volume = volume;
        QUEUED_SOUNDS.add(qs);
    }

    private static void playSoundNow(QueuedSound qs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 距離による音量減衰・可聴範囲のカットオフは、ModSounds 側で登録した
        // createFixedRangeEvent(range) によってエンジン側が自動的に処理する
        // （近いほど大きく、遠いほど滑らかに小さくなり、range を超えると聞こえなくなる）。
        mc.level.playLocalSound(qs.pos.x, qs.pos.y, qs.pos.z, qs.event, SoundSource.RECORDS,
                qs.volume, 1.0f, false);
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
        float volume;
    }
}