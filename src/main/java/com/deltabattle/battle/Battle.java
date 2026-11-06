package com.deltabattle.battle;

import com.deltabattle.data.Encounter;
import com.deltabattle.data.Encounter.AttackDef;
import com.deltabattle.data.Encounter.EnemyDef;
import com.deltabattle.data.Encounter.ItemDef;
import com.deltabattle.data.Encounter.MenuEntry;
import com.deltabattle.entity.BattleMobEntity;
import com.deltabattle.net.Net;
import com.deltabattle.net.S2C;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/** Серверная машина состояний одной битвы. Описание врагов/атак берётся из Encounter (датапак). */
public class Battle {
    public static final int MENU = 0, EXECUTE = 1, TALK = 2, ATTACK = 3;
    public static final int CMD_FIGHT = 0, CMD_ACT = 1, CMD_ITEM = 2, CMD_SPARE = 3, CMD_DEFEND = 4;
    public static final int EV_TEXT = 0, EV_DAMAGE = 1, EV_MISS = 2, EV_HEAL = 3, EV_DEFEND = 4, EV_SPARED = 5;
    public static final int RES_KILLED = 1, RES_SPARED = 2, RES_DEFEAT = 3, RES_ABORT = 4;
    public static final int GONE_KILLED = 1, GONE_SPARED = 2;
    static final int MENU_TIMEOUT = 20 * 45, IFRAMES = 20;

    public static class Member {
        public final ServerPlayer p;
        public int hp, max, tp = 0, cmd = -1, sub, target, acc, inv;
        public float x, y;
        public boolean defend;
        Member(ServerPlayer p, int hp) { this.p = p; this.hp = this.max = hp; }
        public boolean down() { return hp <= 0; }
    }

    public static class EState {
        public final EnemyDef def;
        public int hp, mercy, gone, phase = -1, cursor;
        String reply = "";
        EState(EnemyDef d) { def = d; hp = d.hp; }
        public boolean alive() { return gone == 0; }
    }

    public final int id;
    public final ServerLevel level;
    public final UUID mobId;                 // моб в мире (может быть null: бой запущен командой)
    public final Encounter enc;
    public final List<Member> members = new ArrayList<>();
    public final List<EState> enemies = new ArrayList<>();
    private final Random rnd = new Random();
    private final Pattern.Frame frame = new Pattern.Frame();
    private final float[] aimX = new float[Pattern.AIM_SLOTS], aimY = new float[Pattern.AIM_SLOTS];
    private Pattern.Active[] active = new Pattern.Active[0];
    private int[] atkRef = new int[0];       // тройки (враг, фаза, номер атаки) для клиента
    int phase, phaseTick, phaseLen, turn, result, boxW = 150, boxH = 150;
    boolean over;
    long seed;
    private String notice = "";
    private final boolean[] fired;
    private final boolean[] goneCue = new boolean[3];

    public Battle(int id, ServerLevel level, BattleMobEntity mob, Encounter enc, List<ServerPlayer> players) {
        this.id = id; this.level = level; this.enc = enc;
        this.mobId = mob == null ? null : mob.getUUID();
        for (ServerPlayer p : players) members.add(new Member(p, enc.party_hp));
        for (EnemyDef d : enc.enemies) enemies.add(new EState(d));
        Arrays.fill(aimX, Float.NaN);
        Arrays.fill(aimY, Float.NaN);
        fired = new boolean[enc.cues.size()];
        if (mob != null) mob.setInBattle(true);
        sendStart();
        if (!enc.music.isEmpty()) music(enc.music, 0);
        fireCues("start", -1, 0);
        enterMenu(null);
    }

    // ---------- музыка и реплики по триггерам ----------
    /** mode: 0 - играть по кругу, 1 - стоп, 2 - одиночный звук. */
    private void music(String id, int mode) { all(new S2C(S2C.MUSIC).ints(mode).strs(id)); }

