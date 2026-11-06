package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.deltabattle.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;

@Mod.EventBusSubscriber(modid = DeltaBattle.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.FROGGIT.get(), BattleMobRenderer::new);
        e.registerEntityRenderer(ModEntities.BATTLE_MOB.get(), BattleMobRenderer::new);
    }

    /** Папка игры deltabattle_assets подключается как всегда включённый ресурс-пак (импортированные модели, анимации, музыка). */
    @SubscribeEvent
    public static void packs(AddPackFindersEvent e) {
        if (e.getPackType() != PackType.CLIENT_RESOURCES) return;
        ClientAssets.ensurePack();
        java.nio.file.Path dir = ClientAssets.userDir();
        e.addRepositorySource(consumer -> {
            Pack p = Pack.readMetaAndCreate("deltabattle_user", Component.literal("DeltaBattle imported assets"), true,
                    id -> new PathPackResources(id, dir, true), PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.BUILT_IN);
            if (p != null) consumer.accept(p);
        });
    }
}
