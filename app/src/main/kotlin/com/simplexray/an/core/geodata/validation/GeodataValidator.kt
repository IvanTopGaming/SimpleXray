package com.simplexray.an.core.geodata.validation

import com.google.protobuf.CodedInputStream
import com.simplexray.an.core.geodata.MAX_BYTES
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object GeodataValidator {
    suspend fun validate(file: File, filename: String) {
        try {
            file.inputStream().buffered().use { input ->
                val coded = CodedInputStream.newInstance(input)
                coded.setSizeLimit(MAX_BYTES.toInt())
                var entries = 0
                var records = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val tag = coded.readTag()
                    if (tag == 0) break
                    if (tag == 10) {
                        coded.message {
                            var category = ""
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val entryTag = readTag()
                                if (entryTag == 0) break
                                when (entryTag) {
                                    10 -> category = shortString()
                                    18 -> {
                                        message {
                                            if (filename == "geoip.dat") validateCidr()
                                            else validateDomain()
                                        }
                                        records++
                                    }
                                    24 ->
                                        if (filename == "geoip.dat") readBool() else skip(entryTag)
                                    else -> skip(entryTag)
                                }
                            }
                            checkData(category.isNotBlank())
                        }
                        entries++
                    } else {
                        coded.skip(tag)
                    }
                }
                coded.checkLastTagWas(0)
                checkData(entries > 0 && records > 0)
            }
        } catch (error: IOException) {
            throw IOException("Некорректная или неполная база $filename", error)
        }
    }

    private fun CodedInputStream.validateCidr() {
        var addressSize = 0
        var prefix = 0
        while (true) {
            val tag = readTag()
            if (tag == 0) break
            when (tag) {
                10 -> {
                    addressSize = readRawVarint32()
                    checkData(addressSize == 4 || addressSize == 16)
                    skipRawBytes(addressSize)
                }
                16 -> prefix = readUInt32()
                else -> skip(tag)
            }
        }
        checkData(
            (addressSize == 4 || addressSize == 16) && prefix >= 0 && prefix <= addressSize * 8
        )
    }

    private fun CodedInputStream.validateDomain() {
        var type = 0
        var value = ""
        while (true) {
            val tag = readTag()
            if (tag == 0) break
            when (tag) {
                8 -> type = readEnum()
                18 -> value = shortString()
                26 ->
                    message {
                        while (true) {
                            val attributeTag = readTag()
                            if (attributeTag == 0) break
                            when (attributeTag) {
                                10 -> shortString()
                                16 -> readBool()
                                24 -> readInt64()
                                else -> skip(attributeTag)
                            }
                        }
                    }
                else -> skip(tag)
            }
        }
        checkData(type in 0..3 && value.isNotBlank())
    }

    private inline fun CodedInputStream.message(block: CodedInputStream.() -> Unit) {
        val limit = pushLimit(readRawVarint32())
        try {
            block()
            checkLastTagWas(0)
            checkData(bytesUntilLimit == 0)
        } finally {
            popLimit(limit)
        }
    }

    private fun CodedInputStream.shortString(): String {
        val length = readRawVarint32()
        checkData(length in 0..(1024 * 1024))
        val bytes = com.google.protobuf.ByteString.copyFrom(readRawBytes(length))
        checkData(bytes.isValidUtf8)
        return bytes.toStringUtf8()
    }

    private fun CodedInputStream.skip(tag: Int) {
        checkData(tag and 7 != 3 && tag and 7 != 4 && skipField(tag))
    }

    private fun checkData(condition: Boolean) {
        if (!condition) throw IOException("Неверный формат базы")
    }
}
