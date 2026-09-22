package com.example

import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

data class RrcMessage(
    val id: Long,
    val ts: Double,
    val nick: String,
    val body: String,
    val kind: String // "msg", "system", "action", "dm"
)

data class RrcPollResponse(
    val room: String,
    val nick: String,
    val topic: String,
    val rooms: List<String>,
    val messages: List<RrcMessage>,
    val dms: List<RrcMessage>,
    val users: List<String>
)

data class RrcSendResponse(
    val replies: List<String>,
    val room: String?
)

data class BillboardPost(
    val text: String,
    val signature: String
)

class StumpHttpClient {
    var baseUrl = "http://192.168.4.1"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun checkReachability(url: String = baseUrl): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // ==========================================
    // RRC Chat Endpoints (rrc.py)
    // ==========================================

    suspend fun pollRrc(room: String, sinceId: Long = 0L): Result<RrcPollResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanRoom = room.trim().removePrefix("#")
            val encodedRoom = URLEncoder.encode(cleanRoom, "UTF-8")
            val url = "$baseUrl/rrc/poll?room=$encodedRoom&since=$sinceId"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)

                    val respRoom = json.optString("room", cleanRoom).trim().removePrefix("#")
                    val respNick = json.optString("nick", "")
                    val respTopic = json.optString("topic", "")

                    val roomsList = mutableListOf<String>()
                    val roomsArr = json.optJSONArray("rooms")
                    if (roomsArr != null) {
                        for (i in 0 until roomsArr.length()) {
                            val r = roomsArr.optString(i).trim().removePrefix("#")
                            if (r.isNotEmpty()) {
                                roomsList.add(r)
                            }
                        }
                    }

                    fun parseMessages(key: String): List<RrcMessage> {
                        val list = mutableListOf<RrcMessage>()
                        val arr = json.optJSONArray(key) ?: return list
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            list.add(
                                RrcMessage(
                                    id = obj.optLong("id", 0L),
                                    ts = obj.optDouble("ts", 0.0),
                                    nick = obj.optString("nick", "anon"),
                                    body = obj.optString("body", ""),
                                    kind = obj.optString("kind", "msg")
                                )
                            )
                        }
                        return list
                    }

                    val messages = parseMessages("messages")
                    val dms = parseMessages("dms")

                    val usersList = mutableListOf<String>()
                    val usersArr = json.optJSONArray("users")
                    if (usersArr != null) {
                        for (i in 0 until usersArr.length()) {
                            usersList.add(usersArr.optString(i))
                        }
                    }

                    Result.success(
                        RrcPollResponse(
                            room = respRoom,
                            nick = respNick,
                            topic = respTopic,
                            rooms = roomsList,
                            messages = messages,
                            dms = dms,
                            users = usersList
                        )
                    )
                } else {
                    Result.failure(Exception("RRC Poll error: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendRrc(rawLine: String): Result<RrcSendResponse> = withContext(Dispatchers.IO) {
        try {
            val reqBody = rawLine.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$baseUrl/rrc/send")
                .post(reqBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 413) {
                    return@withContext Result.failure(Exception("Message too long"))
                }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)

                    val repliesList = mutableListOf<String>()
                    val repliesArr = json.optJSONArray("replies")
                    if (repliesArr != null) {
                        for (i in 0 until repliesArr.length()) {
                            repliesList.add(repliesArr.optString(i))
                        }
                    }

                    val newRoom = if (json.has("room") && !json.isNull("room")) {
                        json.optString("room").trim().removePrefix("#").takeIf { it.isNotEmpty() }
                    } else null

                    Result.success(RrcSendResponse(repliesList, newRoom))
                } else {
                    Result.failure(Exception("Send failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // Billboard Endpoints (billboard.py)
    // ==========================================

    private fun cleanHtml(html: String): String {
        return html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .trim()
    }

    suspend fun fetchBillboardPosts(): Result<List<BillboardPost>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/billboard")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val posts = mutableListOf<BillboardPost>()

                    // Match verified pattern: <li>(.*?)<small>&mdash;\s*(.*?)</small></li>
                    val pattern = Pattern.compile(
                        "<li>(.*?)<small>(?:&mdash;|—|-)?\\s*(.*?)</small></li>",
                        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
                    )
                    val matcher = pattern.matcher(html)
                    while (matcher.find()) {
                        val textRaw = matcher.group(1) ?: ""
                        val sigRaw = matcher.group(2) ?: ""
                        val textClean = cleanHtml(textRaw)
                        val sigClean = cleanHtml(sigRaw)
                        if (textClean.isNotBlank() || sigClean.isNotBlank()) {
                            posts.add(BillboardPost(textClean, sigClean))
                        }
                    }

                    // Fallback if rendered differently or plain list items
                    if (posts.isEmpty()) {
                        val liPattern = Pattern.compile("<li>(.*?)</li>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
                        val liMatcher = liPattern.matcher(html)
                        while (liMatcher.find()) {
                            val liRaw = liMatcher.group(1) ?: ""
                            val clean = cleanHtml(liRaw)
                            if (clean.isNotBlank()) {
                                posts.add(BillboardPost(clean, ""))
                            }
                        }
                    }

                    // Plain text lines fallback if completely non-HTML
                    if (posts.isEmpty() && html.isNotBlank() && !html.contains("<html")) {
                        html.lines().filter { it.isNotBlank() }.forEach {
                            posts.add(BillboardPost(it.trim(), ""))
                        }
                    }

                    Result.success(posts)
                } else {
                    Result.failure(Exception("Failed to fetch billboard: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun postBillboardEntry(text: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("entry", text)
                .build()
            val request = Request.Builder()
                .url("$baseUrl/post")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 303) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Post failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchBillboard(): Result<List<String>> = withContext(Dispatchers.IO) {
        fetchBillboardPosts().map { posts ->
            posts.map { p -> if (p.signature.isNotEmpty()) "${p.text} — ${p.signature}" else p.text }
        }
    }

    // ==========================================
    // File Server Endpoints (fserv.py)
    // ==========================================

    suspend fun uploadFile(file: File, filename: String, hash: String = ""): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sanitizedName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val reqBuilder = Request.Builder()
                .url("$baseUrl/upload")
                .header("X-Filename", sanitizedName)

            if (hash.isNotBlank()) {
                reqBuilder.header("X-Hash", hash)
            }
            reqBuilder.post(requestBody)

            client.newCall(reqBuilder.build()).execute().use { response ->
                when (response.code) {
                    400 -> Result.failure(Exception("Invalid or empty upload (HTTP 400)"))
                    503 -> Result.failure(Exception("No SD card mounted (HTTP 503)"))
                    507 -> Result.failure(Exception("Storage Full: 75% limit reached (HTTP 507)"))
                    in 200..299 -> {
                        val body = response.body?.string()?.trim() ?: "Upload successful"
                        Result.success(body.ifEmpty { "Upload successful" })
                    }
                    else -> Result.failure(Exception("Upload failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchFileList(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/files")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val fileSet = linkedSetOf<String>()

                    // Match links like href="/download?f=filename" or href="download?f=filename"
                    val linkPattern = Pattern.compile(
                        """href=["'](?:/download\?f=|download\?f=)([^"'&\s]+)["']""",
                        Pattern.CASE_INSENSITIVE
                    )
                    val linkMatcher = linkPattern.matcher(html)
                    while (linkMatcher.find()) {
                        val rawName = linkMatcher.group(1) ?: ""
                        val decoded = try {
                            URLDecoder.decode(rawName, "UTF-8")
                        } catch (_: Exception) {
                            rawName
                        }
                        val clean = decoded.trim().replace(Regex("[/\\\\]"), "")
                        if (clean.isNotBlank()) {
                            fileSet.add(clean)
                        }
                    }

                    // Secondary pass: look for general links inside <a ...>...</a>
                    if (fileSet.isEmpty()) {
                        val aPattern = Pattern.compile(
                            """<a\s+[^>]*href=["'][^"']*["'][^>]*>(.*?)</a>""",
                            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
                        )
                        val aMatcher = aPattern.matcher(html)
                        while (aMatcher.find()) {
                            val nameRaw = cleanHtml(aMatcher.group(1) ?: "")
                            val clean = nameRaw.trim().replace(Regex("[/\\\\]"), "")
                            if (clean.isNotBlank() && !clean.equals("Back", ignoreCase = true) && !clean.startsWith("..")) {
                                fileSet.add(clean)
                            }
                        }
                    }

                    // Fallback for <li>...</li> items
                    if (fileSet.isEmpty()) {
                        val liPattern = Pattern.compile("""<li>(.*?)</li>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
                        val liMatcher = liPattern.matcher(html)
                        while (liMatcher.find()) {
                            val clean = cleanHtml(liMatcher.group(1) ?: "").trim()
                            if (clean.isNotBlank() && !clean.contains("<") && clean.length < 128) {
                                fileSet.add(clean)
                            }
                        }
                    }

                    // Fallback for plain text lines if not HTML
                    if (fileSet.isEmpty() && !html.contains("<html", ignoreCase = true)) {
                        html.lines().forEach { line ->
                            val clean = line.trim()
                            if (clean.isNotBlank() && clean.length < 128) {
                                fileSet.add(clean)
                            }
                        }
                    }

                    Result.success(fileSet.toList())
                } else {
                    Result.failure(Exception("Failed to fetch file list: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(filename: String, context: Context): Result<File> = withContext(Dispatchers.IO) {
        try {
            val encodedName = URLEncoder.encode(filename, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/download?f=$encodedName")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Result.failure(Exception("File not found on server (HTTP 404)"))
                    !response.isSuccessful -> Result.failure(Exception("Download failed: HTTP ${response.code}"))
                    else -> {
                        val body = response.body ?: return@use Result.failure(Exception("Empty response body"))
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) {
                            downloadsDir.mkdirs()
                        }
                        val destFile = File(downloadsDir, filename)
                        body.byteStream().use { input ->
                            BufferedOutputStream(FileOutputStream(destFile)).use { output ->
                                input.copyTo(output)
                                output.flush()
                            }
                        }

                        // Run MediaScannerConnection so Android MediaStore indexes it immediately
                        MediaScannerConnection.scanFile(
                            context.applicationContext,
                            arrayOf(destFile.absolutePath),
                            null,
                            null
                        )

                        Result.success(destFile)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(filename: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val encodedName = URLEncoder.encode(filename, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/download?f=$encodedName")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Result.failure(Exception("File not found on server (HTTP 404)"))
                    !response.isSuccessful -> Result.failure(Exception("Download failed: HTTP ${response.code}"))
                    else -> {
                        val body = response.body ?: return@use Result.failure(Exception("Empty response body"))
                        destination.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                                input.copyTo(output)
                                output.flush()
                            }
                        }
                        Result.success(destination)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // Discovery & Connectivity
    // ==========================================

    fun getDefaultGateway(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return null
            for (route in linkProps.routes) {
                val gateway = route.gateway
                if (gateway is Inet4Address && !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress) {
                    val host = gateway.hostAddress
                    if (!host.isNullOrEmpty() && host != "0.0.0.0") {
                        return host
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getLocalSubnetPrefix(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            if (activeNetwork != null) {
                val linkProps = cm.getLinkProperties(activeNetwork)
                if (linkProps != null) {
                    for (linkAddr in linkProps.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val host = addr.hostAddress ?: continue
                            val parts = host.split(".")
                            if (parts.size == 4) {
                                return "${parts[0]}.${parts[1]}.${parts[2]}"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: Avoid NetworkInterface.getNetworkInterfaces() which triggers SELinux netlink audit denials in untrusted_app
        return null

        return null
    }

    fun isPortOpen(host: String, port: Int = 80, timeoutMs: Int = 250): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun isStumpNode(host: String): Boolean = withContext(Dispatchers.IO) {
        val probeClient = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()

        try {
            val reqBillboard = Request.Builder()
                .url("http://$host/billboard")
                .head()
                .build()
            probeClient.newCall(reqBillboard).execute().use { response ->
                if (response.isSuccessful || response.code in 200..399) {
                    return@withContext true
                }
            }
        } catch (_: Exception) {}

        try {
            val reqRoot = Request.Builder()
                .url("http://$host/")
                .head()
                .build()
            probeClient.newCall(reqRoot).execute().use { response ->
                if (response.isSuccessful || response.code in 200..399) {
                    return@withContext true
                }
            }
        } catch (_: Exception) {}

        false
    }

    suspend fun scanSubnet(
        subnetPrefix: String,
        onProgress: (scanned: Int, total: Int) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val total = 254
        val scannedCount = AtomicInteger(0)
        val discoveredIp = AtomicReference<String?>(null)

        // Process in manageable chunks of 16 concurrent probes to prevent socket flood and SELinux audit rate-limits
        val hostChunks = (1..total).chunked(16)
        for (chunk in hostChunks) {
            if (discoveredIp.get() != null) break
            val chunkJobs = chunk.map { hostNum ->
                async {
                    if (discoveredIp.get() != null) {
                        val current = scannedCount.incrementAndGet()
                        onProgress(current, total)
                        return@async null
                    }

                    val targetIp = "$subnetPrefix.$hostNum"
                    var found: String? = null
                    try {
                        if (isPortOpen(targetIp, 80, 250)) {
                            if (isStumpNode(targetIp)) {
                                discoveredIp.compareAndSet(null, targetIp)
                                found = targetIp
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        val current = scannedCount.incrementAndGet()
                        onProgress(current, total)
                    }
                    found
                }
            }
            chunkJobs.awaitAll()
        }

        discoveredIp.get()
    }

    suspend fun autoDiscover(
        context: Context,
        onProgress: (scanned: Int, total: Int) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val gateway = getDefaultGateway(context)
        if (gateway != null) {
            onProgress(0, 1)
            if (isPortOpen(gateway, 80, 500) && isStumpNode(gateway)) {
                onProgress(1, 1)
                baseUrl = "http://$gateway"
                return@withContext gateway
            }
        }

        if (gateway != "192.168.4.1") {
            if (isPortOpen("192.168.4.1", 80, 300) && isStumpNode("192.168.4.1")) {
                baseUrl = "http://192.168.4.1"
                return@withContext "192.168.4.1"
            }
        }

        val subnetPrefix = getLocalSubnetPrefix(context) ?: "192.168.4"
        val found = scanSubnet(subnetPrefix, onProgress)
        if (found != null) {
            baseUrl = "http://$found"
            return@withContext found
        }

        if (subnetPrefix != "192.168.4") {
            val fallbackFound = scanSubnet("192.168.4", onProgress)
            if (fallbackFound != null) {
                baseUrl = "http://$fallbackFound"
                return@withContext fallbackFound
            }
        }

        null
    }
}


