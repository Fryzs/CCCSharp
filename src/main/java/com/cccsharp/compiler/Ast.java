package com.cccsharp.compiler;

import java.util.List;
import java.util.Set;

/** The syntax tree of the C# subset CC: CSharp understands. */
final class Ast {
    private Ast() {}

    /** A type as written: {@code int}, {@code List<string>}, {@code int[]}, {@code Point?}. */
    record TypeRef(String name, List<TypeRef> args, int arrayRank, boolean nullable, int line, int col) {
        boolean isVar() {
            return name.equals("var") && args.isEmpty() && arrayRank == 0;
        }

        boolean isVoid() {
            return name.equals("void") && arrayRank == 0;
        }

        TypeRef element() {
            return new TypeRef(name, args, arrayRank - 1, false, line, col);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name);
            if (!args.isEmpty()) {
                sb.append('<');
                for (int i = 0; i < args.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(args.get(i));
                }
                sb.append('>');
            }
            sb.append("[]".repeat(arrayRank));
            return sb.toString();
        }
    }

    // --- program and declarations ------------------------------------------------------------

    record CompilationUnit(List<TypeDecl> types, List<Stmt> topLevel) {}

    sealed interface TypeDecl permits ClassDecl, EnumDecl {
        String name();

        int line();

        int col();
    }

    record ClassDecl(String name, Set<String> modifiers, boolean isStruct, List<TypeRef> bases, List<Member> members,
            int line, int col) implements TypeDecl {
        boolean isStatic() {
            return modifiers.contains("static");
        }
    }

    record EnumDecl(String name, List<EnumMember> members, int line, int col) implements TypeDecl {}

    record EnumMember(String name, Expr value, int line, int col) {}

    sealed interface Member permits FieldDecl, PropertyDecl, MethodDecl, CtorDecl {
        String name();

        Set<String> modifiers();

        int line();

        int col();

        default boolean isStatic() {
            return modifiers().contains("static") || modifiers().contains("const");
        }
    }

    record FieldDecl(Set<String> modifiers, TypeRef type, String name, Expr init, int line, int col)
            implements Member {}

    /**
     * A property. Auto-properties ({@code { get; set; }}) have {@code auto} set and become plain fields;
     * others become {@code get_Name}/{@code set_Name} methods.
     */
    record PropertyDecl(Set<String> modifiers, TypeRef type, String name, boolean auto, Expr init,
            Block getter, Expr getterExpr, Block setter, boolean hasSetter, int line, int col) implements Member {}

    record MethodDecl(Set<String> modifiers, TypeRef returnType, String name, List<Param> params, Block body,
            Expr bodyExpr, int line, int col) implements Member {
        boolean hasBody() {
            return body != null || bodyExpr != null;
        }
    }

    /** {@code : base(...)} or {@code : this(...)} before a constructor body. */
    record CtorInit(boolean isBase, List<Expr> args, int line, int col) {}

    record CtorDecl(Set<String> modifiers, String name, List<Param> params, CtorInit init, Block body, int line,
            int col) implements Member {}

    record Param(TypeRef type, String name, Expr defaultValue, boolean isParams, int line, int col) {}

    // --- statements ------------------------------------------------------------------------

    sealed interface Stmt permits Block, LocalDecl, LocalFunc, ExprStmt, If, While, DoWhile, For, Foreach, Switch,
            Break, Continue, Return, Throw, Try, Empty {
        int line();
    }

    record Block(List<Stmt> stmts, int line) implements Stmt {}

    record Declarator(String name, Expr init, int line, int col) {}

    /** {@code type a = 1, b;} or {@code var x = ...;}; {@code type} is {@code var} when inferred. */
    record LocalDecl(TypeRef type, List<Declarator> vars, boolean isConst, int line) implements Stmt {}

    record LocalFunc(MethodDecl method, int line) implements Stmt {}

    record ExprStmt(Expr expr, int line) implements Stmt {}

    record If(Expr cond, Stmt then, Stmt otherwise, int line) implements Stmt {}

    record While(Expr cond, Stmt body, int line) implements Stmt {}

    record DoWhile(Stmt body, Expr cond, int line) implements Stmt {}

    record For(List<Stmt> init, Expr cond, List<Expr> update, Stmt body, int line) implements Stmt {}

    record Foreach(TypeRef type, String name, Expr collection, Stmt body, int line) implements Stmt {}

    /** A switch section: its labels ({@code null} label = {@code default}) and statements. */
    record SwitchSection(List<Expr> labels, boolean hasDefault, List<Stmt> body, int line) {}

    record Switch(Expr subject, List<SwitchSection> sections, int line) implements Stmt {}

    record Break(int line) implements Stmt {}

    record Continue(int line) implements Stmt {}

    record Return(Expr value, int line) implements Stmt {}

    /** {@code throw expr;} or a bare {@code throw;} (value null) that rethrows in a catch. */
    record Throw(Expr value, int line) implements Stmt {}

    record Catch(TypeRef type, String name, Block body, int line) {}

    record Try(Block body, List<Catch> catches, Block fin, int line) implements Stmt {}

    record Empty(int line) implements Stmt {}

    // --- expressions -----------------------------------------------------------------------

    sealed interface Expr permits Literal, Interp, Name, MemberAccess, Call, Index, New, NewArray, Unary, IncDec,
            Binary, Assign, Conditional, Lambda, Cast, Is, This, Base, Default, Typeof {
        int line();

        int col();
    }

    enum LitKind { INT, FLOAT, STRING, CHAR, BOOL, NULL }

    record Literal(LitKind kind, String value, int line, int col) implements Expr {}

    /** An interpolation hole: the expression and its optional format ({@code F2}, {@code N0}, ...). */
    record Hole(Expr expr, String format) {}

    /** Interpolated string: each part is a {@link String} or a {@link Hole}. */
    record Interp(List<Object> parts, int line, int col) implements Expr {}

    record Name(String id, int line, int col) implements Expr {}

    record MemberAccess(Expr target, String name, boolean nullConditional, int line, int col) implements Expr {}

    /** An argument; {@code outVar} is set for {@code out var x} / {@code out T x}. */
    record Arg(Expr expr, String outVar, TypeRef outType) {}

    record Call(Expr callee, List<Arg> args, int line, int col) implements Expr {}

    record Index(Expr target, Expr index, boolean nullConditional, int line, int col) implements Expr {}

    /** One {@code Name = value} in an object initializer. */
    record FieldInit(String name, Expr value, int line, int col) {}

    /**
     * {@code new T(args)} with an optional collection initializer ({@code { 1, 2 }} or, for dictionaries,
     * {@code { {k, v}, ... }} / {@code { [k] = v }}) or object initializer ({@code { X = 1 }}).
     */
    record New(TypeRef type, List<Expr> args, List<Expr> items, List<Expr[]> pairs, List<FieldInit> fields,
            int line, int col) implements Expr {}

    /** {@code new T[n]} or {@code new T[] { ... }} or {@code new[] { ... }} (type null). */
    record NewArray(TypeRef elementType, Expr size, List<Expr> items, int line, int col) implements Expr {}

    record Unary(String op, Expr operand, int line, int col) implements Expr {}

    /** {@code ++x}, {@code x++}, {@code --x}, {@code x--}. */
    record IncDec(String op, boolean prefix, Expr target, int line, int col) implements Expr {}

    record Binary(String op, Expr left, Expr right, int line, int col) implements Expr {}

    /** {@code =}, {@code +=}, {@code ??=}, ... */
    record Assign(String op, Expr target, Expr value, int line, int col) implements Expr {}

    record Conditional(Expr cond, Expr whenTrue, Expr whenFalse, int line, int col) implements Expr {}

    record Lambda(List<Param> params, Expr bodyExpr, Block body, int line, int col) implements Expr {}

    record Cast(TypeRef type, Expr operand, int line, int col) implements Expr {}

    /** {@code x is T}, {@code x is T name}, {@code x is null}. */
    record Is(Expr operand, TypeRef type, String bindName, boolean isNull, boolean negated, int line, int col)
            implements Expr {}

    record This(int line, int col) implements Expr {}

    record Base(int line, int col) implements Expr {}

    record Default(TypeRef type, int line, int col) implements Expr {}

    record Typeof(TypeRef type, int line, int col) implements Expr {}
}