    private void fireCues(String when, int enemy, int n) {
        for (int k = 0; k < enc.cues.size(); k++) {
            Encounter.Cue c = enc.cues.get(k);
            if (fired[k] || !c.when.equalsIgnoreCase(when)) continue;
            boolean ok = switch (when) {
                case "turn" -> c.n == n;
                case "hp_below" -> {
                    EState e = enemy >= 0 && enemy < enemies.size() ? enemies.get(enemy) : null;
                    yield e != null && e.alive() && (c.enemy < 0 || c.enemy == enemy) && 100f * e.hp / e.def.hp <= c.n;
                }
                case "enemy_killed", "enemy_spared" -> c.enemy < 0 || c.enemy == enemy;
                default -> true;     // start, player_down
            };
            if (!ok) continue;
            fired[k] = true;
            if (!c.text.isEmpty()) notice = c.text;
            if (!c.dialogue.isEmpty()) {
                EState t = c.enemy >= 0 && c.enemy < enemies.size() ? enemies.get(c.enemy) : enemy >= 0 && enemy < enemies.size() ? enemies.get(enemy) : null;
                if (t == null) { List<EState> al = alive(); if (!al.isEmpty()) t = al.get(0); }
                if (t != null && t.alive() && t.reply.isEmpty()) t.reply = c.dialogue;
            }
            if (c.music.equals("stop")) music("", 1); else if (!c.music.isEmpty()) music(c.music, 0);
            if (!c.sfx.isEmpty()) music(c.sfx, 2);
        }
    }

    // ---------- рассылка ----------
    private void all(S2C pkt) { for (Member m : members) Net.toPlayer(m.p, pkt); }

    private void sendStart() {
        int n = members.size();
        String json = enc.toJson();
        for (int mi = 0; mi < n; mi++) {
            int[] ints = new int[3 + n * 2];
            ints[0] = id; ints[1] = n; ints[2] = mi;
            String[] strs = new String[n + 1];
            for (int k = 0; k < n; k++) {
                ints[3 + k * 2] = members.get(k).hp; ints[4 + k * 2] = members.get(k).max;
                strs[k] = members.get(k).p.getGameProfile().getName();
            }
            strs[n] = json;
            Net.toPlayer(members.get(mi).p, new S2C(S2C.START).ints(ints).strs(strs));
        }
    }

    private void sendState() {
        int ne = enemies.size(), np = members.size();
        int[] v = new int[2 + ne * 3 + np * 2];
        int o = 0;
        v[o++] = ne;
        for (EState e : enemies) { v[o++] = e.hp; v[o++] = e.mercy; v[o++] = e.gone; }
        v[o++] = np;
        for (Member m : members) { v[o++] = m.hp; v[o++] = m.tp; }
        all(new S2C(S2C.STATE).ints(v));
    }

    private void setPhase(int ph, int len, String... texts) {
        phase = ph; phaseTick = 0; phaseLen = len;
        int na = atkRef.length / 3;
        int[] v = new int[6 + atkRef.length];
        v[0] = ph; v[1] = len; v[2] = turn; v[3] = boxW; v[4] = boxH; v[5] = na;
        System.arraycopy(atkRef, 0, v, 6, atkRef.length);
        all(new S2C(S2C.PHASE).ints(v).lng(seed).strs(texts));
    }

    // ---------- ввод ----------
    public void onChoice(ServerPlayer p, int cmd, int sub, int target, int acc) {
        Member m = get(p);
        if (m == null || phase != MENU || m.down() || m.cmd >= 0 || cmd < 0 || cmd > 4) return;
        m.cmd = cmd; m.sub = sub; m.target = target; m.acc = Math.max(0, Math.min(1000, acc));
        all(new S2C(S2C.READY).ints(members.indexOf(m)));
        if (allReady()) execute();
    }

    public void onSoul(ServerPlayer p, float x, float y) {
        Member m = get(p);
        if (m == null || phase != ATTACK || m.down()) return;
        x = Math.max(Pattern.SOUL_CLAMP, Math.min(boxW - Pattern.SOUL_CLAMP, x));
        y = Math.max(Pattern.SOUL_CLAMP, Math.min(boxH - Pattern.SOUL_CLAMP, y));
        if (Math.abs(x - m.x) < 10 && Math.abs(y - m.y) < 10) { m.x = x; m.y = y; } // защита от телепорта
    }

