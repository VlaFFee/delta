package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.deltabattle.battle.Battle;
import com.deltabattle.battle.Pattern;
import com.deltabattle.data.Encounter;
import com.deltabattle.data.Encounter.EnemyDef;
import com.deltabattle.data.Encounter.MenuEntry;
import com.deltabattle.data.Encounter.ModelDef;
import com.deltabattle.entity.BattleMobEntity;
import com.deltabattle.net.C2S;
import com.deltabattle.net.Net;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Экран боя в стиле Deltarune. Виртуальное разрешение 640x480 (как в игре), все координаты взяты со скриншотов оригинала:
 * слева спрайты пати и вертикальный TP-бар, справа враги, снизу панели героев с иконками команд и текстовое поле,
 * в фазе атаки по центру появляется зелёная рамка с красным сердцем.
 * Спрайты героев - скины игроков (оригинальные спрайты Deltarune в мод не включены).
 * Шрифт: если в ресурсах есть assets/deltabattle/font/deltarune.ttf, он используется автоматически.
 */
public class BattleScreen extends Screen {
    private static final ResourceLocation SOUL = new ResourceLocation(DeltaBattle.MODID, "textures/gui/soul.png");
    private static final ResourceLocation FONT_ID = new ResourceLocation(DeltaBattle.MODID, "deltarune");
    private static final ResourceLocation FONT_FILE = new ResourceLocation(DeltaBattle.MODID, "font/deltarune.ttf");
    private static final int UI_MAIN = 0, UI_TARGET = 1, UI_ACT = 2, UI_FIGHT = 3, UI_WAIT = 4;
    private static final int W = 640, H = 480, BOX_CX = 320, BOX_CY = 170;
    private static final int YELLOW = 0xFFFFFF00, WHITE = 0xFFFFFFFF, ORANGE = 0xFFFF8A00, ORANGE_DIM = 0xFF8A4A00, LINE = 0xFF2F2A40;
    private static final float TS = 2.4f;   // масштаб основного текста

    private final ClientBattle b;
    private final ResourceLocation[] etex = new ResourceLocation[3];
    private ResourceLocation[] atkTex = new ResourceLocation[0];
    private Object atkTexFor;
    private final BattleMobEntity[] ePup = new BattleMobEntity[3], pPup = new BattleMobEntity[4];
    private final long[] lastFlash = new long[4];
    private final int gridColor;
    private final boolean useFont;
    private int ui = UI_WAIT, sel, actSel, tsel, pendCmd, target, lastEvent = -1, lastChars;
    private List<MenuEntry> menu = new ArrayList<>();
    private float menuT, boxT, myX = 75, myY = 75, lastSentX = -1, lastSentY = -1;
    private final float[] dHp = new float[4], fade = {1f, 1f, 1f}, stepX = new float[4];
    private final long[] enemyHitAt = new long[3];
    private String fullText = "";
    private long typeStart, fightStart, lastNs = System.nanoTime();

