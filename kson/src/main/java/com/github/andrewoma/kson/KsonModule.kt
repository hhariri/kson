/*
 * Copyright (c) 2014 Andrew O'Malley
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.andrewoma.kson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.`type`.TypeFactory
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.core.JsonToken
import java.util.Stack
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.Module

class JsValueSerializer : JsonSerializer<JsValue>() {
    override fun serialize(value: JsValue?, json: JsonGenerator?, provider: SerializerProvider?) {
        json!!

        when (value) {
            is JsNumber -> json.writeNumber(value.asBigDecimal())
            is JsString -> json.writeString(value.asString())
            is JsBoolean -> json.writeBoolean(value.asBoolean()!!)
            is JsArray -> {
                json.writeStartArray()
                for (e in value) {
                    serialize(e, json, provider)
                }
                json.writeEndArray()
            }
            is JsObject -> {
                json.writeStartObject()
                for ((name, fieldValue) in value.fields) {
                    json.writeFieldName(name)
                    serialize(fieldValue, json, provider)
                }
                json.writeEndObject()
            }
            is JsNull -> json.writeNull()
            is JsUndefined -> json.writeNull()
        }
    }
}

trait DeserializerContext {
    fun addValue(value: JsValue): DeserializerContext
}

class ArrayContext(val content: MutableList<JsValue> = arrayListOf()) : DeserializerContext {
    override fun addValue(value: JsValue): DeserializerContext {
        content.add(value)
        return this
    }
}

class ObjectKeyContext(val content: MutableList<Pair<String, JsValue>>, val fieldName: String) : DeserializerContext {
    override fun addValue(value: JsValue): DeserializerContext {
        content.add(fieldName to value)
        return ObjectContext(content)
    }
}

// Context for reading one item of an Object (we already read fieldName)
class ObjectContext(val content: MutableList<Pair<String, JsValue>> = arrayListOf()) : DeserializerContext {
    fun setField(fieldName: String) = ObjectKeyContext(content, fieldName)
    override fun addValue(value: JsValue): DeserializerContext {
        throw RuntimeException("Cannot add a value on an object without a key, malformed JSON object!")
    }
}

class JsValueDeserializer(val factory: TypeFactory, val klass: Class<*>) : JsonDeserializer<Any>() {
    override fun isCachable() = true

    override fun deserialize(jp: JsonParser?, ctxt: DeserializationContext?): Any? {
        val value = doDeserialize(jp!!, ctxt!!, Stack<DeserializerContext>())

        if (!klass.isAssignableFrom(value.javaClass)) {
            throw ctxt.mappingException(klass)!!
        }

        return value
    }

    tailRecursive fun doDeserialize(jp: JsonParser, context: DeserializationContext, stack: Stack<DeserializerContext>): JsValue {
        if (jp.getCurrentToken() == null) {
            jp.nextToken()
        }

        val value: JsValue? = when (jp.getCurrentToken()) {

            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> JsNumber(jp.getDecimalValue())

            JsonToken.VALUE_STRING -> JsString(jp.getText())

            JsonToken.VALUE_TRUE -> JsBoolean(true)

            JsonToken.VALUE_FALSE -> JsBoolean(false)

            JsonToken.VALUE_NULL -> JsNull()

            JsonToken.START_ARRAY -> {
                stack.push(ArrayContext())
                null
            }

            JsonToken.END_ARRAY -> {
                val content = stack.pop()
                if (content is ArrayContext) JsArray(content.content) else {
                    throw RuntimeException("We should have been reading list, something got wrong")
                }
            }

            JsonToken.START_OBJECT -> {
                stack.push(ObjectContext())
                null
            }

            JsonToken.FIELD_NAME -> {
                val content = stack.pop()
                if (content is ObjectContext) {
                    stack.push(content.setField(jp.getCurrentName()!!))
                    null
                } else throw RuntimeException("We should be reading map, something got wrong")

            }

            JsonToken.END_OBJECT -> {
                val content = stack.pop()
                // TODO ... would be nice to avoid the conversion to an array
                if (content is ObjectContext) JsObject(*content.content.copyToArray()) else {
                    throw RuntimeException("We should have been reading an object, something got wrong")
                }
            }

            JsonToken.NOT_AVAILABLE -> throw RuntimeException("We should have been reading an object, something got wrong")
            JsonToken.VALUE_EMBEDDED_OBJECT -> throw RuntimeException("We should have been reading an object, something got wrong")
            else -> throw IllegalStateException()
        }

        jp.nextToken() // Read ahead

        return (if (value != null && stack.isEmpty() && jp.getCurrentToken() == null) {
            value
        } else if (value != null && stack.isEmpty()) {
            throw RuntimeException("Malformed JSON: Got a sequence of JsValue outside an array or an object.")
        } else {
            val toPass = if (value == null) stack else {
                val previous = stack.pop()!!
                stack.push(previous.addValue(value))
                stack
            }

            doDeserialize(jp, context, toPass)
        }) as JsValue
    }

    override fun getNullValue(): Any? = JsNull()
}

class KsonDeserializers() : Deserializers.Base() {
    override fun findBeanDeserializer(javaType: JavaType?, config: DeserializationConfig?, beanDesc: BeanDescription?): JsonDeserializer<out Any?>? {
        val klass = javaType?.getRawClass()!!
        return if (javaClass<JsValue>().isAssignableFrom(klass) || klass == javaClass<JsNull>()) {
            JsValueDeserializer(config!!.getTypeFactory()!!, klass)
        } else null
    }
}

class KsonSerializers : Serializers.Base() {
    override fun findSerializer(config: SerializationConfig?, `type`: JavaType?, beanDesc: BeanDescription?): JsonSerializer<out Any?>? {
        return if (javaClass<JsValue>().isAssignableFrom(beanDesc!!.getBeanClass()!!)) {
            JsValueSerializer()
        } else {
            null
        }
    }
}

public class KsonModule() : SimpleModule("kson", Version.unknownVersion()) {
    override fun setupModule(context: Module.SetupContext?) {
        context!!.addDeserializers(KsonDeserializers())
        context.addSerializers(KsonSerializers())
    }
}