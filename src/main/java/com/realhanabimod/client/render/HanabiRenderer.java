package com.realhanabimod.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.realhanabimod.RealHanabiMod;
import com.realhanabimod.data.ColorPresets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * 花火を実際に画面へ描画するレンダラー。
 * ・チャンク（描画距離）を無視し、どれだけ遠くても見える。
 * ・壁（ブロック）越しには、GPUの深度テスト（本物のピクセル単位の判定）でちゃんと隠れる。
 *   ブロックの隙間から一部だけ見える、といった現実の花火と同じ見え方になる
 *   （以前は花火1発ごとに代表点1つだけをレイキャストして丸ごと表示/非表示を切り替えていたため、
 *   遮蔽物に入った瞬間に爆発全体が"パッ"と消えてしまっていた。今は深度テストに任せている）。
 * ・雲には隠れない…と言いたいところだが、これは現状「描画順を雲より後ろにずらす」ことで対応しようとして
 *   実験的に AFTER_WEATHER ステージに変更したところ、そのステージでは PoseStack の状態が不安定なのか、
 *   カメラの向きによって花火の位置がズレてしまう別の不具合が発生することが分かったため、いったん
 *   AFTER_PARTICLES に戻している（＝位置の正しさを最優先）。雲に隠れる問題への対応は保留中。
 * ・影MOD(Iris/OptiFine系シェーダーパック)対応のため POSITION_COLOR_TEX_LIGHTMAP フォーマット＋
 *   ライトマップ付きシェーダーを使用し、uv2 を常にフルブライトに固定して発光表現する。
 * ・上昇中の玉は頂点到達後 FUSE フェーズに入り、光が消えてから一呼吸おいて爆発する。
 * ・上昇中の玉の尾、および柳(willow)など火花の「尾」は、現実の長時間露光写真のように
 *   減速しながら下から徐々にほどけていく見た目にするため、透明度のグラデーションではなく
 *   進行度に応じたテクスチャの切り替え（firework1.png〜firework5.png）で表現している
 *   （詳細は TrailBufferBatch / drawSparkTrailCurve のコメント参照）。
 */
public class HanabiRenderer {

    private static final ResourceLocation HANABI_TEXTURE =
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/hanabi.png");

    // --- 各種「尾」共通：進行度に応じて切り替える5段階のほどけテクスチャ ---
    // firework1(まだ四角い=ほどけていない) → firework5(ほぼ消えかけ=ほとんどほどけきった) の順。
    // 上昇中の玉の尾と、爆発後の火花(柳など)の尾の両方で共有して使う。
    private static final ResourceLocation[] TRAIL_UNRAVEL_TEXTURES = new ResourceLocation[] {
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/firework1.png"),
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/firework2.png"),
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/firework3.png"),
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/firework4.png"),
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/firework5.png"),
    };

    // 各テクスチャが担当する進行度(0.0〜1.0)の上限。
    // 例: 進行度0.30なら「0.40以下」に最初に該当するfirework1.pngが選ばれる。
    private static final float[] TRAIL_UNRAVEL_THRESHOLDS = {
            0.40f, 0.55f, 0.70f, 0.85f, 1.00f
    };

    // 尾専用バッファの初期容量(バイト)。花火が多いフレームでも毎フレーム再確保(grow)が
    // 何度も走らないよう、余裕を持ったサイズにしておく（足りない場合は自動で伸びる）。
    private static final int TRAIL_BUFFER_INITIAL_CAPACITY = 262144; // 256KB

    private static final float BALL_SIZE = 0.75f;
    private static final float TRAIL_WIDTH = 0.42f;

    // --- 上昇中の玉の「尾」を何分割してテクスチャを切り替えるか ---
    // 直線・カーブ共通で、テクスチャの段数(5)にきれいに対応するよう6分割にしている。
    private static final int BALL_TRAIL_UNRAVEL_SEGMENTS = 6;

    // --- カーブ花火の玉の「尾」用（軌道に沿ったリボンで描く） ---
    // 直線の花火は今まで通り軽量な分割リボンだけで済むが、カーブ花火は玉の実際の曲線軌道を
    // なめらかに再現するため、こちらは曲がり具合の精度を優先してセグメント数を多めにしている。
    private static final int ASCEND_TRAIL_SEGMENTS = 8;

