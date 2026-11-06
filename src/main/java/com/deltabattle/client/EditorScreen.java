package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.deltabattle.battle.Pattern;
import com.deltabattle.data.Encounter;
import com.deltabattle.data.Encounter.AttackDef;
import com.deltabattle.data.Encounter.EnemyDef;
import com.deltabattle.data.Encounter.ModelDef;
import com.deltabattle.entity.BattleMobEntity;
import com.deltabattle.net.C2S;
import com.deltabattle.net.Net;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Редактор битв в стиле BBS: слева ДЕРЕВО битвы (враги, атаки, стадии, триггеры...), справа СВОЙСТВА выбранного,
 * в центре ВЬЮПОРТ: для модели - 3D-превью с кнопками анимаций, для атаки - живое превью с таймлайном.
 * Файлы моделей (.geo.json, .animation.json, .png, .ogg) импортируются перетаскиванием в окно.
 * Форма строится рефлексией по классам Encounter, поэтому любое новое поле битвы появляется в редакторе само.
 */
public class EditorScreen extends Screen {
    /** После тестового боя игрок возвращается в этот редактор. */
    public static EditorScreen PENDING;

    private static final ResourceLocation SOUL = new ResourceLocation(DeltaBattle.MODID, "textures/gui/soul.png");
    private static final Map<String, String[]> OPTIONS = Map.of(
            "pattern", new String[]{"aimed", "rain", "sweep", "ring", "spiral", "hop", "bounce"},
            "from", new String[]{"top", "bottom", "left", "right", "random"},
            "type", new String[]{"heal_party", "damage", "mercy"},
            "attack_mode", new String[]{"random", "sequence"},
            "world_behavior", new String[]{"hop", "still"},
            "when", new String[]{"start", "turn", "hp_below", "enemy_killed", "enemy_spared", "player_down"});
    private static final int TOP = 32, ROW_H = 16, PROW = 22;
    private static final int BG = 0xFF101018, PANEL = 0xFF181824, EDGE = 0xFF2C2C44, SEL = 0xFF2A3A6A, ACCENT = 0xFF6C9CFF;
    private static final int T_OBJ = 0, T_LIST = 1, T_STRINGS = 2;

    private static class TRow {
        Object obj; String label; int depth, type; boolean kids;
        List<Object> parent; Class<?> elem; List<Object> path;
    }

    private static class PRow { final String name, key; final int y; PRow(String n, String k, int y) { name = n; key = k; this.y = y; } }

    private static class Picker {
        final List<String> opts; final Consumer<String> pick; final int x, y, w; int scroll;
        Picker(List<String> o, Consumer<String> p, int x, int y, int w) { opts = o; pick = p; this.x = x; this.y = y; this.w = w; }
    }

    private final String id;
    private final Encounter enc;
    private final List<TRow> tree = new ArrayList<>();
    private final Set<Object> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<PRow> pRows = new ArrayList<>();
    private Object selected;
    private TRow selRow;
    private int treeScroll, propScroll, propContentH;
    private Picker picker;
    private boolean dirty;
    private String status = "";

    // вьюпорт
    private final Pattern.Frame frame = new Pattern.Frame();
    private final float[] aimX = new float[Pattern.AIM_SLOTS], aimY = new float[Pattern.AIM_SLOTS];
    private float previewT, vyaw, vzoom = 1f, soulX = -1, soulY = -1;
    private boolean playing = true, dragTimeline, dragModel;
    private BattleMobEntity viewPup;
    private long lastNs = System.nanoTime();

    public EditorScreen(String id, String json) {
        super(Component.literal("Encounter editor"));
        this.id = id;
        this.enc = Encounter.GSON.fromJson(json, Encounter.class).normalize(id);
        expanded.add(enc);
        expanded.add(enc.enemies);
        if (!enc.enemies.isEmpty()) expanded.add(enc.enemies.get(0));
        selected = enc;
    }

    @Override public boolean isPauseScreen() { return false; }

    // ---------- геометрия ----------
    private int leftW() { return Math.max(150, Math.min(230, width * 22 / 100)); }
    private int rightW() { return Math.max(210, Math.min(300, width * 30 / 100)); }
    private int vpX() { return leftW() + 6; }
    private int vpW() { return width - leftW() - rightW() - 12; }
    private int bottom() { return height - 22; }
    private int propX() { return width - rightW() + 6; }
    private int propW() { return rightW() - 12; }

