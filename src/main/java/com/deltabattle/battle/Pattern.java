package com.deltabattle.battle;

import com.deltabattle.data.Encounter.AttackDef;

/**
 * Детерминированные паттерны пуль. Позиция пули - чистая функция (параметры атаки, seed, время),
 * поэтому сервер и клиенты считают её сами, а по сети пули не передаются. Координаты - в пикселях коробки.
 * Без аллокаций в горячем цикле (случайность - хэш, а не java.util.Random).
 */
public final class Pattern {
    public static final float SOUL_HALF = 4f;       // половина хитбокса сердца
    public static final float SOUL_CLAMP = 9f;      // отступ центра сердца от рамки (сердце рисуется 18x16)
    public static final int MAX_PER = 32;           // максимум пуль на одну атаку (и размер блока aim-индексов)
    public static final int MAX_ATTACKS = 3;
    public static final int AIM_SLOTS = MAX_ATTACKS * MAX_PER;

    /** Атака, участвующая в текущем ходу (одна на врага). */
    public static final class Active {
        public final AttackDef def;
        public final int dmg;
        public Active(AttackDef def, int dmg) { this.def = def; this.dmg = dmg; }
    }

    /** Кадр пуль. Заполняется collect(). */
    public static final class Frame {
        public static final int CAP = 256;
        public final float[] x = new float[CAP], y = new float[CAP], r = new float[CAP];
        public final byte[] att = new byte[CAP];
        public final int[] dmg = new int[CAP];
        public int n;
        float tx, ty; // временные координаты (scratch, свои у каждого кадра => потокобезопасно)

        void add(float px, float py, float pr, int a, int d) {
            if (n >= CAP) return;
            x[n] = px; y[n] = py; r[n] = pr; att[n] = (byte) a; dmg[n++] = d;
        }
    }

