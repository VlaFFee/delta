package com.deltabattle.registry;

import com.deltabattle.DeltaBattle;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, DeltaBattle.MODID);
    /** Монстр-конфета: лечит в бою на 10 HP (расходуется из инвентаря), можно съесть и вне боя. */
    public static final RegistryObject<Item> MONSTER_CANDY = ITEMS.register("monster_candy",
            () -> new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(2).saturationMod(0.3f).alwaysEat().fast().build())));
    public static final RegistryObject<Item> FROGGIT_SPAWN_EGG = ITEMS.register("froggit_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.FROGGIT, 0xE8F0E0, 0x2C7A3C, new Item.Properties()));
    /** Палочка редактора битв: ПКМ в креативе открывает редактор. */
    public static final RegistryObject<Item> EDITOR_WAND = ITEMS.register("editor_wand",
            () -> new com.deltabattle.editor.EditorWandItem(new Item.Properties().stacksTo(1)));
}
