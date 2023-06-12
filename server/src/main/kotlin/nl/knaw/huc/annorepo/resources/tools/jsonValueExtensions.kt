package nl.knaw.huc.annorepo.resources.tools

import jakarta.json.JsonNumber
import jakarta.json.JsonValue

fun JsonValue.toSimpleValue(): Any? {
    return when (valueType) {
        JsonValue.ValueType.NUMBER -> toSimpleNumber()
        JsonValue.ValueType.STRING -> toString()
        JsonValue.ValueType.TRUE -> true
        JsonValue.ValueType.FALSE -> false
        JsonValue.ValueType.NULL -> null
        JsonValue.ValueType.ARRAY -> toSimpleArray()
        JsonValue.ValueType.OBJECT -> toSimpleMap()
        else -> throw IllegalArgumentException("Invalid JSON value type: $valueType")
    }
}

fun JsonValue.toSimpleMap(): Map<String, Any?> {
    val jsonObject = asJsonObject()
    val map = mutableMapOf<String, Any?>()
    jsonObject.forEach { (key, value) -> map[key] = value.toSimpleValue() }
    return map.toMap()
}

fun JsonValue.toSimpleArray(): Array<Any?> =
    asJsonArray()
        .map { it.toSimpleValue() }
        .toTypedArray()

fun JsonValue.toSimpleNumber(): Number {
    val jsonNumber = this as JsonNumber
    return if (jsonNumber.isIntegral) {
        jsonNumber.longValueExact()
    } else {
        jsonNumber.doubleValue()
    }
}