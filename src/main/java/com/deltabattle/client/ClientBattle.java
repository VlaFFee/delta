package com.deltabattle.client;

import com.deltabattle.battle.Battle;
import com.deltabattle.battle.Pattern;
import com.deltabattle.data.Encounter;
import com.deltabattle.net.S2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Клиентское состояние боя. Данные приходят от сервера, время фаз считается по nanoTime (плавно на любом FPS). */
public final class ClientBattle {
    public static final int[] COLORS = {0x00FFFF, 0xFF00FF, 0x00FF00, 0xFFFF00};   // Kris / Susie / Ralsei / 4-й
    public static ClientBattle cur;

    public static final class Popup { public float x, y; public String text; public int color; public long born; }

    public Encounter enc;
    public int id, myIdx, n, ne, phase, turn, boxW = 150, boxH = 150;
    public long seed;
    public String[] names = new String[0];
    public int[] hp = new int[4], max = new int[4], tp = new int[4];
    public int[] ehp = new int[3], emercy = new int[3], egone = new int[3];
    public float[] shownEhp = new float[3];
    public boolean[] ready = new boolean[4], down = new boolean[4];
    public float[] sx = new float[4], sy = new float[4], tx = new float[4], ty = new float[4];
    public long[] flashUntil = new long[4];
    public final float[] aimX = new float[Pattern.AIM_SLOTS], aimY = new float[Pattern.AIM_SLOTS];
    public Pattern.Active[] active = new Pattern.Active[0];
    public int[] activeEnemy = new int[0];
    public final Pattern.Frame frame = new Pattern.Frame();
    public final List<Popup> popups = new ArrayList<>();
    public long phaseStart = System.nanoTime(), shakeUntil;
    public int phaseLen;
    public String[] phaseTexts = new String[0];
    public int[] events = new int[0];
    public String[] eventText = new String[0];
    public int endResult = -1;
    public long endAt;

    public String phaseText() { return phaseTexts.length > 0 ? phaseTexts[0] : ""; }

    /** Время в тиках (дробное) с начала фазы. */
    public float phaseTicks() { return (System.nanoTime() - phaseStart) / 50_000_000f; }

    public boolean enemyAlive(int i) { return i < ne && egone[i] == 0; }

    public static void handle(S2C p) {
        Minecraft mc = Minecraft.getInstance();
        if (p.kind == S2C.START) {
            ClientBattle b = new ClientBattle();
            b.id = p.i[0]; b.n = p.i[1]; b.myIdx = p.i[2];
            b.names = Arrays.copyOfRange(p.s, 0, b.n);
            for (int k = 0; k < b.n; k++) { b.hp[k] = p.i[3 + k * 2]; b.max[k] = p.i[4 + k * 2]; }
            b.enc = Encounter.fromJson(p.s[b.n], "client");
            b.ne = b.enc.enemies.size();
            for (int k = 0; k < b.ne; k++) { b.ehp[k] = b.enc.enemies.get(k).hp; b.shownEhp[k] = b.ehp[k]; }
            Arrays.fill(b.sx, 75); Arrays.fill(b.sy, 75); Arrays.fill(b.tx, 75); Arrays.fill(b.ty, 75);
            Arrays.fill(b.aimX, Float.NaN); Arrays.fill(b.aimY, Float.NaN);
            cur = b;
            mc.setScreen(new BattleScreen(b));
            return;
        }
        ClientBattle b = cur;
        if (b == null) return;
        long now = System.nanoTime();
        switch (p.kind) {
            case S2C.PHASE -> {
                b.phase = p.i[0]; b.phaseLen = p.i[1]; b.turn = p.i[2]; b.boxW = p.i[3]; b.boxH = p.i[4];
                int na = p.i[5];
                b.active = new Pattern.Active[na];
                b.activeEnemy = new int[na];
                for (int k = 0; k < na; k++) {
                    b.activeEnemy[k] = p.i[6 + k * 3];
                    Encounter.EnemyDef e = b.enc.enemies.get(p.i[6 + k * 3]);
                    Encounter.AttackDef a = e.attacksFor(p.i[7 + k * 3]).get(p.i[8 + k * 3]);
                    b.active[k] = new Pattern.Active(a, 0);
                }
                b.seed = p.l; b.phaseTexts = p.s; b.phaseStart = now;
                if (b.phase == Battle.TALK) {
                    Arrays.fill(b.aimX, Float.NaN); Arrays.fill(b.aimY, Float.NaN);
                    for (int k = 0; k < 4; k++) { b.sx[k] = b.tx[k] = b.boxW / 2f; b.sy[k] = b.ty[k] = b.boxH / 2f; }
                }
                if (mc.screen instanceof BattleScreen s) s.onPhase();
            }
            case S2C.EVENTS -> { b.events = p.i; b.eventText = p.s; }
            case S2C.READY -> { int who = p.i[0]; if (who >= 0 && who < 4) b.ready[who] = true; }
            case S2C.STATE -> {
                int o = 0;
                int ne = p.i[o++];
                for (int k = 0; k < ne && k < 3; k++) { b.ehp[k] = p.i[o++]; b.emercy[k] = p.i[o++]; b.egone[k] = p.i[o++]; }
                int np = p.i[o++];
                for (int k = 0; k < np && k < 4; k++) { b.hp[k] = p.i[o++]; b.tp[k] = p.i[o++]; b.down[k] = b.hp[k] <= 0; }
                if (b.phase != Battle.EXECUTE) Arrays.fill(b.ready, false);
            }
            case S2C.AIM -> { int i = p.i[0]; if (i >= 0 && i < Pattern.AIM_SLOTS) { b.aimX[i] = p.f[0]; b.aimY[i] = p.f[1]; } }
            case S2C.HIT -> {
                int k = p.i[0]; b.hp[k] = p.i[1]; b.tp[k] = p.i[2]; b.down[k] = b.hp[k] <= 0;
                b.flashUntil[k] = now + 1_000_000_000L;
                if (k == b.myIdx) { b.shakeUntil = now + 250_000_000L; mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_HURT, 1.2f, 0.5f)); }
            }
            case S2C.SOULS -> {
                int cnt = p.i[0];
                for (int k = 0; k < cnt && k < 4; k++) { b.tx[k] = p.f[k * 2]; b.ty[k] = p.f[k * 2 + 1]; b.down[k] = p.i[k + 1] == 1; }
            }
            case S2C.END -> { b.endResult = p.i[0]; b.endAt = now + 2_200_000_000L; b.phase = 4; if (mc.screen instanceof BattleScreen s) s.onEnd(); }
            default -> {}
        }
    }
}
