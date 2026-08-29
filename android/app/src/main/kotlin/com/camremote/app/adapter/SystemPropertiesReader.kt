package com.camremote.app.adapter

import com.camremote.core.logic.GetPropOutput
import com.camremote.core.port.PropertyReader

/**
 * Reads a property through the hidden `android.os.SystemProperties` class.
 *
 * The alternate implementation, and the concrete answer to "how do I swap a port's adapter?" in the
 * extension guide. It is the fallback rather than the primary because hidden-API restrictions block
 * this reflection on many modern builds — when they do, it throws and the chain moves on.
 */
class SystemPropertiesReader : PropertyReader {

    private val getter by lazy {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
    }

    /**
     * Reads a property by reflection.
     *
     * @throws Exception on builds where hidden-API restrictions block this, which is the signal
     *   for the chain to fall back to another reader.
     */
    override fun read(key: String): String? =
        GetPropOutput.parse(getter.invoke(null, key) as? String ?: "")
}
