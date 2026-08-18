package me.kzheart.klib.script.kether.core;

/** 表示 Kether 源码在词元化阶段格式错误。 */
public final class KetherLexException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;
    private final int position;

    private KetherLexException(String message, int position) {
        super(message + " at character " + position);
        this.position = position;
    }

    public static KetherLexException endOfInput(int position) {
        return new KetherLexException("Unexpected end of Kether input", position);
    }

    public static KetherLexException stringNotClosed(int quotes, int position) {
        return new KetherLexException("Kether string opened with " + quotes + " quote(s) is not closed", position);
    }

    public static KetherLexException notMatching(String expected, String actual, int position) {
        return new KetherLexException("Expected '" + expected + "' but got '" + actual + "'", position);
    }

    public int getPosition() {
        return position;
    }
}
