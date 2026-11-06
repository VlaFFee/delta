package com.deltabattle.client;

import com.deltabattle.data.Encounter.ModelDef;
import com.deltabattle.entity.BattleMobEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/** Модель/анимации/текстура берутся из ModelDef моба (синхронизируется с сервера), а не из жёстко заданных путей. */
public class DynamicGeoModel extends GeoModel<BattleMobEntity> {
    @Override public ResourceLocation getModelResource(BattleMobEntity e) { return e.model().geoRL(); }
    @Override public ResourceLocation getTextureResource(BattleMobEntity e) { return e.model().texRL(); }
    @Override public ResourceLocation getAnimationResource(BattleMobEntity e) { return e.model().animRL(); }
}
