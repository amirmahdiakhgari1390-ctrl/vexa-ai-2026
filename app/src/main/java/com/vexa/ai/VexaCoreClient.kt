package com.vexa.ai

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class VexaCoreClient(
    private val prefs: SharedPreferences,
    private val memory: MemoryStore
) {

    companion object {
        const val DEFAULT_GATEWAY_URL = "https://wolf-ai.amirmahdi-akhgari1390.workers.dev"
        const val FALLBACK_CLOUD_URL = "https://text.pollinations.ai/openai"

        fun isPersianText(text: String): Boolean = Regex("[\u0600-\u06FF]").containsMatchIn(text)

        private val BLOCKED_TERMS = listOf(
            "سکس", "پورن", "جنسی", "لخت", "برهنه", "عریان", "شهوانی", "اروتیک",
            "sex", "porn", "nude", "naked", "nsfw", "erotic", "xxx", "hentai"
        )

        fun containsBlockedContent(prompt: String): Boolean {
            val low = prompt.lowercase(Locale.ROOT)
            return BLOCKED_TERMS.any { low.contains(it) }
        }

        fun isLogoRequest(prompt: String): Boolean {
            val low = prompt.lowercase(Locale.ROOT)
            return Regex("(لوگو|آرم|نشان تجاری|برندینگ|logo|brand mark|emblem)").containsMatchIn(low)
        }
    }

    enum class IntelligenceLevel(val id: String, val labelFa: String, val labelEn: String) {
        DEEP_THINK("deep", "هوش عمیق (Deep Think)", "Deep Think"),
        FAST("fast", "پاسخ سریع (Fast)", "Fast")
    }

    private val deepModels = listOf(
        "deepseek/deepseek-r1:free",
        "deepseek/deepseek-chat-v3-0324:free",
        "qwen/qwen3-235b-a22b:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemma-3-27b-it:free",
        "openrouter/free",
        "openai"
    )

    private val fastModels = listOf(
        "google/gemma-3-27b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "deepseek/deepseek-chat-v3-0324:free",
        "openrouter/free",
        "openai"
    )

    private val maxHistory = 36
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Global AI",
            "وِکسا هوشمند",
            "You are Vexa AI — a high-IQ, structured, accurate multilingual assistant. Analyze carefully, verify facts, structure answers clearly, and respond fluently in the exact language of the user's message."
        ),
        TRADER(
            "Market Analyst",
            "تحلیلگر بازار",
            "You are Vexa AI in Market Analyst mode. Provide structured market analysis, scenarios, key levels, risk management, and clear caveats without financial guarantees."
        ),
        CODER(
            "Software Engineer",
            "مهندس نرم‌افزار",
            "You are Vexa AI in Software Engineer mode. Write clean, bug-free, production-ready code with clear architectural explanations and edge-case handling."
        ),
        TUTOR(
            "Language Tutor",
            "مترجم و مدرس زبان",
            "You are Vexa AI in Language Tutor mode. Translate and explain accurately with natural phrasing and helpful examples."
        )
    }

    data class AiResult(
        val text: String,
        val generatedImage: Bitmap? = null,
        val generatedFile: FileGenerator.GeneratedFile? = null
    )

    fun getGatewayUrl(): String {
        val custom = prefs.getString("gateway_url", "").orEmpty().trim()
        return custom.ifEmpty { DEFAULT_GATEWAY_URL }
    }

    fun getPersona(): Persona {
        val name = prefs.getString("persona", Persona.ASSISTANT.name) ?: Persona.ASSISTANT.name
        return try { Persona.valueOf(name) } catch (_: Exception) { Persona.ASSISTANT }
    }

    fun setPersona(p: Persona) = prefs.edit().putString("persona", p.name).apply()

    fun getIntelligenceLevel(): IntelligenceLevel {
        val saved = prefs.getString("iq_level", IntelligenceLevel.DEEP_THINK.id)
        return IntelligenceLevel.values().firstOrNull { it.id == saved } ?: IntelligenceLevel.DEEP_THINK
    }

    fun setIntelligenceLevel(level: IntelligenceLevel) =
        prefs.edit().putString("iq_level", level.id).apply()

    @Synchronized
    fun clear() = history.clear()

    @Synchronized
    fun restoreHistory(stored: List<ChatHistoryStore.StoredMessage>) {
        history.clear()
        for (m in stored.takeLast(maxHistory)) {
            if (m.senderName == "USER") history.add("user" to m.text)
            else if (m.senderName == "VEXA") history.add("model" to m.text)
        }
    }

    private fun craftRichVisualPrompt(rawUserRequest: String, isLogo: Boolean): String {
        val sys = if (isLogo) {
            "Convert the logo idea into a concise, clean English visual prompt for an AI image generator (minimalist vector icon, centered, solid background, high contrast). Output ONLY the English prompt."
        } else {
            "Expand and translate the user's image request into a vivid, detailed, photorealistic English prompt describing subject, composition, lighting, colors, atmosphere, and camera style. Output ONLY the English prompt."
        }
        for (m in listOf("google/gemma-3-27b-it:free", "meta-llama/llama-3.3-70b-instruct:free", "openai")) {
            try {
                val res = callGateway(m, rawUserRequest, null, sys, includeHistory = false, temperature = 0.5)
                    .replace("\"", "")
                    .replace("\n", " ")
                    .trim()
                if (res.length >= 4 && !isPersianText(res)) return res
            } catch (_: Exception) { }
        }
        return if (isLogo) {
            "minimalist modern vector logo icon, clean lines, professional graphic design, centered, solid background"
        } else {
            "$rawUserRequest, photorealistic, cinematic lighting, 8k resolution, sharp focus"
        }
    }

    @Synchronized
    fun generateImageOrLogo(rawPrompt: String, isFa: Boolean): AiResult {
        if (containsBlockedContent(rawPrompt)) {
            val msg = if (isFa) "امکان تولید تصویر با این موضوع وجود ندارد. سوژه مناسبی انتخاب کنید."
            else "Image generation for this topic is not allowed."
            return AiResult(msg)
        }
        val isLogo = isLogoRequest(rawPrompt)
        val richPromptEn = craftRichVisualPrompt(rawPrompt, isLogo)
        val seed = (System.currentTimeMillis() % 899999).toInt() + 1000
        val bmp = fetchBitmapMultiFallback(richPromptEn, rawPrompt, seed, isLogo, 0)

        val caption = if (isLogo) {
            if (isFa) "✒️ لوگو ساخته شد:\n«$rawPrompt»" else "✒️ Logo created:\n\"$rawPrompt\""
        } else {
            if (isFa) "🎨 تصویر ساخته شد:\n«$rawPrompt»" else "🎨 Image created:\n\"$rawPrompt\""
        }
        return AiResult(caption, generatedImage = bmp)
    }

    private fun tryFetchServerVideo(ctx: Context, promptEn: String): FileGenerator.GeneratedFile? {
        val base = getGatewayUrl().trimEnd('/')
        val enc = URLEncoder.encode(promptEn, "UTF-8")
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("$base/video?prompt=$enc").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.setRequestProperty("User-Agent", "VexaAI-Android/17.0")
            if (conn.responseCode == 200) {
                val cType = conn.contentType.orEmpty().lowercase(Locale.ROOT)
                if (cType.contains("video/mp4")) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > 4000) {
                        val outDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VexaVideos")
                        if (!outDir.exists()) outDir.mkdirs()
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val file = File(outDir, "Vexa_Cloud_${stamp}_${UUID.randomUUID().toString().take(6)}.mp4")
                        FileOutputStream(file).use { it.write(bytes) }
                        return FileGenerator.GeneratedFile(file, "video/mp4", file.name)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    @Synchronized
    fun generateVideo(ctx: Context, rawPrompt: String, isFa: Boolean): AiResult {
        if (containsBlockedContent(rawPrompt)) {
            val msg = if (isFa) "امکان ساخت ویدیو با این موضوع وجود ندارد."
            else "Video generation for this topic is not allowed."
            return AiResult(msg)
        }
        val baseEn = craftRichVisualPrompt(rawPrompt, isLogo = false)

        // اگر سرور خروجی مستقیم MP4 بدهد، همان را برمی‌گرداند
        tryFetchServerVideo(ctx, baseEn)?.let { cloudVideo ->
            val msg = if (isFa) "🎬 ویدیوی ساخته شده آماده است:\n«$rawPrompt»"
            else "🎬 Video ready:\n\"$rawPrompt\""
            return AiResult(msg, generatedFile = cloudVideo)
        }

        // در غیر این صورت، صحنه‌های سینمایی را گرفته و فایل MP4 می‌سازد
        val scenePrompts = listOf(
            "$baseEn, wide establishing cinematic shot, ultra detailed, 8k",
            "$baseEn, dynamic medium action shot, dramatic lighting, 8k",
            "$baseEn, dramatic close-up cinematic shot, depth of field, 8k"
        )
        val bitmaps = mutableListOf<Bitmap>()
        val baseSeed = (System.currentTimeMillis() % 899999).toInt() + 1000
        scenePrompts.forEachIndexed { idx, sp ->
            bitmaps.add(fetchBitmapMultiFallback(sp, rawPrompt, baseSeed + idx * 31, isLogo = false, sceneIdx = idx))
        }

        val vf = VideoGenerator.createCinematicMp4(ctx, bitmaps, rawPrompt)
        return if (vf != null) {
            val msg = if (isFa) {
                "🎬 ویدیوی سینمایی ${bitmaps.size * 2} ثانیه‌ای (MP4) ساخته شد:\n«$rawPrompt»\nبرای تماشا روی «پخش / باز کردن» بزنید."
            } else {
                "🎬 ${bitmaps.size * 2}s cinematic MP4 video ready:\n\"$rawPrompt\""
            }
            AiResult(msg, generatedImage = bitmaps.first(), generatedFile = vf)
        } else {
            AiResult(
                text = if (isFa) "🎬 فریم‌های ویدیو ساخته شد:\n«$rawPrompt»" else "🎬 Video frames ready:\n\"$rawPrompt\"",
                generatedImage = bitmaps.first()
            )
        }
    }

    private fun renderFallbackFrame(title: String, isLogo: Boolean, sceneIdx: Int): Bitmap {
        val w = 720
        val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colors = if (isLogo) {
            intArrayOf(Color.parseColor("#09090B"), Color.parseColor("#18181B"), Color.parseColor("#10A37F"))
        } else {
            when (sceneIdx % 3) {
                0 -> intArrayOf(Color.parseColor("#09090B"), Color.parseColor("#064E3B"), Color.parseColor("#10A37F"))
                1 -> intArrayOf(Color.parseColor("#09090B"), Color.parseColor("#1E3A8A"), Color.parseColor("#0284C7"))
                else -> intArrayOf(Color.parseColor("#09090B"), Color.parseColor("#4C1D95"), Color.parseColor("#7C3AED"))
            }
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colors, null, Shader.TileMode.CLAMP)
        })
        canvas.drawText(if (isLogo) "VEXA LOGO" else "VEXA STUDIO", 360f, 150f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        })
        canvas.drawText(title.take(36), 360f, 360f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        })
        return bmp
    }

    private fun fetchBitmapMultiFallback(
        promptEn: String,
        rawTitle: String,
        seed: Int,
        isLogo: Boolean,
        sceneIdx: Int
    ): Bitmap {
        val enc = URLEncoder.encode(promptEn, "UTF-8")
        val base = getGatewayUrl().trimEnd('/')
        val endpoints = listOf(
            "$base/image?prompt=$enc&seed=$seed",
            "https://image.pollinations.ai/prompt/$enc?width=1024&height=1024&seed=$seed&nologo=true&model=flux",
            "https://image.pollinations.ai/prompt/$enc?width=1024&height=1024&seed=${seed + 7}&nologo=true&model=turbo"
        )
        for (u in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 14000
                conn.readTimeout = 40000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                conn.setRequestProperty("Accept", "image/*,*/*")
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > 2500) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null && bmp.width >= 128 && bmp.height >= 128) return bmp
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }
        return renderFallbackFrame(rawTitle, isLogo, sceneIdx)
    }

    fun searchWebLive(query: String): String {
        val sb = StringBuilder()
        val base = getGatewayUrl().trimEnd('/')
        val enc = URLEncoder.encode(query, "UTF-8")
        val isFa = isPersianText(query)

        // ۱) جست‌وجو از مسیر /search ورکر
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$base/search?q=$enc").openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "VexaAI-Android/17.0")
            if (conn.responseCode == 200) {
                val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = JSONObject(raw).optJSONArray("results")
                if (arr != null && arr.length() > 0) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val title = item.optString("title", "").trim()
                        val snippet = item.optString("snippet", "").trim()
                        val link = item.optString("link", "").trim()
                        if (title.isNotEmpty()) {
                            sb.append("${i + 1}.$title\n")
                            if (snippet.isNotEmpty()) sb.append("   $snippet\n")
                            if (link.isNotEmpty()) sb.append("   Source: $link\n")
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }

        // ۲) پشتیبان مستقیم Wikipedia اگر ورکر نتیجه‌ای نداد
        if (sb.isEmpty()) {
            try {
                val lang = if (isFa) "fa" else "en"
                val wUrl = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$enc&format=json&srlimit=4"
                val wConn = URL(wUrl).openConnection() as HttpURLConnection
                wConn.connectTimeout = 10000
                wConn.readTimeout = 15000
                wConn.setRequestProperty("User-Agent", "VexaAI-Android/17.0")
                if (wConn.responseCode == 200) {
                    val wTxt = wConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val srArr = JSONObject(wTxt).optJSONObject("query")?.optJSONArray("search")
                    if (srArr != null) {
                        for (i in 0 until srArr.length()) {
                            val o = srArr.getJSONObject(i)
                            val title = o.optString("title", "")
                            val snip = o.optString("snippet", "").replace(Regex("<[^>]+>"), "").trim()
                            if (title.isNotEmpty()) {
                                sb.append("${i + 1}. $title (Wikipedia)\n$snip\n")
                            }
                        }
                    }
                }
                wConn.disconnect()
            } catch (_: Exception) { }
        }

        // ۳) پشتیبان نرخ جهانی طلا برای سوالات مرتبط با طلا/سکه/دلار
        val low = query.lowercase(Locale.ROOT)
        if (Regex("(طلا|gold|سکه|دلار|xau)").containsMatchIn(low) && !sb.contains("XAU/USD")) {
            try {
                val gConn = URL("https://data-asg.goldprice.org/dbXRates/USD").openConnection() as HttpURLConnection
                gConn.connectTimeout = 8000
                gConn.readTimeout = 10000
                gConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (gConn.responseCode == 200) {
                    val gTxt = gConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val item = JSONObject(gTxt).optJSONArray("items")?.optJSONObject(0)
                    val xau = item?.optDouble("xauPrice", 0.0) ?: 0.0
                    if (xau > 0.0) {
                        sb.append("- Global Spot Gold (XAU/USD): $").append(xau).append(" per troy oz\n")
                    }
                }
                gConn.disconnect()
            } catch (_: Exception) { }
        }

        return sb.toString().trim()
    }

    private fun shouldAutoSearchWeb(text: String): Boolean {
        val low = text.lowercase(Locale.ROOT)
        return Regex(
            "(جستجو|جست‌وجو|سرچ|گوگل|خبر|اخبار|امروز|قیمت|نرخ|دلار|طلا|سکه|بیت کوین|بیت‌کوین|ارز|آب و هوا|هواشناسی|نتیجه بازی|جدیدترین|آخرین|search|google|news|latest|today|price|weather|current|2025|2026)"
        ).containsMatchIn(low)
    }

    @Synchronized
    fun generateRawFileContent(ext: String, topic: String): String? {
        val sys = "Output ONLY clean, complete raw content for a .$ext file about the request. No markdown fences (```)."
        for (m in deepModels) {
            try {
                val res = callGateway(m, "Format: .$ext\nTopic: $topic", null, sys, includeHistory = false, temperature = 0.3)
                    .replace(Regex("^```[a-zA-Z]*\\n?"), "")
                    .replace(Regex("\\n?```$"), "")
                    .trim()
                if (res.isNotEmpty()) return res
            } catch (_: Exception) { }
        }
        return null
    }

    @Synchronized
    fun ask(
        text: String,
        image: Bitmap? = null,
        forceWebSearch: Boolean = false,
        forcePersona: Persona? = null,
        userDisplayName: String = ""
    ): AiResult {
        memory.autoLearnFromUserMessage(text)
        val isFa = isPersianText(text)
        val activePersona = forcePersona ?: getPersona()
        val iq = getIntelligenceLevel()
        val candidateModels = if (iq == IntelligenceLevel.DEEP_THINK) deepModels else fastModels
        val temp = if (iq == IntelligenceLevel.DEEP_THINK) 0.35 else 0.55

        val doSearch = (image == null) && (forceWebSearch || shouldAutoSearchWeb(text))
        val webContext = if (doSearch) searchWebLive(text) else ""

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (iq == IntelligenceLevel.DEEP_THINK) {
                append("\nThink step-by-step and verify logic carefully before writing the final answer. Present a clear, well-structured, comprehensive response.")
            } else {
                append("\nGive a direct, clear, and concise answer.")
            }
            if (userDisplayName.isNotBlank()) append("\nUser: $userDisplayName.")
            if (isFa) {
                append("\nReply clearly, accurately, and naturally in Persian. Never say you cannot browse or lack access when live data is provided below; use the provided data directly.")
            } else {
                append("\nDetect the user's language and reply fluently in that exact same language. Use provided live search results directly.")
            }
            if (webContext.isNotEmpty()) {
                append("\n\n[LIVE WEB & MARKET DATA]:\n")
                append(webContext)
                append("\nUse these live results directly to answer accurately.")
            }
            append(memory.asPromptContext())
        }

        var lastErr = ""
        for (m in candidateModels) {
            try {
                val raw = callGateway(m, text, image, sysPrompt, includeHistory = true, temperature = temp)
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim()
                if (raw.isNotEmpty()) {
                    val userLog = if (image != null) "[Image] $text" else text
                    history.add("user" to userLog)
                    history.add("model" to raw)
                    while (history.size > maxHistory) history.removeAt(0)
                    return AiResult(raw)
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }

        if (webContext.isNotEmpty()) {
            return AiResult(
                if (isFa) "نتایج زنده‌ یافت‌شده:\n\n$webContext" else "Live results found:\n\n$webContext"
            )
        }

        return AiResult(
            if (isFa) "ارتباط با سرور موقتاً قطع شد ($lastErr). اینترنت را بررسی کنید."
            else "Connection temporarily unavailable ($lastErr). Please check your network."
        )
    }

    private fun bitmapToDataUrl(bmp: Bitmap): String {
        val maxDim = 900
        val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        return "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callGateway(
        model: String,
        text: String,
        image: Bitmap?,
        systemInstruction: String,
        includeHistory: Boolean,
        temperature: Double = 0.4
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        if (includeHistory) {
            for ((role, t) in history) {
                messages.put(JSONObject().put("role", if (role == "model") "assistant" else "user").put("content", t))
            }
        }
        if (image != null) {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Analyze this image." }))
            arr.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", bitmapToDataUrl(image))))
            messages.put(JSONObject().put("role", "user").put("content", arr))
        } else {
            messages.put(JSONObject().put("role", "user").put("content", text))
        }

        val endpoints = listOf(getGatewayUrl(), FALLBACK_CLOUD_URL).distinct()
        var lastEx: Exception = IOException("Connection failed")

        for (ep in endpoints) {
            val effModel = if (ep.contains("pollinations.ai")) "openai" else model
            val body = JSONObject()
                .put("model", effModel)
                .put("temperature", temperature)
                .put("messages", messages)
            for (attempt in 1..2) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(ep).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Connection", "close")
                    conn.setRequestProperty("User-Agent", "VexaAI-Android/17.0")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val choices = JSONObject(resp).optJSONArray("choices") ?: throw IOException("Empty choices")
                    if (choices.length() == 0) throw IOException("No choice")
                    val content = choices.getJSONObject(0).getJSONObject("message").optString("content", "")
                    if (content.isNotBlank()) return content
                } catch (e: Exception) {
                    lastEx = e
                    try { Thread.sleep(350L) } catch (_: InterruptedException) { }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
        }
        throw lastEx
    }
}
