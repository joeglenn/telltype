package net.jbglenn.telltype.core.model.settings

/**
 * How the idle bubble looks (board U9, decision D56): its colour, its transparency and the microphone glyph. Only the
 * Ready bubble uses them; Listening, Working, Done and Problem keep their state colours at full opacity, so the
 * states stay apart whatever the user picks. The defaults are the look before this setting existed.
 *
 * @property colour the Ready bubble's fill.
 * @property transparency how see-through the Ready bubble is, in percent, `0..`[MAX_TRANSPARENCY] (values outside
 *   are clamped where they are used).
 * @property icon the microphone glyph of the Ready bubble (and of the dictation start).
 */
public data class BubbleLook(
    public val colour: BubbleColour = BubbleColour.Preset(BubblePreset.DEFAULT),
    public val transparency: Int = DEFAULT_TRANSPARENCY,
    public val icon: MicIcon = MicIcon.STANDARD,
) {
    public companion object {
        /** The Ready bubble's transparency before this setting existed (70 % opacity). */
        public const val DEFAULT_TRANSPARENCY: Int = 30

        /** The most see-through the bubble may be. */
        public const val MAX_TRANSPARENCY: Int = 80

        /** Today's look: the theme's colour, 30 % transparent, the standard glyph. */
        public val Default: BubbleLook = BubbleLook()
    }
}

/** The Ready bubble's fill: one of the presets, or a colour of the user's own. */
public sealed interface BubbleColour {
    /**
     * A preset colour.
     *
     * @property preset which one.
     */
    public data class Preset(public val preset: BubblePreset) : BubbleColour

    /**
     * A colour of the user's own, from a hue and a lightness (fixed saturation).
     *
     * @property hue the hue in degrees, `0..359`.
     * @property lightness the lightness in percent, `0..100`.
     */
    public data class Custom(public val hue: Int, public val lightness: Int) : BubbleColour
}

/** The preset colours of the Ready bubble. */
public enum class BubblePreset {
    /** The theme's surface colour (the default). */
    DEFAULT,

    /** Dark grey. */
    GRAPHITE,

    /** Blue. */
    BLUE,

    /** Teal. */
    TEAL,

    /** Green. */
    GREEN,

    /** Violet. */
    VIOLET,

    /** Rose. */
    ROSE,

    /** Sand. */
    SAND,
}

/** The microphone glyph styles of the Ready bubble. */
public enum class MicIcon {
    /** The rounded microphone (the default). */
    STANDARD,

    /** A heavier, wider microphone. */
    BOLD,

    /** A microphone inside a ring. */
    RING,

    /** A small microphone between level bars. */
    WAVE,
}