    // --- 柳(willow)など「尾」を持つ火花用 ---
    private static final float SPARK_TRAIL_WIDTH = 0.10f;
    // 曲がった尾を再現するために、実際の軌道(発生時の速度＋重力)を経過時間ぶん再計算して
    // つなぎ合わせる分割数。多いほど滑らかにカーブするが負荷も増える。
    // ※この分割数は「テクスチャの切り替わり段階」の粒度も兼ねている
    //   （TRAIL_UNRAVEL_THRESHOLDSが5段階なので、6分割だとほぼ1セグメントごとに
    //   1段階ずつテクスチャが進んでいく自然な見た目になる）。
    private static final int SPARK_TRAIL_SEGMENTS = 6;
    // 上の再計算を安定させるための内部の細分ステップ数(1セグメントあたり)。
    private static final int SPARK_TRAIL_SUBSTEPS = 3;
    // 実際のSparkParticle.tick()と同じ物理定数（1tick=1/20秒あたりの減衰・重力）に合わせる。
    private static final float SPARK_TICK_DT = 1f / 20f;
    private static final float SPARK_DECAY_PER_TICK = 0.96f;
    private static final float SPARK_GRAVITY = 9.0f;
    // 実際の写真（長時間露光）は「動いた軌跡がまるごと写り込む」ため、瞬間的な移動量より
    //   ずっと長く伸びて見える。ゲームはリアルタイム描画なので同じ効果を得るには、
    //   物理シミュレーション自体はそのまま(カーブの形はキープ)で、見た目の長さだけ
    //   発生点から実際より遠くまで引き伸ばす。これにより爆発直後から「もう糸を引いている」
    //   ように見え、長時間待たなくても十分に伸びた尾になる。
    //   trailScale(デザイン側で指定)に比例させることで、柳は大きく・菊は控えめに、と
    //   デザインごとに伸び具合を自然に変えられるようにしている。
    private static final float SPARK_TRAIL_STRETCH_PER_SCALE = 0.6f;

    private static final int SOFT_SPARK_LIMIT = 3500;
    private static final int HARD_SPARK_LIMIT = 7000;

    /** 常に最大光量 */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * 尾専用のバッファ本体（テクスチャ5段階分）。
     * <p>
     * 重要：これは絶対に毎フレーム new してはいけない。BufferBuilder は内部でオフヒープの
     * ByteBuffer を確保しており、フレームごとに使い捨てて作り直すと、解放が追いつかず
     * ネイティブメモリを圧迫し続け、最終的にクラッシュ(STATUS_STACK_BUFFER_OVERRUN等)の
     * 原因になる。Tesselator のシングルトンバッファと同じ発想で、ゲーム起動中ずっと
     * 同じインスタンスを使い回し、フレームごとには begin()/end() だけを呼ぶようにする。
     */
    private static final BufferBuilder[] TRAIL_BUFFERS = createTrailBuffers();

