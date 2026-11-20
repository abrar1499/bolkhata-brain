package com.abrar.gupshup

import android.content.Context
import android.content.Intent
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
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

data class Msg(val role: String, var text: String, var info: String = "", var art: String = "", var img: String = "")

/** A saved piece of work (app, page, script) with all its versions. */
data class Artifact(val id: Int, var title: String, val lang: String, val versions: MutableList<String>)   // role: user | bot | note

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
        set(v) { prefs.edit().putBoolean("tools_on", v).apply(); resetConversation() }
    private var backendUsed = ""
    private var modelName = ""

    fun init(ctx: Context) {
        if (started) return
        started = true; app = ctx.applicationContext
        restoreChat(); restoreArts()
        val saved = prefs.getString(KEY_MODEL, null)?.let { File(it) }?.takeIf { it.exists() && it.length() > 100_000_000 }
        if (saved != null) load(saved)
    }

    // ---------------- artifacts ----------------
    val artifacts = ArrayList<Artifact>()
    @Volatile var currentArt = -1
    private val artFile get() = File(app.filesDir, "artifacts.json")

    private fun restoreArts() {
        runCatching {
            val o = JSONObject(artFile.readText()); currentArt = o.optInt("cur", -1)
            val a = o.getJSONArray("list")
            synchronized(artifacts) {
                for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                    val v = j.getJSONArray("v")
                    artifacts += Artifact(j.getInt("id"), j.getString("t"), j.getString("l"), (0 until v.length()).map { v.getString(it) }.toMutableList())
                }
            }
        }
    }

    private fun saveArts() {
        val a = JSONArray()
        synchronized(artifacts) { artifacts.takeLast(60).forEach { x ->
            a.put(JSONObject().put("id", x.id).put("t", x.title).put("l", x.lang).put("v", JSONArray(x.versions.takeLast(15))))
        } }
        runCatching { artFile.writeText(JSONObject().put("cur", currentArt).put("list", a).toString()) }
    }

    fun art(id: Int): Artifact? = synchronized(artifacts) { artifacts.firstOrNull { it.id == id } }
    fun artList(): List<Artifact> = synchronized(artifacts) { artifacts.reversed().map { it.copy(versions = it.versions.toMutableList()) } }

    private fun titleOf(code: String, lang: String, fallback: String): String =
        Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(code)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.take(40)
            ?: fallback.replace(Regex("[^\\p{L}\\p{N} ]"), " ").trim().split(Regex("\\s+")).take(5).joinToString(" ").ifBlank { lang.ifBlank { "Code" } }

    private fun newArtifact(lang: String, code: String, ask: String): Artifact {
        val a = synchronized(artifacts) {
            Artifact((artifacts.maxOfOrNull { it.id } ?: 0) + 1, titleOf(code, lang, ask), lang, mutableListOf(code)).also { artifacts += it }
        }
        currentArt = a.id; saveArts(); return a
    }

    private fun addVersion(a: Artifact, code: String): Int {
        synchronized(artifacts) { a.versions += code; titleOf(code, a.lang, "").takeIf { code.contains("<title>", true) }?.let { a.title = it } }
        currentArt = a.id; saveArts(); return a.versions.size
    }

    /** Longest fenced code block in a reply: (lang, code) or null. */
    private fun mainCode(txt: String): Pair<String, String>? {
        val re = Regex("```[ \\t]*([\\w+#.-]*)[^\\n]*\\n([\\s\\S]*?)(?:\\n?[ \\t]*```|\\z)")
        val best = re.findAll(txt).maxByOrNull { it.groupValues[2].length } ?: return null
        val code = best.groupValues[2].trimEnd(); if (code.length < 120) return null
        var lang = best.groupValues[1].lowercase()
        if (lang.isBlank()) lang = if (code.trimStart().startsWith("<")) "html" else if (Regex("^\\s*(def |import |print\\()", RegexOption.MULTILINE).containsMatchIn(code)) "python" else ""
        return lang to code
    }

    private val PATCH = Regex("<{5,}[^\\n]*\\n([\\s\\S]*?)\\n?={5,}[^\\n]*\\n([\\s\\S]*?)\\n?>{5,}[^\\n]*")

    /** Apply DHOONDO/BADLO blocks. Exact match first, then line match ignoring spaces. Returns (newCode, applied, failed). */
    private fun applyPatches(code: String, txt: String): Triple<String, Int, Int> {
        var cur = code; var ok = 0; var bad = 0
        for (m in PATCH.findAll(txt)) {
            fun noFence(x: String) = x.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n")
            val find = noFence(m.groupValues[1]).trimEnd('\n'); val repl = noFence(m.groupValues[2]).trimEnd('\n')
            if (find.isBlank()) { bad++; continue }
            if (cur.contains(find)) { cur = cur.replaceFirst(find, repl); ok++; continue }
            val lines = cur.lines(); val want = find.lines().map { it.trim() }.filter { it.isNotEmpty() }
            var hit = -1; var end = -1
            outer@ for (i in lines.indices) {
                var j = i; var k = 0
                while (j < lines.size && k < want.size) {
                    val t = lines[j].trim()
                    if (t.isEmpty()) { j++; continue }
                    if (t.replace(Regex("\\s+"), "") != want[k].replace(Regex("\\s+"), "")) continue@outer
                    j++; k++
                }
                if (k == want.size) { hit = i; end = j; break }
            }
            if (hit < 0) { bad++; continue }
            cur = (lines.subList(0, hit) + (if (repl.isEmpty()) emptyList() else repl.lines()) + lines.subList(end, lines.size)).joinToString("\n"); ok++
        }
        return Triple(cur, ok, bad)
    }

    private fun editPrompt(a: Artifact, ask: String): String = """Tum ek file me tabdeeli kar rahe ho. Kaam: $ask

Qawaid:
- Poori file dobara MAT likho.
- Pehle 1 jumla: kya badla.
- Phir har tabdeeli ka ek chhota block. DHOONDO me file ki sirf wo 1-6 lines jo badalni hain (bilkul waisi hi), BADLO me un ki nayi shakal. Line hatani ho to BADLO khaali chhod do.
- ``` mat lagao.

Misaal (agar title badalna ho):
Title badal diya.
<<<<<<< DHOONDO
<title>Purana</title>
=======
<title>Naya</title>
>>>>>>> BADLO

FILE:
${a.versions.last()}"""

    /** Did the user actually ask for code / an app / a design? */
    private val CODE_WORDS = Regex("(banao|bana do|bana k|bana ke|banay|banaye|bnao|bna do|code|coding|app\\b|game|website|web ?page|page bana|html|css|javascript|\\bjs\\b|python|script|program|calculator|design|function|kotlin|java\\b|sql|tool|form|card bana|dikhao code|likh ?do code|build|make|create|write)", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("```[\\s\\S]*?(```|\\z)")

    private val EDIT_WORDS = Regex("\\b(is ?me|isme|isko|is ko|ise|usme|us me|isay|badlo|badal|change|add|daal|dal do|lagao|hatao|remove|theek|thik|fix|rang|color|colour|bara|chhota|chota|update|edit|behtar|improve|aur (button|feature))\\b", RegexOption.IGNORE_CASE)
    private val NEW_WORDS = Regex("\\b(naya|nayi|new|alag|ek aur|dusra|doosra)\\b", RegexOption.IGNORE_CASE)

    /** Ask the model to change an artifact with small edits instead of rewriting it. */
    fun edit(artId: Int, ask: String, shown: String? = ask, round: Int = 0) {
        val a = art(artId) ?: return send(ask)
        if (shown != null) add("user", shown) else note("✏️ ${a.title} me tabdeeli…")
        send(editPrompt(a, ask), hidden = true, round = round, editArt = artId, fresh = true)
    }

    // ---------------- chats (many conversations, like the Claude app) ----------------
    data class ChatInfo(val id: String, var title: String, var updated: Long)
    private val chatsDir get() = File(app.filesDir, "chats").also { it.mkdirs() }
    private val indexFile get() = File(app.filesDir, "chats.json")
    val chats = ArrayList<ChatInfo>()
    @Volatile var chatId = ""

    private fun restoreChat() {
        runCatching {
            val a = JSONArray(indexFile.readText())
            synchronized(chats) { for (i in 0 until a.length()) a.getJSONObject(i).let { chats += ChatInfo(it.getString("id"), it.getString("t"), it.getLong("u")) } }
        }
        // migrate the old single chat
        val old = File(app.filesDir, "chat.json")
        if (old.exists() && synchronized(chats) { chats.isEmpty() }) {
            val id = "c" + System.currentTimeMillis()
            runCatching { old.copyTo(File(chatsDir, "$id.json"), true); old.delete() }
            synchronized(chats) { chats += ChatInfo(id, "Purani baat-cheet", System.currentTimeMillis()) }
            saveIndex()
        }
        chatId = prefs.getString("cur_chat", null)?.takeIf { id -> synchronized(chats) { chats.any { it.id == id } } }
            ?: synchronized(chats) { chats.maxByOrNull { it.updated }?.id } ?: newChatId()
        loadMessages(chatId)
    }

    private fun newChatId(): String {
        val id = "c" + System.currentTimeMillis()
        synchronized(chats) { chats += ChatInfo(id, "Nayi baat-cheet", System.currentTimeMillis()) }
        saveIndex(); return id
    }

    private fun loadMessages(id: String) {
        synchronized(messages) {
            messages.clear()
            runCatching {
                val a = JSONArray(File(chatsDir, "$id.json").readText())
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    messages += Msg(it.getString("r"), it.getString("t"), it.optString("i", ""), it.optString("a", ""), it.optString("m", ""))
                }
            }
        }
        prefs.edit().putString("cur_chat", id).apply()
    }

    private fun saveIndex() {
        val a = JSONArray()
        synchronized(chats) { chats.sortedByDescending { it.updated }.take(200).forEach { a.put(JSONObject().put("id", it.id).put("t", it.title).put("u", it.updated)) } }
        runCatching { indexFile.writeText(a.toString()) }
    }

    private fun saveChat() {
        val a = JSONArray()
        var firstUser: String? = null
        synchronized(messages) {
            messages.takeLast(300).forEach { a.put(JSONObject().put("r", it.role).put("t", it.text).put("i", it.info).put("a", it.art).put("m", it.img)) }
            firstUser = messages.firstOrNull { it.role == "user" }?.text
        }
        runCatching { File(chatsDir, "$chatId.json").writeText(a.toString()) }
        synchronized(chats) {
            chats.firstOrNull { it.id == chatId }?.let { c ->
                c.updated = System.currentTimeMillis()
                if (c.title == "Nayi baat-cheet" || c.title == "Purani baat-cheet") firstUser?.let { t ->
                    c.title = t.lines().first().removePrefix("📎").trim().take(38).ifBlank { c.title }
                }
            }
        }
        saveIndex()
    }

    fun chatList(): List<ChatInfo> = synchronized(chats) { chats.sortedByDescending { it.updated }.map { it.copy() } }

    fun newChat() {
        if (busy) return
        if (synchronized(messages) { messages.none { it.role == "user" } }) { events.tryEmit(-2); return }
        chatId = newChatId(); loadMessages(chatId); events.tryEmit(-2)
        resetConversation(replayUpTo = 0)
    }

    fun switchChat(id: String) {
        if (busy || id == chatId) return
        chatId = id; loadMessages(id); events.tryEmit(-2)
        resetConversation()
    }

    fun deleteChat(id: String) {
        if (busy) return
        synchronized(chats) { chats.removeAll { it.id == id } }
        runCatching { File(chatsDir, "$id.json").delete() }
        saveIndex()
        if (id == chatId) { chatId = synchronized(chats) { chats.maxByOrNull { it.updated }?.id } ?: newChatId(); loadMessages(chatId); events.tryEmit(-2); resetConversation() }
    }

    fun renameChat(id: String, title: String) {
        synchronized(chats) { chats.firstOrNull { it.id == id }?.title = title.take(40) }; saveIndex()
    }

    /** Recreate the model conversation, giving it the earlier messages so it remembers the chat. */
    private fun resetConversation(replayUpTo: Int = Int.MAX_VALUE) {
        val e = engine ?: return
        scope.launch { runCatching { conversation?.close() }; conversation = newConversation(e, replayUpTo) }
    }

    /** Last few turns as model history (user/model alternating, text only). */
    private fun history(upTo: Int): List<Message> {
        val turns = synchronized(messages) { messages.take(minOf(upTo, messages.size)).filter { it.role == "user" || it.role == "bot" }.map { it.copy() } }
        val out = ArrayList<Pair<String, String>>()
        for (m in turns.takeLast(8)) {
            // long answers (code) are cut short: the model only needs the gist to keep the conversation going
            val t = if (m.text.length > 700) m.text.take(500) + "\n…" else m.text
            if (out.isNotEmpty() && out.last().first == m.role) out[out.size - 1] = m.role to (out.last().second + "\n" + t) else out += m.role to t
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        if (out.isNotEmpty() && out.last().first == "user") out.removeAt(out.size - 1)
        return out.map { (r, t) -> if (r == "user") Message.user(t) else Message.model(t) }
    }

    /** 🔁 Write the last answer again. */
    fun regenerate() {
        if (busy || engine == null) return
        val lastUser = synchronized(messages) { messages.indexOfLast { it.role == "user" } }
        if (lastUser < 0) return
        val m = synchronized(messages) { messages[lastUser].copy() }
        synchronized(messages) { while (messages.size > lastUser + 1) messages.removeAt(messages.size - 1) }
        saveChat(); events.tryEmit(-2)
        val e = engine ?: return
        scope.launch {
            runCatching { conversation?.close() }; conversation = newConversation(e, lastUser)
            send(m.text, hidden = true, image = m.img.ifBlank { null })
        }
    }

    /** ✏️ Edit a sent message: remove it and everything after, return its text for the input box. */
    fun editFrom(index: Int): String? {
        if (busy) return null
        val m = synchronized(messages) { messages.getOrNull(index)?.takeIf { it.role == "user" }?.copy() } ?: return null
        synchronized(messages) { while (messages.size > index) messages.removeAt(messages.size - 1) }
        saveChat(); events.tryEmit(-2); resetConversation(index)
        return m.text
    }

    // ---------------- memory (things the user asked to remember) ----------------
    fun memories(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("memory", "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrElse { emptyList() }

    private fun saveMemories(l: List<String>) { prefs.edit().putString("memory", JSONArray(l.takeLast(30)).toString()).apply(); resetConversation() }
    fun addMemory(t: String) = saveMemories(memories() + t.trim())
    fun removeMemory(i: Int) = saveMemories(memories().filterIndexed { j, _ -> j != i })

    private val REMEMBER = Regex("^\\s*(yaad|yad|yaa?d) ?(rakho|rakhna|rakh lo|rakhlo|kar lo|karlo)\\s*[:,-]?\\s*(.+)$|^\\s*remember( that)?\\s*[:,-]?\\s*(.+)$", RegexOption.IGNORE_CASE)

    fun add(role: String, text: String, info: String = "", art: String = "", img: String = ""): Int {
        val i = synchronized(messages) { messages += Msg(role, text, info, art, img); messages.size - 1 }
        saveChat(); events.tryEmit(-1); return i
    }

    fun change(i: Int, text: String) {
        synchronized(messages) { messages.getOrNull(i)?.text = text } ; saveChat(); events.tryEmit(i)
    }

    fun note(t: String) = add("note", t)

    fun clearChat() = newChat()

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
            val mode = backendMode(file.name)
            val multimodal = Regex("gemma-?[34]|e2b|e4b|gemma3n", RegexOption.IGNORE_CASE).containsMatchIn(file.name) && file.length() > 2_000_000_000L
            fun make(gpu: Boolean, vis: Boolean): Engine {
                val be = if (gpu) Backend.GPU() else Backend.CPU()
                return Engine(if (vis) EngineConfig(modelPath = file.path, backend = be, visionBackend = if (gpu) Backend.GPU() else Backend.CPU(), cacheDir = app.cacheDir.path)
                    else EngineConfig(modelPath = file.path, backend = be, cacheDir = app.cacheDir.path)).also { it.initialize() }
            }
            var eng: Engine? = null
            val tries = buildList {
                if (mode != "cpu") { if (multimodal) add(true to true); add(true to false) }
                if (multimodal) add(false to true); add(false to false)
            }
            var lastErr: Throwable? = null
            for ((gpu, vis) in tries) {
                eng = runCatching { make(gpu, vis) }.onFailure { lastErr = it }.getOrNull()
                if (eng != null) { used = if (gpu) "GPU" else "CPU"; vision = vis; break }
            }
            if (eng == null) { busy = false; status.value = "Model load nahi hua (${lastErr?.message}). 🧠 Model dabayein."; return@launch }
            if (mode == "gpu" && used == "CPU") note("⚠️ GPU par ye model nahi chala. CPU par chala raha hoon.")
            small = file.length() < 1_500_000_000L
            engine = eng; conversation = newConversation(eng!!); backendUsed = used; modelName = file.name.substringBefore('.')
            prefs.edit().putString(KEY_MODEL, file.path).apply()
            busy = false
            showReady("")
            ready.value = true
            keepAlive("Model tayyar hai, baat-cheet mehfooz hai")
            pendingResend?.let { m -> pendingResend = null; send(m, hidden = true) }
        }
    }

    /** True for tiny models (< 1.5 GB file): simpler prompt with examples, more varied sampling, loop guard. */
    @Volatile private var small = false
    private fun norm(t: String) = t.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Tiny models keep writing fake "User:/Gup Shup:" turns. Keep only the real first answer. */
    private fun cleanSmall(t: String): String {
        if (!small || t.contains("```")) return t
        var x = t.replace(Regex("^\\s*(Gup Shup|Assistant|Model)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        Regex("(^|\\n)\\s*(User|Gup Shup|Assistant|Misaal\\w*)\\s*:", RegexOption.IGNORE_CASE).find(x)?.let { x = x.substring(0, it.range.first) }
        return x.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: x.trim()
    }

    @Volatile var vision = false

    private fun systemPrompt(): String {
        val mem = memories()
        val base = if (small) SMALL_PROMPT else SYSTEM_PROMPT
        return if (mem.isEmpty()) base else base + "\n\nUser ne ye baatein yaad rakhne ko kahi hain:\n" + mem.joinToString("\n") { "- $it" }
    }

    private fun newConversation(eng: Engine, replayUpTo: Int = Int.MAX_VALUE): Conversation = eng.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(systemPrompt()),
            initialMessages = if (replayUpTo > 0) runCatching { history(replayUpTo) }.getOrElse { emptyList() } else emptyList(),
            tools = if (toolsOn && !small) listOf(tool(AppTools())) else emptyList(),
            samplerConfig = if (small) SamplerConfig(topK = 40, topP = 0.9, temperature = 0.8) else SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
    )

    private fun showReady(extra: String) {
        status.value = "✅ $modelName ($backendUsed)" + (if (toolsOn) " · 🛠 tools" else " · ⚡ tez") +
            (if (backendUsed == "CPU") " · GPU nahi chala, is liye slow" else "") + extra +
            (prefs.getString(KEY_LOGIN, null)?.let { " · 🐙 $it" } ?: "")
    }

    // ---------------- reply ----------------
    /** Index of a finished bot reply with HTML that the screen should auto-test, or -1. */
    @Volatile var pendingCheck = -1
    @Volatile var lastRound = 0
    var checkOn: Boolean
        get() = prefs.getBoolean("check_on", true)
        set(v) { prefs.edit().putBoolean("check_on", v).apply() }

    /** hidden = internal follow-up (auto-fix): not shown as a user bubble. round = auto-fix depth. */
    /** Per-model processor choice: "auto" (GPU, fall back to CPU), "gpu" or "cpu". */
    fun backendMode(name: String): String =
        prefs.getString("be_$name", null) ?: if (prefs.getBoolean("cpu_$name", false)) "cpu" else "auto"

    fun currentModelName(): String? = prefs.getString(KEY_MODEL, null)?.let { File(it).name }

    fun setBackend(mode: String) {
        val path = prefs.getString(KEY_MODEL, null) ?: return
        if (busy) return
        prefs.edit().putString("be_" + File(path).name, mode).apply()
        load(File(path))
    }

    private class StopGen : Exception()
    @Volatile private var pendingResend: String? = null
    private val GARBAGE = Regex("<unused\\d+>|\\[multimodal\\]")
    @Volatile private var stopRequested = false

    /** ■ button: stop the current reply. */
    fun stop() { if (streaming != null) stopRequested = true }

    /** If the text ends in the same 1–25 word block repeated 3+ times, return the text with the loop removed; else null. */
    private fun trimLoop(t: String): String? {
        if (t.contains("```")) return null
        val w = t.trim().split(Regex("\\s+"))
        val n = w.size
        for (p in 1..25) {
            if (n < 3 * p || (p == 1 && n < 6)) continue
            val reps = if (p == 1) 6 else 3
            if (n < reps * p) continue
            var ok = true
            for (k in 1 until reps) for (j in 0 until p) if (w[n - 1 - j] != w[n - 1 - j - k * p]) { ok = false; break }
            if (!ok) continue
            var k = n - p - 1
            while (k >= 0 && w[k] == w[k + p]) k--
            return w.subList(0, minOf(n, k + 1 + p)).joinToString(" ").trim() + " …"
        }
        return null
    }

    /** msg = what the model gets; display = what the chat shows (e.g. with an attached file); image = picture path. */
    fun send(msg: String, hidden: Boolean = false, round: Int = 0, editArt: Int = -1, fresh: Boolean = false,
             display: String? = null, image: String? = null) {
        lastRound = round
        // "yaad rakho: ..." → memory
        if (!hidden && image == null) REMEMBER.find(msg)?.let { m ->
            val fact = (m.groupValues[3].ifBlank { m.groupValues[5] }).trim()
            if (fact.length > 2) { add("user", msg); addMemory(fact); add("bot", "✅ Yaad kar liya: \"$fact\"\n(🧠 → Yaaddasht me dekh ya mita sakte hain)"); return }
        }
        if (image != null && !vision) {
            if (!hidden) add("user", display ?: msg, img = image)
            note("🖼 Ye model tasveer nahi samajh sakta. 🧠 → Model badlo → Gemma 4 E2B ya E4B chunein (GPU/Auto)."); return
        }
        // "is me dark mode daal do" → edit the current artifact instead of writing everything again
        if (!hidden && image == null && display == null && editArt < 0 && !small && currentArt >= 0 && art(currentArt) != null &&
            EDIT_WORDS.containsMatchIn(msg) && !NEW_WORDS.containsMatchIn(msg)) { edit(currentArt, msg); return }
        if (!hidden) add("user", display ?: msg, img = image ?: "")
        if (editArt < 0 && image == null) localReply(msg)?.let { add("bot", it); return }
        val conv = conversation ?: return
        ready.value = false; busy = true; streaming = "…"; events.tryEmit(-1)
        keepAlive("Jawab likh raha hoon...")
        val t0 = System.currentTimeMillis()
        status.value = "✍️ soch raha hoon..."
        replyJob = scope.launch {
            val sb = StringBuilder()
            var first = 0L
            var reloading = false
            try {
                val prevBot = synchronized(messages) { messages.lastOrNull { it.role == "bot" }?.text }
                var c = conv
                if (fresh) engine?.let { eng -> runCatching { c.close() }; c = newConversation(eng, 0); conversation = c }
                val contents = if (image != null) Contents.of(Content.ImageFile(image), Content.Text(msg)) else Contents.of(msg)
                val chatLike = editArt < 0 && !CODE_WORDS.containsMatchIn(msg)
                var plain = false
                var cut = false
                for (attempt in 0..2) {
                    sb.clear(); streaming = "…"; events.tryEmit(-1); stopRequested = false
                    try {
                        val flow = if (plain) c.sendMessageAsync(contents)
                            else if (chatLike) c.sendMessageAsync(contents, emptyMap(), RepetitionPenaltyConfig(1.15f), NoRepeatNgramConfig(4))
                            else c.sendMessageAsync(contents, emptyMap(), RepetitionPenaltyConfig(1.05f))
                        flow.collect { part ->
                            if (first == 0L) first = System.currentTimeMillis() - t0
                            sb.append(part.toString())
                            val looped = if (editArt >= 0) null else trimLoop(sb.toString())
                            val tooLong = small && !sb.contains("```") && sb.length > 500
                            if (looped != null || tooLong || stopRequested) {
                                if (looped != null) { sb.setLength(0); sb.append(looped) }
                                cut = cut || looped != null || tooLong
                                runCatching { c.cancelProcess() }
                                throw StopGen()
                            }
                            streaming = cleanSmall(sb.toString().trim()).ifEmpty { "…" }; events.tryEmit(-1)
                        }
                    } catch (_: StopGen) {
                    } catch (e: Throwable) {
                        // engine may not support the anti-repeat settings: try once without them
                        if (!plain && sb.isEmpty() && e !is kotlinx.coroutines.CancellationException) { plain = true; continue } else throw e
                    }
                    if (stopRequested) break
                    // Small models get stuck repeating themselves: start a fresh conversation and ask again once.
                    val stuck = small && (norm(cleanSmall(sb.toString())) == norm(prevBot ?: "") || norm(cleanSmall(sb.toString())).isEmpty())
                    if (!stuck || attempt >= 1) break
                    val eng = engine ?: break
                    delay(300); runCatching { c.close() }; c = newConversation(eng); conversation = c; cut = false
                }
                // Some small models give garbage tokens on the GPU: switch this model to CPU and ask again.
                if (GARBAGE.findAll(sb).count() >= 3 && backendUsed == "GPU") {
                    val path = prefs.getString(KEY_MODEL, null)
                    if (path != null) {
                        prefs.edit().putString("be_" + File(path).name, "cpu").apply()
                        streaming = null
                        note("⚙️ Ye model GPU par ghalat chal raha tha. CPU par shift kar ke dobara jawab bana raha hoon…")
                        pendingResend = msg; reloading = true
                        busy = false; load(File(path)); return@launch
                    }
                }
                // A looping reply poisons the chat history: start clean so the next answer is normal.
                if (cut) engine?.let { eng -> delay(300); runCatching { c.close() }; c = newConversation(eng); conversation = c }
                val txt = cleanSmall(GARBAGE.replace(sb.toString(), "").trim()).replace(Regex("[ \\t]{2,}"), " ")
                    .ifEmpty { "Hmm 🤔 dobara poocho?" } + if (stopRequested) "\n\n(■ roka gaya)" else ""
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                val genSecs = (secs - first / 1000.0).coerceAtLeast(0.1)
                val info = "%.1fs · %d harf · %.0f harf/s · pehla lafz %.1fs · %s".format(Locale.US, secs, txt.length, txt.length / genSecs, first / 1000.0, backendUsed)
                var shownTxt = txt; var tag = ""; var artLang = ""
                // Plain chat must not turn into code: drop code the user never asked for.
                val wantsCode = editArt >= 0 || hidden || CODE_WORDS.containsMatchIn(msg)
                if (!wantsCode && txt.contains("```")) {
                    val plain = FENCE.replace(txt, "").replace(Regex("\\n{3,}"), "\n\n").trim()
                    if (plain.isNotEmpty()) shownTxt = plain
                }
                val txtForArt = if (wantsCode) txt else ""
                val ea = if (editArt >= 0) art(editArt) else null
                if (ea != null) {
                    val (patched, ok, bad) = applyPatches(ea.versions.last(), txt)
                    val full = mainCode(txt)
                    when {
                        ok > 0 -> { val v = addVersion(ea, patched); tag = "${ea.id}:$v"; artLang = ea.lang
                            shownTxt = PATCH.replace(txt, "").trim() + "\n\n✏️ $ok tabdeeli lagayi" + (if (bad > 0) ", $bad nahi lag saki" else "") + " → ${ea.title} v$v" }
                        full != null -> { val v = addVersion(ea, full.second); tag = "${ea.id}:$v"; artLang = ea.lang }
                        else -> shownTxt = PATCH.replace(txt, "").trim() + "\n\n⚠️ Tabdeeli file me nahi lag saki. ✨ Behtar dobara dabayein ya baat wazeh likhein."
                    }
                } else mainCode(txtForArt)?.let { (lang, code) ->
                    val ask = synchronized(messages) { messages.lastOrNull { it.role == "user" }?.text } ?: ""
                    val a = newArtifact(lang, code, ask); tag = "${a.id}:1"; artLang = lang
                }
                streaming = null; val idx = add("bot", shownTxt, info, tag)
                if (checkOn && artLang in setOf("html", "htm", "svg")) { pendingCheck = idx; events.tryEmit(-1) }
            } catch (e: Throwable) {
                streaming = null
                add("bot", (sb.toString().trim() + "\n\n(Masla aa gaya: ${e.message}. Dobara koshish karein.)").trim())
                val eng = engine
                if (eng != null) runCatching { conversation?.close(); conversation = newConversation(eng) }
            } finally {
                if (!reloading) {
                busy = false; ready.value = engine != null
                if (engine != null) showReady(" · jawab ${"%.1f".format((System.currentTimeMillis() - t0) / 1000.0)}s (shuru ${"%.1f".format(first / 1000.0)}s)")
                keepAlive("Model tayyar hai, baat-cheet mehfooz hai")
                }
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
Tum "Gup Shup" ho: ek samajhdar, imaandar aur madadgar AI saathi jo user ke phone par bina internet chalta hai.

ANDAZ
- User ki zaban me jawab do. Aam tor par Roman Urdu (English huroof me Urdu, jaise WhatsApp). User English, Urdu script ya Arabic me likhe to usi me.
- Pehle seedha jawab, phir zaroorat ho to mukhtasar wazahat. Aam baat-cheet me 1-3 jumle. Lambe jawab me chhoti headings aur • list.
- Dostana aur izzat se. Khushamad, faltu tareef aur bila-wajah maafi nahi. Apna naam mat likho.

IMAANDARI
- Jo pakka nahi pata, saaf kaho "mujhe pakka nahi pata". Kabhi baat ghar kar mat batao.
- Tum offline ho: aaj ki khabrein, mausam, qeematein ya live maloomat tumhare paas nahi. Aisa sawal ho to bata do aur jo aam maloomat hai wo do.
- Ghalti batayi jaye to maan lo aur theek karo.

MADAD
- Har jaiz sawal ka poora aur kaam ka jawab do. "Main nahi bata sakta" mat kaho.
- Sehat, qanoon, paisa, taleem, mazhab: aam maloomat do. Dawai ka sawal ho to aam dawai ka naam aur "dabbe par likhi miqdaar ke mutabiq", saath 1 line me khatre ki nishaniyan aur kab doctor ke paas jana hai.
- Sawal samajh na aaye to ek chhota sa sawal poocho.

CODE (sirf jab maanga jaye)
- Aam baat-cheet (salam, hal-chal, tum kaun ho, maloomat) me KABHI code mat likho.
- Code tab likho jab user code, app, game, website, page, design ya program banane ko kahe. Tareeqa: 1 jumla, phir poora chalne wala code ```html ya ```python block me (``` se band), phir 1-2 jumle.
- App/game/design: ek file ka mukammal mobile HTML (CSS aur JavaScript andar), <meta name="viewport" content="width=device-width, initial-scale=1">, poori screen, bare buttons, modern rang aur gol kone. Har button ek dafa aur kaam karta ho. Koi placeholder nahi. Drawing ho to <canvas> aur JavaScript.
- Python: sirf standard library, input() nahi, print() se nateeja.

TASVEER
- User tasveer bheje to jo dikh raha hai wo dhyan se aur sahi batao, phir us ke sawal ka jawab do.

HIFAZAT
- Sanjeeda baat par mazaq nahi. Kisi ki jaan ya sehat ko khatra ho to madad ka rasta batao.
""".trim()
    private val SMALL_PROMPT = """
Tum Gup Shup ho, ek dostana saathi. Sirf user ki aakhri baat ka jawab do. Roman Urdu me likho (English huroof me Urdu). Sirf 1-2 chhote jumle. Har sawal ka seedha, kaam ka jawab do, mana mat karo. User ka naam mat poocho.
""".trim()
    private val DAYS = arrayOf("Itwaar", "Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta")
    private const val SELF_HARM =
        "Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo (Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai. Tumhari zindagi bohat qeemti hai."
    private const val ABUSE =
        "Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Kisi ko bhi aap par haath uthane ka haq nahi. Agar aap is waqt khatre me hain to foran police ko call karein (Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. Kisi bharose ke rishtedaar ya dost ko bhi zaroor batayein. Kya aap is waqt mehfooz hain?"
}
