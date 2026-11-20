package expo.modules.readium

/**
 * Whether the volume buttons currently mean "turn the page" rather than "change the
 * volume", for [EpubView] and for `MainActivity`.
 *
 * The decision needs three facts: the setting is on, the reader UI is hidden, and
 * whether the two buttons are swapped. All three live in React props, which only
 * [EpubView] can read; the keys arrive at `MainActivity.dispatchKeyEvent` first, which
 * cannot see them. So the view publishes the state here and the activity reads it.
 *
 * The activity is where the key has to be claimed: when this was handled in the view
 * instead, the system still received the press — the volume panel appeared and was
 * dismissed from outside — so the page turned unreliably.
 *
 * A plain object rather than anything activity-scoped, so it survives a configuration
 * change; the view rewrites it on every prop update, which also covers being read after
 * the process has been rebuilt.
 */
object ReaderInputState {
    /** The "turn pages with the volume buttons" setting. */
    @Volatile
    var volumeKeysTurnPages: Boolean = false

    /**
     * Whether the reader UI is on screen. While it is, the volume buttons must keep
     * their normal meaning, so this is inverted at the call site rather than stored the
     * other way round.
     */
    @Volatile
    var readerUiVisible: Boolean = false

    /** Swap which button goes forward and which goes back. */
    @Volatile
    var invertVolumeKeys: Boolean = false

    /**
     * Whether a selection or a word menu is open. Volume navigation is off while it is,
     * so a press changes the volume instead of turning the page under the menu.
     */
    @Volatile
    var hasSelection: Boolean = false

    /** True when a volume press should turn the page instead of changing the volume. */
    val volumeKeysTurnPagesNow: Boolean
        get() = volumeKeysTurnPages && !readerUiVisible && !hasSelection
}
