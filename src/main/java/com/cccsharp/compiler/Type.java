package com.cccsharp.compiler;

import java.util.Objects;

/**
 * What the code generator knows about a value's type. Only as much as it needs to choose the right
 * Lua: integer division, string joining, 0-based collections, which methods exist. Anything that
 * comes from Lua (CC: Tweaked APIs, peripherals) is {@link Kind#DYNAMIC}.
 */
final class Type {
    enum Kind { INT, FLOAT, BOOL, STRING, VOID, NULL, LIST, DICT, PAIR, CLASS, ENUM, FUNC, RANDOM, BUILDER, DYNAMIC }

    static final Type INT = new Type(Kind.INT, null, null, null, null);
    static final Type FLOAT = new Type(Kind.FLOAT, null, null, null, null);
    static final Type BOOL = new Type(Kind.BOOL, null, null, null, null);
    static final Type STRING = new Type(Kind.STRING, null, null, null, null);
    static final Type VOID = new Type(Kind.VOID, null, null, null, null);
    static final Type NULL = new Type(Kind.NULL, null, null, null, null);
    static final Type DYNAMIC = new Type(Kind.DYNAMIC, null, null, null, null);
    static final Type RANDOM = new Type(Kind.RANDOM, null, null, null, null);
    static final Type BUILDER = new Type(Kind.BUILDER, null, null, null, null);

    final Kind kind;
    /** Element type of a list, value type of a dictionary or pair, return type of a function. */
    final Type element;
    /** Key type of a dictionary or pair. */
    final Type key;
    /** Class or enum name. */
    final String name;
    /** Whether a list is a fixed-size array (for messages only; both behave the same at runtime). */
    final Boolean array;

    private Type(Kind kind, Type element, Type key, String name, Boolean array) {
        this.kind = kind;
        this.element = element;
        this.key = key;
        this.name = name;
        this.array = array;
    }

    static Type list(Type element, boolean array) {
        return new Type(Kind.LIST, element, null, null, array);
    }

    static Type dict(Type key, Type value) {
        return new Type(Kind.DICT, value, key, null, null);
    }

    static Type pair(Type key, Type value) {
        return new Type(Kind.PAIR, value, key, null, null);
    }

    static Type cls(String name) {
        return new Type(Kind.CLASS, null, null, name, null);
    }

    static Type enumType(String name) {
        return new Type(Kind.ENUM, null, null, name, null);
    }

    static Type func(Type returns) {
        return new Type(Kind.FUNC, returns, null, null, null);
    }

    boolean is(Kind k) {
        return kind == k;
    }

    boolean isNumeric() {
        return kind == Kind.INT || kind == Kind.FLOAT || kind == Kind.ENUM;
    }

    boolean isIntegral() {
        return kind == Kind.INT || kind == Kind.ENUM;
    }

    boolean isDynamic() {
        return kind == Kind.DYNAMIC;
    }

    /** Element type of something that can be looped over with foreach. */
    Type elementType() {
        return switch (kind) {
            case LIST -> element == null ? DYNAMIC : element;
            case DICT -> pair(key, element);
            case STRING -> STRING;
            default -> DYNAMIC;
        };
    }

    /** The value C# gives a field or variable of this type before it is assigned. */
    String defaultLua() {
        return switch (kind) {
            case INT, FLOAT, ENUM -> "0";
            case BOOL -> "false";
            default -> "nil";
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Type t && t.kind == kind && Objects.equals(t.element, element)
                && Objects.equals(t.key, key) && Objects.equals(t.name, name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, element, key, name);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case LIST -> (array != null && array ? "" : "List<") + element + (array != null && array ? "[]" : ">");
            case DICT -> "Dictionary<" + key + ", " + element + ">";
            case PAIR -> "KeyValuePair<" + key + ", " + element + ">";
            case CLASS, ENUM -> name;
            case FUNC -> "delegate";
            default -> kind.name().toLowerCase();
        };
    }
}
