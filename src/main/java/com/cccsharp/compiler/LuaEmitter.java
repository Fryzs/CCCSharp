package com.cccsharp.compiler;

import com.cccsharp.compiler.Ast.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the C# syntax tree into Lua for CC: Tweaked. The generated code needs the
 * {@code cccsharp.runtime} module (lists, dictionaries, classes, exceptions, string helpers).
 */
final class LuaEmitter {
    // --- what the program declares ---------------------------------------------------------

    static final class ClassInfo {
        final String name;
        final String lua;
        final ClassDecl decl; // null for built-in exception classes
        ClassInfo base;
        final Map<String, FieldInfo> fields = new LinkedHashMap<>();
        final Map<String, PropInfo> props = new LinkedHashMap<>();
        final Map<String, List<MethodInfo>> methods = new LinkedHashMap<>();
        final List<CtorInfo> ctors = new ArrayList<>();

        ClassInfo(String name, String lua, ClassDecl decl) {
            this.name = name;
            this.lua = lua;
            this.decl = decl;
        }

        boolean builtin() {
            return decl == null;
        }

        FieldInfo field(String n) {
            for (ClassInfo c = this; c != null; c = c.base) {
                FieldInfo f = c.fields.get(n);
                if (f != null) {
                    return f;
                }
            }
            return null;
        }

        PropInfo prop(String n) {
            for (ClassInfo c = this; c != null; c = c.base) {
                PropInfo p = c.props.get(n);
                if (p != null) {
                    return p;
                }
            }
            return null;
        }

        List<MethodInfo> methods(String n) {
            List<MethodInfo> out = new ArrayList<>();
            for (ClassInfo c = this; c != null; c = c.base) {
                List<MethodInfo> ms = c.methods.get(n);
                if (ms != null) {
                    for (MethodInfo m : ms) {
                        if (out.stream().noneMatch(o -> o.lua.equals(m.lua))) {
                            out.add(m);
                        }
                    }
                }
            }
            return out;
        }

