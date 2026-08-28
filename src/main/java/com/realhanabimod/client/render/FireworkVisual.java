package com.realhanabimod.client.render;

import com.realhanabimod.data.ColorGradient;
import com.realhanabimod.data.ColorPresets;
import com.realhanabimod.data.FireworkEntry;
import net.minecraft.util.Mth;
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
    public final Vec3 apexPos;     // 爆発地点。カーブ無効なら launchPos の真上(height)。カーブ有効なら
    // launchPos から curveOffsetX/Z ぶんだけ横にずれた位置(+height)
    // ＝曲がった先の頂点。
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

    // 柳（willow）の終盤、火花が消える少し手前で「ザラザラ…」というクラックル音を1回だけ鳴らすためのタイミング。
    // 実際に音が鳴るのは、ここから更に SOUND_DELAY_SECONDS(1秒) 遅れて聞こえる(光→音の順にしているため)。
    // なので爆発から約3秒後に聞こえるよう、ここでは爆発から2秒経過した時点をトリガーにしている。
    public static final float WILLOW_CRACKLE_TRIGGER_TIME = 2.0f;
    private boolean crackleQueued = false;

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

    // getBallPosAtTime()のイージングで使う減速係数。timeAtRelativeHeight()で高さ→時刻の逆算にも使うため
    // メソッドローカルではなくクラス定数として共有する。
    private static final double ASCEND_DECEL = 0.6;

    // --- 上昇中の玉の「くねくね」感（wobble） ---
    // 現実の打ち上げ花火は、玉（および尾）が中心の軌道からわずかに横にズレた位置を、
    // そのまま回転しながら上昇していくため、遠目に見ると軌道全体がゆるく螺旋を描いて
    // くねくねしているように見える。これを再現するため、直線・カーブに関わらず
    // getBallPosAtTime() の水平座標(X/Z)へ小さな円運動のオフセットを加える。
    // ・半径は花火の大きさ(size)に比例させ、大玉ほど大きくくねるようにする。
    // ・打ち上げ開始直後(t=0)と頂点到達時(t=1)ではオフセットを0にフェードし、
    // 　発射地点や爆発地点(apexPos)がズレて見えない/軌道と食い違わないようにする。
    private static final double WOBBLE_RADIUS_PER_SIZE = 0.12;
    private static final double WOBBLE_RADIANS_PER_SECOND = 9.0;

    // wobbleの初期回転角。花火ごとに毎回同じ向きにくねると不自然なので、entry.uidから
    // 花火ごとに固定の値をばらけさせている（fuseDurationの揺らぎと同じ考え方）。
    private final double wobblePhase;

    public FireworkVisual(Vec3 blockPos, FireworkEntry entry) {
        this.originBlockPos = blockPos;
        this.entry = entry;
        // 玉はブロックと完全に同じ座標（XZオフセット指定時のみズラす）から発射する
        this.launchPos = blockPos.add(entry.offsetX, 0.0, entry.offsetZ);
        // カーブ無効なら今まで通り真上(offsetX/Zと同じXZ)。カーブ有効なら、発射地点(offsetX/Z)から
        // curveOffsetX/Z ぶんだけ離れた位置が爆発地点のXZになる＝玉はそちらへ向かって曲がりながら上昇していく。
        double apexX = entry.curveEnabled ? entry.offsetX + entry.curveOffsetX : entry.offsetX;
        double apexZ = entry.curveEnabled ? entry.offsetZ + entry.curveOffsetZ : entry.offsetZ;
        // 実際に爆発する高さ = height(玉が見えなくなる高さ) + extraExplodeHeight(見えなくなってから
        // さらに上昇する高さ)。extraExplodeHeightが0(デフォルト)なら今まで通りheightちょうどで爆発する。
        double apexHeight = entry.height + Math.max(0f, entry.extraExplodeHeight);
        this.apexPos = blockPos.add(apexX, apexHeight, apexZ);
        // 毎回ぴったり1秒だと不自然なので 0.8〜1.2秒の範囲で花火ごとに固定の揺らぎを持たせる
        this.fuseDuration = 0.8f + (Math.abs(entry.uid) % 5) * 0.1f;
        // wobbleの初期角度も花火ごとにばらけさせる（0〜2πの範囲）
        this.wobblePhase = (Math.abs(entry.uid) % 360) / 360.0 * (2.0 * Math.PI);
    }

    /**
     * 打ち上げ開始からの経過秒数(0 〜 entry.explodeTime にクランプ)を指定して、その時点の玉の位置を返す。
     * getCurrentBallPos()（＝現在時刻ぶん）だけでなく、尾・煙の「曲がったリボン」を描く際に過去の軌道を
     * 何点かサンプリングする用途でも使う共通ロジック。ここを1箇所にまとめることで、玉の実際の軌道と
     * 尾・煙の見た目が常にぴったり一致するようにしている。
     */
    public Vec3 getBallPosAtTime(float elapsedSeconds) {
        double t = Mth.clamp(elapsedSeconds / Math.max(0.05f, entry.explodeTime), 0.0, 1.0);
        // 終盤ほど減速するが、完全に止まりはしないカーブ（線形と二次イーズアウトのブレンド）。
        // eased = t + DECEL*(t - t^2) なので t=1 では必ず apexPos に到達しつつ、
        // 終端速度が初速の (1 - DECEL) 倍だけ残る＝止まりきらずに減速したまま爆発を迎える。
        double easedY = t + ASCEND_DECEL * (t - t * t);
        double y = launchPos.y + (apexPos.y - launchPos.y) * easedY;

        double x, z;
        if (!entry.curveEnabled) {
            // カーブ無効時は今まで通りXZ固定（真上に上昇するだけ）。
            x = launchPos.x;
            z = launchPos.z;
        } else {
            // カーブ有効時：水平方向(X/Z)は垂直方向とは別に、ease-in-out（序盤・終盤はゆっくり、
            // 中間で最も速く曲がる）のイージングで補間する。急に曲がり始めたり急に止まったりせず、
            // なめらかに発射地点からカーブ先へ移動していく見た目になる。
            double horizT = easeInOutQuad(t);
            x = launchPos.x + (apexPos.x - launchPos.x) * horizT;
            z = launchPos.z + (apexPos.z - launchPos.z) * horizT;
        }

        // --- wobble（中心軌道からのくねくねしたズレ） ---
        // 直線・カーブどちらの軌道にも、上で求めた中心座標(x, z)を軸とした小さな円運動を
        // 上乗せする。t=0(発射地点)とt=1(頂点＝apexPos)では振幅を0にフェードし、
        // 発射地点・爆発地点そのものはズレないようにする（sin(π*t)は両端で0、中間で最大）。
        double wobbleAmp = WOBBLE_RADIUS_PER_SIZE * entry.size * Math.sin(Math.PI * t);
        if (wobbleAmp > 1.0E-6) {
            double wobbleAngle = wobblePhase + elapsedSeconds * WOBBLE_RADIANS_PER_SECOND;
            x += wobbleAmp * Math.cos(wobbleAngle);
            z += wobbleAmp * Math.sin(wobbleAngle);
        }

        return new Vec3(x, y, z);
    }

    /** 2次関数によるease-in-out。t=0とt=1では変化がゆっくり、t=0.5付近が最も速い。 */
    private static double easeInOutQuad(double t) {
        return t < 0.5 ? 2.0 * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 2) / 2.0;
    }

    public Vec3 getCurrentBallPos() {
        return getBallPosAtTime(ascendTimer);
    }

    /**
     * 玉（と尾）の発光の強さ。
     * <p>
     * ・entry.extraExplodeHeight が 0（デフォルト）の場合は今まで通り：ASCENDING中はフルに光り、
     * 　頂点到達(FUSEフェーズ入り)した瞬間から FUSE_FADE_OUT_TIME(0.2秒)かけてすっと消える。
     * <p>
     * ・entry.extraExplodeHeight が 0 より大きい場合：実際の頂点(apexPos)は height + extraExplodeHeight の
     * 　高さにあるが、玉は今まで通り height に到達した時点で同じ0.2秒のなめらかなフェードで消え、
     * 　そこから先（実際に爆発するまでの残りの上昇＋FUSEの「間」）はずっと見えないままになる。
     * 　＝いつも通りの消え方のまま、実際の爆発だけが少し高い位置で起こる。
     */
    public float getBallGlow() {
        if (entry.ballHidden) return 0f;

        if (entry.extraExplodeHeight > 0.001f) {
            float disappearTime = timeAtRelativeHeight(entry.height);
            if (phase != Phase.ASCENDING) return 0f; // この時点で既にフェード済みのはず
            if (ascendTimer < disappearTime) return 1.0f;
            float fadeElapsed = ascendTimer - disappearTime;
            if (fadeElapsed < FUSE_FADE_OUT_TIME) {
                return 1.0f - (fadeElapsed / FUSE_FADE_OUT_TIME);
            }
            return 0f;
        }

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
     * 指定した「発射地点からの相対高さ」に到達するまでの経過秒数を、getBallPosAtTime()と同じイージング式を
     * 逆算して求める。DECEL*t^2 - (1+DECEL)*t + easedTarget = 0 を t について解いている（0〜1の解を採用）。
     */
    private float timeAtRelativeHeight(float targetHeight) {
        double apexHeight = apexPos.y - launchPos.y;
        if (apexHeight <= 1.0E-4) return 0f;
        double easedTarget = Mth.clamp(targetHeight / apexHeight, 0.0, 1.0);
        double a = ASCEND_DECEL;
        double b = -(1.0 + ASCEND_DECEL);
        double c = easedTarget;
        double disc = Math.max(0.0, b * b - 4 * a * c);
        double t = (-b - Math.sqrt(disc)) / (2 * a);
        t = Mth.clamp(t, 0.0, 1.0);
        return (float) (t * Math.max(0.05f, entry.explodeTime));
    }

    /** ballHidden、または（extraExplodeHeight設定時に）既にフェードアウトし終えている場合に true。
     * 玉本体・尾・煙など、上昇中の見た目全般の非表示判定に使う。 */
    public boolean isAscendHidden() {
        if (entry.ballHidden) return true;
        if (entry.extraExplodeHeight <= 0.001f) return false;
        return getBallGlow() <= 0.001f;
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

    /**
     * 柳の火花群が今どのあたりまで垂れ落ちているかの代表座標（現在残っている火花の重心）。
     * クラックル音を鳴らす位置に使う。火花が既に無ければ頂点(apexPos)を代わりに返す。
     */
    public Vec3 getSparkCentroid() {
        if (sparks.isEmpty()) return apexPos;
        double sx = 0, sy = 0, sz = 0;
        for (SparkParticle sp : sparks) {
            sx += sp.pos.x;
            sy += sp.pos.y;
            sz += sp.pos.z;
        }
        int n = sparks.size();
        return new Vec3(sx / n, sy / n, sz / n);
    }

    /**
     * 柳の爆発から一定時間経った（＝火花が垂れ始めた頃）タイミングに来ていれば
     * クラックル音を鳴らすべきとして true を1回だけ返す。
     * 呼び出し側（FireworkShowPlayer）は毎tickこれをポーリングし、trueが返ってきたらその場でサウンドをキューする。
     * 一度trueを返したら内部フラグで消費済みにするため、以後同じ花火では二度と鳴らない。
     */
    public boolean pollCrackleTrigger() {
        if (crackleQueued) return false;
        if (phase != Phase.EXPLODED) return false;
        if (!FireworkShapeManager.isWillow(entry.designIndex)) return false;
        if (explodeTimer < WILLOW_CRACKLE_TRIGGER_TIME) return false;
        crackleQueued = true;
        return true;
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

        // 不発：玉は頂点まで打ち上がるが、そこで爆発せず火花も発生させない。
        // sparks は空のままにしておくと、tick() 側の「火花が0個なら終了」処理により
        // そのまま静かに消える（＝爆発音も出ない）。
        if (entry.misfire) {
            return;
        }

        List<FireworkShapeManager.Spark> pattern =
                FireworkShapeManager.generate(entry.designIndex, entry.uid);

        // グラデーション各段のcolor1/color2を実際のRGBに解決した配列。
        // [段0.color1, 段0.color2, 段1.color1, 段1.color2, ...] の順に並ぶ。
        // 火花の色は「爆発パターン内での位置」ではなく「火花自身の経過時間」で決まるようにするため、
        // 全ての火花で同じ配列を共有して構わない（SparkParticle側で年齢に応じて参照するだけ）。
        int[] gradientStops = resolveGradientStops(entry.gradients);

        for (FireworkShapeManager.Spark spark : pattern) {
            double speed = 6.0 * entry.size * spark.speedScale();
            Vector3f d = spark.dir();
            Vec3 vel = new Vec3(d.x, d.y, d.z).scale(speed);

            // 尾を持つ火花(trailScale > 0)は、通常より長生きさせて尾が伸びる時間を稼ぐ。
            // trailScaleが大きいほど長生き：柳(3.2〜7.2)は約4.6〜6.5秒、
            // 菊(1.0〜2.0)は約3.5〜4.0秒と、柳ほど長くならず短めの尾で済む。
            float maxLife = spark.trailScale() > 0.001f
                    ? Math.min(WILLOW_MAX_LIFE, 3.0f + spark.trailScale() * 0.5f)
                    : SPARK_LIFETIME;

            sparks.add(new SparkParticle(apexPos, vel, gradientStops, maxLife, spark.trailScale()));
        }
    }

    /**
     * グラデーション段のリストを、実際のRGB値の配列に解決する。
     * 戻り値は [段0.color1, 段0.color2, 段1.color1, 段1.color2, ...] の順（長さ = 段数 * 2）。
     */
    private static int[] resolveGradientStops(List<ColorGradient> gradients) {
        int n = Math.max(1, gradients.size());
        int[] stops = new int[n * 2];
        for (int i = 0; i < n; i++) {
            ColorGradient g = i < gradients.size() ? gradients.get(i) : new ColorGradient(0, 0);
            stops[i * 2] = ColorPresets.get(g.color1);
            stops[i * 2 + 1] = ColorPresets.get(g.color2);
        }
        return stops;
    }

    private static int lerpRgb(int c0, int c1, float f) {
        int r = (int) (((c0 >> 16) & 0xFF) * (1 - f) + ((c1 >> 16) & 0xFF) * f);
        int g = (int) (((c0 >> 8) & 0xFF) * (1 - f) + ((c1 >> 8) & 0xFF) * f);
        int b = (int) ((c0 & 0xFF) * (1 - f) + (c1 & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** 個々の火花（爆発後、重力で落ちながら消えていく） */
    public static class SparkParticle {
        public Vec3 pos;
        public Vec3 vel;
        /**
         * グラデーション各段のRGBを解決済みの配列（[段0.c1, 段0.c2, 段1.c1, 段1.c2, ...]）。
         * 全ての火花の寿命(maxLife)が終わるまでの間に、この配列の段数ぶんだけ均等な区間に分けて
         * 順番に color1→color2 を辿っていく＝「花火の寿命とグラデーションの数」から自動でタイミングが決まる。
         */
        private final int[] gradientStops;
        /** 現在の経過時間(age)に応じて算出された、今この瞬間の色。毎tick更新される。 */
        public int color;
        public float life;
        public final float maxLife;
        /** 0なら尾なし。柳などデザイン側で指定された「尾の長さ」の倍率。 */
        public final float trailScale;
        /** 発生地点（頂点付近）に固定。曲がった尾を再現するための軌道再計算の起点にする。 */
        public final Vec3 trailOrigin;
        /** 発生した瞬間の速度。posの実際の軌道（カーブ）を再現するため、レンダラー側で使う。 */
        public final Vec3 initialVel;
        /** 発生してからの経過時間（秒）。軌道再計算の「どこまで進んだか」や色の切り替えタイミングに使う。 */
        public float age = 0f;

        public SparkParticle(Vec3 pos, Vec3 vel, int[] gradientStops, float maxLife, float trailScale) {
            this.pos = pos;
            this.vel = vel;
            this.gradientStops = gradientStops;
            this.life = maxLife;
            this.maxLife = maxLife;
            this.trailScale = trailScale;
            this.trailOrigin = pos;
            this.initialVel = vel;
            this.color = resolveColorAt(0f);
        }

        public void tick(float dt) {
            pos = pos.add(vel.scale(dt));
            // 重力＋空気抵抗。だんだん減速しながら落下する（＝最後は下に落ちる火花）
            vel = vel.scale(0.96).subtract(0, 9.0 * dt, 0);
            life -= dt;
            age += dt;
            color = resolveColorAt(maxLife > 1.0E-4f ? age / maxLife : 1f);
        }

        /**
         * 経過時間の割合(0〜1)から、その瞬間の色を算出する。
         * 段数(gradientStops.length/2)ぶんに0〜1を均等分割し、どの段の中のどの位置にいるかを求めて
         * その段のcolor1→color2をなめらかに補間する。段をまたぐ瞬間は次の段のcolor1へパッと切り替わる。
         */
        private int resolveColorAt(float t) {
            int segments = gradientStops.length / 2;
            if (segments <= 0) return 0xFFFFFF;
            t = Mth.clamp(t, 0f, 1f);
            float scaled = t * segments;
            int seg = Math.min(segments - 1, (int) scaled);
            float localT = scaled - seg;
            int c0 = gradientStops[seg * 2];
            int c1 = gradientStops[seg * 2 + 1];
            return lerpRgb(c0, c1, localT);
        }

        public float alpha() {
            return Math.max(0f, Math.min(1f, life / maxLife));
        }
    }
}