    // ---------- рефлексия ----------
    private static List<Field> fieldsOf(Class<?> c) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) chain.add(0, k);
        List<Field> out = new ArrayList<>();
        for (Class<?> k : chain)
            for (Field f : k.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isPublic(m) && !Modifier.isStatic(m) && !Modifier.isTransient(m)) { f.setAccessible(true); out.add(f); }
            }
        return out;
    }

    private static Object get(Field f, Object o) { try { return f.get(o); } catch (Exception e) { return null; } }
    private static void set(Field f, Object o, Object v) { try { f.set(o, v); } catch (Exception ignored) { } }
    private static boolean isList(Class<?> t) { return List.class.isAssignableFrom(t); }
    private static boolean isLeaf(Class<?> t) { return t.isPrimitive() || t == String.class; }

    private static Class<?> elemType(Field f) {
        try { return (Class<?>) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0]; } catch (Exception e) { return String.class; }
    }

    private static String lang(String key, String fallback) { Language l = Language.getInstance(); return l.has(key) ? l.getOrDefault(key) : fallback; }
    private static String friendly(String name) { return lang("deltabattle.tree." + name, name); }
    private static String fieldLabel(String name) { return lang("deltabattle.field." + name, name); }
    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    private static String summary(Object o, int idx) {
        if (o instanceof AttackDef a) return a.pattern + " x" + a.count + (a.say.isEmpty() ? "" : " \"" + cut(a.say, 10) + "\"");
        if (o instanceof EnemyDef e) return e.name + (e.model.isSet() ? "  [3D]" : "");
        if (o instanceof Encounter.PhaseDef p) return (p.hp_pct > 0 ? "HP<=" + (int) p.hp_pct + "% " : "") + (p.turn > 0 ? "turn>=" + p.turn : "") + (p.hp_pct <= 0 && p.turn <= 0 ? "(?)" : "");
        if (o instanceof Encounter.Cue c) return c.when + (c.when.equals("turn") || c.when.equals("hp_below") ? " " + c.n : "") + "  " + cut(c.text.isEmpty() ? c.dialogue : c.text, 12);
        if (o instanceof Encounter.ActDef a) return a.name;
        if (o instanceof Encounter.SpellDef s) return s.name;
        if (o instanceof Encounter.ItemDef i) return i.item;
        if (o instanceof Encounter.RewardItem r) return r.item + " x" + r.count;
        if (o instanceof ModelDef m) return (m.isSet() ? cut(m.geo, 18) : "—") + "  #" + (idx + 1);
        return "#" + (idx + 1);
    }

    // ---------- дерево ----------
    @SuppressWarnings("unchecked")
    private void buildTree() {
        tree.clear();
        addObj(enc, id, 0, null, null, new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private void addObj(Object o, String label, int depth, List<Object> parent, Class<?> elem, List<Object> path) {
        List<Object> p2 = new ArrayList<>(path);
        p2.add(o);
        TRow r = new TRow();
        r.obj = o; r.label = label; r.depth = depth; r.type = T_OBJ; r.parent = parent; r.elem = elem; r.path = p2;
        tree.add(r);
        for (Field f : fieldsOf(o.getClass())) {
            Class<?> t = f.getType();
            if (isList(t) || !isLeaf(t)) { r.kids = true; break; }
        }
        if (!expanded.contains(o)) return;
        for (Field f : fieldsOf(o.getClass())) {
            Class<?> t = f.getType();
            if (isList(t)) {
                List<Object> list = (List<Object>) get(f, o);
                if (list == null) continue;
                Class<?> et = elemType(f);
                TRow lr = new TRow();
                lr.obj = list; lr.label = friendly(f.getName()) + " (" + list.size() + ")"; lr.depth = depth + 1;
                lr.type = et == String.class ? T_STRINGS : T_LIST; lr.elem = et; lr.path = p2; lr.kids = lr.type == T_LIST && !list.isEmpty();
                tree.add(lr);
                if (lr.type == T_LIST && expanded.contains(list))
                    for (int i = 0; i < list.size(); i++) addObj(list.get(i), summary(list.get(i), i), depth + 2, list, et, p2);
            } else if (!isLeaf(t)) {
                Object v = get(f, o);
                if (v != null) addObj(v, friendly(f.getName()), depth + 1, null, null, p2);
            }
        }
    }

    private TRow rowOf(Object o) { for (TRow r : tree) if (r.obj == o) return r; return null; }

    private void refresh() {
        buildTree();
        selRow = rowOf(selected);
        if (selRow == null) { selected = enc; selRow = rowOf(enc); }
        rebuildProps();
    }

    private void select(TRow r) {
        selected = r.obj; selRow = r; propScroll = 0; previewT = 0; viewPup = null; soulX = -1;
        rebuildProps();
    }

    // ---------- контекст ----------
    private ModelDef ctxModel() {
        if (selected instanceof ModelDef m) return m;
        if (selected instanceof EnemyDef e) return e.model;
        return null;
    }

    private AttackDef ctxAttack() { return selected instanceof AttackDef a ? a : null; }

    private EnemyDef ctxEnemy() {
        if (selRow == null) return null;
        for (int i = selRow.path.size() - 1; i >= 0; i--) if (selRow.path.get(i) instanceof EnemyDef e) return e;
        return null;
    }

    // ---------- виджеты ----------
    @Override protected void init() { refresh(); }

    private void rebuildProps() {
        clearWidgets();
        pRows.clear();
        topBar();
        buildProps();
    }

    private void topBar() {
        String[] keys = {"save", "test", "testattack", "spawn", "list"};
        int x = 6, bw = 84;
        for (String k : keys) {
            if (k.equals("testattack") && ctxAttack() == null) continue;
            addRenderableWidget(Button.builder(Component.translatable("deltabattle.ed." + k), b -> onTool(k)).bounds(x, 6, bw, 20).build());
            x += bw + 4;
        }
    }

    private void onTool(String k) {
        switch (k) {
            case "save" -> { upload(0, enc); dirty = false; status = lang("deltabattle.ed.saved", "Saved"); }
            case "test" -> { PENDING = this; upload(1, enc); }
            case "testattack" -> { PENDING = this; upload(1, attackTest()); }
            case "spawn" -> { upload(0, enc); dirty = false; Net.toServer(C2S.edit(C2S.EDIT_SPAWN, id)); }
            case "list" -> { saveIfDirty(); Net.toServer(C2S.edit(C2S.EDIT_LIST, "")); }
            default -> { }
        }
    }

    private void saveIfDirty() { if (dirty) { upload(0, enc); dirty = false; } }

    @Override public void onClose() { saveIfDirty(); PENDING = null; Minecraft.getInstance().setScreen(null); }

    private void upload(int mode, Encounter e) {
        String json = Encounter.GSON.toJson(e);
        int total = (json.length() + C2S.CHUNK - 1) / C2S.CHUNK;
        for (int i = 0; i < total; i++)
            Net.toServer(C2S.upload(id, mode, i, total, json.substring(i * C2S.CHUNK, Math.min(json.length(), (i + 1) * C2S.CHUNK))));
    }

    /** Мини-битва с одним врагом (с его моделью) и только выбранной атакой. */
    private Encounter attackTest() {
        Encounter t = new Encounter();
        t.party_hp = 999;
        t.party_models = new ArrayList<>(enc.party_models);
        EnemyDef e = new EnemyDef();
        EnemyDef src = ctxEnemy();
        if (src != null) {
            e.texture = src.texture; e.tex_w = src.tex_w; e.tex_h = src.tex_h; e.width = src.width; e.height = src.height; e.color = src.color;
            e.model = Encounter.GSON.fromJson(Encounter.GSON.toJson(src.model), ModelDef.class);
        }
        e.name = "Test"; e.hp = 99999; e.atk = 5;
        e.attacks.add(Encounter.GSON.fromJson(Encounter.GSON.toJson(selected), AttackDef.class));
        t.enemies.add(e);
        t.bg = enc.bg; t.grid = enc.grid;
        return t;
    }

    private <T extends AbstractWidget> T place(T w, int y) {
        w.setY(y);
        w.visible = y >= TOP && y + w.getHeight() <= bottom() + 2;
        return addRenderableWidget(w);
    }

    private List<String> optionsFor(Object owner, String name) {
        String[] en = OPTIONS.get(name);
        if (en != null) return Arrays.asList(en);
        if (owner instanceof ModelDef m) {
            switch (name) {
                case "geo": return ClientAssets.geo();
                case "animations": return ClientAssets.animFiles();
                case "texture": return ClientAssets.textures();
                default: if (name.startsWith("anim_")) return ClientAssets.animNames(m.animations);
            }
        }
        if (name.equals("texture")) return ClientAssets.textures();
        if (name.equals("music") || name.equals("sfx")) {
            List<String> l = new ArrayList<>(ClientAssets.musicIds());
            if (name.equals("music")) l.add(0, "stop");
            return l;
        }
        return null;
    }

    private EditBox box(int x, int w, String value, Consumer<String> sink) {
        EditBox eb = new EditBox(font, x, 0, w, 18, Component.empty());
        eb.setMaxLength(4000);
        eb.setValue(value == null ? "" : value.replace("\n", "\\n"));
        eb.setResponder(s -> sink.accept(s.replace("\\n", "\n")));
        return eb;
    }

    @SuppressWarnings("unchecked")
    private void buildProps() {
        int px = propX(), pw = propW(), y = TOP + 6 - propScroll;
        int lw = Math.min(104, pw / 2 - 4), wx = px + lw, ww = pw - lw;
        if (selected == null) return;
        // панель действий над элементом списка
        if (selRow != null && selRow.parent != null) {
            List<Object> list = selRow.parent;
            int idx = list.indexOf(selected);
            int bw = (pw - 12) / 4;
            String[] names = {"dup", "up", "down", "del"};
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                place(Button.builder(Component.translatable("deltabattle.ed." + names[k]), b -> {
                    if (idx < 0) return;
                    switch (kk) {
                        case 0 -> { Object c = Encounter.GSON.fromJson(Encounter.GSON.toJson(selected), selected.getClass()); list.add(idx + 1, c); selected = c; }
                        case 1 -> { if (idx > 0) Collections.swap(list, idx, idx - 1); }
                        case 2 -> { if (idx < list.size() - 1) Collections.swap(list, idx, idx + 1); }
                        default -> { list.remove(idx); selected = enc; }
                    }
                    dirty = true; viewPup = null; refresh();
                }).bounds(px + k * (bw + 4), 0, bw, 18).build(), y);
            }
            y += ROW_H + 8;
        }
        if (selected instanceof List<?> raw && selRow != null && selRow.type == T_STRINGS) {
            List<String> list = (List<String>) raw;
            for (int i = 0; i < list.size(); i++) {
                final int idx = i;
                pRows.add(new PRow("#" + (i + 1), "", y));
                place(box(px + 26, pw - 26 - 24, list.get(i), s -> { list.set(idx, s); dirty = true; }), y);
                place(Button.builder(Component.literal("X"), b -> { list.remove(idx); dirty = true; rebuildProps(); }).bounds(px + pw - 22, 0, 22, 18).build(), y);
                y += PROW;
            }
            place(Button.builder(Component.translatable("deltabattle.ed.add"), b -> { list.add(""); dirty = true; rebuildProps(); }).bounds(px, 0, pw, 18).build(), y);
            y += PROW;
        } else if (selected instanceof List<?> raw) {
            List<Object> list = (List<Object>) raw;
            Class<?> et = selRow.elem;
            place(Button.builder(Component.translatable("deltabattle.ed.add"), b -> {
                try {
                    Object n = et.getDeclaredConstructor().newInstance();
                    list.add(n); expanded.add(list); selected = n; dirty = true; refresh();
                } catch (Exception ex) { status = "Cannot add: " + ex; }
            }).bounds(px, 0, pw, 18).build(), y);
            y += PROW + 4;
            pRows.add(new PRow("", "tip_list", y));
            y += 40;
        } else {
            for (Field f : fieldsOf(selected.getClass())) {
                Class<?> t = f.getType();
                if (isList(t) || !isLeaf(t)) continue;
                String name = f.getName();
                pRows.add(new PRow(fieldLabel(name), name, y));
                Object owner = selected;
                if (t == String.class) {
                    List<String> opts = optionsFor(owner, name);
                    int bw = opts != null ? ww - 22 : ww;
                    EditBox eb = place(box(wx, bw, (String) get(f, owner), s -> { set(f, owner, s); dirty = true; }), y);
                    if (opts != null) {
                        place(Button.builder(Component.literal("v"), b -> {
                            List<String> o2 = new ArrayList<>();
                            o2.add("");
                            o2.addAll(optionsFor(owner, name));
                            picker = new Picker(o2, v -> { set(f, owner, v); dirty = true; rebuildProps(); }, wx, b.getY() + 20, ww + 40);
                        }).bounds(wx + bw + 2, 0, 20, 18).build(), y);
                    }
                } else if (t == int.class || t == float.class) {
                    boolean isInt = t == int.class;
                    EditBox eb = new EditBox(font, wx, 0, Math.min(ww, 110), 18, Component.empty());
                    eb.setFilter(s -> s.matches("-?\\d*\\.?\\d*"));
                    eb.setValue(String.valueOf(get(f, owner)));
                    eb.setResponder(s -> {
                        try { if (isInt) set(f, owner, (int) Float.parseFloat(s)); else set(f, owner, Float.parseFloat(s)); dirty = true; }
                        catch (NumberFormatException ignored) { }
                    });
                    place(eb, y);
                } else if (t == boolean.class) {
                    place(Button.builder(Component.literal(String.valueOf(get(f, owner))), b -> {
                        boolean nv = !(Boolean) get(f, owner); set(f, owner, nv); dirty = true; b.setMessage(Component.literal(String.valueOf(nv)));
                    }).bounds(wx, 0, 70, 18).build(), y);
                }
                y += PROW;
            }
            if (selected instanceof ModelDef) { pRows.add(new PRow("", "tip_model", y + 4)); y += 44; }
        }
        propContentH = y + propScroll - TOP;
        // кнопки анимаций под моделью
        ModelDef m = ctxModel();
        if (m != null && m.isSet()) {
            String[] an = {"idle", "walk", "attack", "hurt", "talk", "defeat", "spare"};
            int bw = Math.max(40, (vpW() - 8 - an.length * 3) / an.length), x = vpX() + 4;
            for (String a : an) {
                Button b = Button.builder(Component.literal(a), btn -> playAnim(m, a)).bounds(x, bottom() - 24, bw, 18).build();
                addRenderableWidget(b);
                x += bw + 3;
            }
        }
    }

    private void playAnim(ModelDef m, String kind) {
        if (viewPup == null) return;
        String a = switch (kind) { case "walk" -> m.anim_walk; case "attack" -> m.anim_attack; case "hurt" -> m.anim_hurt;
            case "talk" -> m.anim_talk; case "defeat" -> m.anim_defeat; case "spare" -> m.anim_spare; default -> m.anim_idle; };
        if (kind.equals("hurt")) { viewPup.playOnce(a, ClientAssets.animMillis(m.animations, a)); return; }
        viewPup.baseAnim = kind.equals("idle") ? "" : a;
        viewPup.baseHold = kind.equals("defeat") || kind.equals("spare");
        if (a.isEmpty()) status = lang("deltabattle.ed.anim_unset", "Animation is not set for") + " " + kind;
    }

    // ---------- ввод ----------
    @Override
    public void onFilesDrop(List<Path> paths) {
        int n = ClientAssets.importFiles(paths);
        status = n > 0 ? String.format(lang("deltabattle.ed.imported", "Imported %d file(s), reloading resources..."), n)
                : lang("deltabattle.ed.import_none", "Unsupported files. Drop .geo.json, .animation.json, .png or .ogg");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256 && picker != null) { picker = null; return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (picker != null) {
            int ph = pickerH(), qx = Math.min(picker.x, width - picker.w - 4), qy = Math.min(picker.y, height - ph - 4);
            int idx = (int) ((my - qy - 3) / 14) + picker.scroll;
            if (mx >= qx && mx < qx + picker.w && my >= qy && my < qy + ph && idx >= 0 && idx < picker.opts.size()) {
                Picker p = picker; picker = null; p.pick.accept(p.opts.get(idx));
            } else picker = null;
            return true;
        }
        if (my >= TOP && mx < leftW()) { treeClick(mx, my); setFocused(null); return true; }
        if (mx >= vpX() && mx < vpX() + vpW() && my >= TOP && my < bottom() && viewportClick(mx, my)) return true;
        return super.mouseClicked(mx, my, btn);
    }

    private void treeClick(double mx, double my) {
        int idx = (int) ((my - TOP - 4 + treeScroll) / ROW_H);
        if (idx < 0 || idx >= tree.size()) return;
        TRow r = tree.get(idx);
        int arrowX = 8 + r.depth * 12;
        boolean plus = (r.type == T_LIST || r.type == T_STRINGS) && mx >= leftW() - 22;
        if (plus) { addTo(r); return; }
        if (r.kids && mx >= arrowX - 2 && mx < arrowX + 12) {
            if (!expanded.remove(r.obj)) expanded.add(r.obj);
            buildTree(); selRow = rowOf(selected);
            return;
        }
        if (r.type != T_OBJ && r.kids) expanded.add(r.obj);
        select(r);
        buildTree(); selRow = rowOf(selected);
    }

    @SuppressWarnings("unchecked")
    private void addTo(TRow r) {
        try {
            if (r.type == T_STRINGS) { ((List<String>) r.obj).add(""); selected = r.obj; }
            else { Object n = r.elem.getDeclaredConstructor().newInstance(); ((List<Object>) r.obj).add(n); expanded.add(r.obj); selected = n; }
            dirty = true; propScroll = 0; viewPup = null; refresh();
        } catch (Exception ex) { status = "Cannot add: " + ex; }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragTimeline) { setTimeline(mx); return true; }
        if (dragModel) { vyaw += (float) dx * 0.8f; return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { dragTimeline = false; dragModel = false; return super.mouseReleased(mx, my, btn); }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (picker != null) { picker.scroll = Math.max(0, Math.min(Math.max(0, picker.opts.size() - 10), picker.scroll - (int) Math.signum(delta))); return true; }
        if (mx < leftW()) {
            int max = Math.max(0, tree.size() * ROW_H - (bottom() - TOP - 8));
            treeScroll = Math.max(0, Math.min(max, treeScroll - (int) (delta * 32)));
        } else if (mx > width - rightW()) {
            int max = Math.max(0, propContentH - (bottom() - TOP) + 20);
            int ns = Math.max(0, Math.min(max, propScroll - (int) (delta * 32)));
            if (ns != propScroll) { propScroll = ns; rebuildProps(); }
        } else if (ctxModel() != null) vzoom = Math.max(0.3f, Math.min(4f, vzoom + (float) delta * 0.1f));
        return true;
    }

    // ---------- вьюпорт: клики ----------
    private int tlX() { return vpX() + 34; }
    private int tlW() { return vpW() - 44; }
    private int tlY() { return bottom() - 30; }

    private boolean viewportClick(double mx, double my) {
        AttackDef a = ctxAttack();
        if (a != null) {
            if (mx >= vpX() + 6 && mx < vpX() + 28 && my >= tlY() && my < tlY() + 20) { playing = !playing; return true; }
            if (my >= tlY() - 4 && my < tlY() + 22 && mx >= tlX() - 2 && mx < tlX() + tlW() + 2) { dragTimeline = true; setTimeline(mx); return true; }
            float[] g = attackBox(a);
            if (mx >= g[0] && mx < g[0] + a.box_w * g[2] && my >= g[1] && my < g[1] + a.box_h * g[2]) {
                soulX = (float) ((mx - g[0]) / g[2]); soulY = (float) ((my - g[1]) / g[2]);
                return true;
            }
            return false;
        }
        if (ctxModel() != null && my < bottom() - 30) { dragModel = true; return true; }
        return false;
    }

    private void setTimeline(double mx) {
        AttackDef a = ctxAttack();
        if (a == null) return;
        playing = false;
        previewT = (float) Math.max(0, Math.min(1, (mx - tlX()) / tlW())) * (a.duration + 30);
    }

    /** Положение и масштаб рамки атаки во вьюпорте: {x, y, scale}. */
    private float[] attackBox(AttackDef a) {
        float areaW = vpW() - 16, areaH = tlY() - TOP - 40;
        float s = Math.max(0.2f, Math.min(areaW / (a.box_w + 6f), areaH / (a.box_h + 6f)));
        return new float[]{vpX() + (vpW() - a.box_w * s) / 2f, TOP + 24 + (areaH - a.box_h * s) / 2f, s};
    }

    // ---------- пикер ----------
    private int pickerH() { return Math.min(picker.opts.size(), 10) * 14 + 6; }

    private void drawPicker(GuiGraphics g, int mx, int my) {
        int h = pickerH();
        int x = Math.min(picker.x, width - picker.w - 4), y = Math.min(picker.y, height - h - 4);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x - 1, y - 1, x + picker.w + 1, y + h + 1, ACCENT);
        g.fill(x, y, x + picker.w, y + h, 0xFF0C0C14);
        for (int i = 0; i < 10 && picker.scroll + i < picker.opts.size(); i++) {
            String o = picker.opts.get(picker.scroll + i);
            int ry = y + 3 + i * 14;
            boolean hov = mx >= x && mx < x + picker.w && my >= ry && my < ry + 14;
            if (hov) g.fill(x + 1, ry, x + picker.w - 1, ry + 14, SEL);
            String shown = o.isEmpty() ? "—  (" + lang("deltabattle.ed.none", "none") + ")" : o;
            while (font.width(shown) > picker.w - 8 && shown.length() > 4) shown = shown.substring(1);
            g.drawString(font, shown, x + 4, ry + 3, o.isEmpty() ? 0xFF8888AA : 0xFFFFFFFF, false);
        }
        g.pose().popPose();
    }

    // ---------- отрисовка ----------
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        g.fill(0, 0, width, height, BG);
        g.fill(0, 0, width, TOP - 2, 0xFF0C0C14);
        // подсказка импорта
        String drop = lang("deltabattle.ed.drop_hint", "Drop .geo.json / .animation.json / .png / .ogg here to import");
        String dh = cut(drop, Math.max(10, (width - 450) / 6));
        g.drawString(font, dh, width - 8 - font.width(dh), 12, 0xFF7A7AA0, false);
        drawTree(g, mx, my);
        drawViewport(g, mx, my, dt);
        drawProps(g, mx, my);
        super.render(g, mx, my, pt);
        g.fill(0, bottom() + 2, width, height, 0xFF0C0C14);
        String hint = hoverHint(mx, my);
        String line = !hint.isEmpty() ? hint : status;
        g.drawString(font, cut(line, Math.max(10, width / 6)), 8, bottom() + 8, !hint.isEmpty() ? 0xFFB0E0FF : 0xFF80FF90, false);
        if (dirty) g.drawString(font, "*", width - 12, 12, 0xFFFF6060, false);
        if (picker != null) drawPicker(g, mx, my);
    }

    private String hoverHint(int mx, int my) {
        if (mx < width - rightW()) return "";
        for (PRow r : pRows) {
            if (my >= r.y && my < r.y + PROW - 2 && !r.key.isEmpty()) {
                String k = "deltabattle.hint." + r.key;
                if (Language.getInstance().has(k)) return Language.getInstance().getOrDefault(k);
            }
        }
        return "";
    }

    private void drawTree(GuiGraphics g, int mx, int my) {
        int lw = leftW();
        g.fill(2, TOP, lw, bottom() + 2, PANEL);
        g.fill(lw, TOP, lw + 1, bottom() + 2, EDGE);
        g.enableScissor(2, TOP, lw, bottom() + 2);
        for (int i = 0; i < tree.size(); i++) {
            TRow r = tree.get(i);
            int y = TOP + 4 + i * ROW_H - treeScroll;
            if (y < TOP - ROW_H || y > bottom()) continue;
            boolean sel = r.obj == selected;
            boolean hov = mx < lw && my >= y && my < y + ROW_H;
            if (sel) g.fill(3, y, lw - 1, y + ROW_H - 1, SEL);
            else if (hov) g.fill(3, y, lw - 1, y + ROW_H - 1, 0xFF22223A);
            int x = 8 + r.depth * 12;
            if (r.kids) g.drawString(font, expanded.contains(r.obj) ? "v" : ">", x, y + 4, 0xFF9CB4FF, false);
            int col = r.type == T_OBJ ? (sel ? 0xFFFFFFFF : 0xFFE0E0F0) : 0xFFFFD060;
            g.drawString(font, cut(r.label, Math.max(6, (lw - x - 30) / 6)), x + 12, y + 4, col, false);
            if (r.type != T_OBJ) g.drawString(font, "+", lw - 16, y + 4, 0xFF60FF90, false);
        }
        g.disableScissor();
    }

    private void drawProps(GuiGraphics g, int mx, int my) {
        int x0 = width - rightW();
        g.fill(x0 - 1, TOP, width - 2, bottom() + 2, PANEL);
        g.fill(x0 - 1, TOP, x0, bottom() + 2, EDGE);
        g.enableScissor(x0, TOP, width - 2, bottom() + 2);
        for (PRow r : pRows) {
            if (r.y < TOP - 4 || r.y > bottom()) continue;
            if (r.key.startsWith("tip_")) {
                String t = lang("deltabattle." + r.key, "");
                int yy = r.y;
                for (String ln : wrapPlain(t, propW())) { g.drawString(font, ln, propX(), yy, 0xFF8C8CB4, false); yy += 11; }
            } else g.drawString(font, cut(r.name, Math.max(4, Math.min(104, propW() / 2 - 4) / 6)), propX(), r.y + 5, 0xFFC8C8E0, false);
        }
        g.disableScissor();
    }

    private List<String> wrapPlain(String s, int px) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : s.split(" ")) {
            if (font.width(cur + " " + w) > px && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            cur.append(cur.length() == 0 ? "" : " ").append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ---------- вьюпорт ----------
    private void drawViewport(GuiGraphics g, int mx, int my, float dt) {
        int x = vpX(), w = vpW(), yb = bottom() + 2;
        g.fill(x - 2, TOP, x + w + 2, yb, 0xFF0A0A12);
        g.enableScissor(x - 2, TOP, x + w + 2, yb);
        AttackDef a = ctxAttack();
        ModelDef m = ctxModel();
        if (a != null) drawAttack(g, a, mx, my, dt);
        else if (m != null) drawModel(g, m);
        else {
            String tip = lang("deltabattle.tip." + (selected == null ? "none" : selected.getClass().getSimpleName()), lang("deltabattle.tip.none", ""));
            int yy = TOP + 20;
            g.drawString(font, selected == null ? "" : selected.getClass().getSimpleName().replace("Def", ""), x + 10, TOP + 6, ACCENT, false);
            for (String ln : wrapPlain(tip, w - 20)) { g.drawString(font, ln, x + 10, yy, 0xFFB0B0D0, false); yy += 12; }
        }
        g.disableScissor();
    }

    private void drawModel(GuiGraphics g, ModelDef m) {
        int x = vpX(), w = vpW();
        g.drawString(font, lang("deltabattle.ed.model_view", "Model") + "  (" + lang("deltabattle.ed.drag_rotate", "drag = rotate, wheel = zoom") + ")", x + 6, TOP + 6, ACCENT, false);
        if (!m.isSet()) {
            int yy = TOP + 40;
            for (String ln : wrapPlain(lang("deltabattle.ed.no_model", "No model. Pick a geo file on the right, or drop .geo.json / .animation.json / .png onto this window."), w - 24)) {
                g.drawString(font, ln, x + 12, yy, 0xFFB0B0D0, false); yy += 12;
            }
            return;
        }
        if (viewPup == null) viewPup = ModelView.create(m);
        if (viewPup == null) return;
        ModelView.sync(viewPup, m);
        int baseY = bottom() - 60;
        g.fill(x + 10, baseY, x + w - 10, baseY + 1, 0xFF3A3A5A);
        ModelView.draw(g, viewPup, x + w / 2f + m.offset_x, baseY + m.offset_y, 110f * vzoom * m.scale, 180f + vyaw + m.yaw);
    }

    private void drawAttack(GuiGraphics g, AttackDef raw, int mx, int my, float dt) {
        AttackDef pd;
        try { pd = Encounter.GSON.fromJson(Encounter.GSON.toJson(raw), AttackDef.class); pd.normalize(); } catch (Exception e) { return; }
        int x = vpX(), w = vpW();
        float total = pd.duration + 30f;
        if (playing) previewT = (previewT + dt * 20f) % total;
        float[] gb = attackBox(pd);
        float s = gb[2];
        if (soulX < 0) { soulX = pd.box_w / 2f; soulY = pd.box_h * 0.65f; }
        Arrays.fill(aimX, Float.NaN);
        Arrays.fill(aimY, Float.NaN);
        int n = Math.min(pd.count, Pattern.MAX_PER);
        for (int i = 0; i < n; i++) {
            int fire = Pattern.fireTick(pd, i);
            if (fire >= 0 && previewT >= fire) { aimX[i] = soulX; aimY[i] = soulY; }
        }
        Pattern.collect(frame, new Pattern.Active[]{new Pattern.Active(pd, 1)}, 4242L, previewT, pd.box_w, pd.box_h, aimX, aimY);
        g.drawString(font, lang("deltabattle.ed.preview", "Attack preview") + "   " + pd.box_w + "x" + pd.box_h + "   (" + lang("deltabattle.ed.click_soul", "click the box to move the heart") + ")", x + 6, TOP + 6, ACCENT, false);
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(gb[0], gb[1], 0);
        ps.scale(s, s, 1f);
        g.fill(-3, -3, pd.box_w + 3, pd.box_h + 3, 0xFF00FF00);
        g.fill(0, 0, pd.box_w, pd.box_h, 0xFF000000);
        ResourceLocation tex = pd.texture.isEmpty() ? null : ResourceLocation.tryParse(pd.texture);
        for (int i = 0; i < frame.n; i++) {
            float cx = frame.x[i], cy = frame.y[i];
            if (cx < -20 || cy < -20 || cx > pd.box_w + 20 || cy > pd.box_h + 20) continue;
            if (tex != null) {
                int sw = Math.max(1, Math.round(pd.w * pd.scale)), sh = Math.max(1, Math.round(pd.h * pd.scale));
                g.blit(tex, Math.round(cx - sw / 2f), Math.round(cy - sh / 2f), sw, sh, 0, 0, pd.w, pd.h, pd.w, pd.h);
            } else {
                int r = Math.max(2, Math.round(pd.radius));
                for (int dy = -r; dy <= r; dy++) {
                    int half = (int) Math.sqrt(r * r - dy * dy);
                    g.fill(Math.round(cx) - half, Math.round(cy) + dy, Math.round(cx) + half + 1, Math.round(cy) + dy + 1, 0xFFFFFFFF);
                }
            }
        }
        RenderSystem.setShaderColor(1f, 0f, 0f, 1f);
        g.blit(SOUL, Math.round(soulX - 9), Math.round(soulY - 8), 18, 16, 0, 0, 9, 8, 9, 8);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        ps.popPose();
        // таймлайн
        int tx = tlX(), tw = tlW(), ty = tlY();
        g.fill(vpX() + 6, ty, vpX() + 28, ty + 20, 0xFF22223A);
        g.drawString(font, playing ? "||" : ">", vpX() + 14, ty + 6, 0xFFFFFFFF, false);
        g.fill(tx, ty, tx + tw, ty + 20, 0xFF14141F);
        g.fill(tx, ty + 19, tx + tw, ty + 20, EDGE);
        for (int i = 0; i < Math.min(pd.count, 64); i++) {
            int t0 = pd.kind() == AttackDef.RING ? pd.start + i * pd.interval : pd.start + i * pd.interval;
            if (pd.kind() == AttackDef.RING && i >= pd.waves) break;
            int mxp = tx + Math.round(tw * t0 / total);
            g.fill(mxp, ty + 8, mxp + 1, ty + 19, 0xFF6CB0FF);
            int fire = Pattern.fireTick(pd, i);
            if (fire >= 0) { int fx = tx + Math.round(tw * fire / total); g.fill(fx, ty + 3, fx + 1, ty + 12, 0xFFFFD060); }
        }
        if (!pd.say.isEmpty()) { int sx = tx + Math.round(tw * pd.say_tick / total); g.fill(sx, ty, sx + 2, ty + 20, 0xFF60FFD0); }
        int endx = tx + Math.round(tw * pd.duration / total);
        g.fill(endx, ty, endx + 1, ty + 20, 0xFFFF6060);
        int px = tx + Math.round(tw * previewT / total);
        g.fill(px - 1, ty - 2, px + 1, ty + 22, 0xFFFFFFFF);
        g.drawString(font, "t=" + (int) previewT + " / " + pd.duration, tx, ty - 12, 0xFF9090B0, false);
    }
}
