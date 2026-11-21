package com.cccsharp.compiler;

import com.cccsharp.compiler.Ast.Arg;
import com.cccsharp.compiler.Ast.Call;
import com.cccsharp.compiler.LuaEmitter.Ex;
import java.util.ArrayList;
import java.util.List;

/** The parts of .NET that CC: CSharp provides: Console, Math, string, List, Dictionary, Random and friends. */
final class Builtins {
    private Builtins() {}

    private static Ex ex(String code, Type type) {
        return LuaEmitter.ex(code, type);
    }

    private static String args(List<Ex> a) {
        return LuaEmitter.join(a);
    }

    private static Type elem(Type t) {
        return t.element == null ? Type.DYNAMIC : t.element;
    }

    private static void arity(LuaEmitter em, Call c, String name, int min, int max) {
        int n = c.args().size();
        if (n < min || n > max) {
            String want = min == max ? String.valueOf(min) : min + " to " + max;
            throw em.err(c, name + " takes " + want + " argument" + (max == 1 ? "" : "s") + ", not " + n);
        }
    }

    private static boolean allInt(List<Ex> a) {
        for (Ex e : a) {
            if (!LuaEmitter.type(e).isIntegral()) {
                return false;
            }
        }
        return true;
    }

    // --- static classes --------------------------------------------------------------------

