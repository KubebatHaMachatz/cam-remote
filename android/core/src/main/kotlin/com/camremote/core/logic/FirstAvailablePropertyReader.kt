package com.camremote.core.logic

import com.camremote.core.port.PropertyReader

/**
 * Tries several [PropertyReader]s in order and uses the first that does not fail.
 *
 * Note what counts as failure: a reader returning null has answered — the property is not set — and
 * the chain stops there. Only a thrown exception means "this mechanism does not work on this
 * device", which is the case worth retrying with another mechanism.
 */
class FirstAvailablePropertyReader(private val readers: List<PropertyReader>) : PropertyReader {

    init {
        require(readers.isNotEmpty()) { "At least one PropertyReader is required" }
    }

    /**
     * Asks each reader in turn, returning the first answer.
     *
     * @throws Exception the last reader's failure, when every one of them failed.
     */
    override fun read(key: String): String? {
        var lastFailure: Exception? = null
        for (reader in readers) {
            try {
                return reader.read(key)
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw lastFailure!!
    }
}
