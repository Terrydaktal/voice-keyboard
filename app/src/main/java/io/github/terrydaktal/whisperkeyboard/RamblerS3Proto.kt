package io.github.terrydaktal.whisperkeyboard

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire-format protobuf encoder/decoder for Google's S3 speech service
 * (`speech.s3.S3StubbyClientConnectorService/S3Bidi`), matching the desktop
 * `rambler_desktop/s3.py` Gemini v3 (`beyond-a2a-recognizer`) protocol.
 */
internal object RamblerS3Proto {
    const val HOST = "speechs3proto2-pa.googleapis.com"
    const val PATH = "/speech.s3.S3StubbyClientConnectorService/S3Bidi"
    const val URL = "https://$HOST$PATH"

    val API_KEY: String
        get() = BuildConfig.RAMBLER_S3_API_KEY
    const val ANDROID_PACKAGE = "com.google.android.googlequicksearchbox"
    val ANDROID_CERT: String
        get() = BuildConfig.RAMBLER_S3_ANDROID_CERT

    const val DEFAULT_SERVICE = "beyond-a2a-recognizer"
    const val DEFAULT_ROUTING_KEY = "gboard_gemini_v3_streaming_voice_edit_mul"
    const val DEFAULT_LOCALE = "en-GB"
    const val DEFAULT_PROFANITY_FILTER = 0

    const val SAMPLE_RATE = 16_000
    const val OGG_OPUS_ENCODING = 10

    const val EXT_INPUT_BOX_INFO = 1203
    const val EXT_USER_INFO = 293000
    const val EXT_AUDIO_INFO = 293100
    const val EXT_AUDIO_DATA = 293101
    const val EXT_CLIENT_INFO = 294000
    const val EXT_RECOG_INFO = 294500
    const val EXT_HEARTBEAT = 29735924
    const val EXT_RECOGNIZER_EVENT = 1253625

    data class S3Event(
        val status: Long? = null,
        val errorCode: Long? = null,
        val errorDescription: String? = null,
        val language: String? = null,
        val spokenLanguage: String? = null,
        val endpointer: Long? = null,
        val recStatus: Long? = null,
        val finals: List<String> = emptyList(),
        val results: List<String> = emptyList(),
        val partials: List<String> = emptyList(),
        val finalFallback: String? = null,
        val resultFallback: String? = null,
    ) {
        val isEmpty: Boolean
            get() = status == null &&
                errorCode == null &&
                errorDescription == null &&
                language == null &&
                spokenLanguage == null &&
                endpointer == null &&
                recStatus == null &&
                finals.isEmpty() &&
                results.isEmpty() &&
                partials.isEmpty() &&
                finalFallback == null &&
                resultFallback == null
    }

    fun headerRequest(
        service: String = DEFAULT_SERVICE,
        locale: String = DEFAULT_LOCALE,
        routingKey: String = DEFAULT_ROUTING_KEY,
        profanityFilter: Int = DEFAULT_PROFANITY_FILTER,
        androidRelease: String = "16",
        deviceModel: String = "NX809J",
    ): ByteArray {
        val recInfo = ProtoWriter().apply {
            writeBytes(1, ProtoWriter().apply { writeBool(4, false) }.toByteArray())
            writeBool(5, true)
            writeInt32(7, profanityFilter)
            writeBool(13, true)
            writeBool(20, true)
            writeBool(40, true)
            writeBool(41, true)
            if (routingKey.isNotEmpty()) {
                writeString(50, routingKey)
            }
            if (service == DEFAULT_SERVICE) {
                // Laapa (#91): #2 = AdultAccount, #4 = required enable flag for beyond-a2a-recognizer
                writeBytes(91, ProtoWriter().apply {
                    writeBool(2, true)
                    writeBool(4, true)
                }.toByteArray())
            }
        }.toByteArray()

        val userInfo = ProtoWriter().apply {
            writeBytes(2, ProtoWriter().apply {
                writeString(1, locale)
                writeInt32(2, 1)
            }.toByteArray())
            writeBool(20, true)
        }.toByteArray()

        val audioInfo = ProtoWriter().apply {
            writeFloat(2, SAMPLE_RATE.toFloat())
            writeInt32(3, OGG_OPUS_ENCODING)
            writeInt32(4, 1)
            writeInt64(9, 0L)
        }.toByteArray()

        val clientInfo = ProtoWriter().apply {
            writeString(2, "gboard")
            writeString(8, "Android")
            writeString(9, androidRelease)
            writeString(10, "-1")
            writeString(11, deviceModel)
            writeBytes(22, ProtoWriter().apply { writeBool(1, true) }.toByteArray())
            writeInt32(26, 1)
        }.toByteArray()

        val heartbeat = ProtoWriter().apply {
            writeInt32(1, 500)
        }.toByteArray()

        return ProtoWriter().apply {
            writeString(1, service)
            writeBool(2, true)
            writeBytes(EXT_AUDIO_INFO, audioInfo)
            writeBytes(EXT_USER_INFO, userInfo)
            writeBytes(EXT_CLIENT_INFO, clientInfo)
            writeBytes(EXT_RECOG_INFO, recInfo)
            writeBytes(EXT_HEARTBEAT, heartbeat)
            if (service == DEFAULT_SERVICE) {
                writeBytes(EXT_INPUT_BOX_INFO, ProtoWriter().apply { writeString(1, "") }.toByteArray())
            }
        }.toByteArray()
    }

