package com.natesoftware.riftborder.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.junit.jupiter.api.Test;

// What a plugin gets from new BorderTheme() {} - the library's defaults.
class BorderThemeTest {

    @Test
    void crossingOutPlaysTheBassNoteAndSoDoesLingeringOutside() {
        BorderTheme defaults = new BorderTheme() {};
        assertEquals("minecraft:block.note_block.bass", defaults.enterSound());
        assertEquals("minecraft:block.note_block.bass", defaults.enterLongSound());
    }

    @Test
    void theLingeringSoundFollowsTheCrossingSoundUnlessSetItself() {
        BorderTheme crossingOnly = new BorderTheme() {
            @Override
            public String enterSound() {
                return "custom:crossing";
            }
        };
        assertEquals("custom:crossing", crossingOnly.enterLongSound());

        BorderTheme both = new BorderTheme() {
            @Override
            public String enterSound() {
                return "custom:crossing";
            }

            @Override
            public String enterLongSound() {
                return "custom:lingering";
            }
        };
        assertEquals("custom:lingering", both.enterLongSound());

        BorderTheme silent = new BorderTheme() {
            @Override
            public String enterSound() {
                return null;
            }
        };
        assertNull(silent.enterLongSound());
    }

    @Test
    void theDefaultTitleIsBorderWarningInRedSmallCaps() {
        assertEquals(BorderTheme.DEFAULT_WARNING_TITLE, new BorderTheme() {}.warningTitle());
        TextComponent title = (TextComponent) BorderTheme.DEFAULT_WARNING_TITLE;
        assertEquals("\u0299\u1d0f\u0280\u1d05\u1d07\u0280 \u1d21\u1d00\u0280\u0274\u026a\u0274\u0262", title.content());
        assertEquals(NamedTextColor.RED, title.color());
    }

    @Test
    void everyOtherValueDefaultsToNothingOrTheWallAqua() {
        BorderTheme defaults = new BorderTheme() {};
        assertNull(defaults.shrinkColor());
        assertEquals(BorderTheme.DEFAULT_WALL_COLOR, defaults.wallColor());
        assertEquals(Color.WHITE, defaults.indicatorColor());
    }
}
