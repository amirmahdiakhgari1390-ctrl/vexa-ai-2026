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

    private enum class ImageIntent {
        SOLVE_QUESTIONS,
        OCR_EXTRACT,
        CODE_OR_TECH,
        VISUAL_ANALYSIS
    }

    private val deepModels = listOf(
        "google/gemma-3-27b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "deepseek/deepseek-chat-v3-0324:free",
        "openrouter/free",
        "openai"
    )

    private val fastModels = listOf(
        "google/gemma-3-27b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "openrouter/free",
        "openai"
    )

    // فقط مدل‌های سازگار با ورودی تصویر در اولویت بینایی قرار می‌گیرند
    private val visionModels = listOf(
        "google/gemma-3-27b-it:free",
        "openrouter/free",
        "openai"
    )

    private val maxHistory = 24
    private val history = ArrayList<Pair<String, String>>()

    enum class Persona(val titleEn: String, val titleFa: String, val instruction: String) {
        ASSISTANT(
            "Vexa Global AI",
            "وِکسا هوشمند",
            "You are Vexa AI — a clear, accurate, structured multilingual assistant. Respond fluently in the exact language of the user's message."
        ),
        TRADER(
            "Market Analyst",
            "تحلیلگر بازار",
            "You are Vexa AI in Market Analyst mode. Provide structured market analysis, key levels, risk management, and clear caveats."
        ),
        CODER(
            "Software Engineer",
            "مهندس نرم‌افزار",
            "You are Vexa AI in Software Engineer mode. Write clean, working code with clear explanations."
        ),
        TUTOR(
            "Language Tutor",
            "مترجم و مدرس زبان",
            "You are Vexa AI in Language Tutor mode. Translate and explain accurately with natural phrasing."
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
        return try {
            Persona.valueOf(name)
        } catch (_: Exception) {
            Persona.ASSISTANT
        }
    }

    fun setPersona(p: Persona) = prefs.edit().putString("persona", p.name).apply()

    fun getIntelligenceLevel(): IntelligenceLevel {
        val saved = prefs.getString("iq_level", IntelligenceLevel.FAST.id)
        return IntelligenceLevel.values().firstOrNull { it.id == saved } ?: IntelligenceLevel.FAST
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

    private fun detectImageIntent(prompt: String): ImageIntent {
        val low = prompt.lowercase(Locale.ROOT)
        return when {
            Regex("(جواب|پاسخ|حل|سوال|سؤال|تست|گزینه|مسئله|تمرین|برگه|answer|solve|question|quiz|test|homework)").containsMatchIn(low) ->
                ImageIntent.SOLVE_QUESTIONS
            Regex("(متن|بنویس|تایپ|استخراج|اسکن|ترجمه|ocr|read text|extract|transcribe|translate)").containsMatchIn(low) ->
                ImageIntent.OCR_EXTRACT
            Regex("(کد|برنامه|ارور|خطا|باگ|اسکریپت|فرمول|code|bug|error|script|formula)").containsMatchIn(low) ->
                ImageIntent.CODE_OR_TECH
            else ->
                ImageIntent.VISUAL_ANALYSIS
        }
    }

    private fun buildVisionDirective(intent: ImageIntent): String = when (intent) {
        ImageIntent.SOLVE_QUESTIONS ->
            "\n[VISION TASK: QUESTION SOLVER & OCR] First read every question, number, option, and diagram accurately from the image. Then answer each requested question clearly, numbering answers to match the questions, and explain steps concisely."
        ImageIntent.OCR_EXTRACT ->
            "\n[VISION TASK: OCR & TEXT EXTRACTION] Accurately read and transcribe all visible text, numbers, labels, and tables from the image in order without omitting words, then fulfill the user's instruction."
        ImageIntent.CODE_OR_TECH ->
            "\n[VISION TASK: CODE & TECHNICAL SCAN] Read all code lines, error messages, logs, or technical diagrams in the image accurately, identify issues/behavior, and provide clear, working solutions."
        ImageIntent.VISUAL_ANALYSIS ->
            "\n[VISION TASK: MULTIMODAL SCENE & OBJECT ANALYSIS] Inspect objects, visible text, context, and relationships in the image carefully and answer the user's request directly and clearly."
    }

    private fun craftRichVisualPrompt(rawUserRequest: String, isLogo: Boolean): String {
        val cleaned = rawUserRequest.trim()
        if (cleaned.isEmpty()) {
            return if (isLogo) "minimalist modern vector logo icon, clean lines"
            else "cinematic realistic photography, high detail, crisp focus"
        }
        if (isPersianText(cleaned)) {
            val sys = if (isLogo) {
                "Translate this logo request into a precise English prompt for an image generator. Keep the exact subject, text/initials, and colors requested. Output ONLY the English prompt."
            } else {
                "Translate this image description literally and accurately into English for an image generator. Preserve every object, color, theme, and detail requested without adding unrelated people or changing the subject. Output ONLY the English prompt."
            }
            for (m in listOf("google/gemma-3-27b-it:free", "meta-llama/llama-3.3-70b-instruct:free")) {
                try {
                    val tr = callGateway(m, cleaned, null, sys, includeHistory = false, temperature = 0.1)
                        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                        .replace(Regex("^[\"']|[\"']$"), "")
                        .trim()
                    if (tr.length >= 3) return tr
                } catch (_: Exception) {
                }
            }
        }
        return if (isLogo) {
            "$cleaned, minimalist modern vector logo icon, clean composition"
        } else {
            "$cleaned, realistic lighting, sharp focus, high detail"
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
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
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
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
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

        tryFetchServerVideo(ctx, baseEn)?.let { cloudVideo ->
            val msg = if (isFa) "🎬 ویدیوی ساخته شده آماده است:\n«$rawPrompt»"
            else "🎬 Video ready:\n\"$rawPrompt\""
            return AiResult(msg, generatedFile = cloudVideo)
        }

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
            "https://image.pollinations.ai/prompt/$enc?width=768&height=768&seed=$seed&nologo=true&model=flux",
            "https://image.pollinations.ai/prompt/$enc?width=768&height=768&seed=${seed + 13}&nologo=true&model=turbo"
        )
        for (u in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 18000
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
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        return renderFallbackFrame(rawTitle, isLogo, sceneIdx)
    }

    fun searchWebLive(query: String): String {
        val sb = StringBuilder()
        val base = getGatewayUrl().trimEnd('/')
        val enc = URLEncoder.encode(query, "UTF-8")
        val isFa = isPersianText(query)

        var conn: HttpURLConnection? = null
        try {
            conn = URL("$base/search?q=$enc").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 7000
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
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }

        if (sb.isEmpty()) {
            var wConn: HttpURLConnection? = null
            try {
                val lang = if (isFa) "fa" else "en"
                val wUrl = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$enc&format=json&srlimit=3"
                wConn = URL(wUrl).openConnection() as HttpURLConnection
                wConn.connectTimeout = 4500
                wConn.readTimeout = 5500
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
            } catch (_: Exception) {
            } finally {
                try {
                    wConn?.disconnect()
                } catch (_: Exception) {
                }
            }
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
        for (m in fastModels) {
            try {
                val res = callGateway(m, "Format: .$ext\nTopic: $topic", null, sys, includeHistory = false, temperature = 0.3)
                    .replace(Regex("^```[a-zA-Z]*\\n?"), "")
                    .replace(Regex("\\n?```$"), "")
                    .trim()
                if (res.isNotEmpty()) return res
            } catch (_: Exception) {
            }
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
        val imageIntent = if (image != null) detectImageIntent(text) else null
        val effectiveText = if (image != null && text.isBlank()) {
            "تمام متن‌ها، سوالات، گزینه‌ها، اعداد و جزئیات این عکس را با دقت بخوان و به هر سوال داخل آن پاسخ کامل و دقیق بده."
        } else {
            text
        }
        val isFa = isPersianText(effectiveText) || (image != null && text.isBlank())
        val activePersona = forcePersona ?: getPersona()
        val iq = getIntelligenceLevel()
        val candidateModels = if (image != null) {
            visionModels
        } else if (iq == IntelligenceLevel.DEEP_THINK) {
            deepModels
        } else {
            fastModels
        }
        val temp = if (image != null) 0.2 else if (iq == IntelligenceLevel.DEEP_THINK) 0.35 else 0.5

        val doSearch = (image == null) && (forceWebSearch || shouldAutoSearchWeb(effectiveText))
        val webContext = if (doSearch) searchWebLive(effectiveText) else ""

        val sysPrompt = buildString {
            append(activePersona.instruction)
            if (image != null && imageIntent != null) {
                append(buildVisionDirective(imageIntent))
            }
            if (userDisplayName.isNotBlank()) append("\nUser: $userDisplayName.")
            if (isFa) {
                append("\nReply clearly, accurately, and naturally in Persian.")
            } else {
                append("\nDetect the user's language and reply fluently in that exact same language.")
            }
            if (webContext.isNotEmpty()) {
                append("\n\n[LIVE WEB DATA]:\n")
                append(webContext)
            }
            append(memory.asPromptContext())
        }

        var lastErr = ""
        for (m in candidateModels) {
            try {
                val raw = callGateway(m, effectiveText, image, sysPrompt, includeHistory = true, temperature = temp)
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim()
                if (raw.isNotEmpty()) {
                    val userLog = if (image != null) "[Image] $effectiveText" else effectiveText
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
        val maxDim = 1024
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
            for ((role, t) in history.takeLast(8)) {
                messages.put(JSONObject().put("role", if (role == "model") "assistant" else "user").put("content", t))
            }
        }
        if (image != null) {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Analyze this image and answer any questions in it." }))
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

            var conn: HttpURLConnection? = null
            try {
                conn = URL(ep).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
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
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        throw lastEx
    }
}