    public Member get(ServerPlayer p) { for (Member m : members) if (m.p == p) return m; return null; }
    private boolean allReady() { for (Member m : members) if (!m.down() && m.cmd < 0) return false; return true; }

    // ---------- вспомогательное ----------
    private EState targetOf(int t) {
        if (t >= 0 && t < enemies.size() && enemies.get(t).alive()) return enemies.get(t);
        for (EState e : enemies) if (e.alive()) return e;
        return null;
    }

    private List<EState> alive() {
        List<EState> l = new ArrayList<>();
        for (EState e : enemies) if (e.alive()) l.add(e);
        return l;
    }

    private boolean allGone() { for (EState e : enemies) if (e.alive()) return false; return true; }

    private void damageEnemy(EState e, int dmg) {
        e.hp -= dmg;
        if (e.hp <= 0) { e.hp = 0; e.gone = GONE_KILLED; }
    }

    private void healParty(int power) {
        for (Member o : members) o.hp = Math.min(o.max, (o.down() ? 0 : o.hp) + power);
    }

    private void updatePhases() {
        for (EState e : enemies) {
            if (!e.alive()) continue;
            int np = e.def.phaseFor(e.hp, turn);
            if (np != e.phase) {
                e.phase = np;
                if (np >= 0) {
                    Encounter.PhaseDef ph = e.def.phases.get(np);
                    if (ph.notice != null && !ph.notice.isEmpty()) notice = ph.notice;
                    if (ph.music != null && !ph.music.isEmpty()) { if (ph.music.equals("stop")) music("", 1); else music(ph.music, 0); }
                }
            }
        }
    }

    // ---------- фазы ----------
    private void enterMenu(String flavor) {
        turn++;
        updatePhases();
        fireCues("turn", -1, turn);
        for (int i = 0; i < enemies.size(); i++) fireCues("hp_below", i, 0);
        active = new Pattern.Active[0];
        atkRef = new int[0];
        for (Member m : members) { m.cmd = -1; m.defend = false; }
        setPhase(MENU, MENU_TIMEOUT, flavor != null ? flavor : pickFlavor());
        sendState();
    }

    private String pickFlavor() {
        if (!notice.isEmpty()) { String n = notice; notice = ""; return n; }
        if (turn == 1) return enc.intro.isEmpty() ? "deltabattle.flavor.default" : enc.intro;
        List<EState> al = alive();
        if (al.isEmpty()) return "";
        EState e = al.get(rnd.nextInt(al.size()));
        if (e.mercy >= 100 && !e.def.flavor_spareable.isEmpty()) return e.def.flavor_spareable;
        if (e.hp * 3 <= e.def.hp && !e.def.flavor_low.isEmpty()) return e.def.flavor_low;
        List<String> fl = e.def.flavorFor(e.phase);
        return fl.isEmpty() ? "deltabattle.flavor.generic" : fl.get(rnd.nextInt(fl.size()));
    }

    private void checkResult() {
        if (result != 0 || !allGone()) return;
        boolean killed = false;
        for (EState e : enemies) if (e.gone == GONE_KILLED) killed = true;
        result = killed ? RES_KILLED : RES_SPARED;
    }

