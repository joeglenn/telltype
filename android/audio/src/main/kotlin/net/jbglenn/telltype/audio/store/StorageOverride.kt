package net.jbglenn.telltype.audio.store

/**
 * A simulated full disk, bound only by `:app`'s debug source set for the connected tests (release builds bind none,
 * so the real free-space figure and the real writes are used). Called on the I/O dispatcher, where reading a small
 * switch file is allowed.
 */
public interface StorageOverride {
    /** The disk counts as full: the store sees no free space and refuses new recordings with `StorageFull`. */
    public fun full(): Boolean

    /**
     * How many bytes a recording opened now may write before its writes fail like a full disk (`ENOSPC`); null for
     * no limit.
     */
    public fun bytesBeforeFull(): Long?
}