    private static BufferBuilder[] createTrailBuffers() {
        BufferBuilder[] buffers = new BufferBuilder[TRAIL_UNRAVEL_TEXTURES.length];
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = new BufferBuilder(TRAIL_BUFFER_INITIAL_CAPACITY);
        }
        return buffers;
    }

    /**
     * 尾の1点が「進行度(0.0〜1.0)」のうちどこにあたるかを受け取り、
     * それに対応する「ほどけテクスチャ」の配列インデックスを返す。
     * TRAIL_UNRAVEL_THRESHOLDS の各しきい値以下になる最初の段階を採用する。
     */
    private static int pickTrailTextureIndex(float progress) {
        for (int i = 0; i < TRAIL_UNRAVEL_THRESHOLDS.length; i++) {
            if (progress <= TRAIL_UNRAVEL_THRESHOLDS[i]) {
                return i;
            }
        }
        return TRAIL_UNRAVEL_TEXTURES.length - 1;
    }

    /**
     * 「尾」専用の描画バッチ。1回の BufferBuilder#begin〜#end では1枚のテクスチャしか
     * 使えないため、玉本体や火花本体を描く既存のメインバッファ(hanabi.png固定)とは別に、
     * 「ほどけ具合(進行度)」ごとに最大5本のバッファへ、該当する尾のquadだけを振り分けて
     * 溜めていく。フレームの最後にテクスチャを切り替えながらまとめて描画することで、
     * セグメント単位でテクスチャが変わる「ほどけていく尾」を実現する。
     * <p>
     * 実体である TRAIL_BUFFERS は static で使い回すため、このクラス自体は「今フレーム、
     * どの段階が実際に使われたか」を覚えておくだけの軽量なラッパーであり、
     * 毎フレーム new しても一切メモリ確保を伴わない。
     */
    private static final class TrailBufferBatch {
        private final boolean[] started = new boolean[TRAIL_BUFFERS.length];

        VertexConsumer get(int textureIndex) {
            if (!started[textureIndex]) {
                TRAIL_BUFFERS[textureIndex].begin(VertexFormat.Mode.QUADS,
                        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
                started[textureIndex] = true;
            }
            return TRAIL_BUFFERS[textureIndex];
        }

        void drawAll() {
            for (int i = 0; i < TRAIL_BUFFERS.length; i++) {
                if (!started[i]) continue;
                RenderSystem.setShaderTexture(0, TRAIL_UNRAVEL_TEXTURES[i]);
                BufferUploader.drawWithShader(TRAIL_BUFFERS[i].end());
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_WEATHER に変更して雲の後ろに回り込む対応を試したところ、そのステージでは
        // カメラの向きによって花火の位置がズレる不具合が出たため、位置の正しさを優先して
        // AFTER_PARTICLES に戻している（雲に隠れる問題への対応は保留中）。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        List<FireworkVisual> visuals = FireworkShowPlayer.getVisuals();
        if (visuals.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();
        Quaternionf camRot = new Quaternionf(event.getCamera().rotation());

        Vector3f camUp = new Vector3f(0, 1, 0).rotate(camRot);
        Vector3f camRight = new Vector3f(1, 0, 0).rotate(camRot);

        Vector3f viewDir = new Vector3f(0, 0, -1).rotate(camRot);
        Vector3f trailRight = new Vector3f();
        new Vector3f(0, 1, 0).cross(viewDir, trailRight);

        if (trailRight.lengthSquared() < 1.0E-6F) {
            trailRight.set(1, 0, 0);
        }
        trailRight.normalize();

        int totalSparks = 0;
        for (FireworkVisual v : visuals) {
            if (v.phase == FireworkVisual.Phase.EXPLODED) {
                totalSparks += v.sparks.size();
            }
        }

        int stride = totalSparks > HARD_SPARK_LIMIT ? 3 :
                totalSparks > SOFT_SPARK_LIMIT ? 2 : 1;

        float strideSizeBoost = (float) Math.sqrt(stride);

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        Matrix4f matrix = poseStack.last().pose();

        /* ========= 描画設定 ========= */

        RenderSystem.setShader(GameRenderer::getPositionColorTexLightmapShader);
        RenderSystem.setShaderTexture(0, HANABI_TEXTURE);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );

        RenderSystem.depthMask(false);
        // ブロックにはちゃんと隠れてほしい（＝深度テストは有効のまま）。以前はここを無効化して
        // 「雲に隠れて欠けて見える」問題を回避していたが、その副作用として壁越しでも常に手前に
        // 描画されてしまい、ブロックの陰に入った瞬間に爆発全体が"パッ"と消える不自然な見た目になっていた。
        // 雲の問題への対応(AFTER_WEATHERへの変更)は、カメラの向きで花火の位置がズレる別の不具合を
        // 引き起こしたため保留中。深度テストを有効にしたこと自体はこの不具合と無関係で、
        // 引き続きブロックとは正しく・部分的に重なり合うようにする。
        // (depthMaskはfalseのまま＝花火自身は深度を書き込まない。火花同士が重なっても
        //  お互いを隠し合わないようにするため)
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

        // 「尾」だけは進行度に応じてテクスチャが変わるため、メインバッファとは別に集計する。
        // (TrailBufferBatch自体は軽量な使い捨てラッパーで、実体のバッファはstaticで使い回す)
        TrailBufferBatch trailBatch = new TrailBufferBatch();

        for (FireworkVisual v : visuals) {

            if (!v.cachedVisible) continue;

            if (v.phase == FireworkVisual.Phase.ASCENDING || v.phase == FireworkVisual.Phase.FUSE) {

                // 頂点到達後(FUSEフェーズ)は光がすっと消え、爆発まで一呼吸置いて真っ暗になる。
                float glow = v.getBallGlow();
                if (glow <= 0.001f) continue;

                Vec3 ballPos = v.getCurrentBallPos();
                int color = ballColor(v);

                // --- 尾（Trail）の計算 ---
                float maxTrailLen = (float) Math.max(0.0, ballPos.y - v.launchPos.y);
                float tailScale = v.getTailScale(); // 先ほど追加した尾のスケールを取得

                // スケールを掛けることで、尾の下側が徐々に上に縮んでいく（短くなる）
                float currentTrailLen = maxTrailLen * tailScale;

                // 尾の長さがあり、かつスケールが0じゃない時だけ尾を描画
                if (currentTrailLen > 0.05f && tailScale > 0.001f) {
                    if (v.entry.curveEnabled) {
                        // カーブ花火は直線ではないので、専用の「軌道に沿ったリボン」描画に分岐する。
                        drawAscendTrailCurve(trailBatch, matrix, viewDir, v, color, glow, tailScale);
                    } else {
                        drawTrailQuad(trailBatch, matrix, trailRight,
                                ballPos.x, ballPos.y, ballPos.z,
                                ballPos.y - currentTrailLen, // 縮んだ尾の一番下のY座標
                                TRAIL_WIDTH,
                                color,
                                glow * tailScale); // 縮むと同時に透明度もスッと薄くする
                    }
                }

                // --- 玉（Ball）本体の描画 ---
                // tailOnly が有効な場合、玉本体（丸いスプライト）は描かず尾だけ見せる
                // （ballHidden や消える高さの判定は既に glow<=0 の時点でこのブロック自体に入らないため、ここでは考慮不要）。
                if (!v.entry.tailOnly) {
                    drawQuad(buffer, matrix,
                            camRight, camUp,
                            ballPos.x, ballPos.y, ballPos.z,
                            BALL_SIZE,
                            color,
                            glow);
                }

            } else if (v.phase == FireworkVisual.Phase.EXPLODED) {

                List<FireworkVisual.SparkParticle> sparks = v.sparks;

                for (int i = 0; i < sparks.size(); i += stride) {

                    FireworkVisual.SparkParticle sp = sparks.get(i);

                    float size =
                            0.28f * v.entry.size *
                                    strideSizeBoost *
                                    (0.5f + 0.5f * sp.alpha());

                    // --- 柳などの「尾」を先に描く（火花本体の下に敷く） ---
                    if (sp.trailScale > 0.001f) {
                        drawSparkTrailCurve(trailBatch, matrix, viewDir,
                                sp.trailOrigin, sp.initialVel, sp.age, sp.trailScale,
                                v.entry.size, sp.alpha(), sp.color);

                        // 柳は「発生点(天辺)側が明るく残り、垂れ下がった先端は暗く消える」見た目にしたいので、
                        // 先端(落下していく側)に別途明るい点を描かない。玉のハイライトは尾の上端だけで表現する。
                        continue;
                    }

                    drawQuad(buffer, matrix,
                            camRight, camUp,
                            sp.pos.x, sp.pos.y, sp.pos.z,
                            size,
                            sp.color,
                            sp.alpha());
                }
            }
        }

        // まずメインバッファ(玉本体・火花本体)を hanabi.png で描画。
        BufferUploader.drawWithShader(buffer.end());

        // 続けて、各種「尾」を進行度ごとのテクスチャに切り替えながら描画する。
        trailBatch.drawAll();

        /* ========= 後始末 ========= */

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest(); // 後続の描画に影響しないよう、通常状態(深度テスト有効)に戻す
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        poseStack.popPose();
    }

    /** 上昇中の玉の色。花火に設定された色(1色目)を使いつつ、発光している見た目を保つため少し白側に寄せる。 */
    private static int ballColor(FireworkVisual v) {
        int base = ColorPresets.get(v.entry.colors.get(0));
        float mixToWhite = 0.35f;
        int r = (int) (((base >> 16) & 0xFF) * (1 - mixToWhite) + 255 * mixToWhite);
        int g = (int) (((base >> 8) & 0xFF) * (1 - mixToWhite) + 255 * mixToWhite);
        int b = (int) ((base & 0xFF) * (1 - mixToWhite) + 255 * mixToWhite);
        return (r << 16) | (g << 8) | b;
    }

    private static void drawQuad(VertexConsumer buffer,
                                 Matrix4f matrix,
                                 Vector3f right,
                                 Vector3f up,
                                 double wx, double wy, double wz,
                                 float size,
                                 int rgb,
                                 float alpha) {

        float x = (float) wx;
        float y = (float) wy;
        float z = (float) wz;

        float r = ((rgb >> 16) & 255) / 255F;
        float g = ((rgb >> 8) & 255) / 255F;
        float b = (rgb & 255) / 255F;

        float rx = right.x * size;
        float ry = right.y * size;
        float rz = right.z * size;

        float ux = up.x * size;
        float uy = up.y * size;
        float uz = up.z * size;

        buffer.vertex(matrix, x - rx - ux, y - ry - uy, z - rz - uz)
                .color(r, g, b, alpha)
                .uv(0, 1)
                .uv2(FULL_BRIGHT)
                .endVertex();

        buffer.vertex(matrix, x + rx - ux, y + ry - uy, z + rz - uz)
                .color(r, g, b, alpha)
                .uv(1, 1)
                .uv2(FULL_BRIGHT)
                .endVertex();

        buffer.vertex(matrix, x + rx + ux, y + ry + uy, z + rz + uz)
                .color(r, g, b, alpha)
                .uv(1, 0)
                .uv2(FULL_BRIGHT)
                .endVertex();

        buffer.vertex(matrix, x - rx + ux, y - ry + uy, z - rz + uz)
                .color(r, g, b, alpha)
                .uv(0, 0)
                .uv2(FULL_BRIGHT)
                .endVertex();
    }

    /**
     * 上昇中(および導火線待ち)の玉の下に伸びる、直線の「尾」を描画する。
     * <p>
     * 従来は1枚のquadで「下=透明→上=不透明」というアルファのグラデーションを付けていたが、
     * 現実の花火は透明になって消えるのではなく減速しながら下からほどけていくように見えるため、
     * BALL_TRAIL_UNRAVEL_SEGMENTS 枚のリボンに分割し、玉に近い側(新しい・進行度0)から
     * 遠い側(古い・進行度1)にかけて firework1.png → firework5.png へと切り替える方式にした。
     * 透明度自体は glow(および呼び出し元で掛けられているtailScale) による一律フェードのみ。
     */
    private static void drawTrailQuad(TrailBufferBatch trailBatch,
                                      Matrix4f matrix,
                                      Vector3f right,
                                      double topX,
                                      double topY,
                                      double topZ,
                                      double bottomY,
                                      float halfWidth,
                                      int rgb,
                                      float glow) {

        float rx = right.x * halfWidth;
        float rz = right.z * halfWidth;

        float r = ((rgb >> 16) & 255) / 255F;
        float g = ((rgb >> 8) & 255) / 255F;
        float b = (rgb & 255) / 255F;

        float tx = (float) topX;
        float tz = (float) topZ;
        float topYf = (float) topY;
        float bottomYf = (float) bottomY;

        float quadAlpha = 0.85F * glow;

        int segments = BALL_TRAIL_UNRAVEL_SEGMENTS;

        for (int seg = 0; seg < segments; seg++) {
            // t0=0が玉のすぐ下(topY)、t1=1が尾の末端(bottomY)になるよう線形補間する。
            float t0 = seg / (float) segments;
            float t1 = (seg + 1) / (float) segments;

            float y0 = topYf + (bottomYf - topYf) * t0;
            float y1 = topYf + (bottomYf - topYf) * t1;

            // 玉に近い(t=0)ほど進行度は低い(=新しい=firework1)、遠い(t=1)ほど進行度は高い(=firework5)。
            float segProgress = (t0 + t1) * 0.5f;
            int textureIndex = pickTrailTextureIndex(segProgress);
            VertexConsumer buffer = trailBatch.get(textureIndex);

            buffer.vertex(matrix, tx - rx, y0, tz - rz)
                    .color(r, g, b, quadAlpha)
                    .uv(0, 1)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, tx + rx, y0, tz + rz)
                    .color(r, g, b, quadAlpha)
                    .uv(1, 1)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, tx + rx, y1, tz + rz)
                    .color(r, g, b, quadAlpha)
                    .uv(1, 0)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, tx - rx, y1, tz - rz)
                    .color(r, g, b, quadAlpha)
                    .uv(0, 0)
                    .uv2(FULL_BRIGHT)
                    .endVertex();
        }
    }

    /**
     * カーブする花火専用の「尾」の描画。玉の尾(drawTrailQuad)は直線の花火では常に真上→真下の
     * 固定直線で済むが、カーブ花火は玉自体が曲線を描いて動くため、それに沿ってリボンを
     * 描かないと発射地点から不自然に浮いた/ズレた尾になってしまう。
     * <p>
     * ここでは v.getBallPosAtTime(t) （玉の実際の軌道計算そのもの）を複数時刻でサンプリングし、
     * 得られた点列を drawSparkTrailCurve と同じ考え方のbillboardリボンでつなぐ。こうすることで
     * 「玉が今まさに通ってきた道のり」を毎フレーム正確に再構築でき、途中経過を保存しておく必要もない。
     * 直線の花火はこの処理を通らないため、従来通り軽量な分割リボンのままである。
     * <p>
     * 透明度のグラデーションは廃止し、drawTrailQuadと同じくテクスチャ切り替え方式にしている。
     * サンプリングは startTime(古い・発射地点寄り) → endTime(新しい・玉の現在地) の順で
     * 生成されるため、進行度は「新しいほど低い(firework1)」「古いほど高い(firework5)」になるよう
     * インデックスを反転させて計算する。
     */
    private static void drawAscendTrailCurve(TrailBufferBatch trailBatch,
                                             Matrix4f matrix,
                                             Vector3f viewDir,
                                             FireworkVisual v,
                                             int rgb,
                                             float glow,
                                             float tailScale) {

        float endTime = v.ascendTimer;
        // getTailScale()と同じ「終盤ほど尾の付け根(発射地点側)から短くなっていく」見た目にするため、
        // サンプリングする時間の開始点を tailScale に応じて現在時刻側へ寄せていく。
        float startTime = endTime * (1.0f - tailScale);
        if (endTime - startTime < 1.0E-3f) return;

        float r = ((rgb >> 16) & 255) / 255F;
        float g = ((rgb >> 8) & 255) / 255F;
        float b = (rgb & 255) / 255F;

        float halfWidth = TRAIL_WIDTH;
        float quadAlpha = 0.85F * glow;

        int segments = ASCEND_TRAIL_SEGMENTS;
        Vec3[] points = new Vec3[segments + 1];
        for (int i = 0; i <= segments; i++) {
            float t = startTime + (endTime - startTime) * i / segments;
            points[i] = v.getBallPosAtTime(t);
        }

        for (int seg = 0; seg < segments; seg++) {
            Vec3 p0 = points[seg];
            Vec3 p1 = points[seg + 1];
            Vec3 diff = p1.subtract(p0);
            double segLen = diff.length();
            if (segLen < 1.0E-5) continue;

            Vector3f dir = new Vector3f((float) (diff.x / segLen), (float) (diff.y / segLen), (float) (diff.z / segLen));

            Vector3f right = new Vector3f();
            dir.cross(viewDir, right);
            if (right.lengthSquared() < 1.0E-6F) {
                new Vector3f(0, 1, 0).cross(dir, right);
                if (right.lengthSquared() < 1.0E-6F) right.set(1, 0, 0);
            }
            right.normalize();

            float rx = right.x * halfWidth;
            float ry = right.y * halfWidth;
            float rz = right.z * halfWidth;

            float x0 = (float) p0.x, y0 = (float) p0.y, z0 = (float) p0.z;
            float x1 = (float) p1.x, y1 = (float) p1.y, z1 = (float) p1.z;

            // points[0]=startTime(発射地点寄り=古い)、points[segments]=endTime(玉の現在地=新しい)
            // なので、進行度は「新しいほど低い」ようにインデックスを反転させて求める。
            float segMidFraction = (seg + 0.5f) / segments; // 0=古い側 → 1=新しい側
            float segProgress = 1.0f - segMidFraction;       // 0=新しい(firework1) → 1=古い(firework5)
            int textureIndex = pickTrailTextureIndex(segProgress);
            VertexConsumer buffer = trailBatch.get(textureIndex);

            buffer.vertex(matrix, x0 - rx, y0 - ry, z0 - rz)
                    .color(r, g, b, quadAlpha).uv(0, 1).uv2(FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x0 + rx, y0 + ry, z0 + rz)
                    .color(r, g, b, quadAlpha).uv(1, 1).uv2(FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x1 + rx, y1 + ry, z1 + rz)
                    .color(r, g, b, quadAlpha).uv(1, 0).uv2(FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x1 - rx, y1 - ry, z1 - rz)
                    .color(r, g, b, quadAlpha).uv(0, 0).uv2(FULL_BRIGHT).endVertex();
        }
    }

    /**
     * 個々の火花（SparkParticle）の「尾」を、実際の落下軌道に沿った曲線（複数セグメントのリボン）として描く。
     * <p>
     * 現実の花火の尾（長時間露光で写る帯）は、下から透明になって消えるのではなく、
     * 速度が落ちながら「下からほどけていく」ように見える。速度が落ちる処理そのものは
     * 既存の軌道再シミュレーション（減衰＋重力）でカバーできているため、ここでは
     * 従来やっていた「原点=不透明→先端=透明」というアルファのグラデーションを廃止し、
     * 代わりに各セグメント(=軌道サンプル点をつないだ1枚のリボン片)ごとに、
     * 「原点から先端までのうち何%地点か(進行度)」を計算して、その進行度に対応する
     * ほどけテクスチャ(firework1.png〜firework5.png)へ描画先を振り分ける方式に変更している。
     * <p>
     * これにより、同じ火花の尾でもカーブに沿って何枚ものquadが並び、生成順(＝時間順)に
     * 沿って手前(原点寄り)からfirework1→2→3→4→5と、テクスチャそのものが切り替わっていく。
     * 個々のquadが使うテクスチャが異なるため、通常のメインバッファには混ぜられず、
     * 呼び出し元から渡される TrailBufferBatch 経由でテクスチャ別のバッファに振り分けて積む。
     */
    private static void drawSparkTrailCurve(TrailBufferBatch trailBatch,
                                            Matrix4f matrix,
                                            Vector3f viewDir,
                                            Vec3 origin,
                                            Vec3 initialVel,
                                            float age,
                                            float trailScale,
                                            float entrySize,
                                            float alpha,
                                            int rgb) {

        if (age <= 1.0E-3f) return;

        float r = ((rgb >> 16) & 255) / 255F;
        float g = ((rgb >> 8) & 255) / 255F;
        float b = (rgb & 255) / 255F;

        // 「ほどけ具合」はテクスチャそのものが表現するため、透明度は火花自身の寿命フェード(alpha)を
        // そのまま尾全体に一律で適用するだけにする（原点/先端間の人為的なグラデーションは行わない）。
        // ただし、多数の火花のtrailOriginがほぼ同じ点に重なる原点付近は、加算合成によって
        // 自然に明るいコア(白飛び)として浮かび上がる。
        float quadAlpha = 0.9F * alpha;
        float halfWidth = SPARK_TRAIL_WIDTH * entrySize;

        // --- 発生時の速度から、経過時間(age)ぶんの軌道を細かく再シミュレートしてサンプル点を作る ---
        int segments = SPARK_TRAIL_SEGMENTS;
        Vec3[] points = new Vec3[segments + 1];
        points[0] = origin;

        Vec3 simPos = origin;
        Vec3 simVel = initialVel;
        float segDt = age / segments;
        float miniDt = segDt / SPARK_TRAIL_SUBSTEPS;
        // SparkParticle.tick()の「1tickあたり0.96倍」を、より細かい/粗いステップ幅でも
        // 同じ減衰の効き方になるよう指数変換しておく。
        float miniDecay = (float) Math.pow(SPARK_DECAY_PER_TICK, miniDt / SPARK_TICK_DT);

        for (int seg = 1; seg <= segments; seg++) {
            for (int sub = 0; sub < SPARK_TRAIL_SUBSTEPS; sub++) {
                simVel = simVel.scale(miniDecay).subtract(0, SPARK_GRAVITY * miniDt, 0);
                simPos = simPos.add(simVel.scale(miniDt));
            }
            // 発生点からの実際の変位ベクトルを、見た目だけ引き伸ばす。デザインのtrailScaleに
            // 比例させ、柳は大きく・菊は控えめに伸びるようにする。カーブの向き・曲がり方は保ったまま、
            // 長さだけ誇張することで長時間露光の写真のような「爆発直後からすでに尾を引いている」印象を再現する。
            float stretch = trailScale * SPARK_TRAIL_STRETCH_PER_SCALE;
            Vec3 offset = simPos.subtract(origin).scale(stretch);
            points[seg] = origin.add(offset);
        }

        // --- サンプル点同士を、カメラ方向を向く帯(billboardストリップ)でつなぐ ---
        for (int seg = 0; seg < segments; seg++) {
            Vec3 p0 = points[seg];
            Vec3 p1 = points[seg + 1];
            Vec3 diff = p1.subtract(p0);
            double segLen = diff.length();
            if (segLen < 1.0E-5) continue;

            Vector3f dir = new Vector3f((float) (diff.x / segLen), (float) (diff.y / segLen), (float) (diff.z / segLen));

            Vector3f right = new Vector3f();
            dir.cross(viewDir, right);
            if (right.lengthSquared() < 1.0E-6F) {
                new Vector3f(0, 1, 0).cross(dir, right);
                if (right.lengthSquared() < 1.0E-6F) right.set(1, 0, 0);
            }
            right.normalize();

            float rx = right.x * halfWidth;
            float ry = right.y * halfWidth;
            float rz = right.z * halfWidth;

            float x0 = (float) p0.x, y0 = (float) p0.y, z0 = (float) p0.z;
            float x1 = (float) p1.x, y1 = (float) p1.y, z1 = (float) p1.z;

            // このセグメントが、原点(0%)から先端(100%)までのうち何%地点にあたるかを求め、
            // 対応するほどけテクスチャを選ぶ。中点(seg+0.5)/segmentsを使うことで、
            // セグメントの前後どちらかに極端に寄らない、素直な段階分けになる。
            // 原点側(seg=0)ほど新しい(=firework1)、先端側(seg=segments-1)ほど古い(=firework5)。
            float segProgress = (seg + 0.5f) / segments;
            int textureIndex = pickTrailTextureIndex(segProgress);
            VertexConsumer buffer = trailBatch.get(textureIndex);

            buffer.vertex(matrix, x0 - rx, y0 - ry, z0 - rz)
                    .color(r, g, b, quadAlpha)
                    .uv(0, 1)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, x0 + rx, y0 + ry, z0 + rz)
                    .color(r, g, b, quadAlpha)
                    .uv(1, 1)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, x1 + rx, y1 + ry, z1 + rz)
                    .color(r, g, b, quadAlpha)
                    .uv(1, 0)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, x1 - rx, y1 - ry, z1 - rz)
                    .color(r, g, b, quadAlpha)
                    .uv(0, 0)
                    .uv2(FULL_BRIGHT)
                    .endVertex();
        }
    }
}