package com.cccsharp;

import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.api.filesystem.Mount;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Headless checks inside a real Minecraft + CC: Tweaked ({@code ./gradlew runGameTestServer}).
 * Only run when game tests are enabled.
 */
@GameTestHolder(CCCSharp.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CCCSharpGameTests {
    /** Our files must show up in CC's ROM, next to CC's own. */
    @GameTest(template = "empty")
    public static void romContainsCSharpFiles(GameTestHelper helper) throws Exception {
        Mount rom = ComputerCraftAPI.createResourceMount(helper.getLevel().getServer(), "computercraft", "lua/rom");
        helper.assertTrue(rom != null, "CC: Tweaked's ROM could not be mounted");
        for (String path : List.of("programs/csharp.lua", "modules/main/cccsharp/runtime.lua", "help/csharp.txt",
                "autorun/cccsharp.lua", "programs/edit.lua")) {
            helper.assertTrue(rom.exists(path), "ROM is missing " + path);
        }
        helper.succeed();
    }

    /** The API compiles and reports errors the way the csharp program expects. */
    @GameTest(template = "empty")
    public static void apiCompilesAndReportsErrors(GameTestHelper helper) throws Exception {
        CSharpApi api = new CSharpApi();
        helper.assertTrue(api.getNames().length == 1 && api.getNames()[0].equals("csharp"), "API should be 'csharp'");
        Object[] ok = api.compile("Console.WriteLine(1 + 2);", java.util.Optional.of("a.cs"));
        helper.assertTrue(ok[0] instanceof String lua && lua.contains("print("), "expected Lua code, got " + ok[0]);
        helper.assertTrue(ok[1] instanceof List<?>, "expected a line map");
        Object[] bad = api.compile("int x = ;", java.util.Optional.of("b.cs"));
        helper.assertTrue(bad[0] == null && String.valueOf(bad[1]).startsWith("b.cs:1:"),
                "expected an error at b.cs:1, got " + bad[1]);
        helper.succeed();
    }
}