    public BattleScreen(ClientBattle b) {
        super(Component.empty());
        this.b = b;
        for (int i = 0; i < 4; i++) dHp[i] = b.hp[i];
        for (int i = 0; i < b.ne; i++) {
            String t = b.enc.enemies.get(i).texture;
            etex[i] = (t == null || t.isEmpty()) ? null : ResourceLocation.tryParse(t);
        }
        for (int i = 0; i < b.ne; i++) ePup[i] = ModelView.create(b.enc.enemies.get(i).model);
        for (int i = 0; i < b.n && i < b.enc.party_models.size(); i++) pPup[i] = ModelView.create(b.enc.party_models.get(i));
        gridColor = Encounter.parseColor(b.enc.grid, 0xFF5A1A6E);
        useFont = Minecraft.getInstance().getResourceManager().getResource(FONT_FILE).isPresent();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    // ---------- текст ----------
    /** Ключ локализации или обычный текст; %1$s - имя игрока, %2$s - имя врага. */
    static String tr(String key, Object... args) {
        if (key == null || key.isEmpty()) return "";
        Language l = Language.getInstance();
        String base = l.has(key) ? l.getOrDefault(key) : key;
        try { return String.format(base, args); } catch (Exception e) { return base; }
    }

    private Component comp(String s) {
        return useFont ? Component.literal(s).withStyle(Style.EMPTY.withFont(FONT_ID)) : Component.literal(s);
    }

    private void text(GuiGraphics g, String s, float x, float y, float sc, int color) {
        PoseStack ps = g.pose();
        ps.pushPose(); ps.translate(x, y, 0); ps.scale(sc, sc, 1f);
        g.drawString(font, comp(s), 0, 0, color, false);
        ps.popPose();
    }

    private int tw(String s, float sc) { return (int) (font.width(comp(s)) * sc); }

    private void textRight(GuiGraphics g, String s, float xr, float y, float sc, int color) { text(g, s, xr - tw(s, sc), y, sc, color); }

    /** Перенос по ширине в пикселях экрана с учётом масштаба; '\n' - принудительный перенос. */
    private List<String> wrap(String s, float sc, int px) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n", -1)) {
            if (para.isEmpty()) { out.add(""); continue; }
            for (net.minecraft.network.chat.FormattedText ft : font.getSplitter().splitLines(para, (int) (px / sc), comp("").getStyle()))
                out.add(ft.getString());
        }
        return out;
    }

    private String enemyName(int i) { return i >= 0 && i < b.ne ? tr(b.enc.enemies.get(i).name) : "?"; }

    private void setText(String key, Object... args) { fullText = tr(key, args); typeStart = System.nanoTime(); lastChars = 0; }

    public void onPhase() {
        switch (b.phase) {
            case Battle.MENU -> { ui = b.down[b.myIdx] ? UI_WAIT : UI_MAIN; setText(b.phaseText()); }
            case Battle.EXECUTE -> { ui = UI_WAIT; lastEvent = -1; fullText = ""; }
            case Battle.TALK -> { ui = UI_WAIT; fullText = ""; myX = b.boxW / 2f; myY = b.boxH / 2f; }
            default -> { ui = UI_WAIT; fullText = ""; }
        }
    }

    public void onEnd() {
        Encounter e = b.enc;
        String key = switch (b.endResult) {
            case Battle.RES_KILLED -> e.win_text.isEmpty() ? "deltabattle.end.1" : e.win_text;
            case Battle.RES_SPARED -> e.spare_text.isEmpty() ? "deltabattle.end.2" : e.spare_text;
            case Battle.RES_DEFEAT -> e.lose_text.isEmpty() ? "deltabattle.end.3" : e.lose_text;
            default -> "deltabattle.end.4";
        };
        setText(key);
        ui = UI_WAIT;
        if (b.endResult == Battle.RES_ABORT) b.endAt = System.nanoTime();
    }

    // ---------- ввод ----------
    private List<Integer> aliveEnemies() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < b.ne; i++) if (b.enemyAlive(i)) l.add(i);
        return l;
    }

    private void beginTarget(int cmd) {
        List<Integer> al = aliveEnemies();
        if (al.isEmpty()) return;
        pendCmd = cmd;
        tsel = 0;
        if (al.size() == 1) afterTarget(al.get(0)); else ui = UI_TARGET;
    }

    private void afterTarget(int enemy) {
        target = enemy;
        switch (pendCmd) {
            case Battle.CMD_FIGHT -> { ui = UI_FIGHT; fightStart = System.nanoTime(); }
            case Battle.CMD_ACT -> { menu = b.enc.actMenu(b.enc.enemies.get(enemy), b.myIdx); actSel = 0; ui = UI_ACT; }
            default -> send(Battle.CMD_SPARE, 0, enemy, 0);
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 261) { Net.toServer(C2S.abort()); return true; }          // DELETE: отмена боя (креатив / OP)
        boolean ok = key == 90 || key == 257 || key == 32, back = key == 88 || key == 259;
        boolean left = key == 263 || key == 65, right = key == 262 || key == 68, up = key == 265 || key == 87, dn = key == 264 || key == 83;
        if (ui == UI_MAIN) {
            if (left) { sel = (sel + 4) % 5; click(1.3f); } else if (right) { sel = (sel + 1) % 5; click(1.3f); }
            else if (ok) {
                switch (sel) {
                    case 0 -> beginTarget(Battle.CMD_FIGHT);
                    case 1 -> beginTarget(Battle.CMD_ACT);
                    case 3 -> beginTarget(Battle.CMD_SPARE);
                    default -> send(sel == 2 ? Battle.CMD_ITEM : Battle.CMD_DEFEND, 0, 0, 0);
                }
                click(1.0f);
            }
        } else if (ui == UI_TARGET) {
            int n = aliveEnemies().size();
            if (up || left) { tsel = (tsel + n - 1) % n; click(1.3f); }
            else if (dn || right) { tsel = (tsel + 1) % n; click(1.3f); }
            else if (ok) afterTarget(aliveEnemies().get(tsel));
            else if (back) ui = UI_MAIN;
        } else if (ui == UI_ACT) {
            int n = menu.size();
            if (up && actSel - 2 >= 0) { actSel -= 2; click(1.3f); }
            else if (dn && actSel + 2 < n) { actSel += 2; click(1.3f); }
            else if (left && actSel % 2 == 1) { actSel--; click(1.3f); }
            else if (right && actSel % 2 == 0 && actSel + 1 < n) { actSel++; click(1.3f); }
            else if (ok) {
                MenuEntry m = menu.get(actSel);
                if (b.tp[b.myIdx] + 4 >= m.tp) send(Battle.CMD_ACT, actSel, target, 0); else click(0.5f);
            } else if (back) ui = UI_MAIN;
        } else if (ui == UI_FIGHT) {
            if (ok) {
                float m = 1f - (System.nanoTime() - fightStart) / 1_000_000_000f;     // маркер бежит справа налево
                float acc = Math.max(0f, 1f - Math.abs(m - 0.18f) / 0.18f);
                send(Battle.CMD_FIGHT, 0, target, Math.round(acc * 1000));
            } else if (back) ui = UI_MAIN;
        }
        return true;
    }

    private void send(int cmd, int sub, int tgt, int acc) {
        Net.toServer(C2S.choice(cmd, sub, tgt, acc));
        ui = UI_WAIT; b.ready[b.myIdx] = true; fullText = ""; click(0.8f);
    }

    private void click(float pitch) { Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch, 0.6f)); }

    @Override
    public void tick() {
        long now = System.nanoTime();
        if (b.endResult >= 0 && now > b.endAt) {
            ClientBattle.cur = null;
            EditorScreen back = EditorScreen.PENDING;
            EditorScreen.PENDING = null;
            Minecraft.getInstance().setScreen(back);
            return;
        }
        if (BattleMusic.isPlaying()) Minecraft.getInstance().getMusicManager().stopPlaying();
        if (b.phase == Battle.ATTACK && !b.down[b.myIdx] && (myX != lastSentX || myY != lastSentY)) {
            Net.toServer(C2S.soul(myX, myY)); lastSentX = myX; lastSentY = myY;
        }
        int chars = Math.min(fullText.length(), (int) ((now - typeStart) / 22_000_000L));
        if (chars > lastChars) {
            if ((chars & 1) == 0 && fullText.charAt(chars - 1) != ' ')
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), 1.6f, 0.25f));
            lastChars = chars;
        }
    }

    @Override public void removed() { BattleMusic.stop(); if (ClientBattle.cur == b && b.endResult < 0) ClientBattle.cur = null; }

    // ---------- обновление кадра ----------
    private static boolean key(long w, int a, int c) { return InputConstants.isKeyDown(w, a) || InputConstants.isKeyDown(w, c); }

    private void update(float dt, long now) {
        float k = 1f - (float) Math.exp(-dt * 16f);
        for (int i = 0; i < b.n; i++) dHp[i] += (b.hp[i] - dHp[i]) * k * 0.6f;
        for (int i = 0; i < b.ne; i++) {
            if (b.egone[i] != 0) fade[i] = Math.max(0f, fade[i] - dt * (b.egone[i] == Battle.GONE_KILLED ? 2f : 1.2f));
        }
        animatePuppets();
        boolean expanded = b.phase == Battle.MENU && ui != UI_WAIT;
        menuT += ((expanded ? 1f : 0f) - menuT) * (1f - (float) Math.exp(-dt * 18f));
        boolean showBox = b.phase == Battle.ATTACK || b.phase == Battle.TALK;
        boxT += ((showBox ? 1f : 0f) - boxT) * (1f - (float) Math.exp(-dt * 14f));
        for (int i = 0; i < b.n; i++) {
            boolean stepping = expanded && i == b.myIdx;
            stepX[i] += ((stepping ? 14f : 0f) - stepX[i]) * (1f - (float) Math.exp(-dt * 18f));
            if (i != b.myIdx) { b.sx[i] += (b.tx[i] - b.sx[i]) * k; b.sy[i] += (b.ty[i] - b.sy[i]) * k; }
        }
        if (b.phase == Battle.ATTACK && !b.down[b.myIdx]) {
            long w = Minecraft.getInstance().getWindow().getWindow();
            float dx = (key(w, 262, 68) ? 1 : 0) - (key(w, 263, 65) ? 1 : 0), dy = (key(w, 264, 83) ? 1 : 0) - (key(w, 265, 87) ? 1 : 0);
            if (dx != 0 && dy != 0) { dx *= 0.7071f; dy *= 0.7071f; }
            float sp = InputConstants.isKeyDown(w, 88) ? 50f : 100f;          // 100 px/s = 4 px за кадр при 30 FPS, X - медленнее
            float c = Pattern.SOUL_CLAMP;
            myX = Math.max(c, Math.min(b.boxW - c, myX + dx * sp * dt));
            myY = Math.max(c, Math.min(b.boxH - c, myY + dy * sp * dt));
            b.sx[b.myIdx] = myX; b.sy[b.myIdx] = myY;
            Pattern.collect(b.frame, b.active, b.seed, b.phaseTicks(), b.boxW, b.boxH, b.aimX, b.aimY);
        } else if (b.phase != Battle.ATTACK) { b.frame.n = 0; b.sx[b.myIdx] = myX; b.sy[b.myIdx] = myY; }
        if (ui == UI_FIGHT && (now - fightStart) > 1_000_000_000L) send(Battle.CMD_FIGHT, 0, target, 0);
        if (b.phase == Battle.EXECUTE) {
            int idx = (int) (b.phaseTicks() / 40f);
            while (lastEvent < idx && lastEvent + 1 < b.events.length / 4) showEvent(++lastEvent, now);
        }
        for (Iterator<ClientBattle.Popup> it = b.popups.iterator(); it.hasNext(); ) if (now - it.next().born > 1_300_000_000L) it.remove();
    }

    /** Анимация "атака" героя (кукла pPup[i]). */
    private void once(BattleMobEntity[] pups, int i, List<ModelDef> defs, int kind) {
        if (i < 0 || i >= pups.length || pups[i] == null || i >= defs.size()) return;
        ModelDef m = defs.get(i);
        String a = kind == 1 ? m.anim_attack : m.anim_hurt;
        pups[i].playOnce(a, ClientAssets.animMillis(m.animations, a));
    }

    private void onceEnemy(int e) {
        if (e < 0 || e >= b.ne || ePup[e] == null) return;
        ModelDef m = b.enc.enemies.get(e).model;
        ePup[e].playOnce(m.anim_hurt, ClientAssets.animMillis(m.animations, m.anim_hurt));
    }

    /** Базовые анимации кукол по состоянию боя. */
    private void animatePuppets() {
        for (int i = 0; i < b.ne; i++) {
            BattleMobEntity p = ePup[i];
            if (p == null) continue;
            ModelDef m = b.enc.enemies.get(i).model;
            boolean attacking = false;
            for (int k = 0; k < b.activeEnemy.length; k++) if (b.activeEnemy[k] == i) attacking = true;
            String a = "";
            boolean hold = false;
            if (b.egone[i] == Battle.GONE_KILLED && !m.anim_defeat.isEmpty()) { a = m.anim_defeat; hold = true; }
            else if (b.egone[i] == Battle.GONE_SPARED && !m.anim_spare.isEmpty()) { a = m.anim_spare; hold = true; }
            else if (b.phase == Battle.ATTACK && attacking && !m.anim_attack.isEmpty()) a = m.anim_attack;
            else if (b.phase == Battle.TALK && i < b.phaseTexts.length && !b.phaseTexts[i].isEmpty() && !m.anim_talk.isEmpty()) a = m.anim_talk;
            p.baseAnim = a; p.baseHold = hold;
        }
        for (int i = 0; i < b.n; i++) {
            BattleMobEntity p = pPup[i];
            if (p == null || i >= b.enc.party_models.size()) continue;
            ModelDef m = b.enc.party_models.get(i);
            p.baseAnim = b.down[i] && !m.anim_defeat.isEmpty() ? m.anim_defeat : "";
            p.baseHold = b.down[i];
            if (b.flashUntil[i] != lastFlash[i]) { lastFlash[i] = b.flashUntil[i]; p.playOnce(m.anim_hurt, ClientAssets.animMillis(m.animations, m.anim_hurt)); }
        }
    }

    private void showEvent(int i, long now) {
        int type = b.events[i * 4], who = b.events[i * 4 + 1], val = b.events[i * 4 + 2], tgt = b.events[i * 4 + 3];
        String name = who < b.names.length ? b.names[who] : "?";
        setText(b.eventText[i], name, enemyName(tgt));
        if (type == Battle.EV_DAMAGE || type == Battle.EV_MISS) once(pPup, who, b.enc.party_models, 1);
        if (type == Battle.EV_DAMAGE) onceEnemy(tgt);
        ClientBattle.Popup p = new ClientBattle.Popup(); p.born = now;
        p.x = ecx(tgt); p.y = ebase(tgt) - edh(tgt) * 0.6f;
        switch (type) {
            case Battle.EV_DAMAGE -> {
                p.text = String.valueOf(val); p.color = 0xFFFF3030;
                if (tgt >= 0 && tgt < 3) enemyHitAt[tgt] = now;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_ATTACK_STRONG, 1.0f, 0.7f));
            }
            case Battle.EV_MISS -> { p.text = "MISS"; p.color = 0xFFAAAAAA; }
            case Battle.EV_HEAL -> {
                p.x = who * (W / (float) Math.max(1, b.n)) + 100; p.y = 330; p.text = String.valueOf(val); p.color = 0xFF40FF60;
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f, 0.5f));
            }
            default -> { return; }
        }
        b.popups.add(p);
    }

    // ---------- раскладка (640x480) ----------
    private float escale() { return b.ne == 1 ? 1f : b.ne == 2 ? 0.8f : 0.66f; }
    private float ecx(int i) { return b.ne == 1 ? 505f : 505f + (i % 2 == 0 ? -30f : 30f); }
    private float ebase(int i) { return b.ne == 1 ? 235f : 130f + i * (b.ne == 2 ? 105f : 70f); }
    private float edh(int i) { return b.enc.enemies.get(i).height * 2f * escale(); }
    private float lineY() { return 363f + 45f * menuT; }

    // ---------- отрисовка ----------
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f); lastNs = now;
        update(dt, now);
        g.fill(0, 0, width, height, 0xFF000000);
        float u = Math.min(width / (float) W, height / (float) H), ox = (width - W * u) / 2f, oy = (height - H * u) / 2f;
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(ox, oy, 0);
        if (now < b.shakeUntil) ps.translate((float) Math.sin(now / 1.5e7) * 3f * u, 0, 0);
        ps.scale(u, u, 1f);
        float t = now / 1e9f;
        drawGrid(g, t);
        drawTp(g);
        for (int i = 0; i < b.ne; i++) drawEnemy(g, ps, i, t, now);
        for (int i = 0; i < b.n; i++) drawPlayer(g, ps, i, now);
        g.fill(0, 325, W, H, 0xFF000000);                       // нижняя чёрная зона под сеткой
        drawStrip(g);
        if (boxT > 0.02f) drawBox(g, ps, ox, oy, u, now);
        drawTextArea(g, ps, now);
        if (b.phase == Battle.TALK) drawBubbles(g, now);
        if (b.phase == Battle.ATTACK) drawSay(g, now);
        var pl = Minecraft.getInstance().player;
        if (pl != null && pl.isCreative()) { String h = tr("deltabattle.abort_hint"); text(g, h, W - 10 - tw(h, 1.1f), 6, 1.1f, 0xFF707070); }
        for (ClientBattle.Popup p : b.popups) {
            float age = (now - p.born) / 1e9f; int a = Math.max(4, (int) (255 * (1f - age / 1.3f)));
            String s = p.text;
            text(g, s, p.x - tw(s, 3f) / 2f, p.y - Math.min(age, 0.4f) * 70f - age * 10f, 3f, (a << 24) | (p.color & 0xFFFFFF));
        }
        ps.popPose();
    }

    private void drawGrid(GuiGraphics g, float t) {
        int main = gridColor, faint = (gridColor & 0x00FFFFFF) | 0x66000000;
        int off = (int) ((t * 10f) % 40f);
        for (int x = -40; x <= W + 40; x += 40) {
            g.fill(x + off, 0, x + off + 2, 325, main);
            g.fill(x + off + 20, 0, x + off + 21, 325, faint);
        }
        for (int y = -40; y <= 325; y += 40) {
            int yy = y + off;
            if (yy < 0 || yy > 323) continue;
            g.fill(0, yy, W, yy + 2, main);
            if (yy + 20 < 325) g.fill(0, yy + 20, W, yy + 21, faint);
        }
    }

    private void drawTp(GuiGraphics g) {
        float tp = b.tp[b.myIdx];
        g.fill(38, 40, 62, 232, 0xFF6A0000);
        int fh = (int) (192 * Math.min(100f, tp) / 100f);
        boolean max = tp >= 100;
        g.fill(38, 232 - fh, 62, 232, max ? YELLOW : 0xFFFFA040);
        text(g, "T", 8, 66, 2.6f, WHITE); text(g, "P", 8, 90, 2.6f, WHITE);
        text(g, max ? "MAX" : String.valueOf((int) tp), max ? 4 : 8, 122, max ? 1.5f : 2.6f, max ? YELLOW : WHITE);
        if (!max) text(g, "%", 8, 148, 2.6f, WHITE);
    }

    // ---------- герои и враги ----------
    /** Встроенный силуэт героя (если у героя нет модели GeckoLib). */
    private ResourceLocation partyTex(int i) { return new ResourceLocation(DeltaBattle.MODID, "textures/gui/party_" + Math.min(i, 3) + ".png"); }

    /** Значок в панели: лицо силуэта или цветной квадрат с буквой для героя с моделью. */
    private void face(GuiGraphics g, int i, int x, int y, int size) {
        if (pPup[i] != null) {
            g.fill(x, y, x + size, y + size, 0xFF000000 | ClientBattle.COLORS[i]);
            String l = b.names[i].isEmpty() ? "?" : b.names[i].substring(0, 1).toUpperCase();
            text(g, l, x + size / 2f - tw(l, 2f) / 2f, y + size / 2f - 8f, 2f, 0xFF000000);
            return;
        }
        RenderSystem.enableBlend();
        g.blit(partyTex(i), x, y, size, size, 3, 0, 10, 10, 16, 24);
    }

    /** Герой: модель GeckoLib или силуэт 48x72. Активный делает шаг вперёд, раненый мигает, павший лежит. */
    private void drawPlayer(GuiGraphics g, PoseStack ps, int i, long now) {
        float sp = Math.min(88f, 250f / Math.max(1, b.n));
        float y0 = 170f - (b.n - 1) * sp / 2f - 36f + (float) Math.sin(now / 4.0e8 + i) * 1.5f * (b.down[i] ? 0 : 1);
        int x = Math.round(92f + stepX[i]), y = Math.round(y0 + (b.down[i] ? 22 : 0));
        if (pPup[i] != null) {
            ModelDef m = b.enc.party_models.get(i);
            ModelView.sync(pPup[i], m);
            ModelView.draw(g, pPup[i], x + 24 + m.offset_x, y0 + 72 + m.offset_y, 60f * m.scale, 180f + 28f + m.yaw);
            return;
        }
        boolean hit = now < b.flashUntil[i] && ((now / 60_000_000L) & 1) == 0;
        float c = b.down[i] ? 0.3f : hit ? 0.45f : 1f;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(c, c, c, 1f);
        if (b.down[i]) { ps.pushPose(); ps.translate(x + 24f, y + 60, 0); ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-80f)); ps.translate(-(x + 24f), -(y + 60), 0); }
        g.blit(partyTex(i), x, y, 48, 72, 0, 0, 16, 24, 16, 24);
        if (b.down[i]) ps.popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private void drawEnemy(GuiGraphics g, PoseStack ps, int i, float t, long now) {
        EnemyDef d = b.enc.enemies.get(i);
        float a = fade[i];
        if (a <= 0.01f) return;
        float s = escale(), cx = ecx(i), base = ebase(i);
        float hurt = Math.max(0f, 1f - (now - enemyHitAt[i]) / 4.5e8f), shake = (float) Math.sin(now / 1.2e7) * 8f * hurt;
        if (ePup[i] != null) {
            ModelDef m = d.model;
            ModelView.sync(ePup[i], m);
            ModelView.draw(g, ePup[i], cx + shake + m.offset_x, base + m.offset_y, 70f * m.scale * s * Math.max(0.05f, a), 180f - 28f + m.yaw);
            return;
        }
        float bob = d.bob == 0 ? 0 : (float) Math.abs(Math.sin(t * 2.4f + i));
        float sqY = 1f + d.squash * (float) Math.cos(t * 4.8f + i), sqX = 1f / sqY;
        int w = Math.round(d.width * 2 * s), h = Math.round(d.height * 2 * s);
        ps.pushPose();
        ps.translate(cx + shake, base - bob * d.bob * 1.4f, 0);
        ps.scale(sqX, sqY, 1f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f - hurt * 0.6f, 1f - hurt * 0.6f, a);
        if (etex[i] != null) g.blit(etex[i], -w / 2, -h, w, h, 0, 0, d.tex_w, d.tex_h, d.tex_w, d.tex_h);
        else g.fill(-w / 2, -h, w / 2, 0, Encounter.parseColor(d.color, 0xFF88AA88));
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        ps.popPose();
    }

    // ---------- панели героев ----------
    private void drawIcon(GuiGraphics g, int k, int x, int y, int w, int h, int c) {
        int cx = x + w / 2, cy = y + h / 2;
        switch (k) {
            case 0 -> { for (int s = 0; s < 14; s++) g.fill(cx - 8 + s, cy + 7 - s, cx - 6 + s, cy + 9 - s, c); g.fill(cx - 8, cy + 4, cx - 3, cy + 6, c); g.fill(cx - 6, cy + 3, cx - 4, cy + 8, c); }
            case 1 -> text(g, "ACT", cx - 11, cy - 5, 1.1f, c);
            case 2 -> { g.fill(cx - 6, cy - 3, cx + 6, cy + 8, c); g.fill(cx - 4, cy - 7, cx + 4, cy - 3, c); g.fill(cx - 3, cy - 1, cx + 3, cy + 5, 0xFF000000); }
            case 3 -> { for (int s = -7; s <= 7; s++) { g.fill(cx + s - 1, cy + s - 1, cx + s + 1, cy + s + 1, c); g.fill(cx + s - 1, cy - s - 1, cx + s + 1, cy - s + 1, c); } }
            default -> { g.fill(cx - 7, cy - 7, cx + 7, cy + 2, c); for (int s = 0; s < 6; s++) g.fill(cx - 7 + s, cy + 2 + s, cx + 7 - s, cy + 3 + s, c); g.fill(cx - 4, cy - 4, cx + 4, cy + 2, 0xFF000000); }
        }
    }

    private void drawStrip(GuiGraphics g) {
        float ly = lineY();
        int line = Math.round(ly);
        if (menuT < 0.5f) g.fill(0, 323, W, 325, LINE);
        g.fill(0, line, W, line + 3, LINE);
        int n = Math.max(1, b.n), w = W / n;
        for (int i = 0; i < b.n; i++) {
            int x0 = i * w;
            boolean active = i == b.myIdx && menuT > 0.5f;
            int c = 0xFF000000 | ClientBattle.COLORS[i];
            int hy = Math.round(active ? ly - 74f : ly - 40f);
            if (active) {
                int top = Math.round(ly - 80f);
                g.fill(x0, top, x0 + w, line, 0xFF000000);
                g.fill(x0, top, x0 + w, top + 3, c); g.fill(x0, top, x0 + 3, line, c); g.fill(x0 + w - 3, top, x0 + w, line, c);
            }
            // иконка-лицо, имя, HP
            face(g, i, x0 + 12, hy + 8, 24);
            String nm = b.names[i].toUpperCase();
            while (tw(nm, 2f) > w - 150 && nm.length() > 3) nm = nm.substring(0, nm.length() - 1);
            text(g, nm, x0 + 46, hy + 12, 2f, b.down[i] ? 0xFF777777 : WHITE);
            textRight(g, b.hp[i] + "/" + b.max[i], x0 + w - 10, hy + 6, 1.5f, WHITE);
            text(g, "HP", x0 + w - 104, hy + 25, 1.1f, WHITE);
            int bx = x0 + w - 86, by = hy + 24;
            g.fill(bx, by, bx + 76, by + 9, 0xFF3A0000);
            g.fill(bx, by, bx + Math.round(76f * Math.max(0f, Math.min(1f, dHp[i] / b.max[i]))), by + 9, b.down[i] ? 0xFF777777 : c);
            if (b.ready[i] && b.phase == Battle.MENU && !active) text(g, "OK", x0 + 46, hy + 30, 1.1f, 0xFF40FF60);
            if (active) {
                for (int k = 0; k < 5; k++) {
                    int bxx = x0 + 19 + k * 36, byy = line - 32;
                    boolean s = ui == UI_MAIN && sel == k;
                    g.fill(bxx, byy, bxx + 31, byy + 27, s ? YELLOW : ORANGE_DIM);
                    g.fill(bxx + 2, byy + 2, bxx + 29, byy + 25, 0xFF000000);
                    drawIcon(g, k, bxx, byy, 31, 27, s ? YELLOW : ORANGE);
                }
                String l = tr("deltabattle.menu." + sel).toUpperCase();
                text(g, l, x0 + 19 + sel * 36 + 15 - tw(l, 1f) / 2f, line - 4, 1f, YELLOW);
            }
        }
    }

    // ---------- рамка атаки ----------
    private void drawBox(GuiGraphics g, PoseStack ps, float ox, float oy, float u, long now) {
        float k = boxT;
        int w = Math.round(b.boxW * k), h = Math.round(b.boxH * k);
        int x = BOX_CX - w / 2, y = BOX_CY - h / 2;
        g.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF00FF00);
        g.fill(x, y, x + w, y + h, 0xFF000000);
        if (b.phase != Battle.ATTACK || k < 0.9f) return;
        refreshAtkTex();
        int bx = BOX_CX - b.boxW / 2, by = BOX_CY - b.boxH / 2;
        g.enableScissor(Math.round(ox + (bx - 3) * u), Math.round(oy + (by - 3) * u), Math.round(ox + (bx + b.boxW + 3) * u), Math.round(oy + (by + b.boxH + 3) * u));
        Pattern.Frame f = b.frame;
        for (int i = 0; i < f.n; i++) {
            int ai = f.att[i];
            if (ai >= b.active.length) continue;
            Encounter.AttackDef def = b.active[ai].def;
            float cx = bx + f.x[i], cy = by + f.y[i];
            if (atkTex[ai] != null) {
                int sw = Math.round(def.w * def.scale), sh = Math.round(def.h * def.scale);
                g.blit(atkTex[ai], Math.round(cx - sw / 2f), Math.round(cy - sh / 2f), sw, sh, 0, 0, def.w, def.h, def.w, def.h);
            } else {
                int r = Math.max(2, Math.round(def.radius));
                for (int dy = -r; dy <= r; dy++) {
                    int half = (int) Math.sqrt(r * r - dy * dy);
                    g.fill(Math.round(cx) - half, Math.round(cy) + dy, Math.round(cx) + half + 1, Math.round(cy) + dy + 1, WHITE);
                }
            }
        }
        for (int i = 0; i < b.n; i++) {
            if (b.down[i]) continue;
            boolean flashing = now < b.flashUntil[i] && ((now / 60_000_000L) & 1) == 0;
            if (i == b.myIdx) soul(g, ps, bx + b.sx[i], by + b.sy[i], 0xFF0000, flashing ? 0.25f : 1f);
            else if (!flashing) soul(g, ps, bx + b.sx[i], by + b.sy[i], ClientBattle.COLORS[i], 0.75f);
        }
        g.disableScissor();
    }

    private void soul(GuiGraphics g, PoseStack ps, float cx, float cy, int rgb, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, alpha);
        g.blit(SOUL, Math.round(cx - 9), Math.round(cy - 8), 18, 16, 0, 0, 9, 8, 9, 8);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private void refreshAtkTex() {
        if (atkTexFor == b.active) return;
        atkTexFor = b.active;
        atkTex = new ResourceLocation[b.active.length];
        for (int i = 0; i < atkTex.length; i++) {
            String s = b.active[i].def.texture;
            atkTex[i] = (s == null || s.isEmpty()) ? null : ResourceLocation.tryParse(s);
        }
    }

    // ---------- текст внизу ----------
    private void drawTextArea(GuiGraphics g, PoseStack ps, long now) {
        int top = Math.round(lineY()) + 3;
        if (b.phase == Battle.ATTACK) return;
        if (ui == UI_TARGET) {
            List<Integer> al = aliveEnemies();
            for (int r = 0; r < al.size(); r++) {
                int e = al.get(r), y = top + 8 + r * 22;
                EnemyDef d = b.enc.enemies.get(e);
                if (r == tsel) heart(g, 36, y + 8, 0xFF0000);
                text(g, tr(d.name), 60, y, 2f, b.emercy[e] >= 100 ? YELLOW : WHITE);
                float pct = Math.max(0f, Math.min(1f, (float) b.ehp[e] / d.hp));
                g.fill(300, y + 3, 380, y + 12, 0xFF550000); g.fill(300, y + 3, 300 + Math.round(80 * pct), y + 12, 0xFF30D050);
                text(g, Math.round(pct * 100) + "%", 386, y + 1, 1.5f, WHITE);
                text(g, "MERCY", 440, y + 1, 1.5f, 0xFFFFA040);
                g.fill(500, y + 3, 560, y + 12, 0xFF402000); g.fill(500, y + 3, 500 + 60 * b.emercy[e] / 100, y + 12, YELLOW);
                text(g, b.emercy[e] + "%", 566, y + 1, 1.5f, WHITE);
            }
        } else if (ui == UI_ACT) {
            int page = actSel / 4;
            for (int i = page * 4; i < Math.min(menu.size(), page * 4 + 4); i++) {
                int l = i - page * 4, x = 60 + (l % 2) * 290, y = top + 10 + (l / 2) * 30;
                MenuEntry m = menu.get(i);
                boolean can = b.tp[b.myIdx] + 4 >= m.tp;
                if (i == actSel) heart(g, x - 24, y + 9, 0xFF0000);
                String label = tr(m.name) + (m.tp > 0 ? " (" + m.tp + "%TP)" : "");
                text(g, label, x, y, 2.1f, !can ? 0xFF808080 : WHITE);
            }
        } else if (ui == UI_FIGHT) {
            float m = Math.max(0f, 1f - (now - fightStart) / 1_000_000_000f);
            int x0 = 40, x1 = 600, y0 = top + 12, y1 = y0 + 46;
            g.fill(x0, y0, x1, y1, WHITE); g.fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, 0xFF000000);
            int tx = x0 + Math.round(0.18f * (x1 - x0));
            g.fill(tx - 9, y0 + 3, tx + 9, y1 - 3, 0xFF204020); g.fill(tx - 1, y0 + 3, tx + 1, y1 - 3, 0xFF40FF60);
            int mxp = x0 + Math.round(m * (x1 - x0));
            g.fill(mxp - 3, y0 - 3, mxp + 3, y1 + 3, WHITE);
        } else if (!fullText.isEmpty()) {
            int shown = Math.min(fullText.length(), (int) ((now - typeStart) / 22_000_000L));
            float sc = TS;
            List<String> lines = wrap(fullText, sc, 540);
            if (lines.size() > 2) { sc = 1.9f; lines = wrap(fullText, sc, 540); }
            int y = top + 14, lh = sc == TS ? 28 : 22, used = 0;
            text(g, "*", 22, y, sc, WHITE);
            for (String ln : lines) {
                if (used >= shown) break;
                int take = Math.min(ln.length(), shown - used);
                text(g, ln.substring(0, take), 58, y, sc, WHITE);
                used += ln.length() + 1; y += lh;
            }
        }
    }

    private void heart(GuiGraphics g, int cx, int cy, int rgb) { soul(g, null, cx, cy, rgb, 1f); }

    /** Реплика врага посреди атаки (AttackDef.say / say_tick). */
    private void drawSay(GuiGraphics g, long now) {
        float t = b.phaseTicks();
        for (int k = 0; k < b.active.length && k < b.activeEnemy.length; k++) {
            Encounter.AttackDef d = b.active[k].def;
            if (d.say.isEmpty() || t < d.say_tick || t > d.say_tick + 80) continue;
            int e = b.activeEnemy[k];
            if (!b.enemyAlive(e)) continue;
            String txt = tr(d.say, "", enemyName(e));
            int shown = Math.min(txt.length(), (int) ((t - d.say_tick) * 1.6f));
            List<String> lines = wrap(txt, 1.6f, 150);
            int h = 18 + lines.size() * 16, wBub = 168;
            int x = Math.round(ecx(e) - edh(e) * 0.9f - wBub), y = Math.max(6, Math.round(ebase(e) - edh(e) * 0.8f));
            x = Math.max(4, Math.min(x, W - wBub - 14));
            g.fill(x, y, x + wBub, y + h, WHITE); g.fill(x + wBub, y + 24, x + wBub + 10, y + 36, WHITE);
            int used = 0, yy = y + 9;
            for (String ln : lines) {
                if (used >= shown) break;
                text(g, ln.substring(0, Math.min(ln.length(), shown - used)), x + 9, yy, 1.6f, 0xFF000000);
                used += ln.length() + 1; yy += 16;
            }
        }
    }

    private void drawBubbles(GuiGraphics g, long now) {
        for (int i = 0; i < b.ne && i < b.phaseTexts.length; i++) {
            String raw = b.phaseTexts[i];
            if (raw == null || raw.isEmpty() || !b.enemyAlive(i)) continue;
            String s = tr(raw, "", enemyName(i));
            int shown = Math.min(s.length(), (int) ((now - b.phaseStart) / 45_000_000L));
            List<String> lines = wrap(s, 1.6f, 150);
            int h = 18 + lines.size() * 16, wBub = 168;
            int x = Math.max(4, Math.round(ecx(i) - edh(i) * 0.9f - wBub));
            int y = Math.max(6, Math.round(ebase(i) - edh(i) * 0.8f));
            g.fill(x, y, x + wBub, y + h, WHITE); g.fill(x + wBub, y + 24, x + wBub + 10, y + 36, WHITE);
            int used = 0, yy = y + 9;
            for (String ln : lines) {
                if (used >= shown) break;
                text(g, ln.substring(0, Math.min(ln.length(), shown - used)), x + 9, yy, 1.6f, 0xFF000000);
                used += ln.length() + 1; yy += 16;
            }
        }
    }
}