        boolean isSubclassOf(String other) {
            for (ClassInfo c = this; c != null; c = c.base) {
                if (c.name.equals(other)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record FieldInfo(String name, Type type, boolean isStatic, Expr init, ClassInfo owner, TypeRef ref) {}

    private record PropInfo(String name, Type type, boolean isStatic, boolean auto, boolean hasSetter,
            PropertyDecl decl, ClassInfo owner) {}

    private record MethodInfo(String name, String lua, List<Param> params, Type ret, boolean isStatic,
            MethodDecl decl, ClassInfo owner) {
        int required() {
            int n = 0;
            for (Param p : params) {
                if (p.defaultValue() == null && !p.isParams()) {
                    n++;
                }
            }
            return n;
        }

        boolean accepts(int args) {
            boolean variadic = !params.isEmpty() && params.getLast().isParams();
            return args >= required() && (variadic || args <= params.size());
        }
    }

    private record CtorInfo(String lua, List<Param> params, CtorDecl decl) {
        boolean accepts(int args) {
            int required = 0;
            for (Param p : params) {
                if (p.defaultValue() == null && !p.isParams()) {
                    required++;
                }
            }
            boolean variadic = !params.isEmpty() && params.getLast().isParams();
            return args >= required && (variadic || args <= params.size());
        }
    }

    record EnumInfo(String name, String lua, Map<String, Long> values) {}

    /** A local variable or local function in scope. */
    private record Local(String lua, Type type, MethodInfo function) {}

    /** A loop or switch that break/continue may target. */
    private static final class Jump {
        final boolean isSwitch;
        final int tryDepth;
        final String continueLabel;
        final String breakLabel;
        boolean usedContinue;
        boolean usedBreakLabel;

        Jump(boolean isSwitch, int tryDepth, String continueLabel, String breakLabel) {
            this.isSwitch = isSwitch;
            this.tryDepth = tryDepth;
            this.continueLabel = continueLabel;
            this.breakLabel = breakLabel;
        }
    }

    /** A Lua expression and what we know about its type. */
    record Ex(String code, Type type, Ref ref) {
        Ex(String code, Type type) {
            this(code, type, null);
        }

        boolean isRef() {
            return ref != null;
        }
    }

    /** A name that refers to a type or API rather than a value. */
    record Ref(ClassInfo cls, EnumInfo en, String builtin, String ccGlobal) {}

    private static final Set<String> LUA_KEYWORDS = Set.of(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
            "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while", "self");

    private static final Set<String> CC_GLOBALS = Set.of(
            "term", "os", "fs", "peripheral", "redstone", "rs", "rednet", "textutils", "colors", "colours", "keys",
            "turtle", "shell", "multishell", "http", "gps", "parallel", "settings", "disk", "paintutils", "window",
            "vector", "pocket", "commands", "help", "io", "bit32", "coroutine", "aura", "utf8", "table", "sleep",
            "print", "write", "read", "printError", "tostring", "tonumber", "type", "pairs", "ipairs", "select",
            "next", "error", "pcall", "require", "unpack", "rawget", "rawset", "setmetatable", "getmetatable");

    private static final Set<String> BUILTIN_STATICS = Set.of(
            "Console", "Math", "string", "String", "int", "Int32", "long", "Int64", "short", "byte", "double",
            "Double", "float", "Single", "decimal", "Decimal", "bool", "Boolean", "char", "Char", "Convert",
            "Thread", "Environment", "Lua", "StringSplitOptions");

    private static final Set<String> BUILTIN_EXCEPTIONS = Set.of(
            "Exception", "SystemException", "ArgumentException", "ArgumentNullException",
            "ArgumentOutOfRangeException", "InvalidOperationException", "FormatException", "KeyNotFoundException",
            "IndexOutOfRangeException", "DivideByZeroException", "NotImplementedException",
            "NotSupportedException", "NullReferenceException", "OverflowException", "TimeoutException",
            "InvalidCastException", "ApplicationException");

    private static final Set<String> INT_TYPES = Set.of(
            "int", "long", "short", "byte", "sbyte", "uint", "ulong", "ushort", "Int32", "Int64", "Int16", "Byte",
            "UInt32", "UInt64");
    private static final Set<String> FLOAT_TYPES = Set.of("double", "float", "decimal", "Double", "Single", "Decimal");

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, EnumInfo> enums = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> methodArities = new HashMap<>();

    // --- generation state ------------------------------------------------------------------

    private final List<String> lines = new ArrayList<>();
    private final List<Integer> lineMap = new ArrayList<>();
    private int indent;
    private int csLine = 1;
    private int tempCounter;
    private final Deque<Map<String, Local>> scopes = new ArrayDeque<>();
    private final Deque<Jump> jumps = new ArrayDeque<>();
    private final List<String> hoisted = new ArrayList<>();
    private ClassInfo currentClass;
    private boolean staticContext = true;
    private int tryDepth;
    private String currentCatchVar;

    // --- results ---------------------------------------------------------------------------

    String lua() {
        return String.join("\n", lines) + "\n";
    }

    /** For each Lua line (index 0 = line 1), the C# line it came from. */
    List<Integer> lineMap() {
        return lineMap;
    }

    // --- output helpers --------------------------------------------------------------------

    private void line(String text) {
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            lines.add(i == 0 ? "  ".repeat(indent) + parts[i] : parts[i]);
            lineMap.add(csLine);
        }
    }

    /** A statement line; guards against Lua reading a leading '(' as a call on the previous line. */
    private void stmtLine(String text) {
        line(text.startsWith("(") ? ";" + text : text);
    }

    private String temp(String base) {
        return "__" + base + (++tempCounter);
    }

    private static CompileException error(int line, int col, String message) {
        return new CompileException(line, col, message);
    }

    private static CompileException error(Expr e, String message) {
        return new CompileException(e.line(), e.col(), message);
    }

    private static String luaName(String id) {
        return LUA_KEYWORDS.contains(id) ? id + "_" : id;
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty() || !Character.isUpperCase(s.charAt(0))) {
            return s;
        }
        // OS -> os, HTTP -> http, SetOutput -> setOutput
        int i = 0;
        while (i < s.length() && Character.isUpperCase(s.charAt(i))) {
            i++;
        }
        if (i > 1 && i < s.length()) {
            i--; // HTTPGet -> httpGet
        }
        return s.substring(0, i).toLowerCase(Locale.ROOT) + s.substring(i);
    }

    static String luaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32 || c >= 127) {
                        sb.append('\\').append(String.format("%03d", c));
                    } else {
                        sb.append((char) c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // --- scopes ----------------------------------------------------------------------------

    private void pushScope() {
        scopes.push(new HashMap<>());
    }

    private void popScope() {
        scopes.pop();
    }

    private String declare(String name, Type type) {
        String lua = luaName(name);
        scopes.peek().put(name, new Local(lua, type, null));
        return lua;
    }

    private Local lookupLocal(String name) {
        for (Map<String, Local> s : scopes) {
            Local l = s.get(name);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    // --- types -----------------------------------------------------------------------------

    private Type typeOf(TypeRef t) {
        if (t == null || t.isVar()) {
            return Type.DYNAMIC;
        }
        if (t.arrayRank() > 0) {
            return Type.list(typeOf(t.element()), true);
        }
        String n = t.name();
        if (INT_TYPES.contains(n)) {
            return Type.INT;
        }
        if (FLOAT_TYPES.contains(n)) {
            return Type.FLOAT;
        }
        switch (n) {
            case "bool", "Boolean" -> {
                return Type.BOOL;
            }
            case "string", "String", "char", "Char" -> {
                return Type.STRING;
            }
            case "object", "dynamic", "Object" -> {
                return Type.DYNAMIC;
            }
            case "void" -> {
                return Type.VOID;
            }
            case "List", "IList", "IEnumerable", "ICollection", "IReadOnlyList", "IReadOnlyCollection",
                    "Queue", "Stack" -> {
                if (n.equals("Queue") || n.equals("Stack")) {
                    throw error(t.line(), t.col(), n + " is not supported by CC: CSharp yet; use List");
                }
                return Type.list(t.args().isEmpty() ? Type.DYNAMIC : typeOf(t.args().getFirst()), false);
            }
            case "Dictionary", "IDictionary", "IReadOnlyDictionary", "SortedDictionary" -> {
                if (t.args().size() != 2) {
                    throw error(t.line(), t.col(), n + " needs two type arguments: " + n + "<TKey, TValue>");
                }
                return Type.dict(typeOf(t.args().get(0)), typeOf(t.args().get(1)));
            }
            case "KeyValuePair" -> {
                return Type.pair(typeOf(t.args().get(0)), typeOf(t.args().get(1)));
            }
            case "Func" -> {
                return Type.func(t.args().isEmpty() ? Type.DYNAMIC : typeOf(t.args().getLast()));
            }
            case "Action" -> {
                return Type.func(Type.VOID);
            }
            case "Predicate" -> {
                return Type.func(Type.BOOL);
            }
            case "Random" -> {
                return Type.RANDOM;
            }
            case "StringBuilder" -> {
                return Type.BUILDER;
            }
            case "HashSet", "ISet" -> throw error(t.line(), t.col(),
                    "HashSet is not supported by CC: CSharp yet; use List or Dictionary");
            default -> {
                // user types below
            }
        }
        if (classes.containsKey(n)) {
            return Type.cls(n);
        }
        if (enums.containsKey(n)) {
            return Type.enumType(n);
        }
        throw error(t.line(), t.col(), "unknown type '" + t + "'");
    }

    /** Parameter types for a lambda assigned to a Func/Action/Predicate variable. */
    private List<Type> delegateParams(TypeRef t) {
        if (t == null || t.arrayRank() > 0) {
            return List.of();
        }
        List<Type> out = new ArrayList<>();
        switch (t.name()) {
            case "Func" -> {
                for (int i = 0; i < t.args().size() - 1; i++) {
                    out.add(typeOf(t.args().get(i)));
                }
            }
            case "Action", "Predicate" -> {
                for (TypeRef a : t.args()) {
                    out.add(typeOf(a));
                }
            }
            default -> {
                // not a delegate
            }
        }
        return out;
    }

    // =========================================================================================
    // program
    // =========================================================================================

    void emit(CompilationUnit unit) {
        collect(unit);
        line("-- Generated by CC: CSharp from C#. Edit the .cs file, not this one.");
        line("local __rt = require(\"cccsharp.runtime\")");

        List<ClassInfo> ordered = baseFirst();
        List<String> names = new ArrayList<>();
        ordered.forEach(c -> names.add(c.lua));
        enums.values().forEach(e -> names.add(e.lua));
        if (!names.isEmpty()) {
            line("local " + String.join(", ", names));
        }
        for (EnumInfo e : enums.values()) {
            StringBuilder sb = new StringBuilder(e.lua + " = { ");
            e.values.forEach((k, v) -> sb.append(luaName(k)).append(" = ").append(v).append(", "));
            line(sb.append("}").toString());
        }
        for (ClassInfo c : ordered) {
            csLine = c.decl.line();
            line(c.lua + " = __rt.class(" + luaString(c.name) + ", " + (c.base == null ? "nil" : c.base.lua) + ")");
        }
        for (ClassInfo c : ordered) {
            emitClass(c);
        }
        for (ClassInfo c : ordered) {
            emitStaticInit(c);
        }
        emitEntry(unit);
    }

    private List<ClassInfo> baseFirst() {
        List<ClassInfo> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (ClassInfo c : classes.values()) {
            addBaseFirst(c, out, done, new HashSet<>());
        }
        return out;
    }

    private void addBaseFirst(ClassInfo c, List<ClassInfo> out, Set<String> done, Set<String> visiting) {
        if (c.builtin() || done.contains(c.name)) {
            return;
        }
        if (!visiting.add(c.name)) {
            throw error(c.decl.line(), c.decl.col(), "class " + c.name + " inherits from itself");
        }
        if (c.base != null) {
            addBaseFirst(c.base, out, done, visiting);
        }
        done.add(c.name);
        out.add(c);
    }

    /** First pass: learn every class, enum, member and overload before generating anything. */
    private void collect(CompilationUnit unit) {
        for (String ex : BUILTIN_EXCEPTIONS) {
            classes.put(ex, new ClassInfo(ex, "__rt.ex." + ex, null));
        }
        for (ClassInfo ex : classes.values()) {
            if (!ex.name.equals("Exception")) {
                ex.base = classes.get("Exception");
            }
            ex.fields.put("Message", new FieldInfo("Message", Type.STRING, false, null, ex, null));
        }
        for (TypeDecl t : unit.types()) {
            if (classes.containsKey(t.name()) || enums.containsKey(t.name())) {
                if (classes.get(t.name()) != null && classes.get(t.name()).builtin()) {
                    throw error(t.line(), t.col(), t.name() + " is already a built-in exception; pick another name");
                }
                throw error(t.line(), t.col(), "type " + t.name() + " is declared twice (partial classes are not "
                        + "supported)");
            }
            if (t instanceof ClassDecl c) {
                classes.put(c.name(), new ClassInfo(c.name(), luaName(c.name()), c));
            } else if (t instanceof EnumDecl e) {
                Map<String, Long> values = new LinkedHashMap<>();
                long next = 0;
                for (EnumMember m : e.members()) {
                    if (m.value() != null) {
                        next = constLong(m.value(), values);
                    }
                    values.put(m.name(), next);
                    next++;
                }
                enums.put(e.name(), new EnumInfo(e.name(), luaName(e.name()), values));
            }
        }
        for (ClassInfo c : classes.values()) {
            if (c.builtin()) {
                continue;
            }
            for (TypeRef b : c.decl.bases()) {
                ClassInfo base = classes.get(b.name());
                if (base != null) {
                    if (c.base != null) {
                        throw error(b.line(), b.col(), "a class can only inherit from one class");
                    }
                    c.base = base;
                } // otherwise an interface: ignored
            }
        }
        for (ClassInfo c : classes.values()) {
            if (c.builtin()) {
                continue;
            }
            for (Member m : c.decl.members()) {
                if (m instanceof MethodDecl md) {
                    methodArities.computeIfAbsent(md.name(), k -> new HashSet<>()).add(md.params().size());
                }
            }
        }
        for (ClassInfo c : classes.values()) {
            if (c.builtin()) {
                continue;
            }
            int ctorCount = (int) c.decl.members().stream().filter(m -> m instanceof CtorDecl).count();
            for (Member m : c.decl.members()) {
                switch (m) {
                    case FieldDecl f -> c.fields.put(f.name(),
                            new FieldInfo(f.name(), typeOf(f.type()), f.isStatic(), f.init(), c, f.type()));
                    case PropertyDecl p -> c.props.put(p.name(), new PropInfo(p.name(), typeOf(p.type()),
                            p.isStatic(), p.auto(), p.hasSetter() || p.auto(), p, c));
                    case MethodDecl md -> {
                        String lua = methodArities.get(md.name()).size() > 1
                                ? md.name() + "_" + md.params().size() : md.name();
                        List<MethodInfo> list = c.methods.computeIfAbsent(md.name(), k -> new ArrayList<>());
                        for (MethodInfo other : list) {
                            if (other.params().size() == md.params().size()) {
                                throw error(md.line(), md.col(), "method " + md.name() + " is declared twice with "
                                        + md.params().size() + " parameters; overloads must differ in how many "
                                        + "parameters they take");
                            }
                        }
                        list.add(new MethodInfo(md.name(), luaName(lua), md.params(), typeOf(md.returnType()),
                                md.isStatic() || c.decl.isStatic(), md, c));
                    }
                    case CtorDecl cd -> {
                        if (!cd.name().equals(c.name)) {
                            throw error(cd.line(), cd.col(), "constructor name must match the class name");
                        }
                        if (cd.isStatic()) {
                            throw error(cd.line(), cd.col(), "static constructors are not supported yet; "
                                    + "initialize static fields where they are declared");
                        }
                        String lua = ctorCount > 1 ? "__ctor_" + cd.params().size() : "__ctor";
                        for (CtorInfo other : c.ctors) {
                            if (other.lua().equals(lua)) {
                                throw error(cd.line(), cd.col(), "constructors of " + c.name
                                        + " must differ in how many parameters they take");
                            }
                        }
                        c.ctors.add(new CtorInfo(lua, cd.params(), cd));
                    }
                }
            }
        }
    }

    private long constLong(Expr e, Map<String, Long> earlier) {
        if (e instanceof Literal l && l.kind() == LitKind.INT) {
            return Long.parseLong(l.value());
        }
        if (e instanceof Unary u && u.op().equals("-")) {
            return -constLong(u.operand(), earlier);
        }
        if (e instanceof Name n && earlier.containsKey(n.id())) {
            return earlier.get(n.id());
        }
        if (e instanceof Binary b && b.op().equals("<<")) {
            return constLong(b.left(), earlier) << constLong(b.right(), earlier);
        }
        if (e instanceof Binary b && b.op().equals("|")) {
            return constLong(b.left(), earlier) | constLong(b.right(), earlier);
        }
        throw error(e, "enum values must be whole-number constants");
    }

    private void emitClass(ClassInfo c) {
        currentClass = c;
        String base = c.base == null ? null : c.base.lua;

        // field initializers
        csLine = c.decl.line();
        staticContext = false;
        line("function " + c.lua + ".__finit(self)");
        indent++;
        pushScope();
        if (base != null && !c.base.builtin()) {
            line(base + ".__finit(self)");
        } else if (base != null) {
            line("__rt.ex.Exception.__finit(self)");
        }
        for (FieldInfo f : c.fields.values()) {
            if (!f.isStatic()) {
                csLine = f.init() != null ? f.init().line() : csLine;
                line("self." + luaName(f.name()) + " = " + fieldValue(f.type(), f.init(), f.ref()));
            }
        }
        for (PropInfo p : c.props.values()) {
            if (!p.isStatic() && p.auto()) {
                csLine = p.decl().line();
                line("self." + luaName(p.name()) + " = " + fieldValue(p.type(), p.decl().init(), p.decl().type()));
            }
        }
        flushHoistedInto(null);
        popScope();
        indent--;
        line("end");

        // constructors
        if (c.ctors.isEmpty()) {
            line("function " + c.lua + ".__ctor(self)");
            indent++;
            implicitBaseCtor(c, c.decl.line(), c.decl.col());
            indent--;
            line("end");
        }
        for (CtorInfo ctor : c.ctors) {
            CtorDecl d = ctor.decl();
            csLine = d.line();
            staticContext = false;
            pushScope();
            String params = paramList(d.params(), true);
            line("function " + c.lua + "." + ctor.lua() + "(" + params + ")");
            indent++;
            paramPrologue(d.params());
            if (d.init() != null) {
                List<Ex> args = new ArrayList<>();
                for (Expr a : d.init().args()) {
                    args.add(expr(a));
                }
                ClassInfo target = d.init().isBase() ? c.base : c;
                if (target == null) {
                    throw error(d.init().line(), d.init().col(), c.name + " has no base class to call");
                }
                CtorInfo tc = findCtor(target, args.size(), d.init().line(), d.init().col());
                String targetLua = target.builtin() ? "__rt.ex.Exception" : target.lua;
                flushHoistedInto(null);
                line(targetLua + "." + (tc == null ? "__ctor" : tc.lua()) + "(self" + joinArgs(args, true) + ")");
            } else {
                implicitBaseCtor(c, d.line(), d.col());
            }
            block(d.body().stmts());
            indent--;
            line("end");
            popScope();
        }

        // properties with bodies
        for (PropInfo p : c.props.values()) {
            if (p.auto()) {
                continue;
            }
            PropertyDecl d = p.decl();
            staticContext = p.isStatic();
            csLine = d.line();
            String self = p.isStatic() ? "" : "self";
            if (d.getter() != null || d.getterExpr() != null) {
                pushScope();
                line("function " + c.lua + ".get_" + p.name() + "(" + self + ")");
                indent++;
                if (d.getterExpr() != null) {
                    Ex v = expr(d.getterExpr());
                    flushHoistedInto(null);
                    stmtLine("return " + v.code());
                } else {
                    block(d.getter().stmts());
                }
                indent--;
                line("end");
                popScope();
            }
            if (d.setter() != null) {
                pushScope();
                declare("value", p.type());
                line("function " + c.lua + ".set_" + p.name() + "(" + (self.isEmpty() ? "" : "self, ") + "value)");
                indent++;
                block(d.setter().stmts());
                indent--;
                line("end");
                popScope();
            }
        }

        // methods
        for (List<MethodInfo> overloads : c.methods.values()) {
            for (MethodInfo m : overloads) {
                if (!m.decl().hasBody()) {
                    continue; // abstract
                }
                emitMethod(c.lua + "." + m.lua(), m);
            }
        }
        currentClass = null;
    }

    private void emitMethod(String luaTarget, MethodInfo m) {
        MethodDecl d = m.decl();
        csLine = d.line();
        staticContext = m.isStatic();
        pushScope();
        String params = paramList(d.params(), !m.isStatic());
        line("function " + luaTarget + "(" + params + ")");
        indent++;
        paramPrologue(d.params());
        methodBody(d);
        indent--;
        line("end");
        popScope();
    }

    private void methodBody(MethodDecl d) {
        if (d.bodyExpr() != null) {
            csLine = d.bodyExpr().line();
            if (d.returnType().isVoid()) {
                exprStatement(d.bodyExpr());
            } else {
                Ex v = expr(d.bodyExpr());
                flushHoistedInto(null);
                stmtLine("return " + v.code());
            }
        } else {
            block(d.body().stmts());
        }
    }

    private void implicitBaseCtor(ClassInfo c, int line, int col) {
        if (c.base == null) {
            return;
        }
        if (c.base.builtin()) {
            line("__rt.ex.Exception.__ctor(self)");
            return;
        }
        CtorInfo tc = findCtor(c.base, 0, line, col);
        line(c.base.lua + "." + (tc == null ? "__ctor" : tc.lua()) + "(self)");
    }

    private CtorInfo findCtor(ClassInfo c, int args, int line, int col) {
        if (c.builtin()) {
            return null;
        }
        if (c.ctors.isEmpty()) {
            if (args != 0) {
                throw error(line, col, c.name + " has no constructor that takes " + args + " arguments");
            }
            return null;
        }
        for (CtorInfo ci : c.ctors) {
            if (ci.accepts(args)) {
                return ci;
            }
        }
        throw error(line, col, c.name + " has no constructor that takes " + args + " arguments");
    }

    private String paramList(List<Param> params, boolean withSelf) {
        List<String> names = new ArrayList<>();
        if (withSelf) {
            names.add("self");
        }
        for (Param p : params) {
            if (p.isParams()) {
                names.add("...");
                break;
            }
            names.add(luaName(p.name()));
        }
        return String.join(", ", names);
    }

    private void paramPrologue(List<Param> params) {
        for (Param p : params) {
            Type t = typeOf(p.type());
            String n = declare(p.name(), t);
            if (p.isParams()) {
                line("local " + n + " = __rt.list(table.pack(...))");
            } else if (p.defaultValue() != null) {
                Ex def = expr(p.defaultValue());
                line("if " + n + " == nil then " + n + " = " + def.code() + " end");
            }
        }
    }

    private String fieldValue(Type type, Expr init, TypeRef declared) {
        if (init == null) {
            return type.defaultLua();
        }
        return expected(init, type, declared).code();
    }

    private void emitStaticInit(ClassInfo c) {
        currentClass = c;
        staticContext = true;
        pushScope();
        for (FieldInfo f : c.fields.values()) {
            if (f.isStatic()) {
                csLine = f.init() != null ? f.init().line() : c.decl.line();
                String v = fieldValue(f.type(), f.init(), f.ref());
                flushHoistedInto(null);
                line(c.lua + "." + luaName(f.name()) + " = " + v);
            }
        }
        for (PropInfo p : c.props.values()) {
            if (p.isStatic() && p.auto()) {
                csLine = p.decl().line();
                String v = fieldValue(p.type(), p.decl().init(), p.decl().type());
                flushHoistedInto(null);
                line(c.lua + "." + luaName(p.name()) + " = " + v);
            }
        }
        popScope();
        currentClass = null;
    }

    private void emitEntry(CompilationUnit unit) {
        if (!unit.topLevel().isEmpty()) {
            currentClass = null;
            staticContext = true;
            csLine = unit.topLevel().getFirst().line();
            pushScope();
            declare("args", Type.list(Type.STRING, true));
            line("local args = __rt.list(table.pack(...))");
            block(unit.topLevel());
            popScope();
            return;
        }
        MethodInfo main = null;
        for (ClassInfo c : classes.values()) {
            if (c.builtin()) {
                continue;
            }
            for (MethodInfo m : c.methods.getOrDefault("Main", List.of())) {
                if (m.isStatic()) {
                    if (main != null) {
                        throw error(m.decl().line(), m.decl().col(), "more than one static Main method");
                    }
                    main = m;
                }
            }
        }
        if (main == null) {
            throw error(1, 1, "no program to run: add a static void Main() method or top-level statements");
        }
        csLine = main.decl().line();
        String call = main.owner().lua + "." + main.lua()
                + (main.params().isEmpty() ? "()" : "(__rt.list(table.pack(...)))");
        line(call);
    }

    // =========================================================================================
    // statements
    // =========================================================================================

    private void block(List<Stmt> stmts) {
        pushScope();
        // local functions are hoisted so they can be called before they are written (C# allows it)
        List<LocalFunc> funcs = new ArrayList<>();
        for (Stmt s : stmts) {
            if (s instanceof LocalFunc lf) {
                funcs.add(lf);
                MethodDecl d = lf.method();
                Type ret = typeOf(d.returnType());
                String lua = luaName(d.name());
                MethodInfo mi = new MethodInfo(d.name(), lua, d.params(), ret, true, d, null);
                scopes.peek().put(d.name(), new Local(lua, Type.func(ret), mi));
            }
        }
        if (!funcs.isEmpty()) {
            List<String> names = new ArrayList<>();
            funcs.forEach(f -> names.add(luaName(f.method().name())));
            line("local " + String.join(", ", names));
            for (LocalFunc f : funcs) {
                emitLocalFunction(f);
            }
        }
        for (int i = 0; i < stmts.size(); i++) {
            Stmt s = stmts.get(i);
            if (!(s instanceof LocalFunc)) {
                statement(s, i == stmts.size() - 1);
            }
        }
        popScope();
    }

    private void emitLocalFunction(LocalFunc f) {
        MethodDecl d = f.method();
        csLine = d.line();
        boolean savedStatic = staticContext;
        if (d.isStatic()) {
            staticContext = true;
        }
        Deque<Jump> savedJumps = new ArrayDeque<>(jumps);
        jumps.clear();
        int savedTry = tryDepth;
        tryDepth = 0;
        pushScope();
        line(luaName(d.name()) + " = function(" + paramList(d.params(), false) + ")");
        indent++;
        paramPrologue(d.params());
        methodBody(d);
        indent--;
        line("end");
        popScope();
        tryDepth = savedTry;
        jumps.addAll(savedJumps);
        staticContext = savedStatic;
    }

    /** Writes any declarations that expressions needed (out vars, pattern variables) before a statement. */
    private void flushHoistedInto(List<String> target) {
        if (hoisted.isEmpty()) {
            return;
        }
        List<String> copy = new ArrayList<>(hoisted);
        hoisted.clear();
        if (target != null) {
            target.addAll(copy);
        } else {
            copy.forEach(this::line);
        }
    }

    private void statement(Stmt s, boolean last) {
        csLine = s.line();
        switch (s) {
            case Block b -> {
                line("do");
                indent++;
                block(b.stmts());
                indent--;
                line("end");
            }
            case Empty e -> {
                // nothing
            }
            case LocalDecl d -> localDecl(d);
            case LocalFunc f -> emitLocalFunction(f);
            case ExprStmt e -> exprStatement(e.expr());
            case If i -> ifStatement(i);
            case While w -> whileStatement(w);
            case DoWhile d -> doWhileStatement(d);
            case For f -> forStatement(f);
            case Foreach f -> foreachStatement(f);
            case Switch sw -> switchStatement(sw);
            case Break b -> breakStatement(b);
            case Continue c -> continueStatement(c);
            case Return r -> returnStatement(r, last);
            case Throw t -> throwStatement(t);
            case Try t -> tryStatement(t);
        }
    }

    private void localDecl(LocalDecl d) {
        Type declared = d.type().isVar() ? null : typeOf(d.type());
        for (Declarator v : d.vars()) {
            String code;
            Type type;
            if (v.init() == null) {
                if (declared == null) {
                    throw error(v.line(), v.col(), "'var " + v.name() + "' needs a value");
                }
                type = declared;
                code = declared.defaultLua();
            } else {
                Ex init = declared != null ? expected(v.init(), declared, d.type()) : expr(v.init());
                if (init.isRef()) {
                    throw error(v.init(), "'" + v.init() + "' is a type, not a value");
                }
                type = declared != null ? declared : init.type();
                if (type.is(Type.Kind.NULL) || type.is(Type.Kind.VOID)) {
                    throw error(v.line(), v.col(), "cannot infer the type of '" + v.name() + "' from "
                            + (type.is(Type.Kind.VOID) ? "a method that returns nothing" : "null"));
                }
                code = init.code();
            }
            flushHoistedInto(null);
            String lua = declare(v.name(), type);
            line("local " + lua + " = " + code);
        }
    }

    /** An expression with a known target type (declarations, fields, returns), so initializers know what to build. */
    private Ex expected(Expr e, Type type, TypeRef declared) {
        if (e instanceof NewArray na && na.elementType() == null) {
            if (!type.is(Type.Kind.LIST)) {
                throw error(e, "a { ... } or [ ... ] initializer needs an array or list type, not " + type);
            }
            return listLiteral(na.items(), type.element, type.array != null && type.array);
        }
        if (e instanceof Lambda l) {
            List<Type> params = delegateParams(declared);
            return lambda(l, params, type.is(Type.Kind.FUNC) ? type.element : null);
        }
        Ex v = expr(e);
        if (type.is(Type.Kind.FLOAT) && v.type().is(Type.Kind.INT)) {
            return new Ex(v.code(), Type.FLOAT);
        }
        return v;
    }

    private void exprStatement(Expr e) {
        switch (e) {
            case Assign a -> assignStatement(a);
            case IncDec i -> incDecStatement(i);
            case Call c -> {
                Ex v = call(c);
                flushHoistedInto(null);
                if (v.code().endsWith(")") && !v.code().startsWith("(")) {
                    stmtLine(v.code());
                } else {
                    stmtLine("local _ = " + v.code());
                }
            }
            case New n -> {
                Ex v = expr(n);
                flushHoistedInto(null);
                stmtLine("local _ = " + v.code());
            }
            default -> throw error(e, "only assignments, calls, ++, -- and 'new' can be used as a statement");
        }
    }

    private void assignStatement(Assign a) {
        if (a.op().equals("??=")) {
            Ex current = expr(a.target());
            Ex value = expr(a.value());
            flushHoistedInto(null);
            line("if " + current.code() + " == nil then");
            indent++;
            store(a.target(), value);
            indent--;
            line("end");
            return;
        }
        Ex value;
        if (a.op().equals("=")) {
            Type targetType = targetType(a.target());
            value = targetType != null ? expected(a.value(), targetType, null) : expr(a.value());
        } else {
            String op = a.op().substring(0, a.op().length() - 1);
            value = binary(op, expr(a.target()), expr(a.value()), a);
        }
        if (value.isRef()) {
            throw error(a.value(), "'" + a.value() + "' is a type, not a value");
        }
        store(a.target(), value);
    }

    private void incDecStatement(IncDec i) {
        Ex current = expr(i.target());
        if (!current.type().isNumeric() && !current.type().isDynamic()) {
            throw error(i, "'" + i.op() + "' needs a number, not " + current.type());
        }
        Ex value = new Ex("(" + current.code() + (i.op().equals("++") ? " + 1" : " - 1") + ")", current.type());
        store(i.target(), value);
    }

    /** The static type of an assignment target, or null when unknown. */
    private Type targetType(Expr target) {
        if (target instanceof Name n) {
            Local l = lookupLocal(n.id());
            if (l != null) {
                return l.type();
            }
            if (currentClass != null) {
                FieldInfo f = currentClass.field(n.id());
                if (f != null) {
                    return f.type();
                }
                PropInfo p = currentClass.prop(n.id());
                if (p != null) {
                    return p.type();
                }
            }
        }
        return null;
    }

    /** Writes {@code value} into an assignable expression. */
    private void store(Expr target, Ex value) {
        String v = value.code();
        switch (target) {
            case Name n -> {
                Local l = lookupLocal(n.id());
                if (l != null) {
                    if (l.function() != null) {
                        throw error(n, "cannot assign to local function " + n.id());
                    }
                    flushHoistedInto(null);
                    stmtLine(l.lua() + " = " + v);
                    return;
                }
                if (currentClass != null) {
                    FieldInfo f = currentClass.field(n.id());
                    if (f != null) {
                        flushHoistedInto(null);
                        stmtLine(fieldOwner(f.isStatic(), f.owner(), n) + "." + luaName(n.id()) + " = " + v);
                        return;
                    }
                    PropInfo p = currentClass.prop(n.id());
                    if (p != null) {
                        flushHoistedInto(null);
                        storeProp(fieldOwner(p.isStatic(), p.owner(), n), p, v, n);
                        return;
                    }
                }
                throw error(n, "the name '" + n.id() + "' does not exist here");
            }
            case MemberAccess m -> {
                if (m.nullConditional()) {
                    throw error(m, "cannot assign through '?.'");
                }
                Ex t = expr(m.target());
                flushHoistedInto(null);
                if (t.isRef() && t.ref().cls() != null) {
                    ClassInfo c = t.ref().cls();
                    FieldInfo f = c.field(m.name());
                    if (f != null && f.isStatic()) {
                        stmtLine(f.owner().lua + "." + luaName(m.name()) + " = " + v);
                        return;
                    }
                    PropInfo p = c.prop(m.name());
                    if (p != null && p.isStatic()) {
                        storeProp(p.owner().lua, p, v, m);
                        return;
                    }
                    throw error(m, c.name + " has no static field or property '" + m.name() + "'");
                }
                if (t.isRef() && t.ref().ccGlobal() != null) {
                    stmtLine(t.code() + "." + m.name() + " = " + v);
                    return;
                }
                if (t.type().is(Type.Kind.CLASS)) {
                    ClassInfo c = classes.get(t.type().name);
                    FieldInfo f = c.field(m.name());
                    if (f != null) {
                        stmtLine(t.code() + "." + luaName(m.name()) + " = " + v);
                        return;
                    }
                    PropInfo p = c.prop(m.name());
                    if (p != null) {
                        storeProp(t.code(), p, v, m);
                        return;
                    }
                    throw error(m, c.name + " has no field or property '" + m.name() + "'");
                }
                if (t.type().isDynamic()) {
                    stmtLine(t.code() + "." + m.name() + " = " + v);
                    return;
                }
                throw error(m, "cannot assign to '" + m.name() + "' on " + t.type());
            }
            case Index ix -> {
                Ex t = expr(ix.target());
                Ex i = expr(ix.index());
                flushHoistedInto(null);
                switch (t.type().kind) {
                    case LIST, DICT -> stmtLine(t.code() + ":set(" + i.code() + ", " + v + ")");
                    case DYNAMIC -> stmtLine(t.code() + "[" + i.code() + "] = " + v);
                    case STRING -> throw error(ix, "strings cannot be changed; build a new string instead");
                    default -> throw error(ix, "cannot index " + t.type());
                }
            }
            case This t -> throw error(t, "cannot assign to 'this'");
            default -> throw error(target, "this cannot be assigned to");
        }
    }

    private void storeProp(String owner, PropInfo p, String v, Expr at) {
        if (p.auto()) {
            stmtLine(owner + "." + luaName(p.name()) + " = " + v);
        } else if (p.decl().setter() != null) {
            stmtLine(owner + (p.isStatic() ? "." : ":") + "set_" + p.name() + "(" + v + ")");
        } else {
            throw error(at, "property " + p.name() + " has no setter");
        }
    }

    private String fieldOwner(boolean isStatic, ClassInfo owner, Expr at) {
        if (isStatic) {
            return owner.lua;
        }
        if (staticContext) {
            throw error(at, "'" + (at instanceof Name n ? n.id() : "member")
                    + "' belongs to an object; it cannot be used from a static method");
        }
        return "self";
    }

    private void ifStatement(If s) {
        Ex cond = condition(s.cond());
        flushHoistedInto(null);
        line("if " + cond.code() + " then");
        Stmt otherwise = s.otherwise();
        bodyBlock(s.then());
        while (otherwise instanceof If next) {
            csLine = next.line();
            Ex c2 = condition(next.cond());
            if (!hoisted.isEmpty()) {
                // the condition needs its own declarations: nest instead of elseif
                hoisted.clear();
                line("else");
                indent++;
                statement(next, true);
                indent--;
                line("end");
                return;
            }
            line("elseif " + c2.code() + " then");
            bodyBlock(next.then());
            otherwise = next.otherwise();
        }
        if (otherwise != null) {
            line("else");
            bodyBlock(otherwise);
        }
        line("end");
    }

    private Ex condition(Expr e) {
        Ex c = expr(e);
        if (!c.type().is(Type.Kind.BOOL) && !c.type().isDynamic()) {
            throw error(e, "a condition must be true or false (bool), not " + c.type());
        }
        return c;
    }

    private void bodyBlock(Stmt body) {
        indent++;
        if (body instanceof Block b) {
            block(b.stmts());
        } else {
            pushScope();
            statement(body, true);
            popScope();
        }
        indent--;
    }

    private Jump pushLoop() {
        Jump j = new Jump(false, tryDepth, "__continue" + (++tempCounter), null);
        jumps.push(j);
        return j;
    }

    private void loopBody(Stmt body, Jump j) {
        line("do");
        bodyBlock(body);
        line("end");
        if (j.usedContinue) {
            line("::" + j.continueLabel + "::");
        }
    }

    private void whileStatement(While w) {
        Ex cond = condition(w.cond());
        flushHoistedInto(null);
        line("while " + cond.code() + " do");
        indent++;
        Jump j = pushLoop();
        loopBody(w.body(), j);
        jumps.pop();
        indent--;
        line("end");
    }

    private void doWhileStatement(DoWhile d) {
        line("repeat");
        indent++;
        Jump j = pushLoop();
        loopBody(d.body(), j);
        jumps.pop();
        csLine = d.cond().line();
        List<String> pre = new ArrayList<>();
        Ex cond = condition(d.cond());
        flushHoistedInto(pre);
        if (!pre.isEmpty()) {
            throw error(d.cond(), "'out' variables and patterns are not supported in a do-while condition");
        }
        indent--;
        line("until not (" + cond.code() + ")");
    }

    private void forStatement(For f) {
        line("do");
        indent++;
        pushScope();
        for (Stmt s : f.init()) {
            statement(s, false);
        }
        Ex cond = f.cond() == null ? new Ex("true", Type.BOOL) : condition(f.cond());
        flushHoistedInto(null);
        line("while " + cond.code() + " do");
        indent++;
        Jump j = pushLoop();
        loopBody(f.body(), j);
        jumps.pop();
        for (Expr u : f.update()) {
            csLine = u.line();
            exprStatement(u);
        }
        indent--;
        line("end");
        popScope();
        indent--;
        line("end");
    }

    private void foreachStatement(Foreach f) {
        Ex coll = expr(f.collection());
        flushHoistedInto(null);
        Type elem = f.type().isVar() ? coll.type().elementType() : typeOf(f.type());
        pushScope();
        String name = declare(f.name(), elem);
        line("for _, " + name + " in __rt.each(" + coll.code() + ") do");
        indent++;
        Jump j = pushLoop();
        loopBody(f.body(), j);
        jumps.pop();
        indent--;
        line("end");
        popScope();
    }

    private void switchStatement(Switch sw) {
        Ex subject = expr(sw.subject());
        flushHoistedInto(null);
        String tmp = temp("sw");
        Jump j = new Jump(true, tryDepth, null, "__switch_end" + tempCounter);
        line("do");
        indent++;
        line("local " + tmp + " = " + subject.code());
        jumps.push(j);
        boolean first = true;
        SwitchSection defaultSection = null;
        for (SwitchSection sec : sw.sections()) {
            if (sec.hasDefault()) {
                defaultSection = sec;
                continue;
            }
            List<String> tests = new ArrayList<>();
            for (Expr label : sec.labels()) {
                Ex l = expr(label);
                tests.add(tmp + " == " + l.code());
            }
            flushHoistedInto(null);
            csLine = sec.line();
            line((first ? "if " : "elseif ") + String.join(" or ", tests) + " then");
            first = false;
            switchBody(sec);
        }
        if (defaultSection != null) {
            csLine = defaultSection.line();
            if (first) {
                line("do");
            } else {
                line("else");
            }
            switchBody(defaultSection);
            first = false;
        }
        if (!first) {
            line("end");
        }
        jumps.pop();
        if (j.usedBreakLabel) {
            line("::" + j.breakLabel + "::");
        }
        indent--;
        line("end");
    }

    private void switchBody(SwitchSection sec) {
        List<Stmt> body = new ArrayList<>(sec.body());
        if (!body.isEmpty() && body.getLast() instanceof Break) {
            body.removeLast(); // the section's closing break ends the if-chain anyway
        }
        indent++;
        block(body);
        indent--;
    }

    private void breakStatement(Break b) {
        Jump target = jumps.peek();
        if (target == null) {
            throw error(b.line(), 1, "'break' outside a loop or switch");
        }
        if (target.tryDepth < tryDepth) {
            line("do return \"brk\" end");
        } else if (target.isSwitch) {
            target.usedBreakLabel = true;
            line("goto " + target.breakLabel);
        } else {
            line("break");
        }
    }

    private void continueStatement(Continue c) {
        Jump target = null;
        for (Jump j : jumps) {
            if (!j.isSwitch) {
                target = j;
                break;
            }
        }
        if (target == null) {
            throw error(c.line(), 1, "'continue' outside a loop");
        }
        if (target.tryDepth < tryDepth) {
            line("do return \"cnt\" end");
        } else {
            target.usedContinue = true;
            line("goto " + target.continueLabel);
        }
    }

    private void returnStatement(Return r, boolean last) {
        String value = "";
        if (r.value() != null) {
            Ex v = expr(r.value());
            flushHoistedInto(null);
            value = v.code();
        }
        String code;
        if (tryDepth > 0) {
            code = "return \"ret\"" + (value.isEmpty() ? "" : ", " + value);
        } else {
            code = "return" + (value.isEmpty() ? "" : " " + value);
        }
        line(last ? code : "do " + code + " end");
    }

    private void throwStatement(Throw t) {
        if (t.value() == null) {
            if (currentCatchVar == null) {
                throw error(t.line(), 1, "'throw;' can only be used inside a catch block");
            }
            line("__rt.throw(" + currentCatchVar + ")");
            return;
        }
        Ex v = expr(t.value());
        flushHoistedInto(null);
        line("__rt.throw(" + v.code() + ")");
    }

    private void tryStatement(Try t) {
        String status = temp("st");
        String result = temp("rv");
        line("local " + status + ", " + result + " = __rt.try(function()");
        indent++;
        tryDepth++;
        block(t.body().stmts());
        indent--;
        if (!t.catches().isEmpty()) {
            String e = temp("e");
            line("end, function(" + e + ")");
            indent++;
            String savedCatch = currentCatchVar;
            currentCatchVar = e;
            boolean first = true;
            boolean catchAll = false;
            for (Catch c : t.catches()) {
                csLine = c.line();
                String cond = null;
                if (c.type() != null && !c.type().name().equals("Exception")) {
                    ClassInfo ci = classes.get(c.type().name());
                    if (ci == null || !ci.isSubclassOf("Exception")) {
                        throw error(c.line(), 1, "'" + c.type() + "' is not an exception class");
                    }
                    cond = "__rt.is(" + e + ", " + ci.lua + ")";
                }
                if (cond != null) {
                    line((first ? "if " : "elseif ") + cond + " then");
                } else {
                    line(first ? "do" : "else");
                    catchAll = true;
                }
                first = false;
                indent++;
                pushScope();
                if (c.name() != null) {
                    String n = declare(c.name(), Type.cls(c.type() == null ? "Exception" : c.type().name()));
                    line("local " + n + " = " + e);
                }
                block(c.body().stmts());
                popScope();
                indent--;
                if (catchAll) {
                    break;
                }
            }
            if (!catchAll) {
                line("else");
                line("  __rt.throw(" + e + ")");
            }
            line("end");
            currentCatchVar = savedCatch;
            indent--;
        }
        tryDepth--;
        if (t.fin() != null) {
            line(t.catches().isEmpty() ? "end, nil, function()" : "end, function()");
            indent++;
            int saved = tryDepth;
            tryDepth = 0; // C# does not allow return in finally
            Deque<Jump> savedJumps = new ArrayDeque<>(jumps);
            jumps.clear();
            block(t.fin().stmts());
            jumps.addAll(savedJumps);
            tryDepth = saved;
            indent--;
        }
        line("end)");
        // propagate return / break / continue out of the try function
        line("if " + status + " == \"ret\" then " + (tryDepth > 0 ? "return \"ret\", " + result : "return " + result)
                + " end");
        Jump loop = jumps.peek();
        if (loop != null) {
            if (loop.tryDepth < tryDepth) {
                line("if " + status + " == \"brk\" then return \"brk\" end");
            } else if (loop.isSwitch) {
                loop.usedBreakLabel = true;
                line("if " + status + " == \"brk\" then goto " + loop.breakLabel + " end");
            } else {
                line("if " + status + " == \"brk\" then break end");
            }
        }
        Jump cont = null;
        for (Jump j : jumps) {
            if (!j.isSwitch) {
                cont = j;
                break;
            }
        }
        if (cont != null) {
            if (cont.tryDepth < tryDepth) {
                line("if " + status + " == \"cnt\" then return \"cnt\" end");
            } else {
                cont.usedContinue = true;
                line("if " + status + " == \"cnt\" then goto " + cont.continueLabel + " end");
            }
        }
    }

    // =========================================================================================
    // expressions
    // =========================================================================================

    private Ex expr(Expr e) {
        return switch (e) {
            case Literal l -> literal(l);
            case Interp i -> interpolation(i);
            case Name n -> name(n);
            case MemberAccess m -> member(m);
            case Call c -> call(c);
            case Index i -> index(i);
            case New n -> newObject(n);
            case NewArray n -> newArray(n);
            case Unary u -> unary(u);
            case IncDec i -> throw error(i, "'" + i.op() + "' can only be used as its own statement "
                    + "(write it on its own line)");
            case Binary b -> binary(b);
            case Assign a -> throw error(a, "assignment can only be used as its own statement");
            case Conditional c -> conditional(c);
            case Lambda l -> lambda(l, List.of(), null);
            case Cast c -> cast(c);
            case Is i -> isExpr(i);
            case This t -> {
                if (staticContext || currentClass == null) {
                    throw error(t, "'this' cannot be used in a static method");
                }
                yield new Ex("self", Type.cls(currentClass.name));
            }
            case Base b -> throw error(b, "'base' can only be used to call a base method: base.Name(...)");
            case Default d -> new Ex(d.type() == null ? "nil" : typeOf(d.type()).defaultLua(),
                    d.type() == null ? Type.NULL : typeOf(d.type()));
            case Typeof t -> throw error(t, "typeof is not supported by CC: CSharp yet");
        };
    }

    private Ex value(Expr e) {
        Ex v = expr(e);
        if (v.isRef()) {
            throw error(e, "this names a type or API, not a value");
        }
        return v;
    }

    private Ex literal(Literal l) {
        return switch (l.kind()) {
            case INT -> new Ex(l.value(), Type.INT);
            case FLOAT -> new Ex(l.value(), Type.FLOAT);
            case STRING, CHAR -> new Ex(luaString(l.value()), Type.STRING);
            case BOOL -> new Ex(l.value(), Type.BOOL);
            case NULL -> new Ex("nil", Type.NULL);
        };
    }

    private Ex interpolation(Interp i) {
        List<String> parts = new ArrayList<>();
        for (Object p : i.parts()) {
            if (p instanceof String s) {
                parts.add(luaString(s));
            } else {
                Hole h = (Hole) p;
                Ex v = value(h.expr());
                if (h.format() != null) {
                    parts.add("__rt.fmt(" + v.code() + ", " + luaString(h.format()) + ")");
                } else {
                    parts.add(toStr(v));
                }
            }
        }
        if (parts.isEmpty()) {
            return new Ex("\"\"", Type.STRING);
        }
        return new Ex("(" + String.join(" .. ", parts) + ")", Type.STRING);
    }

    static String toStr(Ex v) {
        if (v.type().is(Type.Kind.STRING) && neverNil(v.code())) {
            return v.code();
        }
        return "__rt.str(" + v.code() + ")";
    }

    /** A string literal or a concatenation we generated: never nil, so no __rt.str needed. */
    static boolean neverNil(String code) {
        return code.startsWith("\"") || (code.endsWith(")") && (code.startsWith("(__rt.str(")
                || code.startsWith("(\"") || code.startsWith("(__rt.fmt(")));
    }

    private Ex name(Name n) {
        Local l = lookupLocal(n.id());
        if (l != null) {
            return new Ex(l.lua(), l.type());
        }
        if (currentClass != null) {
            FieldInfo f = currentClass.field(n.id());
            if (f != null) {
                return new Ex(fieldOwner(f.isStatic(), f.owner(), n) + "." + luaName(n.id()), f.type());
            }
            PropInfo p = currentClass.prop(n.id());
            if (p != null) {
                return readProp(fieldOwner(p.isStatic(), p.owner(), n), p, n);
            }
            List<MethodInfo> ms = currentClass.methods(n.id());
            if (!ms.isEmpty()) {
                MethodInfo m = ms.getFirst();
                if (m.isStatic()) {
                    return new Ex(m.owner().lua + "." + m.lua(), Type.func(m.ret()));
                }
                if (staticContext) {
                    throw error(n, "'" + n.id() + "' belongs to an object; it cannot be used from a static method");
                }
                return new Ex("function(...) return self:" + m.lua() + "(...) end", Type.func(m.ret()));
            }
        }
        ClassInfo c = classes.get(n.id());
        if (c != null) {
            return new Ex(c.lua, null, new Ref(c, null, null, null));
        }
        EnumInfo en = enums.get(n.id());
        if (en != null) {
            return new Ex(en.lua, null, new Ref(null, en, null, null));
        }
        if (BUILTIN_STATICS.contains(n.id())) {
            return new Ex(n.id(), null, new Ref(null, null, n.id(), null));
        }
        String g = lowerFirst(n.id());
        if (CC_GLOBALS.contains(g)) {
            return new Ex(g, Type.DYNAMIC, new Ref(null, null, null, g));
        }
        throw error(n, "the name '" + n.id() + "' does not exist here"
                + (Character.isUpperCase(n.id().charAt(0)) ? "" : " (for a Lua global use Lua.Global(\"" + n.id()
                        + "\"))"));
    }

    private Ex readProp(String owner, PropInfo p, Expr at) {
        if (p.auto()) {
            return new Ex(owner + "." + luaName(p.name()), p.type());
        }
        if (p.decl().getter() == null && p.decl().getterExpr() == null) {
            throw error(at, "property " + p.name() + " has no getter");
        }
        return new Ex(owner + (p.isStatic() ? "." : ":") + "get_" + p.name() + "()", p.type());
    }

    private Ex member(MemberAccess m) {
        if (m.target() instanceof Base) {
            throw error(m, "only base methods can be called: base." + m.name() + "(...)");
        }
        Ex t = expr(m.target());
        if (t.isRef()) {
            return staticMember(t.ref(), m);
        }
        if (m.nullConditional()) {
            String tmp = temp("t");
            Ex inner = instanceMember(new Ex(tmp, t.type()), m);
            return new Ex("(function() local " + tmp + " = " + t.code() + " if " + tmp + " == nil then return nil end "
                    + "return " + inner.code() + " end)()", inner.type());
        }
        return instanceMember(t, m);
    }

    private Ex staticMember(Ref r, MemberAccess m) {
        String name = m.name();
        if (r.cls() != null) {
            ClassInfo c = r.cls();
            FieldInfo f = c.field(name);
            if (f != null && f.isStatic()) {
                return new Ex(f.owner().lua + "." + luaName(name), f.type());
            }
            PropInfo p = c.prop(name);
            if (p != null && p.isStatic()) {
                return readProp(p.owner().lua, p, m);
            }
            throw error(m, c.name + " has no static field or property '" + name + "'");
        }
        if (r.en() != null) {
            if (!r.en().values().containsKey(name)) {
                throw error(m, "enum " + r.en().name() + " has no member '" + name + "'");
            }
            return new Ex(r.en().lua() + "." + luaName(name), Type.enumType(r.en().name()));
        }
        if (r.ccGlobal() != null) {
            return new Ex(r.ccGlobal() + "." + lowerFirst(name), Type.DYNAMIC);
        }
        return switch (r.builtin() + "." + name) {
            case "Math.PI" -> new Ex("math.pi", Type.FLOAT);
            case "Math.E" -> new Ex("math.exp(1)", Type.FLOAT);
            case "int.MaxValue", "Int32.MaxValue" -> new Ex("2147483647", Type.INT);
            case "int.MinValue", "Int32.MinValue" -> new Ex("(-2147483648)", Type.INT);
            case "long.MaxValue", "Int64.MaxValue" -> new Ex("9007199254740991", Type.INT);
            case "long.MinValue", "Int64.MinValue" -> new Ex("(-9007199254740991)", Type.INT);
            case "double.MaxValue", "Double.MaxValue", "float.MaxValue" -> new Ex("1.7976931348623157e308", Type.FLOAT);
            case "double.MinValue", "Double.MinValue", "float.MinValue" ->
                    new Ex("(-1.7976931348623157e308)", Type.FLOAT);
            case "double.PositiveInfinity", "float.PositiveInfinity" -> new Ex("math.huge", Type.FLOAT);
            case "double.NegativeInfinity", "float.NegativeInfinity" -> new Ex("(-math.huge)", Type.FLOAT);
            case "double.NaN", "float.NaN" -> new Ex("(0/0)", Type.FLOAT);
            case "double.Epsilon" -> new Ex("4.9e-324", Type.FLOAT);
            case "string.Empty", "String.Empty" -> new Ex("\"\"", Type.STRING);
            case "Environment.NewLine" -> new Ex("\"\\n\"", Type.STRING);
            case "Environment.TickCount" -> new Ex("__rt.ticks()", Type.INT);
            case "StringSplitOptions.None" -> new Ex("0", Type.INT);
            case "StringSplitOptions.RemoveEmptyEntries" -> new Ex("1", Type.INT);
            case "StringSplitOptions.TrimEntries" -> new Ex("2", Type.INT);
            default -> throw error(m, r.builtin() + "." + name + " is not supported by CC: CSharp yet");
        };
    }

    private Ex instanceMember(Ex t, MemberAccess m) {
        String name = m.name();
        Type type = t.type();
        switch (type.kind) {
            case LIST -> {
                if (name.equals("Count") || name.equals("Length")) {
                    return new Ex(t.code() + ".n", Type.INT);
                }
            }
            case DICT -> {
                switch (name) {
                    case "Count" -> {
                        return new Ex(t.code() + ".n", Type.INT);
                    }
                    case "Keys" -> {
                        return new Ex(t.code() + ":KeysList()", Type.list(type.key, false));
                    }
                    case "Values" -> {
                        return new Ex(t.code() + ":ValuesList()", Type.list(type.element, false));
                    }
                    default -> {
                        // error below
                    }
                }
            }
            case PAIR -> {
                if (name.equals("Key")) {
                    return new Ex(t.code() + ".Key", type.key == null ? Type.DYNAMIC : type.key);
                }
                if (name.equals("Value")) {
                    return new Ex(t.code() + ".Value", type.element == null ? Type.DYNAMIC : type.element);
                }
            }
            case STRING -> {
                if (name.equals("Length")) {
                    return new Ex("#" + paren(t.code()), Type.INT);
                }
            }
            case BUILDER -> {
                if (name.equals("Length")) {
                    return new Ex(t.code() + ":Length()", Type.INT);
                }
            }
            case CLASS -> {
                ClassInfo c = classes.get(type.name);
                FieldInfo f = c.field(name);
                if (f != null) {
                    if (f.isStatic()) {
                        return new Ex(f.owner().lua + "." + luaName(name), f.type());
                    }
                    return new Ex(t.code() + "." + luaName(name), f.type());
                }
                PropInfo p = c.prop(name);
                if (p != null) {
                    return readProp(p.isStatic() ? p.owner().lua : t.code(), p, m);
                }
                throw error(m, c.name + " has no field or property '" + name + "'");
            }
            case DYNAMIC -> {
                if (name.equals("Length") || name.equals("Count")) {
                    return new Ex("__rt.count(" + t.code() + ")", Type.INT);
                }
                return new Ex(t.code() + "." + name, Type.DYNAMIC);
            }
            default -> {
                // error below
            }
        }
        throw error(m, type + " has no property '" + name + "'");
    }

    private static String paren(String code) {
        if (code.matches("[A-Za-z_][A-Za-z0-9_.]*") || (code.startsWith("(") && code.endsWith(")"))) {
            return code;
        }
        return "(" + code + ")";
    }

    private Ex index(Index ix) {
        Ex t = value(ix.target());
        Ex i = value(ix.index());
        if (ix.nullConditional()) {
            String tmp = temp("t");
            Ex inner = indexOn(new Ex(tmp, t.type()), i, ix);
            return new Ex("(function() local " + tmp + " = " + t.code() + " if " + tmp + " == nil then return nil end "
                    + "return " + inner.code() + " end)()", inner.type());
        }
        return indexOn(t, i, ix);
    }

    private Ex indexOn(Ex t, Ex i, Index ix) {
        return switch (t.type().kind) {
            case LIST -> new Ex(t.code() + ":get(" + i.code() + ")", t.type().element == null ? Type.DYNAMIC
                    : t.type().element);
            case DICT -> new Ex(t.code() + ":get(" + i.code() + ")", t.type().element == null ? Type.DYNAMIC
                    : t.type().element);
            case STRING -> new Ex("__rt.s.charAt(" + t.code() + ", " + i.code() + ")", Type.STRING);
            case DYNAMIC -> new Ex(paren(t.code()) + "[" + i.code() + "]", Type.DYNAMIC);
            default -> throw error(ix, "cannot use [] on " + t.type());
        };
    }

    // --- calls -----------------------------------------------------------------------------

    private Ex call(Call c) {
        Expr callee = c.callee();
        if (callee instanceof Name n) {
            return callName(n, c);
        }
        if (callee instanceof MemberAccess m) {
            if (m.target() instanceof Base) {
                return callBase(m, c);
            }
            Ex t = expr(m.target());
            if (t.isRef()) {
                return callStatic(t.ref(), m, c);
            }
            if (m.nullConditional()) {
                String tmp = temp("t");
                Ex inner = callMethod(new Ex(tmp, t.type()), m, c);
                return new Ex("(function() local " + tmp + " = " + t.code() + " if " + tmp
                        + " == nil then return nil end return " + inner.code() + " end)()", inner.type());
            }
            return callMethod(t, m, c);
        }
        Ex f = value(callee);
        return new Ex(paren(f.code()) + "(" + joinArgs(args(c, List.of()), false) + ")",
                f.type().is(Type.Kind.FUNC) && f.type().element != null ? f.type().element : Type.DYNAMIC);
    }

    /** Evaluates call arguments; lambdas get parameter types from {@code lambdaParams} when known. */
    private List<Ex> args(Call c, List<Type> lambdaParams) {
        List<Ex> out = new ArrayList<>();
        for (Arg a : c.args()) {
            if (a.expr() == null || (a.outVar() != null)) {
                throw error(c, "'out' arguments are only supported for TryParse and TryGetValue");
            }
            if (a.expr() instanceof Lambda l) {
                out.add(lambda(l, lambdaParams, null));
            } else {
                out.add(value(a.expr()));
            }
        }
        return out;
    }

    private static String joinArgs(List<Ex> args, boolean leadingComma) {
        if (args.isEmpty()) {
            return "";
        }
        List<String> codes = new ArrayList<>();
        args.forEach(a -> codes.add(a.code()));
        return (leadingComma ? ", " : "") + String.join(", ", codes);
    }

    private MethodInfo pickOverload(List<MethodInfo> overloads, int argCount, Expr at, String name) {
        for (MethodInfo m : overloads) {
            if (m.accepts(argCount)) {
                return m;
            }
        }
        throw error(at, "no overload of " + name + " takes " + argCount + " arguments");
    }

    private Ex callName(Name n, Call c) {
        Local l = lookupLocal(n.id());
        if (l != null) {
            List<Type> lambdaParams = List.of();
            Type ret = l.type().is(Type.Kind.FUNC) && l.type().element != null ? l.type().element : Type.DYNAMIC;
            if (l.function() != null) {
                ret = l.function().ret();
            }
            List<Ex> a = args(c, lambdaParams);
            return new Ex(l.lua() + "(" + joinArgs(a, false) + ")", ret);
        }
        if (currentClass != null) {
            List<MethodInfo> ms = currentClass.methods(n.id());
            if (!ms.isEmpty()) {
                MethodInfo m = pickOverload(ms, c.args().size(), c, n.id());
                List<Ex> a = args(c, List.of());
                if (m.isStatic()) {
                    return new Ex(m.owner().lua + "." + m.lua() + "(" + joinArgs(a, false) + ")", m.ret());
                }
                if (staticContext) {
                    throw error(n, "'" + n.id() + "' belongs to an object; it cannot be called from a static method");
                }
                return new Ex("self:" + m.lua() + "(" + joinArgs(a, false) + ")", m.ret());
            }
            PropInfo p = currentClass.prop(n.id());
            FieldInfo f = currentClass.field(n.id());
            if (f != null || p != null) {
                Ex fn = name(n);
                return new Ex(paren(fn.code()) + "(" + joinArgs(args(c, List.of()), false) + ")",
                        fn.type().is(Type.Kind.FUNC) && fn.type().element != null ? fn.type().element : Type.DYNAMIC);
            }
        }
        String g = lowerFirst(n.id());
        if (CC_GLOBALS.contains(g)) {
            return new Ex(g + "(" + joinArgs(args(c, List.of()), false) + ")", Type.DYNAMIC);
        }
        throw error(n, "no method or function named '" + n.id() + "' exists here");
    }

    private Ex callBase(MemberAccess m, Call c) {
        if (currentClass == null || currentClass.base == null || staticContext) {
            throw error(m, "'base' needs a class with a base class, inside an instance method");
        }
        ClassInfo b = currentClass.base;
        List<Ex> a = args(c, List.of());
        if (b.builtin()) {
            if (m.name().equals("ToString")) {
                return new Ex("__rt.ex.Exception.ToString(self)", Type.STRING);
            }
            throw error(m, b.name + " has no method " + m.name() + " to call");
        }
        List<MethodInfo> ms = b.methods(m.name());
        if (ms.isEmpty()) {
            if (m.name().equals("ToString")) {
                return new Ex("__rt.defaultToString(self)", Type.STRING);
            }
            throw error(m, b.name + " has no method " + m.name());
        }
        MethodInfo mi = pickOverload(ms, a.size(), c, m.name());
        return new Ex(mi.owner().lua + "." + mi.lua() + "(self" + joinArgs(a, true) + ")", mi.ret());
    }

    private Ex callStatic(Ref r, MemberAccess m, Call c) {
        String name = m.name();
        if (r.cls() != null) {
            ClassInfo cls = r.cls();
            List<MethodInfo> ms = cls.methods(name);
            if (ms.isEmpty()) {
                throw error(m, cls.name + " has no method '" + name + "'");
            }
            MethodInfo mi = pickOverload(ms, c.args().size(), c, name);
            if (!mi.isStatic()) {
                throw error(m, cls.name + "." + name + " belongs to an object; call it on an instance");
            }
            List<Ex> a = args(c, List.of());
            return new Ex(mi.owner().lua + "." + mi.lua() + "(" + joinArgs(a, false) + ")", mi.ret());
        }
        if (r.en() != null) {
            throw error(m, "enums have no methods");
        }
        if (r.ccGlobal() != null) {
            List<Ex> a = args(c, List.of());
            return new Ex(r.ccGlobal() + "." + lowerFirst(name) + "(" + joinArgs(a, false) + ")", Type.DYNAMIC);
        }
        return Builtins.staticCall(this, r.builtin(), name, c);
    }

    private Ex callMethod(Ex t, MemberAccess m, Call c) {
        String name = m.name();
        Type type = t.type();
        if (name.equals("ToString") && c.args().size() <= 1
                && !(type.is(Type.Kind.CLASS) && !classes.get(type.name).methods("ToString").isEmpty()
                        && c.args().isEmpty())) {
            if (c.args().size() == 1) {
                return new Ex("__rt.fmt(" + t.code() + ", " + value(c.args().getFirst().expr()).code() + ")",
                        Type.STRING);
            }
            return new Ex("__rt.str(" + t.code() + ")", Type.STRING);
        }
        if (name.equals("Equals") && c.args().size() == 1) {
            return new Ex("__rt.equals(" + t.code() + ", " + value(c.args().getFirst().expr()).code() + ")",
                    Type.BOOL);
        }
        if (name.equals("CompareTo") && c.args().size() == 1 && !type.is(Type.Kind.CLASS)) {
            return new Ex("__rt.compare(" + t.code() + ", " + value(c.args().getFirst().expr()).code() + ")",
                    Type.INT);
        }
        switch (type.kind) {
            case LIST -> {
                return Builtins.listCall(this, t, name, c);
            }
            case DICT -> {
                return Builtins.dictCall(this, t, name, c);
            }
            case STRING -> {
                return Builtins.stringCall(this, t, name, c);
            }
            case RANDOM -> {
                return Builtins.randomCall(this, t, name, c);
            }
            case BUILDER -> {
                return Builtins.builderCall(this, t, name, c);
            }
            case FUNC -> {
                if (name.equals("Invoke")) {
                    return new Ex(paren(t.code()) + "(" + joinArgs(args(c, List.of()), false) + ")",
                            type.element == null ? Type.DYNAMIC : type.element);
                }
            }
            case CLASS -> {
                ClassInfo cls = classes.get(type.name);
                List<MethodInfo> ms = cls.methods(name);
                if (!ms.isEmpty()) {
                    MethodInfo mi = pickOverload(ms, c.args().size(), c, name);
                    List<Ex> a = args(c, List.of());
                    if (mi.isStatic()) {
                        return new Ex(mi.owner().lua + "." + mi.lua() + "(" + joinArgs(a, false) + ")", mi.ret());
                    }
                    return new Ex(paren(t.code()) + ":" + mi.lua() + "(" + joinArgs(a, false) + ")", mi.ret());
                }
                FieldInfo f = cls.field(name);
                PropInfo p = cls.prop(name);
                if (f != null || p != null) {
                    Ex fn = instanceMember(t, new MemberAccess(m.target(), name, false, m.line(), m.col()));
                    return new Ex(paren(fn.code()) + "(" + joinArgs(args(c, List.of()), false) + ")",
                            fn.type().is(Type.Kind.FUNC) && fn.type().element != null ? fn.type().element
                                    : Type.DYNAMIC);
                }
                throw error(m, cls.name + " has no method '" + name + "'");
            }
            case DYNAMIC -> {
                List<Ex> a = args(c, List.of());
                return new Ex(paren(t.code()) + "." + lowerFirst(name) + "(" + joinArgs(a, false) + ")",
                        Type.DYNAMIC);
            }
            default -> {
                // error below
            }
        }
        throw error(m, type + " has no method '" + name + "'");
    }

    // hooks for Builtins ---------------------------------------------------------------------

    Ex valueOf(Expr e) {
        return value(e);
    }

    List<Ex> argsOf(Call c, List<Type> lambdaParams) {
        return args(c, lambdaParams);
    }

    Ex lambdaOf(Expr e, List<Type> params) {
        if (e instanceof Lambda l) {
            return lambda(l, params, null);
        }
        return value(e);
    }

    static String join(List<Ex> args) {
        return joinArgs(args, false);
    }

    static Ex ex(String code, Type type) {
        return new Ex(code, type);
    }

    static String code(Ex e) {
        return e.code();
    }

    static Type type(Ex e) {
        return e.type();
    }

    CompileException err(Expr at, String message) {
        return error(at, message);
    }

    /** Declares an {@code out} variable before the current statement and returns its Lua name. */
    String outVariable(Arg a, Type type, Expr at) {
        if (a.outVar() != null) {
            Type t = a.outType() != null ? typeOf(a.outType()) : type;
            String lua = declare(a.outVar(), t);
            hoisted.add("  ".repeat(indent) + "local " + lua + " = " + t.defaultLua());
            return lua;
        }
        if (a.expr() instanceof Name n) {
            Local l = lookupLocal(n.id());
            if (l != null) {
                return l.lua();
            }
        }
        throw error(at, "'out' needs a local variable: out var x, out int x or out x");
    }

    // --- object creation -------------------------------------------------------------------

    private Ex newObject(New n) {
        TypeRef tr = n.type();
        Type type = typeOf(tr);
        switch (type.kind) {
            case LIST -> {
                if (n.fields() != null || n.pairs() != null) {
                    throw error(n, "a list initializer is written { item, item }");
                }
                if (n.items() != null) {
                    return listLiteral(n.items(), type.element, false);
                }
                if (n.args().size() == 1) {
                    Ex src = value(n.args().getFirst());
                    if (src.type().isIntegral()) {
                        return new Ex("__rt.list()", type); // capacity: ignored
                    }
                    return new Ex("__rt.listFrom(" + src.code() + ")", type);
                }
                return new Ex("__rt.list()", type);
            }
            case DICT -> {
                if (n.pairs() != null) {
                    List<String> kv = new ArrayList<>();
                    for (Expr[] p : n.pairs()) {
                        kv.add(value(p[0]).code());
                        kv.add(value(p[1]).code());
                    }
                    return new Ex("__rt.dictOf(" + String.join(", ", kv) + ")", type);
                }
                if (n.items() != null && !n.items().isEmpty()) {
                    throw error(n, "a dictionary initializer is written { {key, value}, ... } or { [key] = value }");
                }
                return new Ex("__rt.dict()", type);
            }
            case RANDOM -> {
                List<String> a = new ArrayList<>();
                n.args().forEach(x -> a.add(value(x).code()));
                return new Ex("__rt.Random.new(" + String.join(", ", a) + ")", type);
            }
            case BUILDER -> {
                List<String> a = new ArrayList<>();
                n.args().forEach(x -> a.add(value(x).code()));
                return new Ex("__rt.Builder.new(" + String.join(", ", a) + ")", type);
            }
            case CLASS -> {
                ClassInfo c = classes.get(type.name);
                if (c.decl != null && c.decl.isStatic()) {
                    throw error(n, "cannot create an instance of static class " + c.name);
                }
                if (c.decl != null && c.decl.modifiers().contains("abstract")) {
                    throw error(n, "cannot create an instance of abstract class " + c.name);
                }
                List<Ex> a = new ArrayList<>();
                n.args().forEach(x -> a.add(value(x)));
                CtorInfo ci = findCtor(c, a.size(), n.line(), n.col());
                String created = "__rt.new(" + c.lua + ", \"" + (ci == null ? "__ctor" : ci.lua()) + "\""
                        + joinArgs(a, true) + ")";
                if (n.fields() != null && !n.fields().isEmpty()) {
                    String o = temp("o");
                    StringBuilder sb = new StringBuilder("(function() local " + o + " = " + created + " ");
                    for (FieldInit fi : n.fields()) {
                        FieldInfo f = c.field(fi.name());
                        PropInfo p = c.prop(fi.name());
                        Ex v;
                        if (f != null) {
                            v = expected(fi.value(), f.type(), f.ref());
                            sb.append(o).append('.').append(luaName(fi.name())).append(" = ").append(v.code()).append(' ');
                        } else if (p != null) {
                            v = expected(fi.value(), p.type(), p.decl().type());
                            if (p.auto()) {
                                sb.append(o).append('.').append(luaName(fi.name())).append(" = ").append(v.code())
                                        .append(' ');
                            } else {
                                sb.append(o).append(":set_").append(fi.name()).append('(').append(v.code())
                                        .append(") ");
                            }
                        } else {
                            throw error(fi.line(), fi.col(), c.name + " has no field or property '" + fi.name() + "'");
                        }
                    }
                    return new Ex(sb.append("return ").append(o).append(" end)()").toString(), type);
                }
                return new Ex(created, type);
            }
            default -> throw error(n, "cannot create '" + tr + "' with new");
        }
    }

    private Ex newArray(NewArray n) {
        if (n.elementType() == null) {
            if (n.items() == null || n.items().isEmpty()) {
                throw error(n, "cannot work out the element type of an empty array; write new T[] { ... }");
            }
            return listLiteral(n.items(), null, true);
        }
        Type elem = typeOf(n.elementType());
        if (n.items() != null) {
            return listLiteral(n.items(), elem, true);
        }
        Ex size = value(n.size());
        return new Ex("__rt.array(" + size.code() + ", " + elem.defaultLua() + ")", Type.list(elem, true));
    }

    private Ex listLiteral(List<Expr> items, Type elem, boolean array) {
        List<String> codes = new ArrayList<>();
        Type inferred = elem;
        for (Expr e : items) {
            Ex v = elem != null ? expected(e, elem, null) : value(e);
            if (inferred == null) {
                inferred = v.type();
            }
            codes.add(v.code());
        }
        return new Ex("__rt.listOf(" + String.join(", ", codes) + ")",
                Type.list(inferred == null ? Type.DYNAMIC : inferred, array));
    }

    // --- operators -------------------------------------------------------------------------

    private Ex unary(Unary u) {
        Ex v = value(u.operand());
        return switch (u.op()) {
            case "!" -> new Ex("not " + paren(v.code()), Type.BOOL);
            case "-" -> {
                if (!v.type().isNumeric() && !v.type().isDynamic()) {
                    throw error(u, "'-' needs a number, not " + v.type());
                }
                yield new Ex("(-" + paren(v.code()) + ")", v.type().is(Type.Kind.ENUM) ? Type.INT : v.type());
            }
            case "+" -> v;
            case "~" -> new Ex("bit32.bnot(" + v.code() + ")", Type.INT);
            default -> throw error(u, "unknown operator " + u.op());
        };
    }

    private Ex binary(Binary b) {
        if (b.op().equals("??")) {
            Ex l = value(b.left());
            Ex r = value(b.right());
            String tmp = temp("t");
            return new Ex("(function() local " + tmp + " = " + l.code() + " if " + tmp + " == nil then return "
                    + r.code() + " end return " + tmp + " end)()", l.type().is(Type.Kind.NULL) ? r.type() : l.type());
        }
        if (b.op().equals("&&") || b.op().equals("||")) {
            Ex l = value(b.left());
            Ex r = value(b.right());
            return new Ex("(" + l.code() + (b.op().equals("&&") ? " and " : " or ") + r.code() + ")", Type.BOOL);
        }
        return binary(b.op(), value(b.left()), value(b.right()), b);
    }

    private Ex binary(String op, Ex l, Ex r, Expr at) {
        Type lt = l.type();
        Type rt = r.type();
        switch (op) {
            case "+" -> {
                if (lt.is(Type.Kind.STRING) || rt.is(Type.Kind.STRING)) {
                    return new Ex("(" + toStr(l) + " .. " + toStr(r) + ")", Type.STRING);
                }
                if (lt.isNumeric() && rt.isNumeric()) {
                    return new Ex("(" + l.code() + " + " + r.code() + ")", numeric(lt, rt));
                }
                if (lt.isDynamic() || rt.isDynamic()) {
                    return new Ex("__rt.add(" + l.code() + ", " + r.code() + ")", Type.DYNAMIC);
                }
                throw error(at, "cannot add " + lt + " and " + rt);
            }
            case "-", "*" -> {
                checkNumeric(op, l, r, at);
                return new Ex("(" + l.code() + " " + op + " " + r.code() + ")", numeric(lt, rt));
            }
            case "/" -> {
                checkNumeric(op, l, r, at);
                if (lt.isIntegral() && rt.isIntegral()) {
                    return new Ex("__rt.idiv(" + l.code() + ", " + r.code() + ")", Type.INT);
                }
                return new Ex("(" + l.code() + " / " + r.code() + ")",
                        lt.isDynamic() || rt.isDynamic() ? Type.DYNAMIC : Type.FLOAT);
            }
            case "%" -> {
                checkNumeric(op, l, r, at);
                return new Ex("math.fmod(" + l.code() + ", " + r.code() + ")", numeric(lt, rt));
            }
            case "==", "!=" -> {
                String lc = l.code();
                String rc = r.code();
                return new Ex("(" + lc + (op.equals("==") ? " == " : " ~= ") + rc + ")", Type.BOOL);
            }
            case "<", ">", "<=", ">=" -> {
                if (lt.is(Type.Kind.STRING) || rt.is(Type.Kind.STRING)) {
                    throw error(at, "strings cannot be compared with " + op + "; use string.Compare or CompareTo");
                }
                return new Ex("(" + l.code() + " " + op + " " + r.code() + ")", Type.BOOL);
            }
            case "&" -> {
                if (lt.is(Type.Kind.BOOL)) {
                    return new Ex("(" + l.code() + " and " + r.code() + ")", Type.BOOL);
                }
                return new Ex("bit32.band(" + l.code() + ", " + r.code() + ")", Type.INT);
            }
            case "|" -> {
                if (lt.is(Type.Kind.BOOL)) {
                    return new Ex("(" + l.code() + " or " + r.code() + ")", Type.BOOL);
                }
                return new Ex("bit32.bor(" + l.code() + ", " + r.code() + ")", Type.INT);
            }
            case "^" -> {
                if (lt.is(Type.Kind.BOOL)) {
                    return new Ex("(" + l.code() + " ~= " + r.code() + ")", Type.BOOL);
                }
                return new Ex("bit32.bxor(" + l.code() + ", " + r.code() + ")", Type.INT);
            }
            case "<<" -> {
                return new Ex("bit32.lshift(" + l.code() + ", " + r.code() + ")", Type.INT);
            }
            case ">>" -> {
                return new Ex("bit32.arshift(" + l.code() + ", " + r.code() + ")", Type.INT);
            }
            default -> throw error(at, "operator " + op + " is not supported");
        }
    }

    private void checkNumeric(String op, Ex l, Ex r, Expr at) {
        boolean ok = (l.type().isNumeric() || l.type().isDynamic()) && (r.type().isNumeric() || r.type().isDynamic());
        if (!ok) {
            throw error(at, "'" + op + "' needs numbers, not " + l.type() + " and " + r.type());
        }
    }

    private static Type numeric(Type a, Type b) {
        if (a.isDynamic() || b.isDynamic()) {
            return Type.DYNAMIC;
        }
        return a.isIntegral() && b.isIntegral() ? Type.INT : Type.FLOAT;
    }

    private Ex conditional(Conditional c) {
        Ex cond = condition(c.cond());
        Ex a = value(c.whenTrue());
        Ex b = value(c.whenFalse());
        Type t = a.type().is(Type.Kind.NULL) ? b.type() : a.type();
        if (a.type().isNumeric() && b.type().isNumeric() && !a.type().equals(b.type())) {
            t = Type.FLOAT;
        }
        boolean safe = a.type().isNumeric() || (a.code().startsWith("\"") && a.type().is(Type.Kind.STRING))
                || a.code().equals("true");
        if (safe) {
            return new Ex("(" + cond.code() + " and " + a.code() + " or " + b.code() + ")", t);
        }
        return new Ex("(function() if " + cond.code() + " then return " + a.code() + " end return " + b.code()
                + " end)()", t);
    }

    private Ex cast(Cast c) {
        Type target = typeOf(c.type());
        Ex v = value(c.operand());
        return switch (target.kind) {
            case INT -> {
                if (v.type().is(Type.Kind.STRING)) {
                    yield new Ex("string.byte(" + v.code() + ")", Type.INT); // (int)'a': chars are 1-char strings
                }
                yield v.type().isIntegral() ? new Ex(v.code(), Type.INT) : new Ex("__rt.toint(" + v.code() + ")",
                        Type.INT);
            }
            case FLOAT -> new Ex(v.code(), Type.FLOAT);
            case STRING -> {
                if (c.type().name().equals("char") && v.type().isNumeric()) {
                    yield new Ex("string.char(" + v.code() + ")", Type.STRING);
                }
                yield new Ex(v.code(), Type.STRING);
            }
            case ENUM -> new Ex(v.code(), target);
            case CLASS -> new Ex("__rt.cast(" + v.code() + ", " + classes.get(target.name).lua + ")", target);
            default -> new Ex(v.code(), target);
        };
    }

    private Ex isExpr(Is i) {
        Ex v = value(i.operand());
        String test;
        if (i.isNull()) {
            test = "(" + v.code() + " == nil)";
        } else {
            Type t = typeOf(i.type());
            test = switch (t.kind) {
                case INT -> "__rt.isInt(" + v.code() + ")";
                case FLOAT -> "(type(" + v.code() + ") == \"number\")";
                case STRING -> "(type(" + v.code() + ") == \"string\")";
                case BOOL -> "(type(" + v.code() + ") == \"boolean\")";
                case CLASS -> "__rt.is(" + v.code() + ", " + classes.get(t.name).lua + ")";
                case LIST -> "__rt.isList(" + v.code() + ")";
                case DICT -> "__rt.isDict(" + v.code() + ")";
                default -> throw error(i, "'is " + i.type() + "' is not supported");
            };
            if (i.bindName() != null) {
                String bound = declare(i.bindName(), t);
                hoisted.add("  ".repeat(indent) + "local " + bound);
                String tmp = temp("v");
                test = "(function() local " + tmp + " = " + v.code() + " if " + test.replace(v.code(), tmp)
                        + " then " + bound + " = " + tmp + " return true end return false end)()";
            }
        }
        if (i.negated()) {
            test = "not " + test;
        }
        return new Ex(test, Type.BOOL);
    }

    private Ex lambda(Lambda l, List<Type> paramTypes, Type returnType) {
        Deque<Jump> savedJumps = new ArrayDeque<>(jumps);
        jumps.clear();
        int savedTry = tryDepth;
        tryDepth = 0;
        String savedCatch = currentCatchVar;
        currentCatchVar = null;
        List<String> savedHoisted = new ArrayList<>(hoisted);
        hoisted.clear();
        pushScope();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < l.params().size(); i++) {
            Param p = l.params().get(i);
            Type t = p.type() != null ? typeOf(p.type()) : i < paramTypes.size() ? paramTypes.get(i) : Type.DYNAMIC;
            names.add(declare(p.name(), t));
        }
        String code;
        Type ret;
        if (l.bodyExpr() != null) {
            Ex body = value(l.bodyExpr());
            String pre = hoisted.isEmpty() ? "" : String.join(" ", hoisted.stream().map(String::strip).toList()) + " ";
            hoisted.clear();
            code = "function(" + String.join(", ", names) + ") " + pre + "return " + body.code() + " end";
            ret = body.type();
        } else {
            // render the block into separate lines, then fold them into one expression
            List<String> savedLines = new ArrayList<>(lines);
            List<Integer> savedMap = new ArrayList<>(lineMap);
            int savedIndent = indent;
            lines.clear();
            lineMap.clear();
            indent = 0;
            block(l.body().stmts());
            List<String> body = new ArrayList<>(lines);
            lines.clear();
            lineMap.clear();
            lines.addAll(savedLines);
            lineMap.addAll(savedMap);
            indent = savedIndent;
            code = "function(" + String.join(", ", names) + ")\n" + String.join("\n", body.stream()
                    .map(s -> "  ".repeat(indent + 1) + s).toList()) + "\n" + "  ".repeat(indent) + "end";
            ret = returnType == null ? Type.DYNAMIC : returnType;
        }
        popScope();
        hoisted.addAll(savedHoisted);
        currentCatchVar = savedCatch;
        tryDepth = savedTry;
        jumps.addAll(savedJumps);
        return new Ex(code, Type.func(ret));
    }
}
