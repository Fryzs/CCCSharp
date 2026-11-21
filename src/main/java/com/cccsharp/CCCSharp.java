package com.cccsharp;

import com.mojang.logging.LogUtils;
import dan200.computercraft.api.ComputerCraftAPI;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * CC: CSharp: write CC: Tweaked programs in C#. Every computer gets a {@code csharp} Lua API
 * ({@link CSharpApi}) and the ROM gets a {@code csharp} program and the {@code cccsharp.runtime}
 * module (see {@code data/computercraft/lua/rom}).
 */
@Mod(CCCSharp.MOD_ID)
public final class CCCSharp {
    public static final String MOD_ID = "cccsharp";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CCCSharp(IEventBus modBus) {
        modBus.addListener(CCCSharp::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ComputerCraftAPI.registerAPIFactory(computer -> new CSharpApi());
            LOGGER.info("CC: CSharp: computers can now run C# (csharp prog.cs)");
        });
    }
}
