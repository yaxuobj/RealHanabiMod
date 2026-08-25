package com.realhanabimod.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.realhanabimod.RealHanabiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * 超軽量・半透明でリアルな煙を描画する専用レンダラー。
 * ・ブロックにはGPUの深度テストで正しく（部分的に）隠れる。
 * ・雲には隠れない…とするため一時的に描画タイミングをAFTER_WEATHERへ変更したが、その結果
 *   カメラの向きによって位置がズレる不具合が出たため、位置の正しさを優先していったん
 *   AFTER_PARTICLES に戻している（雲に隠れる問題への対応は保留中。HanabiRendererと同じ理由）。
 */
@Mod.EventBusSubscriber(modid = RealHanabiMod.MOD_ID, value = Dist.CLIENT)
public class SmokeRenderer {

    private static final ResourceLocation SMOKE_TEXTURE =
            new ResourceLocation(RealHanabiMod.MOD_ID, "textures/particle/smoke.png");

    // --- 上昇中の玉に付ける煙用 ---
    // 火花の「尾」と同じく、軽量化のため粒子ではなく1本のY方向に伸びたビルボード帯(quad)1枚だけで表現する。
    private static final float ASCEND_SMOKE_WIDTH_BASE = 0.9f; // 玉の煙の帯の基本幅（entry.size倍率される）
    private static final float ASCEND_SMOKE_MAX_LEN = 40f;     // 高く打ち上げた場合に帯が伸びすぎないための上限
    private static final float ASCEND_SMOKE_MAX_ALPHA = 0.32f; // 煙は光る尾と違い控えめな不透明度に留める
    private static final int ASCEND_SMOKE_RGB = 0xE0E0E0;      // 爆発煙と同じ、柔らかい明るめのグレー

    // --- カーブ花火の煙用（軌道に沿ったリボンで描く） ---
    // 直線の花火は今まで通り1枚のquadだけで済むので軽量。カーブ花火だけ、この分割数でリボンをつなぐ。
    private static final int ASCEND_SMOKE_SEGMENTS = 8;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_WEATHER に変更して雲の後ろに回り込む対応を試したところ、そのステージでは
        // カメラの向きによって煙の位置がズレる不具合が出たため、位置の正しさを優先して
        // AFTER_PARTICLES に戻している（HanabiRendererと同じ理由。雲に隠れる問題への対応は保留中）。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        List<FireworkVisual> visuals = FireworkShowPlayer.getVisuals();
        if (visuals == null || visuals.isEmpty()) return;

        boolean hasTarget = false;
        for (FireworkVisual v : visuals) {
            if (v == null || !v.cachedVisible) continue;
            if (v.phase == FireworkVisual.Phase.ASCENDING) {
                hasTarget = true;
                break;
            }
            if (v.phase == FireworkVisual.Phase.EXPLODED && v.sparks != null && !v.sparks.isEmpty()) {
                hasTarget = true;
                break;
            }
        }
        if (!hasTarget) return;

        Vec3 camPos = event.getCamera().getPosition();
        Quaternionf camRot = new Quaternionf(event.getCamera().rotation());

        Vector3f camUp = new Vector3f(0, 1, 0).rotate(camRot);
        Vector3f camRight = new Vector3f(1, 0, 0).rotate(camRot);

        // 上昇中の煙の帯(quad)はカメラに常に正面を向けるのではなく、火花の「尾」と同じく
        // 視線方向に対して水平に立つ帯として描く方が、Yに長く伸びた形として安定して見える。
        Vector3f viewDir = new Vector3f(0, 0, -1).rotate(camRot);
        Vector3f trailRight = new Vector3f();
        new Vector3f(0, 1, 0).cross(viewDir, trailRight);
        if (trailRight.lengthSquared() < 1.0E-6F) {
            trailRight.set(1, 0, 0);
        }
        trailRight.normalize();

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = poseStack.last().pose();

        /* ========= 描画設定 ========= */
        RenderSystem.setShader(GameRenderer::getPositionColorTexLightmapShader);
        RenderSystem.setShaderTexture(0, SMOKE_TEXTURE);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

