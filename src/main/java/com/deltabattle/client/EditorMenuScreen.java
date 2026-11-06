package com.deltabattle.client;

import com.deltabattle.net.C2S;
import com.deltabattle.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Список битв: выбрать существующую или ввести новый id (без ':' = custom:имя) и открыть редактор. */
public class EditorMenuScreen extends Screen {
    private static final int PER_PAGE = 8;
    private final List<String> ids;
    private EditBox idBox;
    private String typed = "custom:my_battle";
    private int page;
    private boolean confirmDelete;

    public EditorMenuScreen(List<String> ids) {
        super(Component.translatable("deltabattle.ed.title"));
        this.ids = ids;
    }

    @Override
    protected void init() {
        int cx = width / 2, y = 40;
        idBox = new EditBox(font, cx - 150, 18, 300, 18, Component.empty());
        idBox.setMaxLength(100);
        idBox.setValue(typed);
        idBox.setResponder(s -> { typed = s; confirmDelete = false; });
        addRenderableWidget(idBox);
        int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        for (int i = page * PER_PAGE; i < Math.min(ids.size(), page * PER_PAGE + PER_PAGE); i++) {
            String id = ids.get(i);
            addRenderableWidget(Button.builder(Component.literal(id), b -> { idBox.setValue(id); }).bounds(cx - 150, y, 300, 18).build());
            y += 20;
        }
        int by = 40 + PER_PAGE * 20 + 6;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }).bounds(cx - 150, by, 30, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages - 1, page + 1); rebuildWidgets(); }).bounds(cx + 120, by, 30, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("deltabattle.ed.open"), b -> {
            if (!idBox.getValue().isBlank()) Net.toServer(C2S.edit(C2S.EDIT_REQUEST, idBox.getValue().trim()));
        }).bounds(cx - 150, by + 24, 140, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(confirmDelete ? "deltabattle.ed.sure" : "deltabattle.ed.delete"), b -> {
            if (!confirmDelete) { confirmDelete = true; rebuildWidgets(); return; }
            Net.toServer(C2S.edit(C2S.EDIT_DELETE, idBox.getValue().trim()));
        }).bounds(cx - 6, by + 24, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(cx + 80, by + 24, 70, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xFF14101C);
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, Component.translatable("deltabattle.ed.menu_hint"), width / 2, height - 18, 0xFF9090B0);
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
}
