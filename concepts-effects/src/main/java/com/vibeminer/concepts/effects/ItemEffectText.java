package com.vibeminer.concepts.effects;

/** Conservative text projection. Client spell expressions require a separate evaluated context. */
final class ItemEffectText {
    private ItemEffectText() {}

    static EffectsTypes.TooltipLine project(String trigger, long spell, String name, String text) {
        var state = EffectsTypes.TextState.READY;
        if (text == null || text.length() > 16384 || !ItemEffectProfiles.wellFormed(text)) {
            text = null;
            state = EffectsTypes.TextState.INVALID_TEXT;
        } else if (text.isBlank()) {
            state = EffectsTypes.TextState.EMPTY;
        } else if (text.contains("$") || text.contains("|")) {
            state = EffectsTypes.TextState.UNRESOLVED_TOKENS;
        }
        return new EffectsTypes.TooltipLine(trigger, spell, name, text, state);
    }
}
