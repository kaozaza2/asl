package dev.asl.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** Throws the agent's error unless the message carries `"ok":true`. */
internal fun JsonObject.requireOk(): JsonObject = also {
    if (this["ok"]?.jsonPrimitive?.booleanOrNull != true) {
        throw IOException(this["error"]?.jsonPrimitive?.contentOrNull ?: "agent refused the request")
    }
}

/** Opens an agent channel, first bringing the VM up and enforcing the reply's `ok`. */
internal suspend fun VmController.openAgentChannel(header: JsonObject): AgentConnection {
    ensureRunning()
    val conn = openChannel(header)
    try {
        conn.reply.requireOk()
    } catch (e: IOException) {
        conn.close()
        throw e
    }
    return conn
}
