package com.deltabattle.client;

import com.deltabattle.data.Encounter;
import com.deltabattle.data.Encounter.ModelDef;
import com.deltabattle.entity.BattleMobEntity;
import com.deltabattle.registry.ModEntities;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.joml.Quaternionf;

/** Рисует модели GeckoLib внутри экранов (бой, редактор): клиентская "кукла" вне мира + рендер как в инвентаре. */
public final class ModelView {
    /** Кукла для модели или null, если модель не задана / нет мира. */
    public static BattleMobEntity create(ModelDef m) {
        var level = Minecraft.getInstance().level;
        if (level == null || m == null || !m.isSet()) return null;
        BattleMobEntity e = new BattleMobEntity(ModEntities.BATTLE_MOB.get(), level);
        e.puppet = true;
        sync(e, m);
        return e;
    }

    /** Обновляет модель куклы, если ModelDef изменился (например, в редакторе). */
    public static void sync(BattleMobEntity e, ModelDef m) {
        String j = Encounter.GSON.toJson(m);
        if (!j.equals(e.getModelJson())) e.setModelJson(j);
    }

    /** x, y - точка под ногами; px - пикселей на блок; yaw - поворот в градусах (180 = лицом к зрителю). */
    public static void draw(GuiGraphics g, BattleMobEntity e, float x, float y, float px, float yaw) {
        e.yBodyRot = yaw; e.yBodyRotO = yaw; e.setYRot(yaw); e.yRotO = yaw; e.yHeadRot = yaw; e.yHeadRotO = yaw;
        e.setXRot(0f); e.xRotO = 0f;
        Quaternionf q = new Quaternionf().rotateZ((float) Math.PI);
        InventoryScreen.renderEntityInInventory(g, Math.round(x), Math.round(y), Math.max(1, Math.round(px)), q, null, e);
        RenderSystem.clear(256, Minecraft.ON_OSX);           // чтобы последующий GUI не прятался за глубиной модели
    }
}