    private void execute() {
        List<int[]> ev = new ArrayList<>();
        List<String> tx = new ArrayList<>();
        for (int mi = 0; mi < members.size() && result == 0; mi++) {
            Member m = members.get(mi);
            if (m.down()) continue;
            int cmd = m.cmd < 0 ? CMD_DEFEND : m.cmd;
            EState e = targetOf(m.target);
            int ti = e == null ? 0 : enemies.indexOf(e);
            switch (cmd) {
                case CMD_FIGHT -> {
                    if (e == null) break;
                    float a = m.acc / 1000f;
                    if (a < 0.05f) { ev.add(new int[]{EV_MISS, mi, 0, ti}); tx.add("deltabattle.text.miss"); break; }
                    int dmg = Math.max(1, Math.round((enc.player_atk + rnd.nextInt(3)) * (0.4f + 1.1f * a)) - e.def.def);
                    damageEnemy(e, dmg);
                    m.tp = Math.min(100, m.tp + Math.round(a * 10));
                    ev.add(new int[]{EV_DAMAGE, mi, dmg, ti});
                    tx.add("deltabattle.text.fight");
                }
                case CMD_ACT -> {
                    if (e == null) break;
                    m.tp = Math.min(100, m.tp + 4);
                    List<MenuEntry> menu = enc.actMenu(e.def, mi);
                    if (m.sub < 0 || m.sub >= menu.size()) { ev.add(new int[]{EV_TEXT, mi, 0, ti}); tx.add("deltabattle.act.notp"); break; }
                    MenuEntry me = menu.get(m.sub);
                    if (m.tp < me.tp) { ev.add(new int[]{EV_TEXT, mi, 0, ti}); tx.add("deltabattle.act.notp"); break; }
                    m.tp -= me.tp;
                    if (!me.spell) {
                        Encounter.ActDef act = me.act;
                        e.mercy = Math.min(100, e.mercy + act.mercy);
                        if (act.reply != null && !act.reply.isEmpty()) e.reply = act.reply;
                        if (act.heal > 0) healParty(act.heal);
                        if (act.damage > 0) {
                            damageEnemy(e, act.damage);
                            ev.add(new int[]{EV_DAMAGE, mi, act.damage, ti});
                        } else if (act.heal > 0) ev.add(new int[]{EV_HEAL, mi, act.heal, ti});
                        else ev.add(new int[]{EV_TEXT, mi, 0, ti});
                        tx.add(act.text);
                    } else {
                        Encounter.SpellDef s = me.spellDef;
                        switch (s.type.toLowerCase()) {
                            case "damage" -> {
                                int dmg = Math.max(1, s.power + rnd.nextInt(3) - e.def.def);
                                damageEnemy(e, dmg);
                                ev.add(new int[]{EV_DAMAGE, mi, dmg, ti});
                            }
                            case "mercy" -> {
                                e.mercy = Math.min(100, e.mercy + s.power);
                                ev.add(new int[]{EV_TEXT, mi, 0, ti});
                            }
                            default -> {
                                healParty(s.power);
                                ev.add(new int[]{EV_HEAL, mi, s.power, ti});
                            }
                        }
                        tx.add(s.text);
                    }
                }
                case CMD_ITEM -> {
                    ItemDef def = null;
                    ItemStack st = null;
                    for (ItemDef d : enc.items) {
                        st = findItem(m.p, d.item);
                        if (st != null) { def = d; break; }
                    }
                    if (st == null) { ev.add(new int[]{EV_TEXT, mi, 0, 0}); tx.add("deltabattle.item.none"); }
                    else {
                        st.shrink(1);
                        m.hp = Math.min(m.max, m.hp + def.heal);
                        ev.add(new int[]{EV_HEAL, mi, def.heal, 0});
                        tx.add(def.text);
                    }
                }
                case CMD_SPARE -> {
                    if (e == null) break;
                    if (e.mercy >= 100) { e.gone = GONE_SPARED; ev.add(new int[]{EV_SPARED, mi, 0, ti}); tx.add("deltabattle.text.spare"); }
                    else { ev.add(new int[]{EV_TEXT, mi, 0, ti}); tx.add("deltabattle.text.spare_fail"); }
                }
                default -> {
                    m.defend = true; m.tp = Math.min(100, m.tp + 16);
                    ev.add(new int[]{EV_DEFEND, mi, 0, 0}); tx.add("deltabattle.text.defend");
                }
            }
            checkResult();
        }
        for (int i = 0; i < enemies.size(); i++) {
            EState e = enemies.get(i);
            if (e.gone != 0 && !goneCue[i]) { goneCue[i] = true; fireCues(e.gone == GONE_KILLED ? "enemy_killed" : "enemy_spared", i, 0); }
        }
        int n = ev.size();
        int[] flat = new int[n * 4];
        for (int k = 0; k < n; k++) System.arraycopy(ev.get(k), 0, flat, k * 4, 4);
        all(new S2C(S2C.EVENTS).ints(flat).strs(tx.toArray(new String[0])));
        sendState();
        setPhase(EXECUTE, n * 40 + 15);
    }

