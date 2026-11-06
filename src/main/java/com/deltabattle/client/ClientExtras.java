package com.deltabattle.client;

import com.deltabattle.net.S2C;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

/** Клиентская обработка пакетов музыки и редактора. */
public final class ClientExtras {
    public static void handle(S2C p) {
        Minecraft mc = Minecraft.getInstance();
        switch (p.kind) {
            case S2C.MUSIC -> {
                int mode = p.i.length > 0 ? p.i[0] : 0;
                String id = p.s.length > 0 ? p.s[0] : "";
                if (mode == 1) BattleMusic.stop(); else if (mode == 2) BattleMusic.sfx(id); else BattleMusic.play(id);
            }
            case S2C.EDITOR_LIST -> mc.setScreen(new EditorMenuScreen(Arrays.asList(p.s)));
            case S2C.EDITOR_DATA -> mc.setScreen(new EditorScreen(p.s[0], p.s[1]));
            default -> { }
        }
    }
}
