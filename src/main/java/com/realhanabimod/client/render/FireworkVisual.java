package com.realhanabimod.client.render;

import com.realhanabimod.data.ColorPresets;
import com.realhanabimod.data.FireworkEntry;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 花火1発分の動的な状態。
 * フェーズ1: ASCENDING … 打ち上げ地点から height の高さまで玉(hanabi.png単体)が上昇。終盤は減速する。
 * フェーズ2: FUSE      … 頂点に到達。玉の光がすっと消え、実際に爆発するまで約1秒の「間」が空く（導火線のイメージ）。
 * フェーズ3: EXPLODED  … FireworkShapeManager の形に沿って火花が飛び散る
 * フェーズ4: DONE      … 火花が重力で落ちながらフェードアウトし消え、片付け対象になる
 */
public class FireworkVisual {

    public enum Phase { ASCENDING, FUSE, EXPLODED, DONE }

    public final Vec3 originBlockPos;
    public final Vec3 launchPos;   // originBlockPos + offsetX/Z（打ち上げ開始地点、地上）
    public final Vec3 apexPos;     // 爆発地点（launchPos + height）
    public final FireworkEntry entry;

    public Phase phase = Phase.ASCENDING;
    public float ascendTimer = 0f;

    public float fuseTimer = 0f;
    // 光が消えてから実際に爆発するまでの「間」。毎回同じだと不自然なので、花火ごとに少しだけ揺らぎを持たせる。
    public final float fuseDuration;

    private static final float FUSE_FADE_OUT_TIME = 0.2f;

    public float explodeTimer = 0f;
    public static final float SPARK_LIFETIME = 2.6f;
    // 柳の火花が地面近くまで垂れ落ちる時間を確保するための寿命上限と、暴走防止の安全上限。
    public static final float WILLOW_MAX_LIFE = 6.5f;
    private static final float SAFETY_MAX_EXPLODE_TIME = WILLOW_MAX_LIFE + 1.0f;

    public List<SparkParticle> sparks = new ArrayList<>();

    // サウンド管理用フラグ（1秒遅延で再生するため、いつ音を鳴らすべきかをタイマーで管理）
    public boolean flySoundQueued = false;
    public boolean explodeSoundQueued = false;
    public float flySoundTimer = -1f;
    public float explodeSoundTimer = -1f;

    public boolean removed = false;

    // レンダラーが毎フレーム(高FPS時は秒60〜数百回)レイキャストするのは重いため、
    // 可視判定(壁越しに見えないか)は tick 側(秒20回)で一定間隔ごとに計算してキャッシュする。
    public boolean cachedVisible = true;

    public FireworkVisual(Vec3 blockPos, FireworkEntry entry) {
        this.originBlockPos = blockPos;
        this.entry = entry;
        // 玉はブロックと完全に同じ座標（XZオフセット指定時のみズラす）から発射する
        this.launchPos = blockPos.add(entry.offsetX, 0.0, entry.offsetZ);
        this.apexPos = launchPos.add(0, entry.height, 0);
        // 毎回ぴったり1秒だと不自然なので 0.8〜1.2秒の範囲で花火ごとに固定の揺らぎを持たせる
        this.fuseDuration = 0.8f + (Math.abs(entry.uid) % 5) * 0.1f;
    }

    public Vec3 getCurrentBallPos() {
        double t = Math.min(1.0, ascendTimer / Math.max(0.05f, entry.explodeTime));
        // 終盤ほど減速するが、完全に止まりはしないカーブ（線形と二次イーズアウトのブレンド）。
        // eased = t + DECEL*(t - t^2) なので t=1 では必ず apexPos に到達しつつ、
        // 終端速度が初速の (1 - DECEL) 倍だけ残る＝止まりきらずに減速したまま爆発を迎える。
        final double DECEL = 0.6;
        double eased = t + DECEL * (t - t * t);
        return launchPos.lerp(apexPos, eased);
    }

    /**
     * 玉（と尾）の発光の強さ。ASCENDING中はフルに光るが、頂点到達(FUSEフェーズ入り)した瞬間に
     * 即座に消灯し、そのまま爆発までの「間」は真っ暗になる（現実の花火のように、じわっとではなくパッと消える）。
     */
    public float getBallGlow() {
        if (phase == Phase.ASCENDING) return 1.0f;
        if (phase == Phase.FUSE) {
            if (fuseTimer < FUSE_FADE_OUT_TIME) {
                return 1.0f - (fuseTimer / FUSE_FADE_OUT_TIME);
            }
            return 0f;
        }
        return 0f;
    }

    /**
     * 尾の長さ（または不透明度）のスケール (0.0 〜 1.0)。
     * 速度が落ちてくる上昇の終盤（ここでは進行度70%以降）で、徐々に 0 に向かって縮んでいく。
     */
    public float getTailScale() {
        if (phase != Phase.ASCENDING) return 0f; // 頂点到達後は尾は完全に消える

        // 打ち上げの進行度 (0.0 〜 1.0)
        float t = Math.min(1.0f, ascendTimer / Math.max(0.05f, entry.explodeTime));

        // 進行度が 0.7 (70%) を超えたら、徐々に尾を短くする
        float threshold = 0.7f;
        if (t < threshold) {
            return 1.0f;
        } else {
            // 0.7 〜 1.0 の間で、1.0 から 0.0 へフェードさせる
            return 1.0f - ((t - threshold) / (1.0f - threshold));
        }
    }

