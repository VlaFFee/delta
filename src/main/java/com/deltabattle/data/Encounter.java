package com.deltabattle.data;

import com.google.gson.Gson;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Описание одной битвы (JSON в data/&lt;namespace&gt;/encounters/&lt;id&gt;.json).
 * Класс общий для сервера и клиента: сервер читает датапак и присылает клиенту этот же JSON в пакете START.
 * Все тексты - либо ключи локализации (lang), либо обычный текст. Имена полей = ключи JSON.
 */
public class Encounter {
    public static final Gson GSON = new Gson();

    public transient String id = "";
    private transient String json;

    /** Текст в начале боя (по умолчанию стандартный). */
    public String intro = "";
    public String win_text = "", lose_text = "", spare_text = "";
    /** HP каждого игрока в бою, базовая сила удара игрока. */
    public int party_hp = 20, player_atk = 14;
    /** Награда за победу (убийство). gold выдаётся золотыми самородками. */
    public int exp = 0, gold = 0;
    /** Цвета фона и сетки: "#RRGGBB". */
    public String bg = "#000000", grid = "#5A1A6E";
    /** Моб в мире, созданный командой summon: текстура (по умолчанию - текстура первого врага), размер, поведение hop|still. */
    public String world_texture = "", world_behavior = "hop";
    public float world_scale = 1f;

    public List<EnemyDef> enemies = new ArrayList<>();
    /** Заклинания пати (ACT-меню). null = стандартное "Лечение". */
    public List<SpellDef> spells;
    /** Предметы из инвентаря для команды ITEM. null = Монстр-конфета. */
    public List<ItemDef> items;
    public List<RewardItem> reward_items = new ArrayList<>();
    /** Музыка боя: id звука (например "deltabattle:music.battle1" или "minecraft:music_disc.pigstep"). */
    public String music = "";
    /** Реплики/текст/музыка в нужный момент (см. Cue). */
    public List<Cue> cues = new ArrayList<>();
    /** Модели героев (GeckoLib) по номеру в пати. Нет модели у героя = встроенный силуэт. */
    public List<ModelDef> party_models = new ArrayList<>();
    /** Команды, выполняемые от имени каждого участника (права 4). Например: "give @s minecraft:diamond 3". */
    public List<String> win_commands = new ArrayList<>(), spare_commands = new ArrayList<>(), lose_commands = new ArrayList<>();

    // ---------------------------------------------------------------- вложенные описания

    public static class EnemyDef {
        public String name = "Enemy";
        public int hp = 30, atk = 4, def = 0;
        /** "modid:textures/gui/x.png"; пусто = цветной прямоугольник. */
        public String texture = "";
        /** Размер png в пикселях и размер на экране (в единицах сетки 320x240; на экране х2). */
        public int tex_w = 48, tex_h = 48, width = 96, height = 96;
        /** Цвет заглушки, если нет текстуры. */
        public String color = "#88AA88";
        /** Модель GeckoLib (geo.json + animation.json + текстура). Если задана geo - заменяет спрайт. */
        public ModelDef model = new ModelDef();
        /** Амплитуда подпрыгивания и сплющивания (0 = стоит неподвижно). */
        public float bob = 7f, squash = 0.07f;
        public List<String> flavor = new ArrayList<>();
        public String flavor_spareable = "", flavor_low = "";
        public List<ActDef> acts = new ArrayList<>();
        /** Реплики перед атакой (пузырь). */
        public List<String> dialogue = new ArrayList<>();
        /** random (с учётом weight) | sequence (по порядку). */
        public String attack_mode = "random";
        public List<AttackDef> attacks = new ArrayList<>();
        /** Фазы босса: когда HP% <= hp_pct, подменяют attacks/dialogue/flavor. */
        public List<PhaseDef> phases = new ArrayList<>();

        void normalize() {
            if (model == null) model = new ModelDef();
            model.normalize();
            if (flavor == null) flavor = new ArrayList<>();
            if (acts == null) acts = new ArrayList<>();
            if (dialogue == null) dialogue = new ArrayList<>();
            if (attacks == null) attacks = new ArrayList<>();
            if (phases == null) phases = new ArrayList<>();
            if (flavor_spareable == null) flavor_spareable = "";
            if (flavor_low == null) flavor_low = "";
            hp = Math.max(1, hp);
            if (acts.isEmpty()) {
                ActDef c = new ActDef();
                c.name = "deltabattle.menu.act0";
                c.text = "deltabattle.act.check_generic";
                acts.add(c);
            }
            for (AttackDef a : attacks) a.normalize();
            for (PhaseDef p : phases) {
                if (p.attacks != null) for (AttackDef a : p.attacks) a.normalize();
            }
        }

