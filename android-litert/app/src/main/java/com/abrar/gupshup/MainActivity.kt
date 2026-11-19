package com.abrar.gupshup

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gup Shup v4 — on-phone Gemma 4 with tools: Python runner, GitHub, APK builder, code cards. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var job: Job? = null
    private var ready = false
    private var busy = false
    private val prefs by lazy { getSharedPreferences("gupshup", MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    private val gh by lazy { GitHub(prefs.getString(KEY_TOKEN, null)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val saved = prefs.getString(KEY_MODEL, null)?.let { File(it) }?.takeIf { it.exists() && it.length() > 100_000_000 }
        if (saved != null) load(saved) else showModelChooser()
    }

    // ===================== UI =====================
    private fun pad(v: Int) = (v * dp).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0E1A14")) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#123524")); setPadding(pad(12), pad(8), pad(12), pad(8))
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; text = "Gup Shup shuru ho raha hai..." }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun topBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false; setOnClickListener { f() }
        }.also { actions.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        topBtn("🧠 Model") { if (!busy) showModelChooser() }
        topBtn("🐙 GitHub") { githubMenu() }
        topBtn("＋ Nayi") { newChat() }
        top.addView(status); top.addView(actions)

        scroll = ScrollView(this).apply { isFillViewport = true }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(10), pad(10), pad(10), pad(10)) }
        scroll.addView(chat, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(pad(8), pad(8), pad(8), pad(8)); setBackgroundColor(Color.parseColor("#123524"))
        }
        input = EditText(this).apply {
            hint = "Kuch bhi poocho, ya kaho: 'calculator ki app banao'"; setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#9CC7AD")); maxLines = 6; isEnabled = false
        }
        send = Button(this).apply { text = "➤"; textSize = 18f; isEnabled = false; setOnClickListener { onSend() } }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        setContentView(root)
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun rounded(color: String, r: Float = 16f) = GradientDrawable().apply { cornerRadius = r * dp; setColor(Color.parseColor(color)) }

    private fun bubble(text: String, user: Boolean): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad(12), pad(9), pad(12), pad(9))
            background = rounded(if (user) "#1F7A4D" else "#33493D")
        }
        box.addView(TextView(this).apply { this.text = text; textSize = 16f; setTextColor(Color.WHITE); setTextIsSelectable(true) })
        val lp = LinearLayout.LayoutParams(if (user) WRAP_CONTENT else MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START; topMargin = pad(6)
        }
        chat.addView(box, lp); scrollDown()
        return box
    }

    private fun note(text: String) = bubble(text, false)

    /** Finished answer → normal text + code cards for ```lang blocks. */
    private fun renderAnswer(box: LinearLayout, answer: String) {
        box.removeAllViews()
        val re = Regex("```([\\w+#.-]*)[ \\t]*\\n([\\s\\S]*?)```")
        var last = 0
        for (m in re.findAll(answer)) {
            addText(box, answer.substring(last, m.range.first))
            addCodeCard(box, m.groupValues[1].lowercase(), m.groupValues[2].trimEnd())
            last = m.range.last + 1
        }
        addText(box, answer.substring(last))
        if (box.childCount == 0) addText(box, "Hmm 🤔 dobara poocho?")
        scrollDown()
    }

    private fun addText(box: LinearLayout, t: String) {
        val s = t.trim(); if (s.isEmpty()) return
        box.addView(TextView(this).apply {
            text = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1"); textSize = 16f; setTextColor(Color.WHITE); setTextIsSelectable(true)
            setPadding(0, pad(2), 0, pad(2))
        })
    }

    private fun addCodeCard(box: LinearLayout, lang: String, code: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded("#0B0F0D", 12f); setPadding(pad(10), pad(8), pad(10), pad(8)) }
        card.addView(TextView(this).apply { text = lang.ifBlank { "code" }; setTextColor(Color.parseColor("#9CC7AD")); textSize = 12f })
        val hs = HorizontalScrollView(this)
        hs.addView(TextView(this).apply {
            text = code; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#E6F2EA")); setTextIsSelectable(true); maxLines = 14
        })
        card.addView(hs)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 11f; isAllCaps = false; setOnClickListener { f() } }
            .also { row.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        b("📋 Copy") { copy(code) }
        b("⛶ Poora") { fullScreen(lang, code) }
        if (lang in setOf("python", "py", "python3")) b("▶ Chalao") { runPythonCard(box, code) }
        if (lang in setOf("html", "htm")) b("📦 APK") { askApkName(code) }
        b("💾 GitHub") { saveToGitHubDialog(code, lang) }
        card.addView(row)
        box.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(6) })
    }

    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", t))
        Toast.makeText(this, "Copy ho gaya ✅", Toast.LENGTH_SHORT).show()
    }

    private fun fullScreen(lang: String, code: String) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0F0D")) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "📋 Copy"; isAllCaps = false; setOnClickListener { copy(code) } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "✕ Band"; isAllCaps = false; setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sv = ScrollView(this); val hs = HorizontalScrollView(this)
        hs.addView(TextView(this).apply {
            text = code; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#E6F2EA")); setTextIsSelectable(true)
            setPadding(pad(12), pad(12), pad(12), pad(12))
        })
        sv.addView(hs)
        root.addView(TextView(this).apply { text = "  $lang"; setTextColor(Color.parseColor("#9CC7AD")) })
        root.addView(row); root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d.setContentView(root); d.show()
    }

    // ===================== Python =====================
    private fun runPython(code: String): String {
        if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
        return Python.getInstance().getModule("runner").callAttr("run", code, 20).toString()
    }

    private fun runPythonCard(box: LinearLayout, code: String) {
        val out = TextView(this).apply {
            text = "▶ chal raha hai..."; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#FFE082"))
            background = rounded("#1A1A10", 10f); setPadding(pad(10), pad(8), pad(10), pad(8)); setTextIsSelectable(true)
        }
        box.addView(out, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(4) })
        scrollDown()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = try { runPython(code) } catch (e: Throwable) { "Error: ${e.message}" }
            withContext(Dispatchers.Main) { out.text = "Nateeja:\n$r"; scrollDown() }
        }
    }

    // ===================== GitHub =====================
    private fun githubMenu() {
        if (gh.token == null) { githubLogin(); return }
        AlertDialog.Builder(this).setTitle("🐙 GitHub (${prefs.getString(KEY_LOGIN, "")})")
            .setItems(arrayOf("📂 Meri repositories", "📦 HTML se APK banao (code paste karein)", "🚪 Logout")) { _, w ->
                when (w) {
                    0 -> lifecycleScope.launch(Dispatchers.IO) {
                        val r = runCatching { gh.listRepos().joinToString("\n") }.getOrElse { "Error: ${it.message}" }
                        withContext(Dispatchers.Main) { note("📂 Aap ki repositories:\n$r") }
                    }
                    1 -> pasteHtmlForApk()
                    else -> { prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply(); gh.token = null; note("GitHub se logout ho gaya.") }
                }
            }.show()
    }

    private fun clientId(): String = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: BuildDefaults.GITHUB_CLIENT_ID

    private fun githubLogin() {
        val cid = clientId()
        if (cid.isBlank()) {
            val et = EditText(this).apply { hint = "GitHub OAuth App ka Client ID" }
            AlertDialog.Builder(this).setTitle("Ek dafa ka setup")
                .setMessage("GitHub → Settings → Developer settings → OAuth Apps → New. 'Enable Device Flow' on karein aur Client ID yahan daalein.")
                .setView(et).setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString(KEY_CLIENT_ID, et.text.toString().trim()).apply(); githubLogin()
                }.setNegativeButton("Baad me", null).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val j = gh.startDeviceLogin(cid)
                val userCode = j.getString("user_code"); val uri = j.getString("verification_uri")
                withContext(Dispatchers.Main) {
                    copy(userCode)
                    AlertDialog.Builder(this@MainActivity).setTitle("GitHub se jorein")
                        .setMessage("Ye code copy ho gaya hai:\n\n      $userCode\n\nGitHub khulega → code paste karein → Authorize dabayein. Phir wapas yahan aa jayein.")
                        .setPositiveButton("GitHub kholein") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.show()
                    note("⏳ GitHub par Authorize ka intezar...")
                }
                val interval = j.optLong("interval", 5) + 1
                val until = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000
                while (System.currentTimeMillis() < until) {
                    delay(interval * 1000)
                    val t = gh.pollDeviceLogin(cid, j.getString("device_code"))
                    if (t != null) {
                        gh.token = t
                        val login = gh.me()
                        prefs.edit().putString(KEY_TOKEN, t).putString(KEY_LOGIN, login).apply()
                        withContext(Dispatchers.Main) { note("✅ GitHub jur gaya: $login") }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) { note("GitHub login ka waqt khatam ho gaya. Dobara 🐙 dabayein.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { note("GitHub login nahi hua: ${e.message}") }
            }
        }
    }

    private fun needGitHub(): Boolean {
        if (gh.token != null) return true
        Toast.makeText(this, "Pehle 🐙 GitHub se jorein", Toast.LENGTH_SHORT).show(); githubLogin(); return false
    }

    private fun saveToGitHubDialog(code: String, lang: String) {
        if (!needGitHub()) return
        val ext = mapOf("python" to "py", "py" to "py", "html" to "html", "kotlin" to "kt", "java" to "java", "javascript" to "js", "js" to "js")[lang] ?: "txt"
        val repo = EditText(this).apply { hint = "owner/repo"; setText(prefs.getString(KEY_LAST_REPO, "")) }
        val path = EditText(this).apply { hint = "file ka naam"; setText("gupshup/code.$ext") }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(16), 0, pad(16), 0); addView(repo); addView(path) }
        AlertDialog.Builder(this).setTitle("💾 GitHub me save").setView(col).setPositiveButton("Save") { _, _ ->
            val r = repo.text.toString().trim(); prefs.edit().putString(KEY_LAST_REPO, r).apply()
            lifecycleScope.launch(Dispatchers.IO) {
                val res = runCatching { gh.writeFile(r, path.text.toString().trim(), code, "Gup Shup se save") }.getOrElse { "Error: ${it.message}" }
                withContext(Dispatchers.Main) { note(res) }
            }
        }.setNegativeButton("Rehne do", null).show()
    }

    private fun pasteHtmlForApk() {
        val et = EditText(this).apply { hint = "Poora HTML code yahan paste karein"; minLines = 5 }
        AlertDialog.Builder(this).setTitle("📦 HTML se APK").setView(et)
            .setPositiveButton("Aage") { _, _ -> askApkName(et.text.toString()) }.setNegativeButton("Rehne do", null).show()
    }

    private fun askApkName(html: String) {
        if (!needGitHub()) return
        val et = EditText(this).apply { hint = "App ka naam"; setText("Meri App") }
        AlertDialog.Builder(this).setTitle("📦 APK banao").setMessage("Aap ke GitHub par nayi repository banegi aur GitHub APK banayega (3–6 minute).")
            .setView(et).setPositiveButton("Banao") { _, _ -> buildApk(et.text.toString().trim().ifBlank { "Meri App" }, html) }
            .setNegativeButton("Rehne do", null).show()
    }

    private fun buildApk(name: String, html: String) {
        val box = note("📦 \"$name\" ki APK ban rahi hai...")
        val tv = box.getChildAt(0) as TextView
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repo = gh.startHtmlApk(name, html)
                withContext(Dispatchers.Main) { tv.text = "📦 Repo bani: $repo\nGitHub APK bana raha hai (3–6 minute)..." }
                val until = System.currentTimeMillis() + 15 * 60_000
                while (System.currentTimeMillis() < until) {
                    delay(15_000)
                    val link = gh.apkLink(repo)
                    if (link != null) {
                        withContext(Dispatchers.Main) {
                            tv.text = "✅ APK tayyar: $name\n$link"
                            box.addView(Button(this@MainActivity).apply {
                                text = "⬇ APK download"; isAllCaps = false
                                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                            })
                        }
                        return@launch
                    }
                    val st = gh.buildStatus(repo)
                    if (st.startsWith("completed:") && st != "completed:success") {
                        withContext(Dispatchers.Main) { tv.text = "❌ Build fail hua ($st).\nhttps://github.com/$repo/actions" }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { tv.text = "📦 $repo\nBuild: $st ..." }
                }
                withContext(Dispatchers.Main) { tv.text = "⏳ Build lamba ho gaya. Yahan dekhein: https://github.com/$repo/actions" }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tv.text = "APK nahi bani: ${e.message}" }
            }
        }
    }

    /** Blocking yes/no for tools running off the main thread. */
    private fun confirm(title: String, msg: String): Boolean {
        val latch = CountDownLatch(1); var ok = false
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title).setMessage(msg.take(1500)).setCancelable(false)
                .setPositiveButton("Haan") { _, _ -> ok = true; latch.countDown() }
                .setNegativeButton("Nahi") { _, _ -> latch.countDown() }.show()
        }
        latch.await(3, TimeUnit.MINUTES); return ok
    }

    // ===================== Tools the model can call =====================
    inner class AppTools : ToolSet {
        @Tool(description = "Run Python 3 code on the phone and return its printed output or error. Use print() to show results.")
        fun run_python(@ToolParam(description = "Complete Python 3 code to run") code: String): String {
            runOnUiThread { note("🛠 Python chala raha hoon...") }
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
            if (!confirm("💾 GitHub me likhna hai?", "$repo/$path\n\n${content.take(800)}")) return "User ne mana kar diya."
            return runCatching { gh.writeFile(repo, path, content, message) }.getOrElse { "Error: ${it.message}" }
        }
    }

    // ===================== Model download / load =====================
    private fun showModelChooser() {
        AlertDialog.Builder(this).setTitle("Model chuno")
            .setItems(arrayOf(
                "🌟 Gemma 4 E4B — taqatwar, coding ke liye (~3.7 GB, 8 GB+ RAM)",
                "⚡ Gemma 4 E2B — tez (2.6 GB, 6 GB+ RAM)",
                "📁 Phone se .litertlm file chuno"
            )) { _, w ->
                when (w) {
                    0 -> download("gemma4-e4b-litert-v1", "gemma4-e4b.litertlm")
                    1 -> download("gemma4-litert-v1", "gemma4-e2b.litertlm")
                    else -> pickFile.launch(arrayOf("*/*"))
                }
            }.setCancelable(ready).show()
    }

    private fun modelsDir() = File(filesDir, "models").also { it.mkdirs() }

    private fun download(tag: String, name: String) {
        busy = true; setReady(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Download shuru... (WiFi aur charger behtar hai)"
        lifecycleScope.launch(Dispatchers.IO) {
            val target = File(modelsDir(), name); val part = File(modelsDir(), "$name.part")
            try {
                if (!target.exists()) {
                    val urls = GitHub(null).releaseAssets(MODEL_REPO, tag)
                    if (urls.isEmpty()) error("model files nahi mili")
                    part.delete()
                    var total = 0L
                    for (u in urls) {
                        val h = (URL(u).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; instanceFollowRedirects = true }
                        total += h.contentLengthLong.coerceAtLeast(0); h.disconnect()
                    }
                    var done = 0L; var lastPct = -1
                    FileOutputStream(part, true).use { out ->
                        for (u in urls) {
                            val c = (URL(u).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000 }
                            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                            c.inputStream.use { inp ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = inp.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        withContext(Dispatchers.Main) { status.text = "Download: $pct%  (${done shr 20} / ${total shr 20} MB)" }
                                    }
                                }
                            }
                        }
                    }
                    part.renameTo(target)
                }
                withContext(Dispatchers.Main) { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); load(target) }
            } catch (e: Exception) {
                part.delete()
                withContext(Dispatchers.Main) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); busy = false
                    status.text = "Download nahi hua (${e.message}). 🧠 Model dabakar dobara koshish karein."
                }
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        busy = true; status.text = "File copy ho rahi hai..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val t = File(modelsDir(), "custom-${System.currentTimeMillis()}.litertlm")
                contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(t).use { i.copyTo(it) } } ?: error("file nahi khuli")
                withContext(Dispatchers.Main) { load(t) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { busy = false; status.text = "File load nahi hui (${e.message})." } }
        }
    }

    private fun setReady(r: Boolean) { ready = r; input.isEnabled = r; send.isEnabled = r }

    private fun load(file: File) {
        busy = true; setReady(false)
        status.text = "Model load ho raha hai... (pehli dafa 30 second – 2 minute)"
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { conversation?.close() }; runCatching { engine?.close() }
            conversation = null; engine = null
            var used = "GPU"
            val eng = try {
                Engine(EngineConfig(modelPath = file.path, backend = Backend.GPU(), cacheDir = cacheDir.path)).also { it.initialize() }
            } catch (e: Throwable) {
                used = "CPU"
                try {
                    Engine(EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = cacheDir.path)).also { it.initialize() }
                } catch (e2: Throwable) {
                    withContext(Dispatchers.Main) { busy = false; status.text = "Model load nahi hua (${e2.message})."; showModelChooser() }
                    return@launch
                }
            }
            engine = eng; conversation = newConversation(eng)
            prefs.edit().putString(KEY_MODEL, file.path).apply()
            withContext(Dispatchers.Main) {
                busy = false; setReady(true)
                status.text = "✅ ${file.name.substringBefore('.')} tayyar ($used) — bina internet" +
                    (prefs.getString(KEY_LOGIN, null)?.let { " · 🐙 $it" } ?: "")
                if (chat.childCount == 0) note("Assalam o Alaikum! Main Gup Shup hoon 🙂\nGup shup, Python code (▶ chala bhi sakta hoon), HTML apps (📦 APK bhi), aur 🐙 GitHub — sab poocho!")
            }
        }
    }

    private fun newConversation(eng: Engine): Conversation = eng.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            tools = listOf(tool(AppTools())),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.6),
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
    )

    private fun newChat() {
        if (busy) return
        chat.removeAllViews()
        val e = engine ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { conversation?.close() }; conversation = newConversation(e)
            withContext(Dispatchers.Main) { note("Nayi baat-cheet shuru 🙂") }
        }
    }

    // ===================== Chat =====================
    private fun onSend() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || !ready) return
        input.setText("")
        bubble(msg, true)
        localReply(msg)?.let { note(it); return }
        val conv = conversation ?: return
        setReady(false)
        val box = note("…"); val tv = box.getChildAt(0) as TextView
        val sb = StringBuilder()
        job = lifecycleScope.launch {
            try {
                conv.sendMessageAsync(msg).collect { part ->
                    sb.append(part.toString()); tv.text = sb.toString().trim().ifEmpty { "…" }; scrollDown()
                }
                renderAnswer(box, sb.toString())
            } catch (e: Throwable) {
                tv.text = "Masla aa gaya (${e.message}). Dobara koshish karein."
                val eng = engine
                if (eng != null) runCatching { conversation?.close(); conversation = newConversation(eng) }
            } finally { setReady(true) }
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

    override fun onDestroy() {
        job?.cancel()
        if (isFinishing) { runCatching { conversation?.close() }; runCatching { engine?.close() } }
        super.onDestroy()
    }

    companion object {
        private const val KEY_MODEL = "model_path_v4"
        private const val KEY_TOKEN = "gh_token"
        private const val KEY_LOGIN = "gh_login"
        private const val KEY_CLIENT_ID = "gh_client_id"
        private const val KEY_LAST_REPO = "gh_last_repo"
        private const val MODEL_REPO = "abrar1499/bolkhata-brain"
        private val SYSTEM_PROMPT = """
Tum "Gup Shup" ho: ek dostana aur maahir assistant jo user ke phone par bina internet chalta hai.
Zaban: hamesha Roman Urdu (Latin huroof) me baat karo, jab tak user English ya Arabic me na likhe. Code ke andar comments English me theek hain.
Andaz: seedha aur kaam ka jawab. Gup-shup me 1-3 jumle. Apna naam har jawab me mat likho.

Coding qawaid:
- Har code ko ```language block me likho (```python, ```html, ```kotlin waghera), taake user Copy/Chalao/APK button istemal kar sake.
- Poora, chalne wala code do. Adhoora code ya "..." mat chhoro.
- Python: sirf standard library istemal karo. input() mat use karo; misaal ke values code me rakho aur print() se nateeja dikhao.
- Agar user "app/game/tool banao" kahe: ek hi file wala mukammal HTML (CSS aur JavaScript andar) likho, mobile screen ke liye. User 📦 APK button se is ki Android app bana sakta hai.
- Hisaab ya logic pakka karna ho to run_python tool se chala kar check karo.
- GitHub tools sirf tab use karo jab user GitHub ki baat kare. Likhne se pehle app khud user se ijazat leti hai.

Hifazat: sanjeeda baat par mazaq mat karo. Tum bina internet ke ho: aaj ka mausam, khabrein ya taaza maloomat tumhein nahi pata, imaandari se batao.
""".trim()
        private val DAYS = arrayOf("Itwaar", "Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta")
        private const val SELF_HARM =
            "Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo (Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai. Tumhari zindagi bohat qeemti hai."
        private const val ABUSE =
            "Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Kisi ko bhi aap par haath uthane ka haq nahi. Agar aap is waqt khatre me hain to foran police ko call karein (Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. Kisi bharose ke rishtedaar ya dost ko bhi zaroor batayein. Kya aap is waqt mehfooz hain?"
    }
}

/** GitHub OAuth Client ID baked at build time; if empty the app asks once. */
object BuildDefaults { const val GITHUB_CLIENT_ID = "" }
