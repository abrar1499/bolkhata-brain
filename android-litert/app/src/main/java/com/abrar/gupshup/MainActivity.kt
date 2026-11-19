package com.abrar.gupshup

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gup Shup v4.2 — screen only. The model, chat and work live in [Brain] and keep running in the background. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private val views = ArrayList<View>()
    private var streamBox: LinearLayout? = null
    private val dp by lazy { resources.displayMetrics.density }
    private val prefs get() = Brain.prefs
    private val gh get() = Brain.gh

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.init(this)
        Brain.activity = WeakReference(this)
        buildUi()
        renderAll()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        // Back button sends the app to the background instead of closing it, so nothing is lost.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Brain.status.collect { status.text = it } }
                launch { Brain.ready.collect { input.isEnabled = it; send.isEnabled = it } }
                launch { Brain.events.collect { onEvent(it) } }
                launch { while (true) { syncNew(); delay(1500) } }   // safety net
            }
        }
        if (!Brain.hasModel && !Brain.busy) showModelChooser()
    }

    override fun onResume() { super.onResume(); Brain.activity = WeakReference(this); renderAll() }

    override fun onDestroy() {
        if (Brain.activity?.get() === this) Brain.activity = null
        super.onDestroy()
    }

    // ===================== UI =====================
    private fun pad(v: Int) = (v * dp).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0E1A14")) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#123524")); setPadding(pad(12), pad(8), pad(12), pad(8))
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun topBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false; setOnClickListener { f() }
        }.also { actions.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        topBtn("🧠 Model") { if (!Brain.busy) showModelChooser() }
        topBtn("🐙 GitHub") { githubMenu() }
        topBtn("＋ Nayi") { Brain.clearChat() }
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

    private fun snapshot(): List<Msg> = synchronized(Brain.messages) { Brain.messages.map { it.copy() } }

    // ===================== rendering from Brain =====================
    private fun renderAll() {
        if (!::chat.isInitialized) return
        chat.removeAllViews(); views.clear(); streamBox = null
        snapshot().forEach { addMsgView(it) }
        updateStream(); scrollDown()
    }

    private fun syncNew() {
        val msgs = snapshot()
        if (msgs.size < views.size) { renderAll(); return }
        if (msgs.size > views.size) {
            streamBox?.let { chat.removeView(it) }; streamBox = null
            for (m in msgs.subList(views.size, msgs.size)) addMsgView(m)
            scrollDown()
        }
        updateStream()
    }

    private fun onEvent(e: Int) {
        when {
            e == -2 -> renderAll()
            e >= 0 -> {
                val m = snapshot().getOrNull(e) ?: return
                if (e < views.size) {
                    val idx = chat.indexOfChild(views[e]); if (idx < 0) { renderAll(); return }
                    chat.removeViewAt(idx)
                    val v = msgView(m); chat.addView(v, idx, lp(m.role == "user")); views[e] = v
                } else syncNew()
            }
            else -> syncNew()
        }
    }

    private fun updateStream() {
        val s = Brain.streaming
        if (s == null) { streamBox?.let { chat.removeView(it) }; streamBox = null; return }
        val box = streamBox ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad(12), pad(9), pad(12), pad(9)); background = rounded("#33493D")
            addView(TextView(this@MainActivity).apply { textSize = 16f; setTextColor(Color.WHITE) })
        }.also { streamBox = it; chat.addView(it, lp(false)) }
        (box.getChildAt(0) as TextView).apply {
            val code = s.contains("```")
            text = s; typeface = if (code) Typeface.MONOSPACE else Typeface.DEFAULT; textSize = if (code) 13f else 16f
        }
        scrollDown()
    }

    private fun lp(user: Boolean) = LinearLayout.LayoutParams(if (user) WRAP_CONTENT else MATCH_PARENT, WRAP_CONTENT).apply {
        gravity = if (user) Gravity.END else Gravity.START; topMargin = pad(6)
    }

    private fun addMsgView(m: Msg) { val v = msgView(m); chat.addView(v, lp(m.role == "user")); views += v }

    private fun msgView(m: Msg): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad(12), pad(9), pad(12), pad(9))
            background = rounded(when (m.role) { "user" -> "#1F7A4D"; "note" -> "#26362D"; else -> "#33493D" })
        }
        if (m.role == "user") addText(box, m.text) else renderAnswer(box, m.text)
        Regex("https://\\S+\\.apk").find(m.text)?.value?.let { link ->
            box.addView(Button(this).apply {
                text = "⬇ APK download"; isAllCaps = false
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            })
        }
        return box
    }

    /** Split an answer into text and code. Handles ``` / ~~~ fences, an unclosed last fence, and bare HTML. */
    private fun segments(answer: String): List<Triple<Boolean, String, String>> {
        val out = ArrayList<Triple<Boolean, String, String>>()
        val re = Regex("(```|~~~)[ \\t]*([\\w+#.-]*)[^\\n]*\\n([\\s\\S]*?)(?:\\n?[ \\t]*\\1|\\z)")
        var last = 0
        for (m in re.findAll(answer)) {
            if (m.range.first > last) out += Triple(false, "", answer.substring(last, m.range.first))
            out += Triple(true, m.groupValues[2].lowercase(), m.groupValues[3].trimEnd())
            last = m.range.last + 1
        }
        if (last < answer.length) out += Triple(false, "", answer.substring(last))
        if (out.none { it.first }) {
            val i = answer.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: answer.indexOf("<html", ignoreCase = true)
            if (i >= 0) {
                val end = answer.lastIndexOf("</html>", ignoreCase = true).let { if (it >= i) it + 7 else answer.length }
                return listOf(Triple(false, "", answer.substring(0, i)), Triple(true, "html", answer.substring(i, end)), Triple(false, "", answer.substring(end)))
            }
        }
        return out
    }

    private fun renderAnswer(box: LinearLayout, answer: String) {
        for ((isCode, lang, t) in segments(answer)) {
            if (isCode) { if (t.isNotBlank()) addCodeCard(box, guessLang(lang, t), t) } else addText(box, t)
        }
        if (box.childCount == 0) addText(box, answer.ifBlank { "…" })
    }

    private fun guessLang(lang: String, code: String): String = when {
        lang.isNotBlank() -> lang
        code.trimStart().startsWith("<") -> "html"
        Regex("^\\s*(def |import |print\\(|for |from )", RegexOption.MULTILINE).containsMatchIn(code) -> "python"
        else -> ""
    }

    private fun addText(box: LinearLayout, t: String) {
        val s = t.trim(); if (s.isEmpty()) return
        box.addView(TextView(this).apply {
            text = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1"); textSize = 16f; setTextColor(Color.WHITE); setTextIsSelectable(true)
            setPadding(0, pad(2), 0, pad(2))
        })
    }

    private fun addCodeCard(box: LinearLayout, lang: String, code: String) {
        val lines = code.lines()
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded("#0B0F0D", 12f); setPadding(pad(10), pad(8), pad(10), pad(8)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "‹/› ${lang.ifBlank { "code" }} · ${lines.size} lines"; setTextColor(Color.parseColor("#9CC7AD")); textSize = 12f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(Button(this).apply { text = "⛶ Code page"; textSize = 12f; isAllCaps = false; setOnClickListener { codePage(lang, code) } })
        card.addView(head)
        val hs = HorizontalScrollView(this)
        hs.addView(TextView(this).apply {
            text = lines.take(12).joinToString("\n") + if (lines.size > 12) "\n…" else ""
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#E6F2EA"))
            setOnClickListener { codePage(lang, code) }
        })
        card.addView(hs)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 11f; isAllCaps = false; setOnClickListener { f() } }
            .also { row.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        b("📋 Copy") { copy(code) }
        if (lang in PY) b("▶ Chalao") { runPythonCard(box, code) }
        if (lang in HTML) b("📦 APK") { askApkName(code) }
        b("💾 GitHub") { saveToGitHubDialog(code, lang) }
        card.addView(row)
        box.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(6) })
    }

    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", t))
        Toast.makeText(this, "Copy ho gaya ✅", Toast.LENGTH_SHORT).show()
    }

    /** Separate full-screen code page: line numbers, Copy, Run, APK, Save. */
    private fun codePage(lang: String, code: String) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0F0D")) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#123524")); gravity = Gravity.CENTER_VERTICAL }
        head.addView(Button(this).apply { text = "← Wapas"; isAllCaps = false; setOnClickListener { d.dismiss() } })
        head.addView(TextView(this).apply { text = "  ${lang.ifBlank { "code" }} · ${code.lines().size} lines"; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(Button(this).apply { text = "📋 Copy"; isAllCaps = false; setOnClickListener { copy(code) } })
        val output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#FFE082")); setTextIsSelectable(true)
            setPadding(pad(12), pad(8), pad(12), pad(8)); visibility = View.GONE; setBackgroundColor(Color.parseColor("#1A1A10"))
        }
        val numbered = code.lines().mapIndexed { i, l -> "${(i + 1).toString().padStart(3)}  $l" }.joinToString("\n")
        val sv = ScrollView(this); val hs = HorizontalScrollView(this)
        hs.addView(TextView(this).apply {
            text = numbered; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#E6F2EA")); setTextIsSelectable(true)
            setPadding(pad(8), pad(10), pad(12), pad(10))
        })
        sv.addView(hs)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#123524")) }
        fun b(t: String, f: () -> Unit) = bottom.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (lang in PY) b("▶ Chalao") {
            output.visibility = View.VISIBLE; output.text = "▶ chal raha hai..."
            lifecycleScope.launch(Dispatchers.IO) {
                val r = try { Brain.runPython(code) } catch (e: Throwable) { "Error: ${e.message}" }
                withContext(Dispatchers.Main) { output.text = "Nateeja:\n$r" }
            }
        }
        if (lang in HTML) b("📦 APK") { d.dismiss(); askApkName(code) }
        b("💾 GitHub") { saveToGitHubDialog(code, lang) }
        root.addView(head)
        root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bottom)
        d.setContentView(root); d.show()
    }

    private fun runPythonCard(box: LinearLayout, code: String) {
        val out = TextView(this).apply {
            text = "▶ chal raha hai..."; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.parseColor("#FFE082"))
            background = rounded("#1A1A10", 10f); setPadding(pad(10), pad(8), pad(10), pad(8)); setTextIsSelectable(true)
        }
        box.addView(out, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(4) })
        scrollDown()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = try { Brain.runPython(code) } catch (e: Throwable) { "Error: ${e.message}" }
            withContext(Dispatchers.Main) { out.text = "Nateeja:\n$r"; scrollDown() }
        }
    }

    private fun onSend() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || !Brain.ready.value) return
        input.setText("")
        Brain.send(msg)
    }

    // ===================== GitHub =====================
    private fun githubMenu() {
        if (gh.token == null) { githubLogin(); return }
        AlertDialog.Builder(this).setTitle("🐙 GitHub (${prefs.getString(Brain.KEY_LOGIN, "")})")
            .setItems(arrayOf("📂 Meri repositories", "📦 HTML se APK banao (code paste karein)", "🚪 Logout")) { _, w ->
                when (w) {
                    0 -> Brain.scope.launch {
                        Brain.note("📂 Aap ki repositories:\n" + runCatching { gh.listRepos().joinToString("\n") }.getOrElse { "Error: ${it.message}" })
                    }
                    1 -> pasteHtmlForApk()
                    else -> { prefs.edit().remove(Brain.KEY_TOKEN).remove(Brain.KEY_LOGIN).apply(); gh.token = null; Brain.note("GitHub se logout ho gaya.") }
                }
            }.show()
    }

    private fun clientId(): String = prefs.getString(Brain.KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: BuildDefaults.GITHUB_CLIENT_ID

    private fun githubLogin() {
        val cid = clientId()
        if (cid.isBlank()) {
            val et = EditText(this).apply { hint = "GitHub OAuth App ka Client ID" }
            AlertDialog.Builder(this).setTitle("Ek dafa ka setup")
                .setMessage("GitHub → Settings → Developer settings → OAuth Apps → New. 'Enable Device Flow' on karein aur Client ID yahan daalein.")
                .setView(et).setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString(Brain.KEY_CLIENT_ID, et.text.toString().trim()).apply(); githubLogin()
                }.setNegativeButton("Baad me", null).show()
            return
        }
        Brain.scope.launch {
            try {
                val j = gh.startDeviceLogin(cid)
                val userCode = j.getString("user_code"); val uri = j.getString("verification_uri")
                withContext(Dispatchers.Main) {
                    copy(userCode)
                    AlertDialog.Builder(this@MainActivity).setTitle("GitHub se jorein")
                        .setMessage("Ye code copy ho gaya hai:\n\n      $userCode\n\nGitHub khulega → code paste karein → Authorize dabayein. Phir wapas yahan aa jayein.")
                        .setPositiveButton("GitHub kholein") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.show()
                }
                Brain.note("⏳ GitHub par Authorize ka intezar...")
                val interval = j.optLong("interval", 5) + 1
                val until = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000
                while (System.currentTimeMillis() < until) {
                    delay(interval * 1000)
                    val t = gh.pollDeviceLogin(cid, j.getString("device_code"))
                    if (t != null) {
                        gh.token = t
                        val login = gh.me()
                        prefs.edit().putString(Brain.KEY_TOKEN, t).putString(Brain.KEY_LOGIN, login).apply()
                        Brain.note("✅ GitHub jur gaya: $login"); return@launch
                    }
                }
                Brain.note("GitHub login ka waqt khatam ho gaya. Dobara 🐙 dabayein.")
            } catch (e: Exception) { Brain.note("GitHub login nahi hua: ${e.message}") }
        }
    }

    private fun needGitHub(): Boolean {
        if (gh.token != null) return true
        Toast.makeText(this, "Pehle 🐙 GitHub se jorein", Toast.LENGTH_SHORT).show(); githubLogin(); return false
    }

    private fun saveToGitHubDialog(code: String, lang: String) {
        if (!needGitHub()) return
        val ext = mapOf("python" to "py", "py" to "py", "html" to "html", "kotlin" to "kt", "java" to "java", "javascript" to "js", "js" to "js")[lang] ?: "txt"
        val repo = EditText(this).apply { hint = "owner/repo"; setText(prefs.getString(Brain.KEY_LAST_REPO, "")) }
        val path = EditText(this).apply { hint = "file ka naam"; setText("gupshup/code.$ext") }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(16), 0, pad(16), 0); addView(repo); addView(path) }
        AlertDialog.Builder(this).setTitle("💾 GitHub me save").setView(col).setPositiveButton("Save") { _, _ ->
            val r = repo.text.toString().trim(); prefs.edit().putString(Brain.KEY_LAST_REPO, r).apply()
            val p = path.text.toString().trim()
            Brain.scope.launch { Brain.note(runCatching { gh.writeFile(r, p, code, "Gup Shup se save") }.getOrElse { "Error: ${it.message}" }) }
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
            .setView(et).setPositiveButton("Banao") { _, _ -> Brain.buildApk(et.text.toString().trim().ifBlank { "Meri App" }, html) }
            .setNegativeButton("Rehne do", null).show()
    }

    /** Blocking yes/no for model tools running off the main thread. */
    fun confirm(title: String, msg: String): Boolean {
        val latch = CountDownLatch(1); var ok = false
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title).setMessage(msg.take(1500)).setCancelable(false)
                .setPositiveButton("Haan") { _, _ -> ok = true; latch.countDown() }
                .setNegativeButton("Nahi") { _, _ -> latch.countDown() }.show()
        }
        latch.await(3, TimeUnit.MINUTES); return ok
    }

    // ===================== model chooser =====================
    private fun showModelChooser() {
        AlertDialog.Builder(this).setTitle("Model chuno")
            .setItems(arrayOf(
                "🌟 Gemma 4 E4B — taqatwar, coding ke liye (3.7 GB, 8 GB+ RAM)",
                "⚡ Gemma 4 E2B — tez (2.6 GB, 6 GB+ RAM)",
                "📁 Phone se .litertlm file chuno"
            )) { _, w ->
                when (w) {
                    0 -> Brain.download("gemma4-e4b-litert-v1", "gemma4-e4b.litertlm")
                    1 -> Brain.download("gemma4-litert-v1", "gemma4-e2b.litertlm")
                    else -> pickFile.launch(arrayOf("*/*"))
                }
            }.show()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Brain.importFile { contentResolver.openInputStream(uri) }
    }

    companion object {
        private val PY = setOf("python", "py", "python3")
        private val HTML = setOf("html", "htm")
    }
}

/** GitHub OAuth Client ID baked at build time; if empty the app asks once. */
object BuildDefaults { const val GITHUB_CLIENT_ID = "" }