    static Ex staticCall(LuaEmitter em, String builtin, String name, Call c) {
        String owner = switch (builtin) {
            case "int", "Int32", "long", "Int64", "short", "byte" -> "int";
            case "double", "Double", "float", "Single", "decimal", "Decimal" -> "double";
            case "string", "String" -> "string";
            case "bool", "Boolean" -> "bool";
            case "char", "Char" -> "char";
            default -> builtin;
        };
        String full = owner + "." + name;

        // TryParse needs the raw 'out' argument, so handle it before evaluating arguments
        if (name.equals("TryParse") && (owner.equals("int") || owner.equals("double") || owner.equals("bool"))) {
            arity(em, c, full, 2, 2);
            Arg out = c.args().get(1);
            Type t = owner.equals("int") ? Type.INT : owner.equals("double") ? Type.FLOAT : Type.BOOL;
            Ex s = em.valueOf(c.args().getFirst().expr());
            String var = em.outVariable(out, t, c);
            String fn = owner.equals("int") ? "tryParseInt" : owner.equals("double") ? "tryParseFloat" : "tryParseBool";
            return ex("(function() local __ok, __v = __rt." + fn + "(" + LuaEmitter.code(s) + ") " + var
                    + " = __v return __ok end)()", Type.BOOL);
        }
        if (full.equals("Lua.Multi")) {
            arity(em, c, full, 1, 1);
            return ex("__rt.listOf(" + LuaEmitter.code(em.valueOf(c.args().getFirst().expr())) + ")",
                    Type.list(Type.DYNAMIC, false));
        }

        List<Ex> a = em.argsOf(c, List.of());
        String x = a.isEmpty() ? "" : LuaEmitter.code(a.getFirst());
        switch (full) {
            // Console ---------------------------------------------------------------------
            case "Console.WriteLine" -> {
                if (a.isEmpty()) {
                    return ex("print(\"\")", Type.VOID);
                }
                if (a.size() == 1) {
                    return ex("print(" + str(a.getFirst()) + ")", Type.VOID);
                }
                return ex("print(__rt.format(" + args(a) + "))", Type.VOID);
            }
            case "Console.Write" -> {
                arity(em, c, full, 1, 99);
                if (a.size() == 1) {
                    return ex("write(" + str(a.getFirst()) + ")", Type.VOID);
                }
                return ex("write(__rt.format(" + args(a) + "))", Type.VOID);
            }
            case "Console.ReadLine" -> {
                arity(em, c, full, 0, 0);
                return ex("read()", Type.STRING);
            }
            case "Console.ReadKey" -> {
                return ex("__rt.readKey()", Type.DYNAMIC);
            }
            case "Console.Clear" -> {
                arity(em, c, full, 0, 0);
                return ex("__rt.clear()", Type.VOID);
            }
            case "Console.SetCursorPosition" -> {
                arity(em, c, full, 2, 2);
                return ex("term.setCursorPos((" + x + ") + 1, (" + LuaEmitter.code(a.get(1)) + ") + 1)", Type.VOID);
            }
            // Math --------------------------------------------------------------------------
            case "Math.Abs" -> {
                arity(em, c, full, 1, 1);
                return ex("math.abs(" + x + ")", allInt(a) ? Type.INT : Type.FLOAT);
            }
            case "Math.Max", "Math.Min" -> {
                arity(em, c, full, 2, 2);
                return ex("math." + name.toLowerCase() + "(" + args(a) + ")", allInt(a) ? Type.INT : Type.FLOAT);
            }
            case "Math.Clamp" -> {
                arity(em, c, full, 3, 3);
                return ex("math.max(" + LuaEmitter.code(a.get(1)) + ", math.min(" + LuaEmitter.code(a.get(2)) + ", "
                        + x + "))", allInt(a) ? Type.INT : Type.FLOAT);
            }
            case "Math.Floor" -> {
                return ex("math.floor(" + x + ")", Type.FLOAT);
            }
            case "Math.Ceiling" -> {
                return ex("math.ceil(" + x + ")", Type.FLOAT);
            }
            case "Math.Round" -> {
                arity(em, c, full, 1, 2);
                return ex("__rt.round(" + args(a) + ")", Type.FLOAT);
            }
            case "Math.Truncate" -> {
                return ex("__rt.toint(" + x + ")", Type.FLOAT);
            }
            case "Math.Sqrt", "Math.Sin", "Math.Cos", "Math.Tan", "Math.Asin", "Math.Acos", "Math.Atan",
                    "Math.Exp" -> {
                arity(em, c, full, 1, 1);
                return ex("math." + name.toLowerCase() + "(" + x + ")", Type.FLOAT);
            }
            case "Math.Atan2" -> {
                arity(em, c, full, 2, 2);
                return ex("math.atan2(" + args(a) + ")", Type.FLOAT);
            }
            case "Math.Pow" -> {
                arity(em, c, full, 2, 2);
                return ex("(" + x + " ^ " + LuaEmitter.code(a.get(1)) + ")", Type.FLOAT);
            }
            case "Math.Log" -> {
                arity(em, c, full, 1, 2);
                return ex("math.log(" + args(a) + ")", Type.FLOAT);
            }
            case "Math.Log10" -> {
                return ex("math.log(" + x + ", 10)", Type.FLOAT);
            }
            case "Math.Sign" -> {
                return ex("__rt.sign(" + x + ")", Type.INT);
            }
            // string ------------------------------------------------------------------------
            case "string.Join" -> {
                arity(em, c, full, 2, 99);
                if (a.size() == 2 && !LuaEmitter.type(a.get(1)).is(Type.Kind.STRING)) {
                    return ex("__rt.s.Join(" + args(a) + ")", Type.STRING);
                }
                List<Ex> rest = new ArrayList<>(a.subList(1, a.size()));
                return ex("__rt.s.Join(" + x + ", __rt.listOf(" + args(rest) + "))", Type.STRING);
            }
            case "string.IsNullOrEmpty", "string.IsNullOrWhiteSpace" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.s." + name + "(" + x + ")", Type.BOOL);
            }
            case "string.Format" -> {
                arity(em, c, full, 1, 99);
                return ex("__rt.format(" + args(a) + ")", Type.STRING);
            }
            case "string.Concat" -> {
                List<String> parts = new ArrayList<>();
                a.forEach(e -> parts.add(str(e)));
                return ex(parts.isEmpty() ? "\"\"" : "(" + String.join(" .. ", parts) + ")", Type.STRING);
            }
            case "string.Compare" -> {
                arity(em, c, full, 2, 2);
                return ex("__rt.compare(" + args(a) + ")", Type.INT);
            }
            case "string.Equals" -> {
                arity(em, c, full, 2, 2);
                return ex("(" + x + " == " + LuaEmitter.code(a.get(1)) + ")", Type.BOOL);
            }
            // parsing -----------------------------------------------------------------------
            case "int.Parse" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.parseInt(" + x + ")", Type.INT);
            }
            case "double.Parse" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.parseFloat(" + x + ")", Type.FLOAT);
            }
            case "bool.Parse" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.parseBool(" + x + ")", Type.BOOL);
            }
            case "double.IsNaN" -> {
                return ex("(" + x + " ~= " + x + ")", Type.BOOL);
            }
            case "double.IsInfinity" -> {
                return ex("(math.abs(" + x + ") == math.huge)", Type.BOOL);
            }
            case "char.IsDigit", "char.IsLetter", "char.IsWhiteSpace", "char.IsUpper", "char.IsLower",
                    "char.IsLetterOrDigit", "char.IsPunctuation" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.c." + name + "(" + x + ")", Type.BOOL);
            }
            case "char.ToUpper", "char.ToLower" -> {
                arity(em, c, full, 1, 1);
                return ex("string." + (name.equals("ToUpper") ? "upper" : "lower") + "(" + x + ")", Type.STRING);
            }
            // Convert -----------------------------------------------------------------------
            case "Convert.ToInt32", "Convert.ToInt64", "Convert.ToInt16", "Convert.ToByte" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.convertInt(" + x + ")", Type.INT);
            }
            case "Convert.ToDouble", "Convert.ToSingle", "Convert.ToDecimal" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.convertFloat(" + x + ")", Type.FLOAT);
            }
            case "Convert.ToString" -> {
                arity(em, c, full, 1, 1);
                return ex(str(a.getFirst()), Type.STRING);
            }
            case "Convert.ToBoolean" -> {
                arity(em, c, full, 1, 1);
                return ex("__rt.convertBool(" + x + ")", Type.BOOL);
            }
            case "Convert.ToChar" -> {
                arity(em, c, full, 1, 1);
                return ex("string.char(" + x + ")", Type.STRING);
            }
            // misc --------------------------------------------------------------------------
            case "Thread.Sleep" -> {
                arity(em, c, full, 1, 1);
                return ex("sleep((" + x + ") / 1000)", Type.VOID);
            }
            case "Lua.Global" -> {
                arity(em, c, full, 1, 1);
                return ex("_ENV[" + x + "]", Type.DYNAMIC);
            }
            case "Lua.Table" -> {
                arity(em, c, full, 0, 0);
                return ex("{}", Type.DYNAMIC);
            }
            default -> throw em.err(c, full + " is not supported by CC: CSharp yet");
        }
    }

    static String str(Ex e) {
        return LuaEmitter.toStr(e);
    }

    // --- List<T> and arrays ----------------------------------------------------------------

    static Ex listCall(LuaEmitter em, Ex t, String name, Call c) {
        Type lt = LuaEmitter.type(t);
        Type e = elem(lt);
        String self = LuaEmitter.code(t);
        String full = "List." + name;
        List<Type> one = List.of(e);
        switch (name) {
            case "Add", "AddRange", "Insert", "RemoveAt", "RemoveRange", "Clear", "ForEach", "Reverse" -> {
                List<Ex> a = em.argsOf(c, one);
                if (name.equals("Reverse") && !a.isEmpty()) {
                    throw em.err(c, "List.Reverse() takes no arguments");
                }
                return ex(self + ":" + name + "(" + args(a) + ")", Type.VOID);
            }
            case "Sort" -> {
                List<Ex> a = em.argsOf(c, List.of(e, e));
                return ex(self + ":Sort(" + args(a) + ")", Type.VOID);
            }
            case "Remove", "Contains", "Exists", "TrueForAll", "All" -> {
                arity(em, c, full, 1, 1);
                return ex(self + ":" + name + "(" + args(em.argsOf(c, one)) + ")", Type.BOOL);
            }
            case "Any" -> {
                arity(em, c, full, 0, 1);
                return ex(self + ":Any(" + args(em.argsOf(c, one)) + ")", Type.BOOL);
            }
            case "IndexOf", "LastIndexOf", "FindIndex", "RemoveAll" -> {
                arity(em, c, full, 1, 1);
                return ex(self + ":" + name + "(" + args(em.argsOf(c, one)) + ")", Type.INT);
            }
            case "Count" -> {
                arity(em, c, full, 0, 1);
                if (c.args().isEmpty()) {
                    return ex(self + ".n", Type.INT);
                }
                return ex(self + ":CountWhere(" + args(em.argsOf(c, one)) + ")", Type.INT);
            }
            case "Find", "FindLast", "First", "FirstOrDefault", "Last", "LastOrDefault", "ElementAt",
                    "Single" -> {
                arity(em, c, full, 0, 1);
                return ex(self + ":" + name + "(" + args(em.argsOf(c, one)) + ")", e);
            }
            case "FindAll", "Where", "ToList", "ToArray", "GetRange", "Take", "Skip", "Distinct", "Concat",
                    "OrderBy", "OrderByDescending", "TakeWhile", "SkipWhile" -> {
                List<Ex> a = em.argsOf(c, one);
                return ex(self + ":" + name + "(" + args(a) + ")", Type.list(e, name.equals("ToArray")));
            }
            case "Select" -> {
                arity(em, c, full, 1, 1);
                List<Ex> a = em.argsOf(c, one);
                Type ft = LuaEmitter.type(a.getFirst());
                Type out = ft.is(Type.Kind.FUNC) && ft.element != null ? ft.element : Type.DYNAMIC;
                return ex(self + ":Select(" + args(a) + ")", Type.list(out, false));
            }
            case "Sum", "Min", "Max" -> {
                arity(em, c, full, 0, 1);
                List<Ex> a = em.argsOf(c, one);
                Type out = e;
                if (!a.isEmpty()) {
                    Type ft = LuaEmitter.type(a.getFirst());
                    out = ft.is(Type.Kind.FUNC) && ft.element != null ? ft.element : Type.DYNAMIC;
                }
                return ex(self + ":" + name + "(" + args(a) + ")", out);
            }
            case "Average" -> {
                arity(em, c, full, 0, 1);
                return ex(self + ":Average(" + args(em.argsOf(c, one)) + ")", Type.FLOAT);
            }
            default -> throw em.err(c, full + " is not supported by CC: CSharp yet");
        }
    }

    // --- Dictionary<K, V> ------------------------------------------------------------------

    static Ex dictCall(LuaEmitter em, Ex t, String name, Call c) {
        Type dt = LuaEmitter.type(t);
        Type k = dt.key == null ? Type.DYNAMIC : dt.key;
        Type v = elem(dt);
        String self = LuaEmitter.code(t);
        String full = "Dictionary." + name;
        switch (name) {
            case "TryGetValue" -> {
                arity(em, c, full, 2, 2);
                Ex key = em.valueOf(c.args().getFirst().expr());
                String var = em.outVariable(c.args().get(1), v, c);
                return ex("(function() local __ok, __v = " + self + ":TryGet(" + LuaEmitter.code(key) + ") " + var
                        + " = __v return __ok end)()", Type.BOOL);
            }
            case "Add" -> {
                arity(em, c, full, 2, 2);
                return ex(self + ":Add(" + args(em.argsOf(c, List.of())) + ")", Type.VOID);
            }
            case "ContainsKey", "ContainsValue", "Remove" -> {
                arity(em, c, full, 1, 1);
                return ex(self + ":" + name + "(" + args(em.argsOf(c, List.of())) + ")", Type.BOOL);
            }
            case "Clear" -> {
                arity(em, c, full, 0, 0);
                return ex(self + ":Clear()", Type.VOID);
            }
            case "GetValueOrDefault" -> {
                arity(em, c, full, 1, 2);
                return ex(self + ":GetValueOrDefault(" + args(em.argsOf(c, List.of())) + ")", v);
            }
            default -> throw em.err(c, full + " is not supported by CC: CSharp yet");
        }
    }

    // --- string ----------------------------------------------------------------------------

    static Ex stringCall(LuaEmitter em, Ex t, String name, Call c) {
        List<Ex> a = em.argsOf(c, List.of());
        String self = LuaEmitter.code(t);
        String call = "__rt.s." + name + "(" + self + (a.isEmpty() ? "" : ", " + args(a)) + ")";
        return switch (name) {
            case "ToUpper", "ToLower", "ToUpperInvariant", "ToLowerInvariant", "Trim", "TrimStart", "TrimEnd",
                    "Substring", "Replace", "PadLeft", "PadRight", "Insert", "Remove" -> ex(call, Type.STRING);
            case "Contains", "StartsWith", "EndsWith" -> ex(call, Type.BOOL);
            case "IndexOf", "LastIndexOf" -> ex(call, Type.INT);
            case "Split" -> ex(call, Type.list(Type.STRING, true));
            case "ToCharArray" -> ex(call, Type.list(Type.STRING, true));
            default -> throw em.err(c, "string." + name + " is not supported by CC: CSharp yet");
        };
    }

    // --- Random and StringBuilder ----------------------------------------------------------

    static Ex randomCall(LuaEmitter em, Ex t, String name, Call c) {
        List<Ex> a = em.argsOf(c, List.of());
        String self = LuaEmitter.code(t);
        return switch (name) {
            case "Next" -> {
                arity(em, c, "Random.Next", 0, 2);
                yield ex(self + ":Next(" + args(a) + ")", Type.INT);
            }
            case "NextDouble" -> ex(self + ":NextDouble()", Type.FLOAT);
            default -> throw em.err(c, "Random." + name + " is not supported by CC: CSharp yet");
        };
    }

    static Ex builderCall(LuaEmitter em, Ex t, String name, Call c) {
        List<Ex> a = em.argsOf(c, List.of());
        String self = LuaEmitter.code(t);
        return switch (name) {
            case "Append", "AppendLine", "Clear" -> ex(self + ":" + name + "(" + args(a) + ")", Type.BUILDER);
            default -> throw em.err(c, "StringBuilder." + name + " is not supported by CC: CSharp yet");
        };
    }
}
