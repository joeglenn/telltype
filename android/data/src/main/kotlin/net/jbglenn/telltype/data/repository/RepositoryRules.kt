package net.jbglenn.telltype.data.repository

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed

/**
 * The repository rule for updates (contracts page 4): updating a row that does not exist is [StorageFailed].
 * [rows] is what the `UPDATE` reported.
 */
internal fun changed(rows: Int): Outcome<Unit, StorageError> = if (rows >
    0
) {
    Outcome.Ok(Unit)
} else {
    Outcome.Err(StorageFailed)
}
