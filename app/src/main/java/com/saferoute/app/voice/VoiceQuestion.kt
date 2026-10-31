package com.saferoute.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spoken questions for when hands are busy (online only). Muse Voice Transcribe turns speech into
 * text; the text then goes through the same offline intent matching as typed questions, so the
 * answer is still a pre-checked one (VALIDATION V13, V20).
 */
class VoiceQuestion(private val apiKey: () -> String) {
    private val recording = AtomicBoolean(false)

    /** Records 16 kHz mono PCM until [stop] or [MAX_SECONDS]; blocks the calling (IO) thread. */
    @SuppressLint("MissingPermission") // caller requests RECORD_AUDIO first
    fun record(): ByteArray {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        val pcm = ByteArrayOutputStream()
        val buf = ByteArray(minBuf)
        recording.set(true)
        rec.startRecording()
        try {
            while (recording.get() && pcm.size() < RATE * 2 * MAX_SECONDS) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
            }
        } finally {
            rec.stop()
            rec.release()
            recording.set(false)
        }
        return wav(pcm.toByteArray())
    }

    fun stop() = recording.set(false)

    val isRecording get() = recording.get()

    fun transcribe(wav: ByteArray, keywords: List<String>): String {
        val boundary = "saferoute-${System.nanoTime()}"
        val settings = JSONObject()
            .put("model", "muse-voice-transcribe-1.0")
            .put("audioEncoding", "WAV")
            .put("mode", "PUSH_TO_TALK")
            .put("languageBias", JSONArray(listOf("Korean", "English")))
            .put("keywords", JSONArray(keywords.take(MAX_KEYWORDS)))
        val body = ByteArrayOutputStream().apply {
            fun line(s: String) = write("$s\r\n".toByteArray())
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"request\"")
            line("Content-Type: application/json")
            line("")
            line(settings.toString())
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"audio\"; filename=\"q.wav\"")
            line("Content-Type: audio/wav")
            line("")
            write(wav)
            line("")
            line("--$boundary--")
        }.toByteArray()
        val conn = (URL(URL_ASR).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey()}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            check(code in 200..299) { "transcribe HTTP $code: ${text.take(200)}" }
            return JSONObject(text).optString("transcript").trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }

    private companion object {
        const val URL_ASR = "https://api.meta.ai/v1/asr/transcribe"
        const val RATE = 16_000
        const val MAX_SECONDS = 10
        const val MAX_KEYWORDS = 50
    }
}
