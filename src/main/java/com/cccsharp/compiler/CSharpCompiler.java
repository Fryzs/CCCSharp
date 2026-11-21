package com.cccsharp.compiler;

import java.util.List;

/** C# source in, Lua out. Pure Java: no .NET needed, so it works on any Minecraft server. */
public final class CSharpCompiler {
    /**
     * The compiled program.
     *
     * @param lua     Lua source; needs the {@code cccsharp.runtime} module
     * @param lineMap for each Lua line (index 0 = line 1), the C# line it came from
     */
    public record Result(String lua, List<Integer> lineMap) {}

    private CSharpCompiler() {}

    /**
     * Compiles a C# program.
     *
     * @throws CompileException with line and column when the source is not valid or uses something unsupported
     */
    public static Result compile(String source) {
        List<Token> tokens = new Lexer(source).tokenize();
        Ast.CompilationUnit unit = new Parser(tokens).parseUnit();
        LuaEmitter emitter = new LuaEmitter();
        emitter.emit(unit);
        return new Result(emitter.lua(), emitter.lineMap());
    }
}
