package com.cccsharp;

import com.cccsharp.compiler.CSharpCompiler;
import com.cccsharp.compiler.CompileException;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import java.util.Optional;

/**
 * The {@code csharp} Lua API.
 *
 * <pre>{@code
 * local lua, lineMap = csharp.compile(source, "prog.cs")
 * -- on failure: nil, "prog.cs:3:5: message"
 * }</pre>
 *
 * Compiling only turns text into text: the result runs in the normal CC: Tweaked sandbox.
 */
public final class CSharpApi implements ILuaAPI {
    /** Keeps a runaway paste from tying up the server thread pool. */
    private static final int MAX_SOURCE = 512 * 1024;

    @Override
    public String[] getNames() {
        return new String[] {"csharp"};
    }

    /** Compiles C# source. Returns the Lua code and a table mapping each Lua line to its C# line. */
    @LuaFunction
    public final Object[] compile(String source, Optional<String> fileName) throws LuaException {
        String name = fileName.orElse("program.cs");
        if (source.length() > MAX_SOURCE) {
            throw new LuaException("source is too large (" + source.length() + " characters)");
        }
        try {
            CSharpCompiler.Result result = CSharpCompiler.compile(source);
            return new Object[] {result.lua(), result.lineMap()};
        } catch (CompileException e) {
            return new Object[] {null, e.format(name)};
        } catch (RuntimeException e) {
            CCCSharp.LOGGER.error("CC: CSharp crashed compiling {}", name, e);
            return new Object[] {null, name + ": internal compiler error (" + e + "). Please report this."};
        }
    }

    /** The CC: CSharp version. */
    @LuaFunction
    public final String version() {
        return "0.1.0";
    }
}
