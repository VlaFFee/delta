package com.deltabattle.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Музыка боя. id - любой звук из sounds.json (свой: deltabattle:music.battle1 ... music.battle6,
 * положите .ogg в assets/deltabattle/sounds/music/battleN.ogg; или ванильный, например minecraft:music_disc.pigstep).
 */
public final class BattleMusic {
    private static LoopingSound cur;
    private static String curId = "";

    public static boolean isPlaying() { return cur != null; }

    public static void play(String id) {
        if (id.equals(curId) && cur != null) return;
        stop();
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return;
        cur = new LoopingSound(SoundEvent.createVariableRangeEvent(rl));
        curId = id;
        Minecraft.getInstance().getSoundManager().play(cur);
    }

    public static void stop() {
        if (cur != null) Minecraft.getInstance().getSoundManager().stop(cur);
        cur = null;
        curId = "";
    }

    public static void sfx(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(rl), 1.0f));
    }

    private static class LoopingSound extends AbstractSoundInstance implements TickableSoundInstance {
        LoopingSound(SoundEvent ev) {
            super(ev, SoundSource.MUSIC, RandomSource.create());
            looping = true; delay = 0; volume = 1f; relative = true; attenuation = SoundInstance.Attenuation.NONE;
        }
        @Override public boolean isStopped() { return false; }
        @Override public void tick() { }
    }
}