        /** Индекс активной фазы (-1 = базовая): последняя подошедшая по HP или по ходу. */
        public int phaseFor(int curHp, int turn) {
            float pct = 100f * curHp / Math.max(1, hp);
            int best = -1;
            for (int i = 0; i < phases.size(); i++) {
                PhaseDef p = phases.get(i);
                boolean m = (p.hp_pct > 0 && pct <= p.hp_pct) || (p.turn > 0 && turn >= p.turn);
                if (m) best = i;
            }
            return best;
        }

        public List<AttackDef> attacksFor(int ph) {
            if (ph >= 0 && ph < phases.size() && phases.get(ph).attacks != null) return phases.get(ph).attacks;
            return attacks;
        }

        public List<String> dialogueFor(int ph) {
            if (ph >= 0 && ph < phases.size() && phases.get(ph).dialogue != null) return phases.get(ph).dialogue;
            return dialogue;
        }

        public List<String> flavorFor(int ph) {
            if (ph >= 0 && ph < phases.size() && phases.get(ph).flavor != null) return phases.get(ph).flavor;
            return flavor;
        }
    }

    /**
     * Модель персонажа GeckoLib. Файлы лежат в ресурсах: assets/&lt;ns&gt;/geo/имя.geo.json,
     * assets/&lt;ns&gt;/animations/имя.animation.json, assets/&lt;ns&gt;/textures/...png.
     * В редакторе файлы можно просто перетащить в окно. Имена анимаций - из animation.json (например animation.knight.idle).
     */
    public static class ModelDef {
        public String geo = "";
        public String animations = "";
        public String texture = "";
        /** Размер, сдвиг на экране боя (пиксели) и поворот (градусы). */
        public float scale = 1f, offset_x = 0f, offset_y = 0f, yaw = 0f;
        public String anim_idle = "", anim_walk = "", anim_attack = "", anim_hurt = "", anim_talk = "", anim_defeat = "", anim_spare = "";

        public boolean isSet() { return geo != null && !geo.isEmpty(); }

        public void normalizePublic() { normalize(); }

        void normalize() {
            if (geo == null) geo = "";
            if (animations == null) animations = "";
            if (texture == null) texture = "";
            if (anim_idle == null) anim_idle = "";
            if (anim_walk == null) anim_walk = "";
            if (anim_attack == null) anim_attack = "";
            if (anim_hurt == null) anim_hurt = "";
            if (anim_talk == null) anim_talk = "";
            if (anim_defeat == null) anim_defeat = "";
            if (anim_spare == null) anim_spare = "";
            if (scale <= 0) scale = 1f;
        }

        public static final ResourceLocation PLACEHOLDER_GEO = new ResourceLocation("deltabattle", "geo/placeholder.geo.json");
        public static final ResourceLocation EMPTY_ANIM = new ResourceLocation("deltabattle", "animations/empty.animation.json");
        public static final ResourceLocation PLACEHOLDER_TEX = new ResourceLocation("deltabattle", "textures/npc/placeholder.png");

        private static ResourceLocation path(String s, String dir, String ext, ResourceLocation fallback) {
            ResourceLocation r = ResourceLocation.tryParse(s);
            if (r == null) return fallback;
            String p = r.getPath();
            if (!p.startsWith(dir)) p = dir + p;
            if (!p.endsWith(ext)) p = p + ext;
            return new ResourceLocation(r.getNamespace(), p);
        }

        /** "ns:name", "ns:geo/name.geo.json" или просто "name" - все варианты приводятся к полному пути. */
        public ResourceLocation geoRL() { return isSet() ? path(geo, "geo/", ".geo.json", PLACEHOLDER_GEO) : PLACEHOLDER_GEO; }
        public ResourceLocation animRL() { return animations.isEmpty() ? EMPTY_ANIM : path(animations, "animations/", ".animation.json", EMPTY_ANIM); }
        public ResourceLocation texRL() { return texture.isEmpty() ? PLACEHOLDER_TEX : path(texture, "textures/", ".png", PLACEHOLDER_TEX); }
    }