    public static float rnd(long seed, int a, int b) {
        long z = seed + a * 0x9E3779B97F4A7C15L + b * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1 << 24);
    }

    public static long mix(long seed, int j) { return seed * 31 + j * 7919L; }

    /** Тик, в который i-я "муха" (aimed) выбирает цель. -1 для остальных паттернов. */
    public static int fireTick(AttackDef a, int i) {
        return a.kind() == AttackDef.AIMED ? a.start + i * a.interval + a.delay : -1;
    }

    private static float easeOut(float u) { return 1 - (1 - u) * (1 - u); }

    private static float tri(float v, float len) {
        float m = v % (2 * len);
        if (m < 0) m += 2 * len;
        return m < len ? m : 2 * len - m;
    }

    /** Точка на расстоянии d внутрь от края e (0 верх, 1 низ, 2 лево, 3 право) на позиции s вдоль края. */
    private static void edge(Frame f, int e, float s, float d, int bw, int bh) {
        switch (e) {
            case 1 -> { f.tx = s; f.ty = bh - d; }
            case 2 -> { f.tx = d; f.ty = s; }
            case 3 -> { f.tx = bw - d; f.ty = s; }
            default -> { f.tx = s; f.ty = d; }
        }
    }

    /** aimX/aimY - цели aimed-пуль (индекс = номер атаки * MAX_PER + номер пули), NaN если ещё не известны. */
    public static void collect(Frame f, Active[] acts, long seed, float t, int bw, int bh, float[] aimX, float[] aimY) {
        f.n = 0;
        for (int j = 0; j < acts.length; j++) {
            AttackDef a = acts[j].def;
            int dmg = acts[j].dmg;
            long sd = mix(seed, j);
            int n = Math.min(a.count, MAX_PER);
            float rad = a.radius;
            switch (a.kind()) {
                case AttackDef.AIMED -> {
                    for (int i = 0; i < n; i++) {
                        float launch = a.start + i * a.interval, lt = t - launch;
                        if (lt < 0) continue;
                        int e = a.edge() < 0 ? ((int) (rnd(sd, i, 9) * 4)) & 3 : a.edge();
                        float span = e < 2 ? bw : bh;
                        float s = 16 + (span - 32) * (0.1f + 0.8f * ((i * 0.37f + rnd(sd, i, 1) * 0.25f) % 1f));
                        float enter = Math.min(lt / 10f, 1f);
                        float d = -8 + (a.hover + 8) * easeOut(enter);
                        float bob = (float) Math.sin(t * 0.9f + i) * 1.2f;
                        float ft = t - (launch + a.delay);
                        int ai = j * MAX_PER + i;
                        if (ft < 0 || Float.isNaN(aimX[ai])) {
                            edge(f, e, s + bob, d, bw, bh);
                            f.add(f.tx, f.ty, rad, j, dmg);
                            continue;
                        }
                        edge(f, e, s, a.hover, bw, bh);
                        float hx = f.tx, hy = f.ty;
                        float dx = aimX[ai] - hx, dy = aimY[ai] - hy, len = (float) Math.sqrt(dx * dx + dy * dy);
                        if (len < 0.001f) len = 1;
                        float sp = a.speed * ft;
                        f.add(hx + dx / len * sp, hy + dy / len * sp, rad, j, dmg);
                    }
                }
                case AttackDef.RAIN -> {
                    for (int i = 0; i < n; i++) {
                        float lt = t - (a.start + i * a.interval);
                        if (lt < 0) continue;
                        int e = a.edge() < 0 ? ((int) (rnd(sd, i, 9) * 4)) & 3 : a.edge();
                        float span = e < 2 ? bw : bh;
                        float s = span * (0.05f + 0.9f * rnd(sd, i, 2)) + (float) Math.sin(lt * 0.18f + i) * a.wobble;
                        float d = -8 + lt * a.speed;
                        if (d > (e < 2 ? bh : bw) + 12) continue;
                        edge(f, e, s, d, bw, bh);
                        f.add(f.tx, f.ty, rad, j, dmg);
                    }
                }
                case AttackDef.SWEEP -> {
                    for (int i = 0; i < n; i++) {
                        float lt = t - (a.start + i * a.interval);
                        if (lt < 0) continue;
                        boolean left = (i & 1) == 0;
                        float y = bh * (0.08f + 0.84f * rnd(sd, i, 3));
                        float x = left ? -8 + lt * a.speed : bw + 8 - lt * a.speed;
                        if (x > bw + 12 || x < -12 - 8) continue;
                        f.add(x, y, rad, j, dmg);
                    }
                }
                case AttackDef.RING -> {
                    int cnt = Math.max(3, a.count);
                    float r0 = Math.max(bw, bh) * 0.75f + 12;
                    for (int w = 0; w < a.waves; w++) {
                        float lt = t - (a.start + w * a.interval);
                        if (lt < 0) continue;
                        float r = r0 - lt * a.speed;
                        if (r < -4) continue;
                        int gapIdx = (int) (rnd(sd, w, 3) * cnt);
                        float off = rnd(sd, w, 4) * 0.5f;
                        for (int k = 0; k < cnt; k++) {
                            if ((k - gapIdx + cnt) % cnt < a.gap) continue;
                            double ang = Math.PI * 2 * k / cnt + off;
                            f.add(bw / 2f + (float) Math.cos(ang) * r, bh / 2f + (float) Math.sin(ang) * r, rad, j, dmg);
                        }
                    }
                }
                case AttackDef.SPIRAL -> {
                    float r0 = Math.max(bw, bh) * 0.75f + 12;
                    float base = rnd(sd, 0, 5) * 6.2832f;
                    for (int i = 0; i < n; i++) {
                        float lt = t - (a.start + i * a.interval);
                        if (lt < 0) continue;
                        float r = r0 - lt * a.speed;
                        if (r < -4) continue;
                        double ang = base + i * a.angle_step;
                        f.add(bw / 2f + (float) Math.cos(ang) * r, bh / 2f + (float) Math.sin(ang) * r, rad, j, dmg);
                    }
                }
                case AttackDef.HOP -> {
                    boolean fromLeft = a.from.equalsIgnoreCase("left");
                    for (int i = 0; i < n; i++) {
                        float x = bw + 6, tt = t - (a.start + i * a.interval);
                        float floorY = bh - rad;
                        if (tt < 0) continue;
                        long hs = sd + i * 131L;
                        boolean placed = false;
                        for (int hop = 0; hop < 12 && !placed; hop++) {
                            float dist = 22 + rnd(hs, hop, 1) * 42, air = 12 + dist * 0.2f, rest = 5 + rnd(hs, hop, 2) * 4;
                            float height = 14 + dist * 0.55f;
                            if (tt < air) {
                                float u = tt / air;
                                float px = x - dist * u;
                                f.add(fromLeft ? bw - px : px, floorY - 4 * height * u * (1 - u), rad, j, dmg);
                                placed = true;
                                break;
                            }
                            tt -= air; x -= dist;
                            if (tt < rest) { f.add(fromLeft ? bw - x : x, floorY, rad, j, dmg); placed = true; break; }
                            tt -= rest;
                        }
                        if (!placed) f.add(fromLeft ? bw - x : x, floorY, rad, j, dmg);
                    }
                }
                default -> { // BOUNCE
                    for (int i = 0; i < n; i++) {
                        float lt = t - (a.start + i * a.interval);
                        if (lt < 0) continue;
                        double ang = rnd(sd, i, 6) * Math.PI * 2;
                        float x0 = bw * (0.15f + 0.7f * rnd(sd, i, 7)), y0 = bh * (0.15f + 0.7f * rnd(sd, i, 8));
                        f.add(tri(x0 + (float) Math.cos(ang) * a.speed * lt, bw), tri(y0 + (float) Math.sin(ang) * a.speed * lt, bh), rad, j, dmg);
                    }
                }
            }
        }
    }

    /** Индекс пули, попавшей в душу, или -1. */
    public static int hit(Frame f, float sx, float sy) {
        for (int i = 0; i < f.n; i++) {
            float dx = Math.abs(f.x[i] - sx), dy = Math.abs(f.y[i] - sy), rr = f.r[i] * 0.8f + SOUL_HALF * 0.6f;
            if (dx < rr && dy < rr && dx * dx + dy * dy < rr * rr * 1.4f) return i;
        }
        return -1;
    }
}
