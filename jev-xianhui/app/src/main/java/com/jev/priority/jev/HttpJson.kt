package com.jev.priority.jev

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** One JSON POST. Failures carry the HTTP status and a trimmed body. */
object HttpJson {

    class ApiException(val status: Int, body: String) :
        Exception("HTTP $status ${body.take(200)}")

    fun post(url: String, apiKey: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $apiKey")
            // OpenRouter attribution; other hosts ignore it.
            setRequestProperty("HTTP-Referer", "https://jev-priority.local")
            setRequestProperty("X-Title", "Jev Priority")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299) throw ApiException(code, text)
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
