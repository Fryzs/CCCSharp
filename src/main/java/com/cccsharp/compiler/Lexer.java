package com.cccsharp.compiler;

import com.cccsharp.compiler.Token.InterpPart;
import com.cccsharp.compiler.Token.Kind;
import java.util.ArrayList;
import java.util.List;

/** Splits C# source into tokens. Comments and preprocessor lines are skipped. */
final class Lexer {
    private static final String[] OPERATORS = {
        "<<=", "??=", "...",
        "=>", "==", "!=", "<=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        "<<", "??", "?.", "::",
        "{", "}", "(", ")", "[", "]", ";", ",", ".", ":", "+", "-", "*", "/", "%", "<", ">", "=", "!",
        "?", "&", "|", "^", "~", "@", "$",
    };

    private final String src;
    private final int lineOffset;
    private final int colOffset;
    private int pos;
    private int line = 1;
    private int col = 1;

    Lexer(String src) {
        this(src, 0, 0);
    }

    /** For the expression inside an interpolation hole: positions are shifted to where it sits. */
    Lexer(String src, int lineOffset, int colOffset) {
        this.src = src;
        this.lineOffset = lineOffset;
        this.colOffset = colOffset;
    }

    List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpaceAndComments();
            if (pos >= src.length()) {
                out.add(new Token(Kind.EOF, "", null, ln(), cl(), pos, pos));
                return out;
            }
            out.add(next());
        }
    }

    private int ln() {
        return line + lineOffset;
    }

    private int cl() {
        return line == 1 ? col + colOffset : col;
    }

    private CompileException error(String message) {
        return new CompileException(ln(), cl(), message);
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private char advance() {
        char c = src.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private void skipSpaceAndComments() {
        boolean lineStart = pos == 0 || src.charAt(pos - 1) == '\n';
        while (pos < src.length()) {
            char c = peek(0);
            if (c == '\n') {
                advance();
                lineStart = true;
            } else if (Character.isWhitespace(c) || c == '﻿') {
                advance();
            } else if (c == '/' && peek(1) == '/') {
                while (pos < src.length() && peek(0) != '\n') {
                    advance();
                }
            } else if (c == '/' && peek(1) == '*') {
                int l = ln();
                int k = cl();
                advance();
                advance();
                while (!(peek(0) == '*' && peek(1) == '/')) {
                    if (pos >= src.length()) {
                        throw new CompileException(l, k, "comment is never closed with */");
                    }
                    advance();
                }
                advance();
                advance();
            } else if (c == '#' && lineStart) {
                // #region, #nullable and friends: ignored
                while (pos < src.length() && peek(0) != '\n') {
                    advance();
                }
            } else {
                return;
            }
        }
    }

    private Token next() {
        int startLine = ln();
        int startCol = cl();
        int start = pos;
        char c = peek(0);

        if (c == '$' || (c == '@' && peek(1) == '$')) {
            boolean verbatim = c == '@' || peek(1) == '@';
            int prefix = (c == '@' || peek(1) == '@') ? 2 : 1;
            if (peek(prefix) == '"') {
                for (int i = 0; i < prefix; i++) {
                    advance();
                }
                List<InterpPart> parts = interpolated(verbatim, startLine, startCol);
                return new Token(Kind.INTERP, src.substring(start, pos), parts, startLine, startCol, start, pos);
            }
        }
        if (c == '@' && peek(1) == '"') {
            advance();
            String value = verbatimString();
            return new Token(Kind.STRING, value, null, startLine, startCol, start, pos);
        }
        if (c == '@' && isIdentStart(peek(1))) {
            advance(); // @class: identifier that is also a keyword
            return identifier(startLine, startCol, start);
        }
        if (c == '"') {
            String value = regularString();
            return new Token(Kind.STRING, value, null, startLine, startCol, start, pos);
        }
        if (c == '\'') {
            advance();
            String value = c == '\'' && peek(0) == '\\' ? escape() : String.valueOf(advance());
            if (peek(0) != '\'') {
                throw error("character literal must hold exactly one character");
            }
            advance();
            return new Token(Kind.CHAR, value, null, startLine, startCol, start, pos);
        }
        if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
            return number(startLine, startCol, start);
        }
        if (isIdentStart(c)) {
            return identifier(startLine, startCol, start);
        }
        for (String op : OPERATORS) {
            if (src.startsWith(op, pos)) {
                for (int i = 0; i < op.length(); i++) {
                    advance();
                }
                return new Token(Kind.OP, op, null, startLine, startCol, start, pos);
            }
        }
        throw error("unexpected character '" + c + "'");
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private Token identifier(int startLine, int startCol, int start) {
        int s = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(peek(0)) || peek(0) == '_')) {
            advance();
        }
        return new Token(Kind.IDENT, src.substring(s, pos), null, startLine, startCol, start, pos);
    }

    private Token number(int startLine, int startCol, int start) {
        StringBuilder digits = new StringBuilder();
        boolean isFloat = false;
        if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance();
            advance();
            while (Character.digit(peek(0), 16) >= 0 || peek(0) == '_') {
                char d = advance();
                if (d != '_') {
                    digits.append(d);
                }
            }
            skipIntSuffix();
            return new Token(Kind.INT, Long.toString(Long.parseUnsignedLong(digits.toString(), 16)), null,
                    startLine, startCol, start, pos);
        }
        if (peek(0) == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            advance();
            advance();
            while (peek(0) == '0' || peek(0) == '1' || peek(0) == '_') {
                char d = advance();
                if (d != '_') {
                    digits.append(d);
                }
            }
            skipIntSuffix();
            return new Token(Kind.INT, Long.toString(Long.parseUnsignedLong(digits.toString(), 2)), null,
                    startLine, startCol, start, pos);
        }
        while (Character.isDigit(peek(0)) || peek(0) == '_') {
            char d = advance();
            if (d != '_') {
                digits.append(d);
            }
        }
        if (peek(0) == '.' && Character.isDigit(peek(1))) {
            isFloat = true;
            digits.append(advance());
            while (Character.isDigit(peek(0)) || peek(0) == '_') {
                char d = advance();
                if (d != '_') {
                    digits.append(d);
                }
            }
        }
        if (peek(0) == 'e' || peek(0) == 'E') {
            isFloat = true;
            digits.append(advance());
            if (peek(0) == '+' || peek(0) == '-') {
                digits.append(advance());
            }
            while (Character.isDigit(peek(0))) {
                digits.append(advance());
            }
        }
        char s = Character.toLowerCase(peek(0));
        if (s == 'f' || s == 'd' || s == 'm') {
            advance();
            isFloat = true;
        } else {
            skipIntSuffix();
        }
        String text = digits.toString();
        if (text.startsWith(".")) {
            text = "0" + text;
        }
        return new Token(isFloat ? Kind.FLOAT : Kind.INT, text, null, startLine, startCol, start, pos);
    }

    private void skipIntSuffix() {
        while (peek(0) == 'u' || peek(0) == 'U' || peek(0) == 'l' || peek(0) == 'L') {
            advance();
        }
    }

    private String regularString() {
        int l = ln();
        int k = cl();
        advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || peek(0) == '\n') {
                throw new CompileException(l, k, "string is never closed with \"");
            }
            char c = peek(0);
            if (c == '"') {
                advance();
                return sb.toString();
            }
            if (c == '\\') {
                sb.append(escape());
            } else {
                sb.append(advance());
            }
        }
    }

    private String verbatimString() {
        int l = ln();
        int k = cl();
        advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new CompileException(l, k, "string is never closed with \"");
            }
            char c = advance();
            if (c == '"') {
                if (peek(0) == '"') {
                    advance();
                    sb.append('"');
                } else {
                    return sb.toString();
                }
            } else {
                sb.append(c);
            }
        }
    }

    /** Reads one escape sequence starting at the backslash. */
    private String escape() {
        advance(); // backslash
        char e = advance();
        return switch (e) {
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            case '0' -> "\0";
            case 'a' -> "\u0007";
            case 'b' -> "\b";
            case 'f' -> "\f";
            case 'v' -> "\u000B";
            case '\\' -> "\\";
            case '"' -> "\"";
            case '\'' -> "'";
            case 'u' -> String.valueOf((char) hex(4));
            case 'x' -> String.valueOf((char) hexVariable());
            default -> throw error("unknown escape sequence \\" + e);
        };
    }

    private int hex(int count) {
        int v = 0;
        for (int i = 0; i < count; i++) {
            int d = Character.digit(peek(0), 16);
            if (d < 0) {
                throw error("bad \\u escape: expected " + count + " hex digits");
            }
            advance();
            v = v * 16 + d;
        }
        return v;
    }

    private int hexVariable() {
        int v = 0;
        int n = 0;
        while (n < 4 && Character.digit(peek(0), 16) >= 0) {
            v = v * 16 + Character.digit(advance(), 16);
            n++;
        }
        if (n == 0) {
            throw error("bad \\x escape");
        }
        return v;
    }

    /** Reads an interpolated string body (after {@code $"}) into literal pieces and holes. */
    private List<InterpPart> interpolated(boolean verbatim, int startLine, int startCol) {
        advance(); // opening quote
        List<InterpPart> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        while (true) {
            if (pos >= src.length() || (!verbatim && peek(0) == '\n')) {
                throw new CompileException(startLine, startCol, "interpolated string is never closed with \"");
            }
            char c = peek(0);
            if (c == '"') {
                if (verbatim && peek(1) == '"') {
                    advance();
                    advance();
                    lit.append('"');
                    continue;
                }
                advance();
                if (!lit.isEmpty()) {
                    parts.add(new InterpPart(lit.toString(), null, null, 0, 0));
                }
                return parts;
            }
            if (c == '{' && peek(1) == '{') {
                advance();
                advance();
                lit.append('{');
            } else if (c == '}' && peek(1) == '}') {
                advance();
                advance();
                lit.append('}');
            } else if (c == '{') {
                if (!lit.isEmpty()) {
                    parts.add(new InterpPart(lit.toString(), null, null, 0, 0));
                    lit.setLength(0);
                }
                advance();
                parts.add(hole());
            } else if (c == '\\' && !verbatim) {
                lit.append(escape());
            } else {
                lit.append(advance());
            }
        }
    }

    /** Reads {@code expr[,align][:format]}} after the opening brace. */
    private InterpPart hole() {
        int hl = ln();
        int hc = cl();
        int depth = 0;
        StringBuilder expr = new StringBuilder();
        String format = null;
        boolean alignment = false;
        while (true) {
            if (pos >= src.length()) {
                throw new CompileException(hl, hc, "'{' in interpolated string is never closed with '}'");
            }
            char c = peek(0);
            if (c == '"' || c == '\'') {
                // a string inside the hole: copy it whole
                int s = pos;
                if (c == '"') {
                    regularString();
                } else {
                    advance();
                    if (peek(0) == '\\') {
                        escape();
                    } else {
                        advance();
                    }
                    advance();
                }
                if (!alignment) {
                    expr.append(src, s, pos);
                }
                continue;
            }
            if (depth == 0 && c == '}') {
                advance();
                break;
            }
            if (depth == 0 && c == ':' ) {
                advance();
                StringBuilder f = new StringBuilder();
                while (pos < src.length() && peek(0) != '}') {
                    f.append(advance());
                }
                if (pos >= src.length()) {
                    throw new CompileException(hl, hc, "'{' in interpolated string is never closed with '}'");
                }
                advance();
                format = f.toString();
                break;
            }
            if (depth == 0 && c == ',') {
                alignment = true; // {x,10}: alignment is ignored
                advance();
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
            char a = advance();
            if (!alignment) {
                expr.append(a);
            }
        }
        if (expr.toString().isBlank()) {
            throw new CompileException(hl, hc, "empty {} in interpolated string");
        }
        return new InterpPart(null, expr.toString(), format, hl, hc);
    }
}
