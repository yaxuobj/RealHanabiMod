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
 * ・壁（ブロック）越しには見えない（可視判定は FireworkShowPlayer が tick側で計算しキャッシュ済みのものを読むだけ＝軽量）。
 * ・影MOD(Iris/OptiFine系シェーダーパック)対応のため POSITION_COLOR_TEX_LIGHTMAP フォーマット＋
 *   ライトマップ付きシェーダーを使用し、uv2 を常にフルブライトに固定して発光表現する。
 * ・上昇中の玉は頂点到達後 FUSE フェーズに入り、光が消えてから一呼吸おいて爆発する。
 */
public class HanabiRenderer {

    private static final ResourceLocation HANABI_TEXTURE =
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/hanabi.png");

    private static final float BALL_SIZE = 1.55f;
    private static final float TRAIL_WIDTH = 0.42f;

    // --- カーブ花火の玉の「尾」用（軌道に沿ったリボンで描く） ---
    // 直線の花火は今まで通り1枚のquadだけで済むので軽量。カーブ花火だけ、この分割数でリボンをつなぐ。
    private static final int ASCEND_TRAIL_SEGMENTS = 8;

    // --- 柳(willow)など「尾」を持つ火花用 ---
    private static final float SPARK_TRAIL_WIDTH = 0.10f;
    // 曲がった尾を再現するために、実際の軌道(発生時の速度＋重力)を経過時間ぶん再計算して
    // つなぎ合わせる分割数。多いほど滑らかにカーブするが負荷も増える。
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

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
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
        // 雲（や遠くの地形）に隠れて花火が欠けて見えるのを防ぐため、深度テストを無効化する。
        //   花火は空高くで展開されるため、手前にある雲の深度値によって火花・尾が
        //   クリップされてしまうことがあった。深度テストを切ることで、常に手前(最前面)に
        //   描画されるようにする。花火自体は不透明な近距離オブジェクトを覆い隠すものではないため、
        //   見た目上の破綻は起きにくい。
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

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
                        drawAscendTrailCurve(buffer, matrix, viewDir, v, color, glow, tailScale);
                    } else {
                        drawTrailQuad(buffer, matrix, trailRight,
                                ballPos.x, ballPos.y, ballPos.z,
                                ballPos.y - currentTrailLen, // 縮んだ尾の一番下のY座標
                                TRAIL_WIDTH,
                                color,
                                glow * tailScale); // 縮むと同時に透明度もスッと薄くする
                    }
                }

                // --- 玉（Ball）本体の描画 ---
                drawQuad(buffer, matrix,
                        camRight, camUp,
                        ballPos.x, ballPos.y, ballPos.z,
                        BALL_SIZE,
                        color,
                        glow);

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
                        drawSparkTrailCurve(buffer, matrix, viewDir,
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

        BufferUploader.drawWithShader(buffer.end());

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
     * 上昇中(および導火線待ち)の玉の下に伸びる「尾」を1枚の板だけで描画する。
     * glow を掛けることで、光が消えていく演出(FUSEフェーズ)に合わせて尾も一緒にフェードする。
     */
    private static void drawTrailQuad(VertexConsumer buffer,
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
        float ty = (float) topY;
        float tz = (float) topZ;
        float by = (float) bottomY;

        float topAlpha = 0.85F * glow;

        buffer.vertex(matrix, tx - rx, by, tz - rz)
                .color(r, g, b, 0F)
                .uv(0, 1)
                .uv2(FULL_BRIGHT)
                .endVertex();

        buffer.vertex(matrix, tx + rx, by, tz + rz)
                .color(r, g, b, 0F)
                .uv(1, 1)
                .uv2(FULL_BRIGHT)
                .endVertex();

        buffer.vertex(matrix, tx + rx, ty, tz + rz)
                .color(r, g, b, topAlpha)
                .uv(1, 0)
                .uv2(FULL_BRIGHT)
                .endVertex();

        buffer.vertex(matrix, tx - rx, ty, tz - rz)
                .color(r, g, b, topAlpha)
                .uv(0, 0)
                .uv2(FULL_BRIGHT)
                .endVertex();
    }

    /**
     * カーブする花火専用の「尾」の描画。玉の尾(drawTrailQuad)は直線の花火では常に真上→真下の
     * 固定直線1枚で済むが、カーブ花火は玉自体が曲線を描いて動くため、それに沿ってリボンを
     * 描かないと発射地点から不自然に浮いた/ズレた尾になってしまう。
     * <p>
     * ここでは v.getBallPosAtTime(t) （玉の実際の軌道計算そのもの）を複数時刻でサンプリングし、
     * 得られた点列を drawSparkTrailCurve と同じ考え方のbillboardリボンでつなぐ。こうすることで
     * 「玉が今まさに通ってきた道のり」を毎フレーム正確に再構築でき、途中経過を保存しておく必要もない。
     * 直線の花火はこの処理を通らないため、従来通り軽量な1枚のquadのままである。
     */
    private static void drawAscendTrailCurve(VertexConsumer buffer,
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
        float topAlpha = 0.85F * glow;

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

            // 発射地点側(seg=0)は透明→玉に近い側(seg=segments)ほど不透明、に線形補間する
            // （直線版のdrawTrailQuadと同じ、下が透明・上が不透明というフェード方向）
            float a0 = topAlpha * (seg / (float) segments);
            float a1 = topAlpha * ((seg + 1) / (float) segments);

            buffer.vertex(matrix, x0 - rx, y0 - ry, z0 - rz)
                    .color(r, g, b, a0).uv(0, 1).uv2(FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x0 + rx, y0 + ry, z0 + rz)
                    .color(r, g, b, a0).uv(1, 1).uv2(FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x1 + rx, y1 + ry, z1 + rz)
                    .color(r, g, b, a1).uv(1, 0).uv2(FULL_BRIGHT).endVertex();
            buffer.vertex(matrix, x1 - rx, y1 - ry, z1 - rz)
                    .color(r, g, b, a1).uv(0, 0).uv2(FULL_BRIGHT).endVertex();
        }
    }

    /**
     * 個々の火花（SparkParticle）の「尾」を、実際の落下軌道に沿った曲線（複数セグメントのリボン）として描く。
     * 玉の尾(drawTrailQuad)は常に真上→真下の固定直線だったが、実際の柳花火の帯は、爆発直後は外側へ
     * 弧を描くように広がり、重力で徐々に真下方向へカーブしていく＝1本の直線では再現できない。
     * <p>
     * ここでは「発生時の速度(initialVel)」と「経過時間(age)」から、SparkParticle.tick()と同じ
     * 減衰＋重力の式を使って軌道を数点に区切って再計算し、その点同士を billboard の帯でつないで
     * カーブする尾を表現する。実際の頭の位置(pos)を直接使わないのは、こうすることで発生点から
     * 現在に至るまでの「通ってきた道のり全体」を毎フレーム再構築でき、途中を保存しておく必要がないため。
     */
    private static void drawSparkTrailCurve(VertexConsumer buffer,
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

        // 明暗を「発生点(天辺)側が明るく、垂れ下がった先端側が暗く消える」向きにする。
        //   天辺は複数の火花のtrailOriginがほぼ同じ点に重なるため、加算合成で明るいコア(白飛び)になり、
        //   そこから伸びる尾は下に行くほど暗くなって消える＝「天辺は残り、周りが垂れる」見た目になる。
        float originAlpha = 0.9F * alpha;
        float tipAlpha = 0.05F * alpha; // 完全な0ではなく、うっすら火の粉が見える程度に残す
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

            // セグメントごとに、天辺側(seg=0)は明るく→先端側(seg=segments-1)は暗く、線形に補間する
            float t0 = seg / (float) segments;
            float t1 = (seg + 1) / (float) segments;
            float a0 = originAlpha + (tipAlpha - originAlpha) * t0;
            float a1 = originAlpha + (tipAlpha - originAlpha) * t1;

            buffer.vertex(matrix, x0 - rx, y0 - ry, z0 - rz)
                    .color(r, g, b, a0)
                    .uv(0, 1)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, x0 + rx, y0 + ry, z0 + rz)
                    .color(r, g, b, a0)
                    .uv(1, 1)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, x1 + rx, y1 + ry, z1 + rz)
                    .color(r, g, b, a1)
                    .uv(1, 0)
                    .uv2(FULL_BRIGHT)
                    .endVertex();

            buffer.vertex(matrix, x1 - rx, y1 - ry, z1 - rz)
                    .color(r, g, b, a1)
                    .uv(0, 0)
                    .uv2(FULL_BRIGHT)
                    .endVertex();
        }
    }
}