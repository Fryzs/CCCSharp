package com.cccsharp.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.ValueFactory;
import org.squiddev.cobalt.compiler.LoadState;
import org.squiddev.cobalt.lib.Bit32Lib;
import org.squiddev.cobalt.lib.CoreLibraries;

/**
 * Runs the in-game {@code csharp} program (rom/programs/csharp.lua) on Cobalt with a fake file system,
 * checking it writes the .lua, runs it, and points runtime errors at the right C# line.
 */
class CSharpRunnerTest {
    private static String resource(String path) {
        try (InputStream in = CSharpRunnerTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("missing resource " + path, e);
        }
    }

    private static final String PRELUDE = """
            __out = {}
            function print(...) local t = table.pack(...) for i = 1, t.n do t[i] = tostring(t[i]) end
              __out[#__out + 1] = table.concat(t, "\\t") .. "\\n" end
            function write(s) __out[#__out + 1] = tostring(s) end
            function printError(s) __out[#__out + 1] = "ERR: " .. tostring(s) .. "\\n" end
            files = { ["prog.cs"] = __cs_src }
            fs = {
              exists = function(p) return files[p] ~= nil end,
              isDir = function() return false end,
              getName = function(p) return p end,
              open = function(p, mode)
                if mode == "r" then return { readAll = function() return files[p] end, close = function() end } end
                local buf = {}
                return { write = function(s) buf[#buf + 1] = s end, close = function() files[p] = table.concat(buf) end }
              end,
            }
            shell = { resolve = function(p) return p end }
            csharp = { compile = function(src, name)
              if __compile_error then return nil, __compile_error end
              return __lua_src, load("return " .. __map_src)()
            end }
            local runtime = load(__runtime_src, "@runtime.lua")()
            function require(name) if name == "cccsharp.runtime" then return runtime end error("no " .. name) end
            local program = assert(load(__runner_src, "@csharp.lua"))
            program(table.unpack(__args))
            return table.concat(__out) .. "|lua=" .. tostring(files["prog.lua"] ~= nil)
            """;

    private static String runCsharpProgram(String cs, String compileError, String... args) throws Exception {
        LuaState state = new LuaState();
        CoreLibraries.standardGlobals(state);
        Bit32Lib.add(state);
        LuaTable g = state.globals();
        g.rawset("__cs_src", ValueFactory.valueOf(cs));
        if (compileError != null) {
            g.rawset("__compile_error", ValueFactory.valueOf(compileError));
            g.rawset("__lua_src", ValueFactory.valueOf(""));
            g.rawset("__map_src", ValueFactory.valueOf("{}"));
        } else {
            CSharpCompiler.Result r = CSharpCompiler.compile(cs);
            g.rawset("__lua_src", ValueFactory.valueOf(r.lua()));
            g.rawset("__map_src", ValueFactory.valueOf(
                    r.lineMap().stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"))));
        }
        g.rawset("__runtime_src", ValueFactory.valueOf(resource(
                "/data/computercraft/lua/rom/modules/main/cccsharp/runtime.lua")));
        g.rawset("__runner_src", ValueFactory.valueOf(resource("/data/computercraft/lua/rom/programs/csharp.lua")));
        LuaTable argTable = new LuaTable();
        for (int i = 0; i < args.length; i++) {
            argTable.rawset(i + 1, ValueFactory.valueOf(args[i]));
        }
        g.rawset("__args", argTable);
        var closure = LoadState.load(state, new ByteArrayInputStream(PRELUDE.getBytes(StandardCharsets.UTF_8)),
                "=prelude", g);
        return LuaThread.runMain(state, closure).first().toString();
    }

    @Test
    void runsAProgramAndWritesTheLua() throws Exception {
        String out = runCsharpProgram("Console.WriteLine(\"hi \" + args[0]);", null, "prog.cs", "there");
        assertEquals("hi there\n|lua=true", out);
    }

    @Test
    void mapsExceptionsToTheCSharpLine() throws Exception {
        String out = runCsharpProgram("""
                int[] a = new int[2];
                Console.WriteLine("before");
                Console.WriteLine(a[5]);
                """, null, "prog.cs");
        assertTrue(out.startsWith("before\nERR: prog.cs:3: Unhandled exception. ArgumentOutOfRangeException: Index"),
                out);
    }

    @Test
    void mapsLuaErrorsToTheCSharpLine() throws Exception {
        String out = runCsharpProgram("""
                Console.WriteLine("start");

                dynamic thing = null;
                thing.Explode();
                """, null, "prog.cs");
        assertTrue(out.startsWith("start\nERR: prog.cs:4: "), out);
    }

    @Test
    void showsCompileErrors() throws Exception {
        String out = runCsharpProgram("int x = ;", "prog.cs:1:9: unexpected ';'", "prog.cs");
        assertEquals("ERR: prog.cs:1:9: unexpected ';'\n|lua=false", out);
    }

    @Test
    void compileOnlyWritesWithoutRunning() throws Exception {
        String out = runCsharpProgram("Console.WriteLine(\"should not run\");", null, "-c", "prog.cs");
        assertEquals("Wrote prog.lua\n|lua=true", out);
    }
}