    /** 可視判定(壁越しに見えないか)に使う代表座標。火花1つ1つではなく爆発全体で1点だけ判定する。 */
    public Vec3 getVisibilityCheckPos() {
        return phase == Phase.ASCENDING ? getCurrentBallPos() : apexPos;
    }

    public void tick(float deltaSeconds) {
        if (phase == Phase.ASCENDING) {
            ascendTimer += deltaSeconds;
            if (ascendTimer >= Math.max(0.05f, entry.explodeTime)) {
                phase = Phase.FUSE;
                fuseTimer = 0f;
            }
        } else if (phase == Phase.FUSE) {
            fuseTimer += deltaSeconds;
            if (fuseTimer >= fuseDuration) {
                explode();
            }
        } else if (phase == Phase.EXPLODED) {
            explodeTimer += deltaSeconds;
            for (SparkParticle sp : sparks) {
                sp.tick(deltaSeconds);
            }
            sparks.removeIf(sp -> sp.life <= 0f);
            // 柳などは火花ごとに寿命がバラバラ(最大 WILLOW_MAX_LIFE 秒)なので、
            // 固定タイマーではなく「火花が全部消えたら終わり」で判定する。
            // ただし万一消え残った場合に備え、安全上限も設けておく。
            if (sparks.isEmpty() || explodeTimer >= SAFETY_MAX_EXPLODE_TIME) {
                phase = Phase.DONE;
                removed = true;
            }
        }
    }

    private void explode() {
        phase = Phase.EXPLODED;
        List<FireworkShapeManager.Spark> pattern =
                FireworkShapeManager.generate(entry.designIndex, entry.uid);

        int[] palette = new int[entry.colors.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = ColorPresets.get(entry.colors.get(i));
        }

        for (FireworkShapeManager.Spark spark : pattern) {
            float grad = pattern.size() <= 1 ? 0f : sparks.size() / (float) pattern.size();
            int color = palette.length == 1 ? palette[0] : lerpColor(palette, grad);
            double speed = 6.0 * entry.size * spark.speedScale();
            Vector3f d = spark.dir();
            Vec3 vel = new Vec3(d.x, d.y, d.z).scale(speed);

            // 尾を持つ火花(trailScale > 0)は、通常より長生きさせて尾が伸びる時間を稼ぐ。
            // trailScaleが大きいほど長生き：柳(3.2〜7.2)は約4.6〜6.5秒、
            // 菊(1.0〜2.0)は約3.5〜4.0秒と、柳ほど長くならず短めの尾で済む。
            float maxLife = spark.trailScale() > 0.001f
                    ? Math.min(WILLOW_MAX_LIFE, 3.0f + spark.trailScale() * 0.5f)
                    : SPARK_LIFETIME;

            sparks.add(new SparkParticle(apexPos, vel, color, maxLife, spark.trailScale()));
        }
    }

    private static int lerpColor(int[] palette, float t) {
        float scaled = t * (palette.length - 1);
        int i0 = (int) Math.floor(scaled);
        int i1 = Math.min(palette.length - 1, i0 + 1);
        float f = scaled - i0;
        int c0 = palette[Math.max(0, Math.min(palette.length - 1, i0))];
        int c1 = palette[i1];
        int r = (int) (((c0 >> 16) & 0xFF) * (1 - f) + ((c1 >> 16) & 0xFF) * f);
        int g = (int) (((c0 >> 8) & 0xFF) * (1 - f) + ((c1 >> 8) & 0xFF) * f);
        int b = (int) ((c0 & 0xFF) * (1 - f) + (c1 & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** 個々の火花（爆発後、重力で落ちながら消えていく） */
    public static class SparkParticle {
        public Vec3 pos;
        public Vec3 vel;
        public final int color;
        public float life;
        public final float maxLife;
        /** 0なら尾なし。柳などデザイン側で指定された「尾の長さ」の倍率。 */
        public final float trailScale;
        /** 発生地点（頂点付近）に固定。曲がった尾を再現するための軌道再計算の起点にする。 */
        public final Vec3 trailOrigin;
        /** 発生した瞬間の速度。posの実際の軌道（カーブ）を再現するため、レンダラー側で使う。 */
        public final Vec3 initialVel;
        /** 発生してからの経過時間（秒）。軌道再計算の「どこまで進んだか」に使う。 */
        public float age = 0f;

        public SparkParticle(Vec3 pos, Vec3 vel, int color, float maxLife) {
            this(pos, vel, color, maxLife, 0f);
        }

        public SparkParticle(Vec3 pos, Vec3 vel, int color, float maxLife, float trailScale) {
            this.pos = pos;
            this.vel = vel;
            this.color = color;
            this.life = maxLife;
            this.maxLife = maxLife;
            this.trailScale = trailScale;
            this.trailOrigin = pos;
            this.initialVel = vel;
        }

        public void tick(float dt) {
            pos = pos.add(vel.scale(dt));
            // 重力＋空気抵抗。だんだん減速しながら落下する（＝最後は下に落ちる火花）
            vel = vel.scale(0.96).subtract(0, 9.0 * dt, 0);
            life -= dt;
            age += dt;
        }

        public float alpha() {
            return Math.max(0f, Math.min(1f, life / maxLife));
        }
    }
}