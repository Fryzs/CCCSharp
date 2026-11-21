package com.cccsharp.compiler;

/** A problem in the C# source, with the line and column it was found at (both from 1). */
public final class CompileException extends RuntimeException {
    private final int line;
    private final int column;

    public CompileException(int line, int column, String message) {
        super(message);
        this.line = line;
        this.column = column;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    /** {@code prog.cs:12:5: message}. */
    public String format(String fileName) {
        return fileName + ":" + line + ":" + column + ": " + getMessage();
    }
}
