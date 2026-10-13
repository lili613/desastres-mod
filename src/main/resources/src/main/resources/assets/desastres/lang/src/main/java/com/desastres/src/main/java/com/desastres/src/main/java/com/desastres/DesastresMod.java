package com.desastres;

import java.util.EnumMap;
import java.util.Map;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class DesastresMod implements ModInitializer {
    public static final String MOD_ID = "desastres";
    public static final Map<DisasterType, Block> BLOCKS = new EnumMap<>(DisasterType.class);

    @Override
    public void onInitialize() {
        for (DisasterType type : DisasterType.values()) {
            Identifier id = new Identifier(MOD_ID, type.id);
            Block block = Registry.register(Registries.BLOCK, id,
                    new DisasterBlock(FabricBlockSettings.create()
                            .mapColor(type.color)
                            .strength(2.0f, 1200.0f)
                            .sounds(BlockSoundGroup.STONE), type));
            Registry.register(Registries.ITEM, id, new BlockItem(block, new FabricItemSettings()));
            BLOCKS.put(type, block);
        }

        ItemGroup group = FabricItemGroup.builder()
                .icon(() -> new ItemStack(BLOCKS.get(DisasterType.VULCAO)))
                .displayName(Text.translatable("itemGroup." + MOD_ID + ".main"))
                .entries((context, entries) -> BLOCKS.values().forEach(b -> entries.add(b)))
                .build();
        Registry.register(Registries.ITEM_GROUP, new Identifier(MOD_ID, "main"), group);

        DisasterManager.init();
    }
}