    public static class PhaseDef {
        /** Фаза включается, когда HP% врага <= hp_pct (0 = не по HP). */
        public float hp_pct = 0f;
        /** ...или когда номер хода >= turn (0 = не по ходу). Побеждает последняя подходящая фаза в списке. */
        public int turn = 0;
        /** Музыка этой стадии ("" = не менять, "stop" = выключить). */
        public String music;
        /** Текст в меню при входе в фазу (например "The boss gets serious."). */
        public String notice;
        public List<AttackDef> attacks;
        public List<String> dialogue;
        public List<String> flavor;
    }

    public static class ActDef {
        public String name = "Act";
        /** Текст события. */
        public String text = "";
        /** Реплика врага в следующей атаке. */
        public String reply = "";
        /** Прибавка к Mercy врага (100 = можно пощадить). */
        public int mercy = 0;
        public int damage = 0, heal = 0, tp_cost = 0;
    }

    /**
     * pattern: aimed | rain | sweep | ring | spiral | hop | bounce.
     * Все позиции пуль - чистая функция (seed, тик), поэтому по сети пули не передаются.
     */
    public static class AttackDef {
        public static final int AIMED = 0, RAIN = 1, SWEEP = 2, RING = 3, SPIRAL = 4, HOP = 5, BOUNCE = 6;
        public String pattern = "rain";
        /** Спрайт пули ("" = белый круг) и его размер на экране. */
        public String texture = "";
        public int w = 9, h = 6;
        /** Радиус попадания в пикселях. */
        public float radius = 7f;
        /** Множитель размера спрайта пули при рисовании. */
        public float scale = 1f;
        public int count = 5;
        /** Пикселей за тик (20 тиков в секунду). */
        public float speed = 2f;
        /** Тиков между появлениями. */
        public int interval = 9;
        public int start = 10;
        /** aimed: через сколько тиков после появления муха летит в душу. */
        public int delay = 26;
        public int duration = 150;
        /** 0 = атака врага (atk). */
        public int damage = 0;
        public int weight = 1;
        public int box_w = 150, box_h = 150;
        /** Откуда: top | bottom | left | right | random. */
        public String from = "top";
        public float wobble = 0f;
        public int waves = 3, gap = 1;
        public float angle_step = 0.55f, hover = 22f;
        /** Реплика врага (пузырь) во время этой атаки: текст и тик начала. */
        public String say = "";
        public int say_tick = 20;

        private transient int kind = -1, edge = -2;
        private transient String kindFor, edgeFor;

        public void normalize() {
            count = Math.max(1, Math.min(count, 32));
            interval = Math.max(1, interval);
            duration = Math.max(10, duration);
            box_w = Math.max(40, Math.min(box_w, 300));
            box_h = Math.max(40, Math.min(box_h, 200));
            weight = Math.max(1, weight);
            waves = Math.max(1, Math.min(waves, 8));
            if (pattern == null) pattern = "rain";
            if (from == null) from = "top";
            if (texture == null) texture = "";
            if (say == null) say = "";
        }

        public int kind() {
            if (kind < 0 || pattern != kindFor) {
                kindFor = pattern;
                kind = switch (pattern.toLowerCase()) {
                    case "aimed", "flies", "homing" -> AIMED;
                    case "sweep", "wall" -> SWEEP;
                    case "ring" -> RING;
                    case "spiral" -> SPIRAL;
                    case "hop", "frog" -> HOP;
                    case "bounce" -> BOUNCE;
                    default -> RAIN;
                };
            }
            return kind;
        }

        /** 0 top, 1 bottom, 2 left, 3 right, -1 random. */
        public int edge() {
            if (edge == -2 || from != edgeFor) {
                edgeFor = from;
                edge = switch (from.toLowerCase()) {
                    case "bottom" -> 1;
                    case "left" -> 2;
                    case "right" -> 3;
                    case "random" -> -1;
                    default -> 0;
                };
            }
            return edge;
        }
    }

    public static class SpellDef {
        public String name = "Spell";
        public String text = "";
        public int tp_cost = 0;
        /** heal_party | damage | mercy. */
        public String type = "heal_party";
        public int power = 6;
        /** -1 = доступно всем, иначе только игроку с этим номером в пати (0..3). */
        public int slot = -1;
    }

    public static class ItemDef {
        public String item = "deltabattle:monster_candy";
        public int heal = 10;
        public String text = "deltabattle.item.candy";
    }

