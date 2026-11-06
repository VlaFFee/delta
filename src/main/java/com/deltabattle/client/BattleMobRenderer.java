package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.deltabattle.data.Encounter.ModelDef;
import com.deltabattle.entity.BattleMobEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.util.HashMap;
import java.util.Map;

/**
 * Если у моба задана модель GeckoLib - рисует её (анимации idle/walk из ModelDef).
 * Если модели нет - рисует прежний билборд со спрайтом (Фроггит и простые мобы).
 */
public class BattleMobRenderer extends GeoEntityRenderer<BattleMobEntity> {
    private static final ResourceLocation DEFAULT = new ResourceLocation(DeltaBattle.MODID, "textures/entity/froggit.png");
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    public BattleMobRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new DynamicGeoModel());
        this.shadowRadius = 0.35f;
    }

    @Override
    public void render(BattleMobEntity e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int light) {
        ModelDef m = e.model();
        if (m.isSet()) {
            float s = e.puppet ? 1f : Math.max(0.05f, m.scale * Math.max(0.2f, e.getScale2()));
            ps.pushPose();
            ps.scale(s, s, s);
            super.render(e, yaw, pt, ps, buf, light);
            ps.popPose();
            return;
        }
        billboard(e, pt, ps, buf, light);
    }

    // ---------- билборд-спрайт (без модели) ----------
    private ResourceLocation sprite(BattleMobEntity e) {
        String s = e.getTex();
        if (s == null || s.isEmpty()) return DEFAULT;
        return CACHE.computeIfAbsent(s, k -> { ResourceLocation r = ResourceLocation.tryParse(k); return r == null ? DEFAULT : r; });
    }

    private void billboard(BattleMobEntity e, float pt, PoseStack ps, MultiBufferSource buf, int light) {
        float vy = (float) e.getDeltaMovement().y;
        float stretch = Mth.clamp(vy * 0.9f, -0.25f, 0.3f);            // тянется вверх в прыжке, сплющивается при приземлении
        float sy = 1f + stretch, sx = 1f - stretch * 0.6f;
        float sink = e.isInBattle() ? (float) Math.sin((e.tickCount + pt) * 0.25f) * 0.03f : 0f;
        float sc = Math.max(0.2f, e.getScale2());
        ps.pushPose();
        ps.translate(0, sink, 0);
        ps.mulPose(this.entityRenderDispatcher.cameraOrientation());   // всегда лицом к камере
        ps.scale(sx * sc, sy * sc, 1f);
        PoseStack.Pose pose = ps.last();
        Matrix4f mt = pose.pose(); Matrix3f n = pose.normal();
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(sprite(e)));
        float hw = 0.5f, h = 1.0f;
        v(vc, mt, n, -hw, 0, 0, 1, light); v(vc, mt, n, hw, 0, 1, 1, light); v(vc, mt, n, hw, h, 1, 0, light); v(vc, mt, n, -hw, h, 0, 0, light);
        ps.popPose();
    }

    private static void v(VertexConsumer vc, Matrix4f m, Matrix3f n, float x, float y, float u, float v, int light) {
        vc.vertex(m, x, y, 0).color(255, 255, 255, 255).uv(u, v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
    }
}