    private ItemStack findItem(ServerPlayer p, String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return null;
        Item it = ForgeRegistries.ITEMS.getValue(rl);
        if (it == null) return null;
        for (ItemStack s : p.getInventory().items) if (s.is(it)) return s;
        return null;
    }

    private void startTalk() {
        seed = rnd.nextLong();
        List<Pattern.Active> acts = new ArrayList<>();
        List<Integer> refs = new ArrayList<>();
        String[] texts = new String[enemies.size()];
        boolean anyText = false;
        boxW = 150; boxH = 150;
        boolean sized = false;
        for (int i = 0; i < enemies.size(); i++) {
            EState e = enemies.get(i);
            texts[i] = "";
            if (!e.alive()) continue;
            // реплика: ответ на действие или обычная
            if (!e.reply.isEmpty()) { texts[i] = e.reply; e.reply = ""; }
            else {
                List<String> d = e.def.dialogueFor(e.phase);
                if (!d.isEmpty()) texts[i] = d.get(rnd.nextInt(d.size()));
            }
            if (!texts[i].isEmpty()) anyText = true;
            // выбор атаки
            List<AttackDef> list = e.def.attacksFor(e.phase);
            if (list.isEmpty() || acts.size() >= Pattern.MAX_ATTACKS) continue;
            int idx;
            if (e.def.attack_mode.equalsIgnoreCase("sequence")) idx = e.cursor++ % list.size();
            else {
                int sum = 0;
                for (AttackDef a : list) sum += a.weight;
                int r = rnd.nextInt(sum);
                idx = 0;
                for (int k = 0; k < list.size(); k++) { r -= list.get(k).weight; if (r < 0) { idx = k; break; } }
            }
            AttackDef a = list.get(idx);
            acts.add(new Pattern.Active(a, a.damage > 0 ? a.damage : e.def.atk));
            refs.add(i); refs.add(e.phase); refs.add(idx);
            if (!sized) { boxW = a.box_w; boxH = a.box_h; sized = true; }
            else { boxW = Math.max(boxW, a.box_w); boxH = Math.max(boxH, a.box_h); }
        }
        active = acts.toArray(new Pattern.Active[0]);
        atkRef = new int[refs.size()];
        for (int k = 0; k < atkRef.length; k++) atkRef[k] = refs.get(k);
        Arrays.fill(aimX, Float.NaN);
        Arrays.fill(aimY, Float.NaN);
        for (Member m : members) { m.x = boxW / 2f; m.y = boxH / 2f; m.inv = 0; }
        setPhase(TALK, anyText ? 45 : 8, texts);
    }

    private void startAttack() {
        if (active.length == 0) { enterMenu(null); return; }
        int len = 0;
        for (Pattern.Active a : active) len = Math.max(len, a.def.duration);
        setPhase(ATTACK, len);
    }

    // ---------- тик ----------
    public void tick() {
        if (over) return;
        for (Member m : members) if (m.p.isRemoved()) { end(RES_ABORT); return; }
        if (mobId != null) {
            Entity e = level.getEntity(mobId);
            if (e == null || !e.isAlive()) { end(RES_ABORT); return; }
        }
        phaseTick++;
        switch (phase) {
            case MENU -> { if (phaseTick >= phaseLen) { for (Member m : members) if (!m.down() && m.cmd < 0) m.cmd = CMD_DEFEND; execute(); } }
            case EXECUTE -> {
                if (phaseTick >= phaseLen) {
                    if (result == RES_KILLED || result == RES_SPARED) end(result); else startTalk();
                }
            }
            case TALK -> { if (phaseTick >= phaseLen) startAttack(); }
            case ATTACK -> tickAttack();
        }
    }

