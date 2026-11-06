package com.deltabattle.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/** Фроггит: обычный BattleMobEntity с битвой "froggit" (data/deltabattle/encounters/froggit.json). */
public class FroggitEntity extends BattleMobEntity {
    public FroggitEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        if (!level.isClientSide) {
            setEncounterId("froggit");
            setTex("deltabattle:textures/entity/froggit.png");
        }
    }
}
