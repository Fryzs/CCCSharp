package com.cccsharp.compiler;

import java.util.List;

/**
 * One token of C# source.
 *
 * @param kind  what sort of token
 * @param text  identifier name, operator text, or the decoded value of a string/char literal
 * @param parts for interpolated strings: literal text pieces and holes, in order
 * @param line  line it starts on, from 1
 * @param col   column it starts at, from 1
 * @param start offset of its first character
 * @param end   offset just past its last character
 */
record Token(Kind kind, String text, List<InterpPart> parts, int line, int col, int start, int end) {
    enum Kind { IDENT, INT, FLOAT, STRING, CHAR, INTERP, OP, EOF }

    /** A piece of an interpolated string: either literal text or a {@code {expr[,align][:format]}} hole. */
    record InterpPart(String literal, String exprSource, String format, int line, int col) {
        boolean isHole() {
            return exprSource != null;
        }
    }

    boolean is(Kind k, String t) {
        return kind == k && text.equals(t);
    }

    boolean isOp(String t) {
        return kind == Kind.OP && text.equals(t);
    }

    boolean isIdent(String t) {
        return kind == Kind.IDENT && text.equals(t);
    }

    @Override
    public String toString() {
        return kind == Kind.EOF ? "end of file" : "'" + text + "'";
    }
}