        for (FireworkVisual v : visuals) {
            if (v == null || !v.cachedVisible) continue;

            if (v.phase == FireworkVisual.Phase.ASCENDING) {
                // 玉が非表示設定(ballHidden)、または消える高さを超えている間は、光る玉・尾だけでなく
                // 煙も一緒に非表示にする（実際の花火で、光が消えた場所からは煙も見えなくなるのが自然なため）。
                if (v.isAscendHidden()) continue;
                if (v.entry.curveEnabled) {
                    // カーブ花火は直線ではないので、専用の「軌道に沿ったリボン」描画に分岐する。
                    drawAscendSmokeTrailCurve(mc, buffer, matrix, viewDir, v);
                } else {
                    drawAscendSmokeTrail(mc, buffer, matrix, trailRight, v);
                }
                continue;
            }

            if (v.phase != FireworkVisual.Phase.EXPLODED || v.sparks == null || v.sparks.isEmpty()) continue;

            FireworkVisual.SparkParticle firstSpark = v.sparks.get(0);
            if (firstSpark == null) continue;

            // 進行度 (0.0 -> 1.0)
            float sparkAlpha = firstSpark.alpha();
            float progress = Math.max(0.0f, 1.0f - sparkAlpha);

            Vec3 center = v.getCurrentBallPos();
            if (center == null) continue;

            double windX = progress * 8.0;
            double windY = progress * 2.5;
            double windZ = progress * 1.0;

            double posX = center.x + windX;
            double posY = center.y + windY;
            double posZ = center.z + windZ;

            float smokeSize = v.entry.size * (4.0f + (float) Math.sqrt(progress) * 4.0f);

            float alpha = (float) Math.sin(progress * Math.PI) * 1.0f;

            if (alpha <= 0.001f) continue;

            float angle = (Math.abs(v.hashCode()) % 360) * (float) (Math.PI / 180.0);
            float cosA = (float) Math.cos(angle);
            float sinA = (float) Math.sin(angle);

            Vector3f smokeRight = new Vector3f(camRight).mul(cosA).add(new Vector3f(camUp).mul(sinA));
            Vector3f smokeUp = new Vector3f(camRight).mul(-sinA).add(new Vector3f(camUp).mul(cosA));

            // ワールドの明るさを取得
            BlockPos bpos = BlockPos.containing(posX, posY, posZ);
            int lightmap = LevelRenderer.getLightColor(mc.level, bpos);

            // 柔らかい明るめのグレー（背景になじみやすい）
            int rgb = 0xE0E0E0;

            drawSmokeQuad(buffer, matrix, smokeRight, smokeUp, posX, posY, posZ, smokeSize, rgb, alpha, lightmap);
        }

        BufferUploader.drawWithShader(buffer.end());

