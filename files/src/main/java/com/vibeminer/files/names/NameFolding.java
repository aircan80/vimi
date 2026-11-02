package com.vibeminer.files.names;

/** Culture-independent ASCII case and slash folding. */
public final class NameFolding {
    private NameFolding() {}

    public static char fold(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - 32) : c == '/' ? '\\' : c;
    }

    public static int fold(int c) {
        return c >= 'a' && c <= 'z' ? c - 32 : c == '/' ? '\\' : c;
    }

    public static String fold(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            b.append(fold(text.charAt(i)));
        }
        return b.toString();
    }

    public static boolean hasPrefix(String name, String prefix) {
        if (name.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (fold(name.charAt(i)) != fold(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean same(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (fold(a[i] & 255) != fold(b[i] & 255)) {
                return false;
            }
        }
        return true;
    }
}
