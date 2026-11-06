package com.deltabattle.entity;

import com.deltabattle.battle.Battle;
import com.deltabattle.battle.BattleManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import com.deltabattle.data.Encounter;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.minecraft.world.phys.Vec3;

/** Моб в мире, запускающий битву по id битвы из датапака. Текстура/размер синхронизируются с клиентом. */
public class BattleMobEntity extends PathfinderMob implements GeoEntity {
    private static final EntityDataAccessor<String> ENC = SynchedEntityData.defineId(BattleMobEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TEX = SynchedEntityData.defineId(BattleMobEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(BattleMobEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> MODEL = SynchedEntityData.defineId(BattleMobEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> HOP = SynchedEntityData.defineId(BattleMobEntity.class, EntityDataSerializers.BOOLEAN);

    private boolean inBattle;
    private int hopCooldown = 20, encounterCooldown;

    public BattleMobEntity(EntityType<? extends PathfinderMob> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 30).add(Attributes.MOVEMENT_SPEED, 0.2);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ENC, "");
        entityData.define(TEX, "");
        entityData.define(SCALE, 1f);
        entityData.define(HOP, true);
        entityData.define(MODEL, "");
    }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putString("Encounter", getEncounterId());
        t.putString("Tex", getTex());
        t.putFloat("Scale", getScale2());
        t.putBoolean("Hop", entityData.get(HOP));
        t.putString("Model", entityData.get(MODEL));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        if (t.contains("Encounter")) setEncounterId(t.getString("Encounter"));
        if (t.contains("Tex")) setTex(t.getString("Tex"));
        if (t.contains("Scale")) setScale2(t.getFloat("Scale"));
        if (t.contains("Hop")) entityData.set(HOP, t.getBoolean("Hop"));
        if (t.contains("Model")) entityData.set(MODEL, t.getString("Model"));
    }

    public String getEncounterId() { return entityData.get(ENC); }
    public void setEncounterId(String s) { entityData.set(ENC, s); }
    public String getTex() { return entityData.get(TEX); }
    public void setTex(String s) { entityData.set(TEX, s); }
    public float getScale2() { return entityData.get(SCALE); }
    public void setScale2(float f) { entityData.set(SCALE, f); }
    public void setHopping(boolean b) { entityData.set(HOP, b); }

    // ---------- модель GeckoLib ----------
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private String modelJson = null;
    private Encounter.ModelDef modelDef = new Encounter.ModelDef();
    /** Клиентская "кукла" для экрана боя/редактора: не в мире, анимируется по времени, состояние задаёт экран. */
    public boolean puppet;
    public String baseAnim = "", onceAnim = "";
    public boolean baseHold;
    public long onceUntil;

    public String getModelJson() { return entityData.get(MODEL); }

    public void setModelJson(String json) { entityData.set(MODEL, json == null ? "" : json); }

    /** Разобранная модель (кэшируется, пока не изменится синхронизируемая строка). */
    public Encounter.ModelDef model() {
        String j = entityData.get(MODEL);
        if (!j.equals(modelJson)) {
            modelJson = j;
            try {
                Encounter.ModelDef m = j.isEmpty() ? null : Encounter.GSON.fromJson(j, Encounter.ModelDef.class);
                modelDef = m == null ? new Encounter.ModelDef() : m;
            } catch (Exception e) { modelDef = new Encounter.ModelDef(); }
            modelDef.normalizePublic();
        }
        return modelDef;
    }

    /** Проиграть анимацию один раз (потом возврат к базовой). */
    public void playOnce(String anim, long ms) { if (anim != null && !anim.isEmpty()) { onceAnim = anim; onceUntil = System.nanoTime() + ms * 1_000_000L; } }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, this::animate));
    }

    private PlayState animate(AnimationState<BattleMobEntity> st) {
        Encounter.ModelDef m = model();
        String base;
        boolean hold = false;
        if (puppet) { base = baseAnim.isEmpty() ? m.anim_idle : baseAnim; hold = baseHold; }
        else base = st.isMoving() && !m.anim_walk.isEmpty() ? m.anim_walk : m.anim_idle;
        if (!onceAnim.isEmpty() && System.nanoTime() < onceUntil) {
            RawAnimation r = RawAnimation.begin().thenPlay(onceAnim);
            return st.setAndContinue(base.isEmpty() ? r : r.thenLoop(base));
        }
        if (base.isEmpty()) return PlayState.STOP;
        return st.setAndContinue(hold ? RawAnimation.begin().thenPlayAndHold(base) : RawAnimation.begin().thenLoop(base));
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    @Override public double getTick(Object o) { return puppet ? System.nanoTime() / 50_000_000.0 : tickCount; }

    public boolean isInBattle() { return inBattle; }

    public void setInBattle(boolean v) { inBattle = v; setNoAi(v); setInvulnerable(v); if (v) setDeltaMovement(Vec3.ZERO); }

    public void finishBattle(int result) {
        setInBattle(false);
        encounterCooldown = 200;
        if (!(level() instanceof ServerLevel sl)) return;
        if (result == Battle.RES_KILLED) { sl.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.5, getZ(), 20, 0.3, 0.3, 0.3, 0.02); discard(); }
        else if (result == Battle.RES_SPARED) { sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 0.6, getZ(), 12, 0.3, 0.3, 0.3, 0); discard(); }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || inBattle) return;
        if (encounterCooldown > 0) encounterCooldown--;
        if (entityData.get(HOP) && onGround() && --hopCooldown <= 0) {      // прыжки вместо ходьбы
            hopCooldown = 25 + random.nextInt(35);
            double a = random.nextDouble() * Math.PI * 2;
            setYRot((float) Math.toDegrees(a));
            setDeltaMovement(-Math.sin(a) * 0.22, 0.42, Math.cos(a) * 0.22);
            hasImpulse = true;
        }
        if (encounterCooldown == 0 && tickCount % 4 == 0) {
            Player p = level().getNearestPlayer(this, 1.8 * Math.max(1f, getScale2()));
            if (p instanceof ServerPlayer sp && !sp.isCreative() && !sp.isSpectator()) BattleManager.start(sp, this);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide && player instanceof ServerPlayer sp) BattleManager.start(sp, this);
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource src, float amount) {
        if (src.getEntity() instanceof ServerPlayer sp && !level().isClientSide) { BattleManager.start(sp, this); return false; }
        return inBattle ? false : super.hurt(src, amount);
    }

    @Override public boolean removeWhenFarAway(double d) { return !inBattle; }
}
