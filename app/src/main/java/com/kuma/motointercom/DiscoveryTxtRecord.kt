package com.kuma.motointercom

/** One DNS-SD TXT entry includes the UTF-8 key, '=', and value within 255 bytes. */
internal fun boundedDiscoveryTxtValue(key: String, value: String): String {
    val valueBudget = 255 - key.toByteArray(Charsets.UTF_8).size - 1
    require(valueBudget >= 0) { "DNS-SD TXT key is too long" }
    if (value.toByteArray(Charsets.UTF_8).size <= valueBudget) return value
    var end = 0
    var bytes = 0
    while (end < value.length) {
        val next = end + Character.charCount(value.codePointAt(end))
        val count = value.substring(end, next).toByteArray(Charsets.UTF_8).size
        if (bytes + count > valueBudget) break
        bytes += count
        end = next
    }
    return value.substring(0, end)
}