    /**
     * Срабатывает один раз. when: start | turn | hp_below | enemy_killed | enemy_spared | player_down.
     * n: для turn - номер хода, для hp_below - проценты HP. enemy: номер врага с 0 (-1 = любой).
     */
    public static class Cue {
        public String when = "turn";
        public int n = 1;
        public int enemy = 0;
        /** Текст в нижнем поле в ближайшем меню. */
        public String text = "";
        /** Реплика врага (пузырь) в ближайшей атаке. */
        public String dialogue = "";
        /** "" = не менять, "stop" = выключить, иначе id звука (зацикливается). */
        public String music = "";
        /** Звук-эффект один раз. */
        public String sfx = "";
    }

    public static class RewardItem {
        public String item = "minecraft:gold_nugget";
        public int count = 1;
    }

    /** Строка ACT-меню: либо действие врага, либо заклинание. Клиент и сервер строят список одинаково. */
    public static class MenuEntry {
        public String name;
        public int tp;
        public boolean spell;
        public ActDef act;
        public SpellDef spellDef;
    }

    public List<MenuEntry> actMenu(EnemyDef e, int slot) {
        List<MenuEntry> out = new ArrayList<>();
        for (ActDef a : e.acts) {
            MenuEntry m = new MenuEntry();
            m.name = a.name; m.tp = a.tp_cost; m.act = a;
            out.add(m);
        }
        for (SpellDef s : spells) {
            if (s.slot >= 0 && s.slot != slot) continue;
            MenuEntry m = new MenuEntry();
            m.name = s.name; m.tp = s.tp_cost; m.spell = true; m.spellDef = s;
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- служебное

    public Encounter normalize(String id) {
        this.id = id;
        json = null;
        if (enemies == null) enemies = new ArrayList<>();
        if (enemies.isEmpty()) enemies.add(new EnemyDef());
        if (enemies.size() > 3) enemies = new ArrayList<>(enemies.subList(0, 3));
        if (spells == null) {
            spells = new ArrayList<>();
            SpellDef h = new SpellDef();
            h.name = "deltabattle.menu.act3"; h.tp_cost = 32; h.type = "heal_party"; h.power = 6; h.text = "deltabattle.act.heal";
            spells.add(h);
        }
        if (items == null) { items = new ArrayList<>(); items.add(new ItemDef()); }
        if (reward_items == null) reward_items = new ArrayList<>();
        if (win_commands == null) win_commands = new ArrayList<>();
        if (spare_commands == null) spare_commands = new ArrayList<>();
        if (lose_commands == null) lose_commands = new ArrayList<>();
        if (intro == null) intro = "";
        if (music == null) music = "";
        if (cues == null) cues = new ArrayList<>();
        for (Cue c : cues) {
            if (c.when == null) c.when = "turn";
            if (c.text == null) c.text = "";
            if (c.dialogue == null) c.dialogue = "";
            if (c.music == null) c.music = "";
            if (c.sfx == null) c.sfx = "";
        }
        if (party_models == null) party_models = new ArrayList<>();
        for (ModelDef m : party_models) m.normalize();
        if (win_text == null) win_text = "";
        if (lose_text == null) lose_text = "";
        if (spare_text == null) spare_text = "";
        party_hp = Math.max(1, party_hp);
        for (EnemyDef e : enemies) e.normalize();
        return this;
    }

    public static final com.google.gson.Gson PRETTY = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    /** Новая битва для редактора: один враг с одной атакой. */
    public static Encounter template(String id) {
        Encounter e = new Encounter();
        EnemyDef d = new EnemyDef();
        d.name = "New Enemy"; d.hp = 60; d.atk = 6;
        d.flavor.add("Something blocks the way.");
        AttackDef a = new AttackDef();
        a.pattern = "rain"; a.count = 10; a.speed = 3f; a.interval = 8; a.start = 8; a.radius = 7f;
        d.attacks.add(a);
        e.enemies.add(d);
        return e.normalize(id);
    }

    public String toJson() {
        if (json == null) json = GSON.toJson(this);
        return json;
    }

    public static Encounter fromJson(String s, String id) {
        return GSON.fromJson(s, Encounter.class).normalize(id);
    }

    /** "#RRGGBB" -> 0xFFRRGGBB. */
    public static int parseColor(String hex, int fallback) {
        try {
            return 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
        } catch (Exception e) {
            return fallback;
        }
    }
}