    /**
     * Wraps an Ogg/Opus chunk into S3AudioData (ext 293101).
     * Never re-sends full S3AudioInfo on chunks (doing so resets beyond-a2a-recognizer).
     */
    fun chunkRequest(payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val audioData = ProtoWriter().apply {
            writeBytes(1, payload, offset, length)
        }.toByteArray()
        return ProtoWriter().apply {
            writeBytes(EXT_AUDIO_DATA, audioData)
        }.toByteArray()
    }

    fun endRequest(): ByteArray = ProtoWriter().apply {
        writeBool(2, true)
        writeBool(3, true)
    }.toByteArray()

    fun grpcFrame(payload: ByteArray): ByteArray {
        val framed = ByteArray(5 + payload.size)
        framed[0] = 0
        val len = payload.size
        framed[1] = ((len ushr 24) and 0xFF).toByte()
        framed[2] = ((len ushr 16) and 0xFF).toByte()
        framed[3] = ((len ushr 8) and 0xFF).toByte()
        framed[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, framed, 5, len)
        return framed
    }

    fun decodeResponse(raw: ByteArray): S3Event {
        val fields = parseFields(raw)
        val status = fields.firstVarint(1)
        val errorCode = fields.firstVarint(2)
        val errorDescription = fields.firstBytes(3)?.toString(Charsets.UTF_8)

        val evBytes = fields.firstBytes(EXT_RECOGNIZER_EVENT)
            ?: return S3Event(status = status, errorCode = errorCode, errorDescription = errorDescription)

        val evFields = parseFields(evBytes)
        val lang = evFields.firstBytes(3)?.toString(Charsets.UTF_8)
        val spokenLang = evFields.firstBytes(4)?.toString(Charsets.UTF_8)
        val endpointer = evFields.firstBytes(2)?.let { parseFields(it).firstVarint(1) }

        val recBytes = evFields.firstBytes(1)
            ?: return S3Event(
                status = status,
                errorCode = errorCode,
                errorDescription = errorDescription,
                language = lang,
                spokenLanguage = spokenLang,
                endpointer = endpointer,
            )

        val recFields = parseFields(recBytes)
        val recStatus = recFields.firstVarint(1)

        val finals = extractHypotheses(recFields.firstBytes(3))
        val partials = extractHypotheses(recFields.firstBytes(4))
        val results = extractHypotheses(recFields.firstBytes(7))
        val finalFb = extractFallback(recFields.firstBytes(3))
        val resultFb = extractFallback(recFields.firstBytes(7))

        return S3Event(
            status = status,
            errorCode = errorCode,
            errorDescription = errorDescription,
            language = lang,
            spokenLanguage = spokenLang,
            endpointer = endpointer,
            recStatus = recStatus,
            finals = finals,
            results = results,
            partials = partials,
            finalFallback = finalFb,
            resultFallback = resultFb,
        )
    }

    private fun extractHypotheses(resultMsg: ByteArray?): List<String> {
        if (resultMsg == null) return emptyList()
        val hypBytes = parseFields(resultMsg).firstBytes(3) ?: return emptyList()
        return parseFields(hypBytes)
            .filter { it.fieldNumber == 1 && it.bytes != null }
            .map { it.bytes!!.toString(Charsets.UTF_8) }
    }

    private fun extractFallback(resultMsg: ByteArray?): String? {
        if (resultMsg == null) return null
        val p = parseFields(resultMsg).firstBytes(5) ?: return null
        val inner = parseFields(p).firstBytes(1) ?: return null
        return parseFields(inner).firstBytes(1)?.toString(Charsets.UTF_8)
    }

    internal class GrpcFrameSplitter {
        private var buffer = ByteArray(0)