        /* ========= 後始末 ========= */
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);

        poseStack.popPose();
    }

    /**
     * 上昇中の玉が引く煙を、粒子ではなく1枚のY方向に伸びた帯(quad)だけで表現する（軽量化のため）。
     * 火花の「尾」(HanabiRenderer.drawTrailQuad)と同じ考え方だが、こちらは玉の直下から発生源(発射地点)側に
     * 向かって伸びる、より太く・控えめな不透明度の帯にすることで「煙」らしい見た目にしている。
     * 玉に近いほど(まだ発生したて)薄く、下(古く溜まった煙)ほど濃く見えるようにグラデーションさせる。
     */
    private static void drawAscendSmokeTrail(Minecraft mc, VertexConsumer buffer, Matrix4f matrix,
                                             Vector3f right, FireworkVisual v) {
        // 尾(光)と同じく、上昇終盤(減速フェーズ)は徐々に短くなって消える
        float tailScale = v.getTailScale();
        if (tailScale <= 0.001f) return;

        Vec3 ballPos = v.getCurrentBallPos();
        if (ballPos == null) return;

        float maxLen = Math.min(ASCEND_SMOKE_MAX_LEN, (float) Math.max(0.0, ballPos.y - v.launchPos.y));
        float len = maxLen * tailScale;
        if (len <= 0.05f) return;

        float halfWidth = ASCEND_SMOKE_WIDTH_BASE * v.entry.size;

        float tx = (float) ballPos.x;
        float ty = (float) ballPos.y;
        float tz = (float) ballPos.z;
        float by = ty - len;

        float rx = right.x * halfWidth;
        float rz = right.z * halfWidth;

        int rgb = ASCEND_SMOKE_RGB;
        float r = ((rgb >> 16) & 255) / 255F;
        float g = ((rgb >> 8) & 255) / 255F;
        float b = (rgb & 255) / 255F;

        // 玉に近い側(発生したて)は薄く、下側(古く溜まった煙)ほど濃くする
        float alphaAtBall = ASCEND_SMOKE_MAX_ALPHA * 0.15f * tailScale;
        float alphaAtBase = ASCEND_SMOKE_MAX_ALPHA * tailScale;

        int lightmap = LevelRenderer.getLightColor(mc.level, BlockPos.containing(tx, ty, tz));

        buffer.vertex(matrix, tx - rx, by, tz - rz).color(r, g, b, alphaAtBase).uv(0, 1).uv2(lightmap).endVertex();
        buffer.vertex(matrix, tx + rx, by, tz + rz).color(r, g, b, alphaAtBase).uv(1, 1).uv2(lightmap).endVertex();
        buffer.vertex(matrix, tx + rx, ty, tz + rz).color(r, g, b, alphaAtBall).uv(1, 0).uv2(lightmap).endVertex();
        buffer.vertex(matrix, tx - rx, ty, tz - rz).color(r, g, b, alphaAtBall).uv(0, 0).uv2(lightmap).endVertex();
    }

    /**
     * カーブする花火専用の、上昇中の煙の描画。直線の花火の煙(drawAscendSmokeTrail)は玉の直下に
     * 真っすぐ伸びる1枚のquadだけで済むが、カーブ花火は玉自体が曲線を描いて動くため、それに沿って
     * リボンを描かないと発射地点から浮いた/ズレた煙になってしまう。
     * <p>
     * HanabiRenderer.drawAscendTrailCurve と同じ考え方で、v.getBallPosAtTime(t) を複数時刻で
     * サンプリングした点列を billboard リボンでつなぐ。発射地点側ほど濃く(古く溜まった煙)、
     * 玉に近い側ほど薄く(発生したて)なるグラデーションは直線版と同じ。
     */
    private static void drawAscendSmokeTrailCurve(Minecraft mc, VertexConsumer buffer, Matrix4f matrix,
                                                  Vector3f viewDir, FireworkVisual v) {
        float tailScale = v.getTailScale();
        if (tailScale <= 0.001f) return;

        float endTime = v.ascendTimer;
        // 尾(光)と同じ考え方で、上昇終盤は発射地点側から徐々に短くなって消える
        float startTime = endTime * (1.0f - tailScale);
        if (endTime - startTime < 1.0E-3f) return;

        float halfWidth = ASCEND_SMOKE_WIDTH_BASE * v.entry.size;

        int rgb = ASCEND_SMOKE_RGB;
        float r = ((rgb >> 16) & 255) / 255F;
        float g = ((rgb >> 8) & 255) / 255F;
        float b = (rgb & 255) / 255F;

        float alphaAtBall = ASCEND_SMOKE_MAX_ALPHA * 0.15f * tailScale;
        float alphaAtBase = ASCEND_SMOKE_MAX_ALPHA * tailScale;

        int segments = ASCEND_SMOKE_SEGMENTS;
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

            // seg=0(発射地点側、古い) ほど濃く、seg=segments(玉に近い、新しい) ほど薄くする
            float a0 = alphaAtBase + (alphaAtBall - alphaAtBase) * (seg / (float) segments);
            float a1 = alphaAtBase + (alphaAtBall - alphaAtBase) * ((seg + 1) / (float) segments);

            int lightmap0 = LevelRenderer.getLightColor(mc.level, BlockPos.containing(x0, y0, z0));
            int lightmap1 = LevelRenderer.getLightColor(mc.level, BlockPos.containing(x1, y1, z1));

            buffer.vertex(matrix, x0 - rx, y0 - ry, z0 - rz).color(r, g, b, a0).uv(0, 1).uv2(lightmap0).endVertex();
            buffer.vertex(matrix, x0 + rx, y0 + ry, z0 + rz).color(r, g, b, a0).uv(1, 1).uv2(lightmap0).endVertex();
            buffer.vertex(matrix, x1 + rx, y1 + ry, z1 + rz).color(r, g, b, a1).uv(1, 0).uv2(lightmap1).endVertex();
            buffer.vertex(matrix, x1 - rx, y1 - ry, z1 - rz).color(r, g, b, a1).uv(0, 0).uv2(lightmap1).endVertex();
        }
    }

    private static void drawSmokeQuad(VertexConsumer buffer, Matrix4f matrix,
                                      Vector3f right, Vector3f up,
                                      double wx, double wy, double wz,
                                      float size, int rgb, float alpha, int lightmap) {
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

        buffer.vertex(matrix, x - rx - ux, y - ry - uy, z - rz - uz).color(r, g, b, alpha).uv(0, 1).uv2(lightmap).endVertex();
        buffer.vertex(matrix, x + rx - ux, y + ry - uy, z + rz - uz).color(r, g, b, alpha).uv(1, 1).uv2(lightmap).endVertex();
        buffer.vertex(matrix, x + rx + ux, y + ry + uy, z + rz + uz).color(r, g, b, alpha).uv(1, 0).uv2(lightmap).endVertex();
        buffer.vertex(matrix, x - rx + ux, y - ry + uy, z - rz + uz).color(r, g, b, alpha).uv(0, 0).uv2(lightmap).endVertex();
    }
}