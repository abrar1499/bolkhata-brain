package com.abrar.gupshup

import android.content.Context
import android.content.Intent
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class Msg(val role: String, var text: String)   // role: user | bot | note

/**
 * Everything that must survive the screen going away (WhatsApp, home button, rotation):
 * the model, the chat, downloads and replies. Lives as long as the app process; the KeepAlive
 * foreground service keeps that process alive. The chat is also saved to disk.
 */
object Brain {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var app: Context
    val status = MutableStateFlow("Gup Shup shuru ho raha hai...")
    val ready = MutableStateFlow(false)
    /** -1 = new messages / stream text changed, -2 = full reset, i >= 0 = message i changed */
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 256)
    val messages = ArrayList<Msg>()
    @Volatile var streaming: String? = null
    @Volatile var busy = false
    var activity: WeakReference<MainActivity>? = null
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var replyJob: Job? = null
    private var started = false

    val prefs by lazy { app.getSharedPreferences("gupshup", Context.MODE_PRIVATE) }
    val gh by lazy { GitHub(prefs.getString(KEY_TOKEN, null)) }
    val hasModel get() = engine != null
    /** Fast mode = no model tools (shorter prompt, quicker replies). Buttons (▶ Chalao, 📦, 💾) work either way. */
    var toolsOn: Boolean
        get() = prefs.getBoolean("tools_on", false)
        set(v) { prefs.edit().putBoolean("tools_on", v).apply(); engine?.let { e -> scope.launch { runCatching { conversation?.close() }; conversation = newConversation(e) } } }
    private var backendUsed = ""
    private var modelName = ""

    fun init(ctx: Context) {
        if (started) return
        started = true; app = ctx.applicationContext
        restoreChat()
        val saved = prefs.getString(KEY_MODEL, null)?.let { File(it) }?.takeIf { it.exists() && it.length() > 100_000_000 }
        if (saved != null) load(saved)
    }

    // ---------------- chat history ----------------
    private val chatFile get() = File(app.filesDir, "chat.json")

    private fun restoreChat() {
        runCatching {
            val a = JSONArray(chatFile.readText())
            synchronized(messages) { for (i in 0 until a.length()) a.getJSONObject(i).let { messages += Msg(it.getString("r"), it.getString("t")) } }
        }
    }

    private fun saveChat() {
        val a = JSONArray()
        synchronized(messages) { messages.takeLast(200).forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) } }
        runCatching { chatFile.writeText(a.toString()) }
    }

    fun add(role: String, text: String): Int {
        val i = synchronized(messages) { messages += Msg(role, text); messages.size - 1 }
        saveChat(); events.tryEmit(-1); return i
    }

    fun change(i: Int, text: String) {
        synchronized(messages) { messages.getOrNull(i)?.text = text } ; saveChat(); events.tryEmit(i)
    }

    fun note(t: String) = add("note", t)

    fun clearChat() {
        if (busy) return
        synchronized(messages) { messages.clear() }; saveChat(); events.tryEmit(-2)
        val e = engine ?: return
        scope.launch { runCatching { conversation?.close() }; conversation = newConversation(e); note("Nayi baat-cheet shuru 🙂") }
    }

    private fun keepAlive(text: String) {
        val i = Intent(app, KeepAlive::class.java).putExtra("text", text)
        runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i) }
    }

    fun shutdown() {
        replyJob?.cancel()
        runCatching { conversation?.close() }; runCatching { engine?.close() }
        conversation = null; engine = null
    }

    // ---------------- download (parallel, resumable) ----------------
    fun download(tag: String, name: String) {
        if (busy) return
        busy = true; ready.value = false
        keepAlive("Model download ho raha hai...")
        status.value = "Download shuru... (WiFi aur charger behtar hai)"
        scope.launch {
            val dir = File(app.filesDir, "models").also { it.mkdirs() }
            val target = File(dir, name); val part = File(dir, "$name.part"); val doneFile = File(dir, "$name.done")
            try {
                if (!target.exists()) {
                    val parts = GitHub(null).releaseAssetSizes(MODEL_REPO, tag)
                    if (parts.isEmpty()) error("model files nahi mili")
                    val total = parts.sumOf { it.second }
                    data class Chunk(val i: Int, val url: String, val from: Long, val len: Long, val at: Long)
                    val chunks = ArrayList<Chunk>(); var base = 0L
                    for ((u, size) in parts) {
                        var o = 0L
                        while (o < size) { val l = minOf(CHUNK, size - o); chunks += Chunk(chunks.size, u, o, l, base + o); o += l }
                        base += size
                    }
                    val sig = "$tag:$total:${chunks.size}"
                    val finished = HashSet<Int>()
                    if (part.exists() && part.length() == total && doneFile.exists()) {
                        val lines = doneFile.readLines()
                        if (lines.firstOrNull() == sig) lines.drop(1).mapNotNullTo(finished) { it.toIntOrNull() }
                    }
                    if (finished.isEmpty()) { part.delete(); RandomAccessFile(part, "rw").use { it.setLength(total) }; doneFile.writeText("$sig\n") }
                    val got = AtomicLong(chunks.filter { it.i in finished }.sumOf { it.len })
                    val startGot = got.get(); val t0 = System.currentTimeMillis()
                    val ui = launch {
                        while (true) {
                            val d = got.get(); val secs = (System.currentTimeMillis() - t0) / 1000.0
                            val speed = if (secs > 1) (d - startGot) / secs else 0.0
                            val eta = if (speed > 0) ((total - d) / speed).toLong() else -1
                            status.value = "Download: ${d * 100 / total}%  (${d shr 20}/${total shr 20} MB)  " +
                                "%.1f MB/s".format(speed / 1048576) + (if (eta >= 0) "  · ${eta / 60}m ${eta % 60}s baqi" else "")
                            delay(700)
                        }
                    }
                    val gate = Semaphore(PARALLEL)
                    try {
                        coroutineScope {
                            chunks.filter { it.i !in finished }.map { c ->
                                async {
                                    gate.withPermit {
                                        var pos = 0L; var tries = 0
                                        while (pos < c.len) {
                                            try {
                                                val conn = (URL(resolve(c.url)).openConnection() as HttpURLConnection).apply {
                                                    connectTimeout = 20_000; readTimeout = 30_000
                                                    setRequestProperty("Range", "bytes=${c.from + pos}-${c.from + c.len - 1}")
                                                }
                                                if (conn.responseCode != 206) error("HTTP ${conn.responseCode}")
                                                RandomAccessFile(part, "rw").use { raf ->
                                                    raf.seek(c.at + pos)
                                                    conn.inputStream.use { inp ->
                                                        val buf = ByteArray(512 * 1024)
                                                        while (pos < c.len) {
                                                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), c.len - pos).toInt()); if (n < 0) break
                                                            raf.write(buf, 0, n); pos += n; got.addAndGet(n.toLong())
                                                        }
                                                    }
                                                }
                                                conn.disconnect()
                                            } catch (e: Exception) {
                                                if (++tries > 8) throw e
                                                delay(2000L * tries)
                                            }
                                        }
                                        synchronized(doneFile) { doneFile.appendText("${c.i}\n") }
                                    }
                                }
                            }.awaitAll()
                        }
                    } finally { ui.cancel() }
                    part.renameTo(target); doneFile.delete()
                }
                busy = false; load(target)
            } catch (e: Exception) {
                busy = false
                status.value = "Download ruk gaya (${e.message}). 🧠 Model dabayein — jahan ruka tha wahin se chalega."
            }
        }
    }

    private fun resolve(url: String): String {
        var u = url
        repeat(5) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; requestMethod = "HEAD"; connectTimeout = 20_000 }
            val code = c.responseCode; val loc = c.getHeaderField("Location"); c.disconnect()
            if (code in 300..399 && loc != null) u = loc else return u
        }
        return u
    }

    fun importFile(open: () -> java.io.InputStream?) {
        busy = true; status.value = "File copy ho rahi hai..."
        scope.launch {
            try {
                val t = File(File(app.filesDir, "models").also { it.mkdirs() }, "custom-${System.currentTimeMillis()}.litertlm")
                open()?.use { i -> FileOutputStream(t).use { i.copyTo(it) } } ?: error("file nahi khuli")
                busy = false; load(t)
            } catch (e: Exception) { busy = false; status.value = "File load nahi hui (${e.message})." }
        }
    }

    // ---------------- model ----------------
    fun load(file: File) {
        busy = true; ready.value = false
        keepAlive("Model load ho raha hai...")
        status.value = "Model load ho raha hai... (pehli dafa 30 second – 2 minute)"
        scope.launch {
            shutdown()
            var used = "GPU"
            val eng = try {
                Engine(EngineConfig(modelPath = file.path, backend = Backend.GPU(), cacheDir = app.cacheDir.path)).also { it.initialize() }
            } catch (e: Throwable) {
                used = "CPU"
                try { Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = app.cacheDir.path)).also { it.initialize() } }
                catch (e2: Throwable) {
                    busy = false; status.value = "Model load nahi hua (${e2.message}). 🧠 Model dabayein."; return@launch
                }
            }
            engine = eng; conversation = newConversation(eng); backendUsed = used; modelName = file.name.substringBefore('.')
            prefs.edit().putString(KEY_MODEL, file.path).apply()
            busy = false
            showReady("")
            ready.value = true
            keepAlive("Model tayyar hai, baat-cheet mehfooz hai")
            if (synchronized(messages) { messages.isEmpty() })
                note("Assalam o Alaikum! Main Gup Shup hoon 🙂\nGup shup, Python code (▶ chala bhi sakta hoon), HTML apps (📦 APK bhi), aur 🐙 GitHub — sab poocho!")
        }
    }

    private fun newConversation(eng: Engine): Conversation = eng.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            tools = if (toolsOn) listOf(tool(AppTools())) else emptyList(),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
    )

    private fun showReady(extra: String) {
        status.value = "✅ $modelName ($backendUsed)" + (if (toolsOn) " · 🛠 tools" else " · ⚡ tez") +
            (if (backendUsed == "CPU") " · GPU nahi chala, is liye slow" else "") + extra +
            (prefs.getString(KEY_LOGIN, null)?.let { " · 🐙 $it" } ?: "")
    }

    // ---------------- reply ----------------
    fun send(msg: String) {
        add("user", msg)
        localReply(msg)?.let { add("bot", it); return }
        val conv = conversation ?: return
        ready.value = false; busy = true; streaming = "…"; events.tryEmit(-1)
        keepAlive("Jawab likh raha hoon...")
        val t0 = System.currentTimeMillis()
        status.value = "✍️ soch raha hoon..."
        replyJob = scope.launch {
            val sb = StringBuilder()
            var first = 0L
            try {
                conv.sendMessageAsync(msg).collect { part ->
                    if (first == 0L) first = System.currentTimeMillis() - t0
                    sb.append(part.toString()); streaming = sb.toString().trim().ifEmpty { "…" }; events.tryEmit(-1)
                }
                streaming = null; add("bot", sb.toString().trim().ifEmpty { "Hmm 🤔 dobara poocho?" })
            } catch (e: Throwable) {
                streaming = null
                add("bot", (sb.toString().trim() + "\n\n(Masla aa gaya: ${e.message}. Dobara koshish karein.)").trim())
                val eng = engine
                if (eng != null) runCatching { conversation?.close(); conversation = newConversation(eng) }
            } finally {
                busy = false; ready.value = engine != null
                if (engine != null) showReady(" · jawab ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s (shuru ${"%.1f".format(first / 1000.0)}s)")
                keepAlive("Model tayyar hai, baat-cheet mehfooz hai")
            }
        }
    }

    // ---------------- Python ----------------
    fun runPython(code: String): String {
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        return Python.getInstance().getModule("runner").callAttr("run", code, 20).toString()
    }

    // ---------------- APK from HTML ----------------
    fun buildApk(name: String, html: String) {
        val i = note("📦 \"$name\" ki APK ban rahi hai...")
        scope.launch {
            try {
                val repo = gh.startHtmlApk(name, html)
                change(i, "📦 Repo bani: $repo\nGitHub APK bana raha hai (3–6 minute)...")
                val until = System.currentTimeMillis() + 15 * 60_000
                while (System.currentTimeMillis() < until) {
                    delay(15_000)
                    val link = gh.apkLink(repo)
                    if (link != null) { change(i, "✅ APK tayyar: $name\n$link"); return@launch }
                    val st = gh.buildStatus(repo)
                    if (st.startsWith("completed:") && st != "completed:success") { change(i, "❌ Build fail hua ($st).\nhttps://github.com/$repo/actions"); return@launch }
                    change(i, "📦 $repo\nBuild: $st ...")
                }
                change(i, "⏳ Build lamba ho gaya. Yahan dekhein: https://github.com/$repo/actions")
            } catch (e: Exception) { change(i, "APK nahi bani: ${e.message}") }
        }
    }

    // ---------------- tools for the model ----------------
    class AppTools : ToolSet {
        @Tool(description = "Run Python 3 code on the phone and return its printed output or error. Use print() to show results.")
        fun run_python(@ToolParam(description = "Complete Python 3 code to run") code: String): String {
            note("🛠 Python chala raha hoon...")
            return try { runPython(code) } catch (e: Throwable) { "Error: ${e.message}" }
        }

        @Tool(description = "List the user's GitHub repositories.")
        fun github_list_repos(): String =
            if (gh.token == null) "GitHub jura nahi. User ko 🐙 GitHub button dabane ko kaho." else runCatching { gh.listRepos().joinToString("\n") }.getOrElse { "Error: ${it.message}" }

        @Tool(description = "List files in a folder of a GitHub repository.")
        fun github_list_files(@ToolParam(description = "owner/repo") repo: String, @ToolParam(description = "folder path, empty for root") path: String): String =
            if (gh.token == null) "GitHub jura nahi." else runCatching { gh.listFiles(repo, path) }.getOrElse { "Error: ${it.message}" }

        @Tool(description = "Read a text file from a GitHub repository.")
        fun github_read_file(@ToolParam(description = "owner/repo") repo: String, @ToolParam(description = "file path") path: String): String =
            if (gh.token == null) "GitHub jura nahi." else runCatching { gh.readFile(repo, path).take(6000) }.getOrElse { "Error: ${it.message}" }

        @Tool(description = "Create or update a file in a GitHub repository. The app asks the user for approval first.")
        fun github_write_file(
            @ToolParam(description = "owner/repo") repo: String,
            @ToolParam(description = "file path") path: String,
            @ToolParam(description = "full new file content") content: String,
            @ToolParam(description = "short commit message") message: String,
        ): String {
            if (gh.token == null) return "GitHub jura nahi."
            val act = activity?.get() ?: return "User abhi app me nahi, baad me poochna."
            if (!act.confirm("💾 GitHub me likhna hai?", "$repo/$path\n\n${content.take(800)}")) return "User ne mana kar diya."
            return runCatching { gh.writeFile(repo, path, content, message) }.getOrElse { "Error: ${it.message}" }
        }
    }

    private fun localReply(msg: String): String? {
        val l = msg.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ")
        if (Regex("khud ?kushi|suicide|mar+ ?jana|marna chaht|je+ne ka dil nahi|jeena nahi chaht|zindagi khatam|khud ko nuqsan|apni jaan").containsMatchIn(l)) return SELF_HARM
        if (Regex("\\b(marta|maarta|marti|maarti|marte|maarte|peet|peetta|pitai|zulm karta|tashaddud|abuse)\\b|haath uthat").containsMatchIn(l)) return ABUSE
        val now = Calendar.getInstance()
        if (l.split(" ").size <= 6) {
            if (Regex("\\b(time|wa?qt|baje)\\b").containsMatchIn(l) && Regex("\\b(kya|kia|kitne|batao|hua|ho)\\b").containsMatchIn(l))
                return "Abhi " + SimpleDateFormat("h:mm a", Locale.US).format(now.time) + " baje hain 🕐"
            if (Regex("\\b(tar(e+|i)kh|date)\\b").containsMatchIn(l)) return "Aaj " + SimpleDateFormat("d MMMM yyyy", Locale.US).format(now.time) + " hai 📅"
            if (Regex("(kaun|kon|konsa|kaunsa) ?sa din|kya din hai").containsMatchIn(l)) return "Aaj " + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + " hai 📆"
        }
        return null
    }

    const val KEY_MODEL = "model_path_v4"
    const val KEY_TOKEN = "gh_token"
    const val KEY_LOGIN = "gh_login"
    const val KEY_CLIENT_ID = "gh_client_id"
    const val KEY_LAST_REPO = "gh_last_repo"
    private const val MODEL_REPO = "abrar1499/bolkhata-brain"
    private const val CHUNK = 32L * 1024 * 1024
    private const val PARALLEL = 8
    private val SYSTEM_PROMPT = """
Tum "Gup Shup" ho, dostana aur maahir assistant, phone par bina internet. Hamesha Roman Urdu me jawab do (jab tak user English/Arabic na likhe). Jawab chhota aur kaam ka. Apna naam mat likho.
Code hamesha ```python ya ```html block me, poora aur chalne wala, aur ``` se band karo. Code ke saath wazahat 1-2 jumle. Python me sirf standard library, input() nahi, print() se nateeja. "App banao" par ek file ka mukammal mobile HTML.
Sanjeeda baat par mazaq nahi. Mausam, khabrein ya taaza maloomat tumhein nahi pata, imaandari se batao.
""".trim()
    private val DAYS = arrayOf("Itwaar", "Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta")
    private const val SELF_HARM =
        "Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo (Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai. Tumhari zindagi bohat qeemti hai."
    private const val ABUSE =
        "Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Kisi ko bhi aap par haath uthane ka haq nahi. Agar aap is waqt khatre me hain to foran police ko call karein (Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. Kisi bharose ke rishtedaar ya dost ko bhi zaroor batayein. Kya aap is waqt mehfooz hain?"
}