        fun feed(data: ByteArray, length: Int = data.size): List<ByteArray> {
            if (length <= 0) return emptyList()
            val combined = ByteArray(buffer.size + length)
            System.arraycopy(buffer, 0, combined, 0, buffer.size)
            System.arraycopy(data, 0, combined, buffer.size, length)

            val frames = mutableListOf<ByteArray>()
            var offset = 0
            while (offset + 5 <= combined.size) {
                val frameLen =
                    ((combined[offset + 1].toInt() and 0xFF) shl 24) or
                        ((combined[offset + 2].toInt() and 0xFF) shl 16) or
                        ((combined[offset + 3].toInt() and 0xFF) shl 8) or
                        (combined[offset + 4].toInt() and 0xFF)
                if (frameLen < 0 || offset + 5 + frameLen > combined.size) break
                val msg = ByteArray(frameLen)
                System.arraycopy(combined, offset + 5, msg, 0, frameLen)
                frames.add(msg)
                offset += 5 + frameLen
            }
            buffer = if (offset < combined.size) {
                combined.copyOfRange(offset, combined.size)
            } else {
                ByteArray(0)
            }
            return frames
        }
    }

    internal class TranscriptAccumulator {
        private val partials = mutableListOf<String>()
        private val finals = mutableListOf<String>()

        fun accept(event: S3Event) {
            if (event.partials.isNotEmpty()) {
                val joined = event.partials.joinToString(" ")
                if (joined !in partials) partials.add(joined)
            }
            val finalList = when {
                event.finals.isNotEmpty() -> event.finals
                event.results.isNotEmpty() -> event.results
                event.finalFallback != null -> listOf(event.finalFallback)
                event.resultFallback != null -> listOf(event.resultFallback)
                else -> emptyList()
            }
            if (finalList.isNotEmpty()) {
                val joined = finalList.joinToString(" ")
                if (joined !in finals) finals.add(joined)
            }
        }

        fun finalText(): String = when {
            finals.isNotEmpty() -> finals.joinToString(" ").trim()
            partials.isNotEmpty() -> partials.joinToString(" ").trim()
            else -> ""
        }
    }

    private data class Field(val fieldNumber: Int, val wireType: Int, val varint: Long? = null, val bytes: ByteArray? = null)

    private fun List<Field>.firstVarint(fieldNumber: Int): Long? =
        firstOrNull { it.fieldNumber == fieldNumber && it.varint != null }?.varint

    private fun List<Field>.firstBytes(fieldNumber: Int): ByteArray? =
        firstOrNull { it.fieldNumber == fieldNumber && it.bytes != null }?.bytes

    private fun parseFields(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        while (i < data.size) {
            val (tag, next1) = readVarint(data, i)
            i = next1
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            when (wireType) {
                0 -> {
                    val (v, next2) = readVarint(data, i)
                    i = next2
                    out.add(Field(fieldNumber, wireType, varint = v))
                }
                1 -> {
                    if (i + 8 > data.size) break
                    out.add(Field(fieldNumber, wireType, bytes = data.copyOfRange(i, i + 8)))
                    i += 8
                }
                2 -> {
                    val (lenLong, next2) = readVarint(data, i)
                    i = next2
                    val len = lenLong.toInt()
                    if (len < 0 || i + len > data.size) break
                    out.add(Field(fieldNumber, wireType, bytes = data.copyOfRange(i, i + len)))
                    i += len
                }
                5 -> {
                    if (i + 4 > data.size) break
                    out.add(Field(fieldNumber, wireType, bytes = data.copyOfRange(i, i + 4)))
                    i += 4
                }
                else -> break
            }
        }
        return out
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
            if (shift >= 64) break
        }
        return result to i
    }

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        fun writeVarint(value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7FL).toInt()
                v = v ushr 7
                if (v != 0L) {
                    out.write(b or 0x80)
                } else {
                    out.write(b)
                    return
                }
            }
        }

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint(((fieldNumber.toLong() shl 3) or wireType.toLong()))
        }

        fun writeBool(fieldNumber: Int, value: Boolean) {
            writeTag(fieldNumber, 0)
            writeVarint(if (value) 1L else 0L)
        }

        fun writeInt32(fieldNumber: Int, value: Int) {
            writeTag(fieldNumber, 0)
            writeVarint(value.toLong() and 0xFFFFFFFFL)
        }

        fun writeInt64(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, 0)
            writeVarint(value)
        }

        fun writeFloat(fieldNumber: Int, value: Float) {
            writeTag(fieldNumber, 5)
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()
            out.write(buf)
        }

        fun writeString(fieldNumber: Int, value: String) {
            writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
        }

        fun writeBytes(fieldNumber: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
            writeTag(fieldNumber, 2)
            writeVarint(length.toLong())
            out.write(data, offset, length)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
