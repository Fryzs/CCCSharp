package com.cccsharp.compiler;

import com.cccsharp.compiler.Ast.*;
import com.cccsharp.compiler.Token.InterpPart;
import com.cccsharp.compiler.Token.Kind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recursive-descent parser for the C# subset. Unsupported C# features stop with a clear message. */
final class Parser {
    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "internal", "static", "readonly", "const", "virtual", "override",
            "abstract", "sealed", "new", "partial", "extern", "unsafe", "volatile", "async", "required");

    private static final Set<String> PREDEFINED = Set.of(
            "int", "long", "short", "byte", "sbyte", "uint", "ulong", "ushort", "double", "float", "decimal",
            "bool", "string", "char", "object", "dynamic", "void", "var");

    private static final Set<String> STATEMENT_KEYWORDS = Set.of(
            "if", "else", "while", "do", "for", "foreach", "switch", "case", "default", "break", "continue",
            "return", "throw", "try", "catch", "finally", "new", "true", "false", "null", "this", "base",
            "typeof", "is", "as", "in", "out", "ref", "using", "namespace", "class", "struct", "enum",
            "interface", "operator", "goto", "lock", "yield", "checked", "unchecked", "fixed", "nameof");

    private final List<Token> tokens;
    private int pos;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    // --- token helpers ---------------------------------------------------------------------

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        int i = Math.min(pos + ahead, tokens.size() - 1);
        return tokens.get(i);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.kind() != Kind.EOF) {
            pos++;
        }
        return t;
    }

    private boolean atOp(String op) {
        return peek().isOp(op);
    }

    private boolean atIdent(String id) {
        return peek().isIdent(id);
    }

    private boolean acceptOp(String op) {
        if (atOp(op)) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean acceptIdent(String id) {
        if (atIdent(id)) {
            pos++;
            return true;
        }
        return false;
    }

    private Token expectOp(String op) {
        if (!atOp(op)) {
            throw error(peek(), "expected '" + op + "' but found " + peek());
        }
        return advance();
    }

    private void expectIdent(String id) {
        if (!atIdent(id)) {
            throw error(peek(), "expected '" + id + "' but found " + peek());
        }
        advance();
    }

    private String identifier(String what) {
        Token t = peek();
        if (t.kind() != Kind.IDENT || (STATEMENT_KEYWORDS.contains(t.text()) && !t.text().equals("value"))) {
            throw error(t, "expected " + what + " but found " + t);
        }
        advance();
        return t.text();
    }

    private static CompileException error(Token t, String message) {
        return new CompileException(t.line(), t.col(), message);
    }

    private static CompileException unsupported(Token t, String what) {
        return new CompileException(t.line(), t.col(), what + " is not supported by CC: CSharp yet");
    }

    // --- compilation unit ------------------------------------------------------------------

    CompilationUnit parseUnit() {
        List<TypeDecl> types = new ArrayList<>();
        List<Stmt> top = new ArrayList<>();
        parseNamespaceBody(types, top, false);
        if (peek().kind() != Kind.EOF) {
            throw error(peek(), "unexpected " + peek());
        }
        return new CompilationUnit(types, top);
    }

    private void parseNamespaceBody(List<TypeDecl> types, List<Stmt> top, boolean braced) {
        while (peek().kind() != Kind.EOF && !(braced && atOp("}"))) {
            if (atIdent("using") && !peek(1).isOp("(") && !peek(1).isIdent("var")) {
                while (!atOp(";")) {
                    if (peek().kind() == Kind.EOF) {
                        throw error(peek(), "expected ';' after using");
                    }
                    advance();
                }
                advance();
                continue;
            }
            if (atIdent("namespace")) {
                advance();
                qualifiedName();
                if (acceptOp(";")) {
                    continue; // file-scoped namespace
                }
                expectOp("{");
                parseNamespaceBody(types, top, true);
                expectOp("}");
                continue;
            }
            if (atOp("[")) {
                skipAttributes();
                continue;
            }
            int save = pos;
            Set<String> mods = modifiers();
            if (atIdent("class") || atIdent("struct") || atIdent("enum") || atIdent("interface")
                    || atIdent("record")) {
                TypeDecl decl = typeDeclaration(mods);
                if (decl != null) {
                    types.add(decl);
                }
                continue;
            }
            pos = save;
            if (braced) {
                throw error(peek(), "expected a class, struct, enum or interface");
            }
            top.add(statement());
        }
    }

    private String qualifiedName() {
        StringBuilder sb = new StringBuilder(identifier("a name"));
        while (acceptOp(".")) {
            sb.append('.').append(identifier("a name"));
        }
        return sb.toString();
    }

    private void skipAttributes() {
        while (atOp("[")) {
            int depth = 0;
            do {
                Token t = advance();
                if (t.isOp("[")) {
                    depth++;
                } else if (t.isOp("]")) {
                    depth--;
                } else if (t.kind() == Kind.EOF) {
                    throw error(t, "attribute is never closed with ']'");
                }
            } while (depth > 0);
        }
    }

    private Set<String> modifiers() {
        Set<String> mods = new LinkedHashSet<>();
        while (peek().kind() == Kind.IDENT && MODIFIERS.contains(peek().text())
                && !(peek().text().equals("new") && (peek(1).isOp("(") || peek(1).kind() != Kind.IDENT))) {
            mods.add(advance().text());
        }
        return mods;
    }

    private TypeDecl typeDeclaration(Set<String> mods) {
        Token kw = advance();
        switch (kw.text()) {
            case "record" -> throw unsupported(kw, "'record'");
            case "interface" -> {
                identifier("an interface name");
                skipToBalancedBody();
                return null; // interfaces only describe shapes; nothing to run
            }
            case "enum" -> {
                return enumDeclaration(kw);
            }
            default -> {
                return classDeclaration(kw, mods);
            }
        }
    }

    private void skipToBalancedBody() {
        while (!atOp("{")) {
            if (peek().kind() == Kind.EOF) {
                throw error(peek(), "expected '{'");
            }
            advance();
        }
        int depth = 0;
        do {
            Token t = advance();
            if (t.isOp("{")) {
                depth++;
            } else if (t.isOp("}")) {
                depth--;
            } else if (t.kind() == Kind.EOF) {
                throw error(t, "'{' is never closed with '}'");
            }
        } while (depth > 0);
    }

    private EnumDecl enumDeclaration(Token kw) {
        String name = identifier("an enum name");
        if (acceptOp(":")) {
            type();
        }
        expectOp("{");
        List<EnumMember> members = new ArrayList<>();
        while (!atOp("}")) {
            skipAttributes();
            Token t = peek();
            String member = identifier("an enum member");
            Expr value = acceptOp("=") ? expression() : null;
            members.add(new EnumMember(member, value, t.line(), t.col()));
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp("}");
        acceptOp(";");
        return new EnumDecl(name, members, kw.line(), kw.col());
    }

    private ClassDecl classDeclaration(Token kw, Set<String> mods) {
        String name = identifier("a class name");
        if (atOp("<")) {
            throw unsupported(peek(), "generic classes");
        }
        if (atOp("(")) {
            throw unsupported(peek(), "primary constructors");
        }
        List<TypeRef> bases = new ArrayList<>();
        if (acceptOp(":")) {
            do {
                bases.add(type());
            } while (acceptOp(","));
        }
        if (atIdent("where")) {
            throw unsupported(peek(), "generic constraints");
        }
        expectOp("{");
        List<Member> members = new ArrayList<>();
        while (!atOp("}")) {
            if (peek().kind() == Kind.EOF) {
                throw error(peek(), "class " + name + " is never closed with '}'");
            }
            member(name, members);
        }
        expectOp("}");
        acceptOp(";");
        return new ClassDecl(name, mods, kw.text().equals("struct"), bases, members, kw.line(), kw.col());
    }

    private void member(String className, List<Member> members) {
        skipAttributes();
        Token start = peek();
        Set<String> mods = modifiers();
        if (atIdent("class") || atIdent("struct") || atIdent("enum") || atIdent("interface") || atIdent("record")) {
            throw unsupported(peek(), "a type declared inside another type");
        }
        if (atOp("~")) {
            throw unsupported(peek(), "finalizers (~" + className + ")");
        }
        if (atIdent("event") || atIdent("delegate") || atIdent("operator") || atIdent("implicit")
                || atIdent("explicit")) {
            throw unsupported(peek(), "'" + peek().text() + "'");
        }
        if (atIdent(className) && peek(1).isOp("(")) {
            members.add(constructor(mods, start));
            return;
        }
        TypeRef type = type();
        Token nameTok = peek();
        if (atIdent("this") && peek(1).isOp("[")) {
            throw unsupported(nameTok, "indexers (this[...])");
        }
        if (atIdent("operator")) {
            throw unsupported(nameTok, "operator overloading");
        }
        String name = identifier("a member name");
        if (atOp("<")) {
            throw unsupported(peek(), "generic methods");
        }
        if (atOp("(")) {
            List<Param> params = parameters();
            if (atIdent("where")) {
                throw unsupported(peek(), "generic constraints");
            }
            Block body = null;
            Expr bodyExpr = null;
            if (atOp("{")) {
                body = block();
            } else if (acceptOp("=>")) {
                bodyExpr = expression();
                expectOp(";");
            } else {
                expectOp(";"); // abstract or extern
            }
            members.add(new MethodDecl(mods, type, name, params, body, bodyExpr, start.line(), start.col()));
            return;
        }
        if (atOp("{")) {
            members.add(property(mods, type, name, start));
            return;
        }
        if (acceptOp("=>")) {
            Expr getter = expression();
            expectOp(";");
            members.add(new PropertyDecl(mods, type, name, false, null, null, getter, null, false, start.line(),
                    start.col()));
            return;
        }
        // field(s)
        while (true) {
            Expr init = acceptOp("=") ? initializerExpression() : null;
            members.add(new FieldDecl(mods, type, name, init, nameTok.line(), nameTok.col()));
            if (!acceptOp(",")) {
                break;
            }
            nameTok = peek();
            name = identifier("a field name");
        }
        expectOp(";");
    }

    private CtorDecl constructor(Set<String> mods, Token start) {
        String name = advance().text();
        List<Param> params = parameters();
        CtorInit init = null;
        if (acceptOp(":")) {
            Token t = peek();
            boolean isBase;
            if (acceptIdent("base")) {
                isBase = true;
            } else if (acceptIdent("this")) {
                isBase = false;
            } else {
                throw error(t, "expected 'base' or 'this' after ':'");
            }
            init = new CtorInit(isBase, arguments().stream().map(Arg::expr).toList(), t.line(), t.col());
        }
        Block body;
        if (acceptOp("=>")) {
            Expr e = expression();
            expectOp(";");
            body = new Block(List.of(new ExprStmt(e, e.line())), e.line());
        } else {
            body = block();
        }
        return new CtorDecl(mods, name, params, init, body, start.line(), start.col());
    }

    private PropertyDecl property(Set<String> mods, TypeRef type, String name, Token start) {
        expectOp("{");
        boolean auto = true;
        boolean hasSetter = false;
        Block getter = null;
        Expr getterExpr = null;
        Block setter = null;
        while (!atOp("}")) {
            skipAttributes();
            modifiers();
            Token acc = peek();
            boolean isGet = acceptIdent("get");
            boolean isSet = !isGet && (acceptIdent("set") || acceptIdent("init"));
            if (!isGet && !isSet) {
                throw error(acc, "expected 'get' or 'set' in property " + name);
            }
            if (isSet) {
                hasSetter = true;
            }
            if (acceptOp(";")) {
                continue;
            }
            auto = false;
            if (atOp("{")) {
                Block b = block();
                if (isGet) {
                    getter = b;
                } else {
                    setter = b;
                }
            } else {
                expectOp("=>");
                Expr e = expression();
                expectOp(";");
                if (isGet) {
                    getterExpr = e;
                } else {
                    setter = new Block(List.of(new ExprStmt(e, e.line())), e.line());
                }
            }
        }
        expectOp("}");
        Expr init = null;
        if (acceptOp("=")) {
            init = initializerExpression();
            expectOp(";");
        }
        if (!auto && init != null) {
            throw error(start, "property " + name + " has a body, so it cannot have an initializer");
        }
        return new PropertyDecl(mods, type, name, auto, init, getter, getterExpr, setter, hasSetter, start.line(),
                start.col());
    }

    private List<Param> parameters() {
        expectOp("(");
        List<Param> params = new ArrayList<>();
        if (!atOp(")")) {
            do {
                skipAttributes();
                Token t = peek();
                boolean isParams = acceptIdent("params");
                if (atIdent("ref") || atIdent("out") || atIdent("in")) {
                    throw unsupported(peek(), "'" + peek().text() + "' parameters");
                }
                acceptIdent("this");
                TypeRef type = type();
                String name = identifier("a parameter name");
                Expr def = acceptOp("=") ? expression() : null;
                params.add(new Param(type, name, def, isParams, t.line(), t.col()));
            } while (acceptOp(","));
        }
        expectOp(")");
        return params;
    }

    // --- types -----------------------------------------------------------------------------

    private TypeRef type() {
        TypeRef t = tryType();
        if (t == null) {
            throw error(peek(), "expected a type but found " + peek());
        }
        return t;
    }

    /** Parses a type at the current position, or returns null (position unchanged) if there is none. */
    private TypeRef tryType() {
        int save = pos;
        Token start = peek();
        if (start.isOp("(")) {
            return null; // tuples are not supported; let the caller report what it expects
        }
        if (start.kind() != Kind.IDENT
                || (STATEMENT_KEYWORDS.contains(start.text()) && !PREDEFINED.contains(start.text()))) {
            return null;
        }
        String name = advance().text();
        while (atOp(".") && peek(1).kind() == Kind.IDENT) {
            advance();
            name = advance().text(); // System.Collections.Generic.List -> List
        }
        List<TypeRef> args = new ArrayList<>();
        if (atOp("<")) {
            int argSave = pos;
            advance();
            boolean ok = true;
            do {
                TypeRef a = tryType();
                if (a == null) {
                    ok = false;
                    break;
                }
                args.add(a);
            } while (acceptOp(","));
            if (!ok || !acceptOp(">")) {
                pos = argSave;
                args.clear();
                if (!PREDEFINED.contains(name)) {
                    pos = save;
                    return null;
                }
            }
        }
        boolean nullable = false;
        if (atOp("?") && (peek(1).kind() == Kind.IDENT || peek(1).isOp(">") || peek(1).isOp(",")
                || peek(1).isOp("[") || peek(1).isOp(")"))) {
            advance();
            nullable = true;
        }
        int rank = 0;
        while (atOp("[") && peek(1).isOp("]")) {
            advance();
            advance();
            rank++;
        }
        if (atOp("[") && peek(1).isOp(",")) {
            throw unsupported(peek(), "multi-dimensional arrays ([,])");
        }
        if (atOp("?") && (peek(1).kind() == Kind.IDENT || peek(1).isOp(">") || peek(1).isOp(","))) {
            advance();
            nullable = true;
        }
        return new TypeRef(name, args, rank, nullable, start.line(), start.col());
    }

    // --- statements ------------------------------------------------------------------------

    Block block() {
        Token open = expectOp("{");
        List<Stmt> stmts = new ArrayList<>();
        while (!atOp("}")) {
            if (peek().kind() == Kind.EOF) {
                throw error(open, "'{' is never closed with '}'");
            }
            stmts.add(statement());
        }
        expectOp("}");
        return new Block(stmts, open.line());
    }

    /** A statement used as a body: wraps a single statement so loops and ifs always hold a block. */
    private Stmt embedded() {
        return statement();
    }

    Stmt statement() {
        Token t = peek();
        int line = t.line();
        if (t.isOp("{")) {
            return block();
        }
        if (t.isOp(";")) {
            advance();
            return new Empty(line);
        }
        if (t.isOp("[")) {
            skipAttributes();
            return statement();
        }
        if (t.kind() == Kind.IDENT) {
            switch (t.text()) {
                case "if" -> {
                    advance();
                    expectOp("(");
                    Expr cond = expression();
                    expectOp(")");
                    Stmt then = embedded();
                    Stmt otherwise = acceptIdent("else") ? embedded() : null;
                    return new If(cond, then, otherwise, line);
                }
                case "while" -> {
                    advance();
                    expectOp("(");
                    Expr cond = expression();
                    expectOp(")");
                    return new While(cond, embedded(), line);
                }
                case "do" -> {
                    advance();
                    Stmt body = embedded();
                    expectIdent("while");
                    expectOp("(");
                    Expr cond = expression();
                    expectOp(")");
                    expectOp(";");
                    return new DoWhile(body, cond, line);
                }
                case "for" -> {
                    return forStatement();
                }
                case "foreach" -> {
                    return foreachStatement();
                }
                case "switch" -> {
                    return switchStatement();
                }
                case "break" -> {
                    advance();
                    expectOp(";");
                    return new Break(line);
                }
                case "continue" -> {
                    advance();
                    expectOp(";");
                    return new Continue(line);
                }
                case "return" -> {
                    advance();
                    Expr value = atOp(";") ? null : expression();
                    expectOp(";");
                    return new Return(value, line);
                }
                case "throw" -> {
                    advance();
                    Expr value = atOp(";") ? null : expression();
                    expectOp(";");
                    return new Throw(value, line);
                }
                case "try" -> {
                    return tryStatement();
                }
                case "using", "lock", "yield", "goto", "checked", "unchecked", "fixed", "unsafe" ->
                    throw unsupported(t, "the '" + t.text() + "' statement");
                case "const" -> {
                    advance();
                    TypeRef type = type();
                    return localDeclaration(type, true, line);
                }
                default -> {
                    // fall through to declarations and expressions
                }
            }
        }
        // local function or variable declaration?
        int save = pos;
        boolean isStatic = acceptIdent("static") | acceptIdent("async");
        TypeRef type = tryType();
        if (type != null && peek().kind() == Kind.IDENT && !STATEMENT_KEYWORDS.contains(peek().text())) {
            Token next = peek(1);
            if (next.isOp("(")) {
                Token nameTok = advance();
                List<Param> params = parameters();
                Block body = null;
                Expr bodyExpr = null;
                if (atOp("{")) {
                    body = block();
                } else {
                    expectOp("=>");
                    bodyExpr = expression();
                    expectOp(";");
                }
                Set<String> mods = isStatic ? Set.of("static") : Set.of();
                return new LocalFunc(new MethodDecl(mods, type, nameTok.text(), params, body, bodyExpr,
                        nameTok.line(), nameTok.col()), line);
            }
            if (next.isOp("=") || next.isOp(";") || next.isOp(",")) {
                return localDeclaration(type, false, line);
            }
        }
        if (type != null && type.isVar() && atOp("(")) {
            throw unsupported(peek(), "deconstruction (var (a, b) = ...)");
        }
        pos = save;
        Expr e = expression();
        if (atIdent("switch")) {
            throw unsupported(peek(), "switch expressions");
        }
        expectOp(";");
        return new ExprStmt(e, line);
    }

    private LocalDecl localDeclaration(TypeRef type, boolean isConst, int line) {
        List<Declarator> vars = new ArrayList<>();
        do {
            Token n = peek();
            String name = identifier("a variable name");
            Expr init = acceptOp("=") ? initializerExpression() : null;
            vars.add(new Declarator(name, init, n.line(), n.col()));
        } while (acceptOp(","));
        expectOp(";");
        return new LocalDecl(type, vars, isConst, line);
    }

    /** The right side of {@code =} in a declaration: an expression or an array initializer {@code { 1, 2 }}. */
    private Expr initializerExpression() {
        if (atOp("{")) {
            Token t = peek();
            return new NewArray(null, null, braceList(), t.line(), t.col());
        }
        if (atOp("[")) {
            Token t = advance();
            List<Expr> items = new ArrayList<>();
            if (!atOp("]")) {
                do {
                    items.add(expression());
                } while (acceptOp(","));
            }
            expectOp("]");
            return new NewArray(null, null, items, t.line(), t.col());
        }
        return expression();
    }

    private List<Expr> braceList() {
        expectOp("{");
        List<Expr> items = new ArrayList<>();
        while (!atOp("}")) {
            items.add(initializerExpression());
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp("}");
        return items;
    }

    private Stmt forStatement() {
        Token t = advance();
        expectOp("(");
        List<Stmt> init = new ArrayList<>();
        if (!atOp(";")) {
            int save = pos;
            TypeRef type = tryType();
            if (type != null && peek().kind() == Kind.IDENT && (peek(1).isOp("=") || peek(1).isOp(",")
                    || peek(1).isOp(";"))) {
                init.add(localDeclaration(type, false, t.line()));
            } else {
                pos = save;
                do {
                    Expr e = expression();
                    init.add(new ExprStmt(e, e.line()));
                } while (acceptOp(","));
                expectOp(";");
            }
        } else {
            expectOp(";");
        }
        Expr cond = atOp(";") ? null : expression();
        expectOp(";");
        List<Expr> update = new ArrayList<>();
        if (!atOp(")")) {
            do {
                update.add(expression());
            } while (acceptOp(","));
        }
        expectOp(")");
        return new For(init, cond, update, embedded(), t.line());
    }

    private Stmt foreachStatement() {
        Token t = advance();
        expectOp("(");
        TypeRef type = type();
        if (atOp("(")) {
            throw unsupported(peek(), "deconstruction in foreach");
        }
        String name = identifier("a loop variable name");
        expectIdent("in");
        Expr coll = expression();
        expectOp(")");
        return new Foreach(type, name, coll, embedded(), t.line());
    }

    private Stmt switchStatement() {
        Token t = advance();
        expectOp("(");
        Expr subject = expression();
        expectOp(")");
        expectOp("{");
        List<SwitchSection> sections = new ArrayList<>();
        while (!atOp("}")) {
            Token st = peek();
            List<Expr> labels = new ArrayList<>();
            boolean hasDefault = false;
            while (atIdent("case") || atIdent("default")) {
                if (acceptIdent("default")) {
                    hasDefault = true;
                } else {
                    advance();
                    int save = pos;
                    TypeRef pattern = tryType();
                    if (pattern != null && peek().kind() == Kind.IDENT && !atIdent("when")) {
                        throw unsupported(peek(), "type patterns in case labels");
                    }
                    pos = save;
                    labels.add(expression());
                    if (atIdent("when")) {
                        throw unsupported(peek(), "'when' in case labels");
                    }
                }
                expectOp(":");
            }
            if (labels.isEmpty() && !hasDefault) {
                throw error(peek(), "expected 'case' or 'default' in switch");
            }
            List<Stmt> body = new ArrayList<>();
            while (!atIdent("case") && !atIdent("default") && !atOp("}")) {
                body.add(statement());
            }
            sections.add(new SwitchSection(labels, hasDefault, body, st.line()));
        }
        expectOp("}");
        return new Switch(subject, sections, t.line());
    }

    private Stmt tryStatement() {
        Token t = advance();
        Block body = block();
        List<Catch> catches = new ArrayList<>();
        while (atIdent("catch")) {
            Token c = advance();
            TypeRef type = null;
            String name = null;
            if (acceptOp("(")) {
                type = type();
                if (peek().kind() == Kind.IDENT) {
                    name = advance().text();
                }
                expectOp(")");
            }
            if (atIdent("when")) {
                throw unsupported(peek(), "exception filters (when)");
            }
            catches.add(new Catch(type, name, block(), c.line()));
        }
        Block fin = acceptIdent("finally") ? block() : null;
        if (catches.isEmpty() && fin == null) {
            throw error(t, "try needs a catch or a finally");
        }
        return new Try(body, catches, fin, t.line());
    }

    // --- expressions -----------------------------------------------------------------------

    Expr expression() {
        return assignment();
    }

    private static final Set<String> ASSIGN_OPS = Set.of(
            "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", "??=");

    private Expr assignment() {
        Lambda lambda = tryLambda();
        if (lambda != null) {
            return lambda;
        }
        Expr left = conditional();
        Token t = peek();
        if (t.kind() == Kind.OP && ASSIGN_OPS.contains(t.text())) {
            advance();
            Expr right = t.isOp("=") ? assignmentValue() : assignment();
            return new Assign(t.text(), left, right, t.line(), t.col());
        }
        if (t.isOp(">") && peek(1).isOp(">=") ) {
            throw unsupported(t, "'>>='");
        }
        return left;
    }

    /** The value of a plain assignment, which may also be a bare collection initializer {@code [1, 2]}. */
    private Expr assignmentValue() {
        if (atOp("[")) {
            return initializerExpression();
        }
        return assignment();
    }

    private Lambda tryLambda() {
        Token t = peek();
        if (t.kind() == Kind.IDENT && !STATEMENT_KEYWORDS.contains(t.text()) && peek(1).isOp("=>")) {
            advance();
            advance();
            return lambdaBody(List.of(new Param(null, t.text(), null, false, t.line(), t.col())), t);
        }
        if (t.isIdent("async")) {
            throw unsupported(t, "async lambdas");
        }
        if (!t.isOp("(")) {
            return null;
        }
        // find the matching ')' and check for '=>'
        int depth = 0;
        int i = pos;
        while (true) {
            Token k = tokens.get(i);
            if (k.kind() == Kind.EOF) {
                return null;
            }
            if (k.isOp("(")) {
                depth++;
            } else if (k.isOp(")")) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            i++;
        }
        if (!tokens.get(i + 1).isOp("=>")) {
            return null;
        }
        advance(); // (
        List<Param> params = new ArrayList<>();
        if (!atOp(")")) {
            do {
                Token p = peek();
                TypeRef type = null;
                if (peek(1).kind() == Kind.IDENT || peek(1).isOp("<") || peek(1).isOp("[")) {
                    type = type();
                }
                String name = identifier("a lambda parameter");
                params.add(new Param(type, name, null, false, p.line(), p.col()));
            } while (acceptOp(","));
        }
        expectOp(")");
        expectOp("=>");
        return lambdaBody(params, t);
    }

    private Lambda lambdaBody(List<Param> params, Token start) {
        if (atOp("{")) {
            return new Lambda(params, null, block(), start.line(), start.col());
        }
        return new Lambda(params, expression(), null, start.line(), start.col());
    }

    private Expr conditional() {
        Expr cond = coalesce();
        Token t = peek();
        if (t.isOp("?")) {
            advance();
            Expr a = expression();
            expectOp(":");
            Expr b = expression();
            return new Conditional(cond, a, b, t.line(), t.col());
        }
        return cond;
    }

    private Expr coalesce() {
        Expr left = logicalOr();
        Token t = peek();
        if (t.isOp("??")) {
            advance();
            Expr right = coalesce();
            return new Binary("??", left, right, t.line(), t.col());
        }
        return left;
    }

    private Expr logicalOr() {
        Expr left = logicalAnd();
        while (atOp("||")) {
            Token t = advance();
            left = new Binary("||", left, logicalAnd(), t.line(), t.col());
        }
        return left;
    }

    private Expr logicalAnd() {
        Expr left = bitOr();
        while (atOp("&&")) {
            Token t = advance();
            left = new Binary("&&", left, bitOr(), t.line(), t.col());
        }
        return left;
    }

    private Expr bitOr() {
        Expr left = bitXor();
        while (atOp("|")) {
            Token t = advance();
            left = new Binary("|", left, bitXor(), t.line(), t.col());
        }
        return left;
    }

    private Expr bitXor() {
        Expr left = bitAnd();
        while (atOp("^")) {
            Token t = advance();
            left = new Binary("^", left, bitAnd(), t.line(), t.col());
        }
        return left;
    }

    private Expr bitAnd() {
        Expr left = equality();
        while (atOp("&")) {
            Token t = advance();
            left = new Binary("&", left, equality(), t.line(), t.col());
        }
        return left;
    }

    private Expr equality() {
        Expr left = relational();
        while (atOp("==") || atOp("!=")) {
            Token t = advance();
            left = new Binary(t.text(), left, relational(), t.line(), t.col());
        }
        return left;
    }

    private Expr relational() {
        Expr left = shift();
        while (true) {
            Token t = peek();
            if (t.isOp("<") || t.isOp(">") || t.isOp("<=") || isGreaterEqual()) {
                if (isGreaterEqual()) {
                    Token g = advance();
                    advance(); // '>' '=' lexed apart because '>=' could close generics
                    left = new Binary(">=", left, shift(), g.line(), g.col());
                    continue;
                }
                if (t.isOp(">") && peek(1).isOp(">") && peek(1).start() == t.end()) {
                    break; // a shift, handled below
                }
                advance();
                left = new Binary(t.text(), left, shift(), t.line(), t.col());
            } else if (t.isIdent("is")) {
                advance();
                left = isExpression(left, t);
            } else if (t.isIdent("as")) {
                throw unsupported(t, "'as'");
            } else {
                return left;
            }
        }
        return left;
    }

    private boolean isGreaterEqual() {
        return peek().isOp(">") && peek(1).isOp("=") && peek(1).start() == peek().end();
    }

    private Expr isExpression(Expr operand, Token t) {
        boolean negated = acceptIdent("not");
        if (acceptIdent("null")) {
            return new Is(operand, null, null, true, negated, t.line(), t.col());
        }
        TypeRef type = type();
        String bind = null;
        if (peek().kind() == Kind.IDENT && !STATEMENT_KEYWORDS.contains(peek().text())
                && !atIdent("and") && !atIdent("or")) {
            bind = advance().text();
        }
        return new Is(operand, type, bind, false, negated, t.line(), t.col());
    }

    private Expr shift() {
        Expr left = additive();
        while (true) {
            Token t = peek();
            if (t.isOp("<<")) {
                advance();
                left = new Binary("<<", left, additive(), t.line(), t.col());
            } else if (t.isOp(">") && peek(1).isOp(">") && peek(1).start() == t.end()) {
                advance();
                advance();
                left = new Binary(">>", left, additive(), t.line(), t.col());
            } else {
                return left;
            }
        }
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (atOp("+") || atOp("-")) {
            Token t = advance();
            left = new Binary(t.text(), left, multiplicative(), t.line(), t.col());
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (atOp("*") || atOp("/") || atOp("%")) {
            Token t = advance();
            left = new Binary(t.text(), left, unary(), t.line(), t.col());
        }
        return left;
    }

    private static final Set<String> CAST_TYPES = Set.of(
            "int", "long", "short", "byte", "sbyte", "uint", "ulong", "ushort", "double", "float", "decimal",
            "bool", "string", "char", "object", "dynamic");

    private Expr unary() {
        Token t = peek();
        if (t.isOp("!") || t.isOp("-") || t.isOp("+") || t.isOp("~")) {
            advance();
            return new Unary(t.text(), unary(), t.line(), t.col());
        }
        if (t.isOp("++") || t.isOp("--")) {
            advance();
            return new IncDec(t.text(), true, unary(), t.line(), t.col());
        }
        if (t.isOp("(")) {
            Cast cast = tryCast();
            if (cast != null) {
                return cast;
            }
        }
        if (t.isIdent("await")) {
            throw unsupported(t, "'await'");
        }
        return postfix(primary());
    }

    private Cast tryCast() {
        int save = pos;
        Token open = advance();
        TypeRef type = tryType();
        if (type != null && acceptOp(")")) {
            Token next = peek();
            boolean primitive = type.args().isEmpty() && type.arrayRank() == 0 && CAST_TYPES.contains(type.name());
            boolean startsOperand = next.kind() == Kind.IDENT && !next.isIdent("is") && !next.isIdent("as")
                    || next.kind() == Kind.INT || next.kind() == Kind.FLOAT || next.kind() == Kind.STRING
                    || next.kind() == Kind.CHAR || next.kind() == Kind.INTERP || next.isOp("(")
                    || next.isOp("!") || next.isOp("~");
            if (primitive && (startsOperand || next.isOp("-") || next.isOp("+"))
                    || !primitive && startsOperand && Character.isUpperCase(type.name().charAt(0))) {
                return new Cast(type, unary(), open.line(), open.col());
            }
        }
        pos = save;
        return null;
    }

    private Expr postfix(Expr e) {
        while (true) {
            Token t = peek();
            if (t.isOp(".")) {
                advance();
                Token n = peek();
                String name = identifier("a member name");
                if (atOp("<") && genericCallAhead()) {
                    throw unsupported(peek(), "calling generic methods with <...>");
                }
                e = new MemberAccess(e, name, false, n.line(), n.col());
            } else if (t.isOp("?.")) {
                advance();
                Token n = peek();
                String name = identifier("a member name");
                e = new MemberAccess(e, name, true, n.line(), n.col());
            } else if (t.isOp("?") && peek(1).isOp("[")) {
                advance();
                advance();
                Expr index = expression();
                expectOp("]");
                e = new Index(e, index, true, t.line(), t.col());
            } else if (t.isOp("(")) {
                e = new Call(e, arguments(), t.line(), t.col());
            } else if (t.isOp("[")) {
                advance();
                Expr index = expression();
                if (atOp(",")) {
                    throw unsupported(peek(), "multi-dimensional indexing");
                }
                expectOp("]");
                e = new Index(e, index, false, t.line(), t.col());
            } else if (t.isOp("++") || t.isOp("--")) {
                advance();
                e = new IncDec(t.text(), false, e, t.line(), t.col());
            } else if (t.isOp("!") && !peek(1).isOp("=")
                    && (peek(1).isOp(".") || peek(1).isOp(";") || peek(1).isOp(")") || peek(1).isOp(","))) {
                advance(); // null-forgiving x!
            } else {
                return e;
            }
        }
    }

    private boolean genericCallAhead() {
        int i = pos + 1;
        int depth = 1;
        while (i < tokens.size()) {
            Token k = tokens.get(i);
            if (k.isOp("<")) {
                depth++;
            } else if (k.isOp(">")) {
                depth--;
                if (depth == 0) {
                    return tokens.get(i + 1).isOp("(");
                }
            } else if (!(k.kind() == Kind.IDENT || k.isOp(",") || k.isOp(".") || k.isOp("[") || k.isOp("]"))) {
                return false;
            }
            i++;
        }
        return false;
    }

    private List<Arg> arguments() {
        expectOp("(");
        List<Arg> args = new ArrayList<>();
        if (!atOp(")")) {
            do {
                if (peek().kind() == Kind.IDENT && peek(1).isOp(":") && !peek(2).isOp(":")) {
                    throw unsupported(peek(), "named arguments (name: value)");
                }
                if (acceptIdent("out")) {
                    Token o = peek();
                    if (acceptIdent("var")) {
                        args.add(new Arg(null, identifier("a variable name"), null));
                    } else if (peek().kind() == Kind.IDENT && peek(1).kind() == Kind.IDENT) {
                        TypeRef type = type();
                        args.add(new Arg(null, identifier("a variable name"), type));
                    } else if (peek().kind() == Kind.IDENT) {
                        args.add(new Arg(new Name(advance().text(), o.line(), o.col()), null, null));
                    } else {
                        throw error(o, "expected a variable after 'out'");
                    }
                    continue;
                }
                if (atIdent("ref") || atIdent("in")) {
                    advance(); // passed by value in Lua; good enough for objects and lists
                }
                args.add(new Arg(expression(), null, null));
            } while (acceptOp(","));
        }
        expectOp(")");
        return args;
    }

    private Expr primary() {
        Token t = advance();
        switch (t.kind()) {
            case INT -> {
                return new Literal(LitKind.INT, t.text(), t.line(), t.col());
            }
            case FLOAT -> {
                return new Literal(LitKind.FLOAT, t.text(), t.line(), t.col());
            }
            case STRING -> {
                return new Literal(LitKind.STRING, t.text(), t.line(), t.col());
            }
            case CHAR -> {
                return new Literal(LitKind.CHAR, t.text(), t.line(), t.col());
            }
            case INTERP -> {
                return interpolation(t);
            }
            case EOF -> throw error(t, "unexpected end of file");
            case OP -> {
                if (t.isOp("(")) {
                    Expr inner = expression();
                    if (atOp(",")) {
                        throw unsupported(peek(), "tuples");
                    }
                    expectOp(")");
                    return inner;
                }
                if (t.isOp("[")) {
                    pos--;
                    return initializerExpression();
                }
                throw error(t, "unexpected " + t);
            }
            default -> {
                // identifiers and keywords below
            }
        }
        switch (t.text()) {
            case "true", "false" -> {
                return new Literal(LitKind.BOOL, t.text(), t.line(), t.col());
            }
            case "null" -> {
                return new Literal(LitKind.NULL, "null", t.line(), t.col());
            }
            case "this" -> {
                return new This(t.line(), t.col());
            }
            case "base" -> {
                return new Base(t.line(), t.col());
            }
            case "new" -> {
                return newExpression(t);
            }
            case "typeof" -> {
                expectOp("(");
                TypeRef type = type();
                expectOp(")");
                return new Typeof(type, t.line(), t.col());
            }
            case "default" -> {
                if (acceptOp("(")) {
                    TypeRef type = type();
                    expectOp(")");
                    return new Default(type, t.line(), t.col());
                }
                return new Default(null, t.line(), t.col());
            }
            case "nameof" -> {
                expectOp("(");
                String last = identifier("a name");
                while (acceptOp(".")) {
                    last = identifier("a name");
                }
                expectOp(")");
                return new Literal(LitKind.STRING, last, t.line(), t.col());
            }
            case "stackalloc", "checked", "unchecked", "sizeof", "delegate" ->
                throw unsupported(t, "'" + t.text() + "'");
            default -> {
                if (STATEMENT_KEYWORDS.contains(t.text())) {
                    throw error(t, "unexpected keyword '" + t.text() + "'");
                }
                return new Name(t.text(), t.line(), t.col());
            }
        }
    }

    private Expr newExpression(Token t) {
        if (atOp("[")) {
            advance();
            expectOp("]");
            return new NewArray(null, null, braceList(), t.line(), t.col());
        }
        if (atOp("(")) {
            throw unsupported(peek(), "target-typed new()");
        }
        if (atOp("{")) {
            throw unsupported(peek(), "anonymous types (new { ... })");
        }
        // element type, then [size] or [] { ... }
        TypeRef type = tryTypeNoArray();
        if (type == null) {
            throw error(peek(), "expected a type after 'new'");
        }
        if (atOp("[")) {
            advance();
            Expr size = null;
            if (!atOp("]")) {
                size = expression();
            }
            expectOp("]");
            while (atOp("[") && peek(1).isOp("]")) {
                advance();
                advance();
                type = new TypeRef(type.name(), type.args(), type.arrayRank() + 1, false, type.line(), type.col());
            }
            List<Expr> items = atOp("{") ? braceList() : null;
            if (size == null && items == null) {
                throw error(peek(), "array creation needs a size or an initializer");
            }
            return new NewArray(type, size, items, t.line(), t.col());
        }
        List<Expr> args = atOp("(") ? arguments().stream().map(Arg::expr).toList() : List.of();
        List<Expr> items = null;
        List<Expr[]> pairs = null;
        List<FieldInit> fields = null;
        if (atOp("{")) {
            advance();
            if (atOp("}")) {
                advance();
                items = List.of();
            } else if (atOp("{")) {
                pairs = new ArrayList<>();
                while (atOp("{")) {
                    advance();
                    Expr k = expression();
                    expectOp(",");
                    Expr v = expression();
                    expectOp("}");
                    pairs.add(new Expr[] {k, v});
                    if (!acceptOp(",")) {
                        break;
                    }
                }
                expectOp("}");
            } else if (atOp("[")) {
                pairs = new ArrayList<>();
                while (atOp("[")) {
                    advance();
                    Expr k = expression();
                    expectOp("]");
                    expectOp("=");
                    Expr v = expression();
                    pairs.add(new Expr[] {k, v});
                    if (!acceptOp(",")) {
                        break;
                    }
                }
                expectOp("}");
            } else if (peek().kind() == Kind.IDENT && peek(1).isOp("=")) {
                fields = new ArrayList<>();
                while (peek().kind() == Kind.IDENT) {
                    Token n = advance();
                    expectOp("=");
                    fields.add(new FieldInit(n.text(), initializerExpression(), n.line(), n.col()));
                    if (!acceptOp(",")) {
                        break;
                    }
                }
                expectOp("}");
            } else {
                items = new ArrayList<>();
                while (!atOp("}")) {
                    items.add(initializerExpression());
                    if (!acceptOp(",")) {
                        break;
                    }
                }
                expectOp("}");
            }
        }
        return new New(type, args, items, pairs, fields, t.line(), t.col());
    }

    private TypeRef tryTypeNoArray() {
        Token start = peek();
        if (start.kind() != Kind.IDENT) {
            return null;
        }
        String name = advance().text();
        while (atOp(".") && peek(1).kind() == Kind.IDENT) {
            advance();
            name = advance().text();
        }
        List<TypeRef> args = new ArrayList<>();
        if (acceptOp("<")) {
            do {
                args.add(type());
            } while (acceptOp(","));
            expectOp(">");
        }
        acceptOp("?");
        return new TypeRef(name, args, 0, false, start.line(), start.col());
    }

    private Expr interpolation(Token t) {
        List<Object> parts = new ArrayList<>();
        for (InterpPart p : t.parts()) {
            if (!p.isHole()) {
                parts.add(p.literal());
                continue;
            }
            List<Token> sub = new Lexer(p.exprSource(), p.line() - 1, p.col() - 1).tokenize();
            Parser parser = new Parser(sub);
            Expr e = parser.expression();
            if (parser.peek().kind() != Kind.EOF) {
                throw error(parser.peek(), "unexpected " + parser.peek() + " in interpolated string");
            }
            parts.add(new Hole(e, p.format()));
        }
        return new Interp(parts, t.line(), t.col());
    }
}
