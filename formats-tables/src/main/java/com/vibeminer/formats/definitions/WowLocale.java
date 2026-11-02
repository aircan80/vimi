package com.vibeminer.formats.definitions;

import java.util.List;

/** Exact ROOT locales, in flag-bit order. */
public enum WowLocale {
    EN_US("enUS", 2),
    KO_KR("koKR", 4),
    FR_FR("frFR", 16),
    DE_DE("deDE", 32),
    ZH_CN("zhCN", 64),
    ES_ES("esES", 128),
    ZH_TW("zhTW", 256),
    EN_GB("enGB", 512),
    EN_CN("enCN", 1024),
    EN_TW("enTW", 2048),
    ES_MX("esMX", 4096),
    RU_RU("ruRU", 8192),
    PT_BR("ptBR", 16384),
    IT_IT("itIT", 32768),
    PT_PT("ptPT", 65536);
    public static final List<WowLocale> ALL = List.of(values());
    private final String code;
    private final int flag;

    WowLocale(String code, int flag) {
        this.code = code;
        this.flag = flag;
    }

    public String code() {
        return code;
    }

    public int flag() {
        return flag;
    }

    public static WowLocale tryParse(String code) {
        for (var locale : values()) {
            if (locale.code.equals(code)) {
                return locale;
            }
        }
        return null;
    }

    public static WowLocale parse(String code) {
        var result = tryParse(code);
        if (result == null) {
            throw new DefinitionException(
                    "DEFINITION_LOCALE_UNKNOWN", "An exact ROOT locale code is required");
        }
        return result;
    }

    @Override
    public String toString() {
        return code;
    }
}