    private void tickAttack() {
        for (int j = 0; j < active.length; j++) {
            AttackDef a = active[j].def;
            if (a.kind() != AttackDef.AIMED) continue;
            int n = Math.min(a.count, Pattern.MAX_PER);
            for (int i = 0; i < n; i++) {
                if (phaseTick == Pattern.fireTick(a, i)) {
                    Member t = pickTarget(j * 7 + i);
                    int ai = j * Pattern.MAX_PER + i;
                    if (t != null) { aimX[ai] = t.x; aimY[ai] = t.y; all(new S2C(S2C.AIM).ints(ai).floats(t.x, t.y)); }
                }
            }
        }
        Pattern.collect(frame, active, seed, phaseTick, boxW, boxH, aimX, aimY);
        for (Member m : members) {
            if (m.inv > 0) m.inv--;
            if (m.down() || m.inv > 0) continue;
            int h = Pattern.hit(frame, m.x, m.y);
            if (h >= 0) {
                int dmg = m.defend ? Math.max(1, frame.dmg[h] / 2) : frame.dmg[h];
                m.hp = Math.max(0, m.hp - dmg); m.inv = IFRAMES; m.tp = Math.min(100, m.tp + 4);
                if (m.hp == 0) fireCues("player_down", -1, 0);
                all(new S2C(S2C.HIT).ints(members.indexOf(m), m.hp, m.tp));
            }
        }
        if ((phaseTick & 1) == 0) sendSouls();
        boolean anyAlive = false;
        for (Member m : members) if (!m.down()) anyAlive = true;
        if (!anyAlive) { end(RES_DEFEAT); return; }
        if (phaseTick >= phaseLen) enterMenu(null);
    }

    private Member pickTarget(int i) {
        int alive = 0;
        for (Member m : members) if (!m.down()) alive++;
        if (alive == 0) return null;
        int k = i % alive;
        for (Member m : members) if (!m.down() && k-- == 0) return m;
        return null;
    }

    private void sendSouls() {
        int n = members.size();
        int[] d = new int[n + 1];
        float[] f = new float[n * 2];
        d[0] = n;
        for (int k = 0; k < n; k++) { Member m = members.get(k); d[k + 1] = m.down() ? 1 : 0; f[k * 2] = m.x; f[k * 2 + 1] = m.y; }
        all(new S2C(S2C.SOULS).ints(d).floats(f));
    }

    // ---------- завершение ----------
    private void run(ServerPlayer p, List<String> cmds) {
        MinecraftServer srv = p.server;
        for (String c : cmds) {
            try {
                srv.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), c);
            } catch (Exception ex) {
                com.deltabattle.DeltaBattle.LOGGER.warn("Encounter command failed: {}", c, ex);
            }
        }
    }

    public void end(int res) {
        if (over) return;
        over = true;
        if (mobId != null) {
            Entity e = level.getEntity(mobId);
            if (e instanceof BattleMobEntity mob) mob.finishBattle(res);
        }
        for (Member m : members) {
            if (res == RES_KILLED && !m.down()) {
                if (enc.exp > 0) m.p.giveExperiencePoints(enc.exp);
                if (enc.gold > 0) give(m.p, new ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, enc.gold));
                for (Encounter.RewardItem r : enc.reward_items) {
                    ResourceLocation rl = ResourceLocation.tryParse(r.item);
                    Item it = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
                    if (it != null && r.count > 0) give(m.p, new ItemStack(it, r.count));
                }
            }
            if (res == RES_KILLED) run(m.p, enc.win_commands);
            else if (res == RES_SPARED) run(m.p, enc.spare_commands);
            else if (res == RES_DEFEAT) run(m.p, enc.lose_commands);
            Net.toPlayer(m.p, new S2C(S2C.END).ints(res));
        }
        BattleManager.remove(this);
    }

    private static void give(ServerPlayer p, ItemStack st) {
        if (!p.getInventory().add(st)) p.drop(st, false);
    }
}
