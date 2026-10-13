package com.desastres;

import net.minecraft.block.MapColor;

public enum DisasterType {
    FURACAO("furacao", "Furacão", MapColor.LIGHT_BLUE),
    TORNADO("tornado", "Tornado", MapColor.GRAY),
    TSUNAMI("tsunami", "Tsunami", MapColor.BLUE),
    EXTINCAO("extincao", "Extinção", MapColor.BLACK),
    VULCAO("vulcao", "Vulcão", MapColor.ORANGE),
    MEGATSUNAMI("megatsunami", "Megatsunami", MapColor.DIAMOND_BLUE),
    TEMPESTADE_RAIOS("tempestade_raios", "Tempestade de Raios", MapColor.PURPLE),
    TEMPESTADE_FOGO("tempestade_fogo", "Tempestade de Fogo", MapColor.RED),
    TERREMOTO("terremoto", "Terremoto", MapColor.BROWN),
    MEGATERREMOTO("megaterremoto", "Megaterremoto", MapColor.DEEPSLATE_GRAY);

    public final String id;
    public final String nome;
    public final MapColor color;

    DisasterType(String id, String nome, MapColor color) {
        this.id = id;
        this.nome = nome;
        this.color = color;
    }
}
