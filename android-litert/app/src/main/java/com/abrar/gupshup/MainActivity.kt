package com.abrar.gupshup

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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Gup Shup v3 — Gemma 4 E2B on the phone with Google's LiteRT-LM engine. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val saved = prefs.getString(KEY_MODEL, null)?.let { File(it) }?.takeIf { it.exists() && it.length() > 100_000_000 }
        if (saved != null) load(saved) else showChooser()
    }

    // ---------------- UI (built in code) ----------------

    private fun buildUi() {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.parseColor("#0E1A14") else Color.parseColor("#F4F7F5")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        status = TextView(this).apply {
            setPadding(pad(16), pad(12), pad(16), pad(12))
            setBackgroundColor(Color.parseColor("#123524")); setTextColor(Color.WHITE); textSize = 14f
            text = "Gup Shup shuru ho raha hai..."
            setOnLongClickListener { if (!busy) showChooser(); true }
        }
        scroll = ScrollView(this).apply { isFillViewport = true }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(10), pad(10), pad(10), pad(10)) }
        scroll.addView(chat, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(8), pad(8), pad(8), pad(8)); setBackgroundColor(Color.parseColor("#123524"))
        }
        input = EditText(this).apply {
            hint = "Kuch bhi likho..."; setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#9CC7AD"))
            imeOptions = EditorInfo.IME_ACTION_SEND; isSingleLine = true; isEnabled = false
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false }
        }
        send = Button(this).apply { text = "➤"; textSize = 18f; isEnabled = false; setOnClickListener { onSend() } }
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        setContentView(root)
    }

    private fun pad(v: Int) = (v * dp).toInt()

    private fun bubble(text: String, user: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text; textSize = 16f; setTextIsSelectable(true)
            setPadding(pad(12), pad(9), pad(12), pad(9))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(if (user) Color.parseColor("#1F7A4D") else Color.parseColor("#33493D"))
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (user) Gravity.END else Gravity.START; topMargin = pad(6)
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun setReady(r: Boolean) {
        ready = r; input.isEnabled = r; send.isEnabled = r
    }

    // ---------------- model download / pick ----------------

    private fun showChooser() {
        AlertDialog.Builder(this)
            .setTitle("Model chuno (sirf pehli dafa)")
            .setItems(arrayOf("🌟 Gemma 4 — 2.6 GB (6 GB+ RAM)", "📁 Phone se .litertlm file chuno")) { _, w ->
                if (w == 0) download(GEMMA_PARTS, "gemma4-e2b.litertlm") else pickFile.launch(arrayOf("*/*"))
            }
            .setCancelable(ready)
            .show()
    }

    private fun modelsDir() = File(filesDir, "models").also { it.mkdirs() }

    private fun download(urls: List<String>, name: String) {
        busy = true; setReady(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "Download shuru... (WiFi behtar hai)"
        lifecycleScope.launch(Dispatchers.IO) {
            val target = File(modelsDir(), name)
            val part = File(modelsDir(), "$name.part")
            try {
                if (!target.exists()) {
                    part.delete()
                    var total = 0L
                    for (u in urls) {
                        val h = (URL(u).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; instanceFollowRedirects = true }
                        total += h.contentLengthLong.coerceAtLeast(0); h.disconnect()
                    }
                    var done = 0L; var lastPct = -1
                    FileOutputStream(part, true).use { out ->
                        for (u in urls) {
                            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
                            }
                            c.connect()
                            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                            c.inputStream.use { inp ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = inp.read(buf); if (n < 0) break
                                    out.write(buf, 0, n); done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        withContext(Dispatchers.Main) {
                                            status.text = "Download: $pct%  (${done / 1_048_576} / ${total / 1_048_576} MB)"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    part.renameTo(target)
                }
                withContext(Dispatchers.Main) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); load(target)
                }
            } catch (e: Exception) {
                part.delete()
                withContext(Dispatchers.Main) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); busy = false
                    status.text = "Download nahi hua (${e.message}). Dobara koshish ke liye yahan dabaye rakhein."
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { busy = false; status.text = "File load nahi hui (${e.message})." }
            }
        }
    }

    // ---------------- engine ----------------

    private fun load(file: File) {
        busy = true; setReady(false)
        status.text = "Model load ho raha hai... (pehli dafa 10–30 second)"
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
                    withContext(Dispatchers.Main) {
                        busy = false
                        status.text = "Model load nahi hua (${e2.message}). Doosri file chunein."
                        showChooser()
                    }
                    return@launch
                }
            }
            engine = eng
            conversation = newConversation(eng)
            prefs.edit().putString(KEY_MODEL, file.path).apply()
            withContext(Dispatchers.Main) {
                busy = false; setReady(true)
                status.text = "✅ Gup Shup tayyar ($used) — bina internet\n(Model badalne ke liye yahan dabaye rakhein)"
                if (chat.childCount == 0) bubble("Assalam o Alaikum! Main Gup Shup hoon 🙂 Kuch bhi poocho!", false)
            }
        }
    }

    private fun newConversation(eng: Engine): Conversation =
        eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
        )

    private fun onSend() {
        val msg = input.text.toString().trim()
        if (msg.isEmpty() || !ready) return
        input.setText("")
        bubble(msg, true)
        localReply(msg)?.let { bubble(it, false); return }

        val conv = conversation ?: return
        setReady(false)
        val out = bubble("…", false)
        val sb = StringBuilder()
        job = lifecycleScope.launch {
            try {
                conv.sendMessageAsync(msg).collect { part ->
                    sb.append(part.toString())
                    out.text = sb.toString().trim().ifEmpty { "…" }
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                if (sb.isBlank()) out.text = "Hmm 🤔 dobara poocho?"
            } catch (e: Throwable) {
                out.text = "Masla aa gaya (${e.message}). Dobara koshish karein."
                runCatching { engine?.let { conversation?.close(); conversation = newConversation(it) } }
            } finally {
                setReady(true)
            }
        }
    }

    // ---------------- on-phone safety + quick replies ----------------

    private fun localReply(msg: String): String? {
        val l = msg.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ")
        if (Regex("khud ?kushi|suicide|mar+ ?jana|marna chaht|je+ne ka dil nahi|jeena nahi chaht|zindagi khatam|khud ko nuqsan|apni jaan").containsMatchIn(l)) return SELF_HARM
        if (Regex("\\b(marta|maarta|marti|maarti|marte|maarte|peet|peetta|pitai|zulm karta|tashaddud|abuse)\\b|haath uthat").containsMatchIn(l)) return ABUSE
        val now = Calendar.getInstance()
        if (Regex("\\b(time|wa?qt|baje)\\b").containsMatchIn(l) && Regex("\\b(kya|kia|kitne|batao|hua|ho)\\b").containsMatchIn(l))
            return "Abhi " + SimpleDateFormat("h:mm a", Locale.US).format(now.time) + " baje hain 🕐"
        if (Regex("\\b(tar(e+|i)kh|date)\\b").containsMatchIn(l)) return "Aaj " + SimpleDateFormat("d MMMM yyyy", Locale.US).format(now.time) + " hai 📅"
        if (Regex("(kaun|kon|konsa|kaunsa) ?sa din|kya din hai").containsMatchIn(l)) return "Aaj " + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + " hai 📆"
        return null
    }

    override fun onDestroy() {
        job?.cancel()
        if (isFinishing) { runCatching { conversation?.close() }; runCatching { engine?.close() } }
        super.onDestroy()
    }

    companion object {
        private const val KEY_MODEL = "model_path_v3"
        private val GEMMA_PARTS = listOf(
            "https://github.com/abrar1499/bolkhata-brain/releases/download/gemma4-litert-v1/gupshup-gemma4.litertlm.part0",
            "https://github.com/abrar1499/bolkhata-brain/releases/download/gemma4-litert-v1/gupshup-gemma4.litertlm.part1",
        )
        private const val SYSTEM_PROMPT =
            "Tum \"Gup Shup\" ho, ek dostana saathi. Hamesha Roman Urdu me jawab do (Latin huroof, jaise WhatsApp par likhte hain). " +
                "Jawab chhote rakho, 1-3 jumle. Apna naam har jawab me mat likho. Sanjeeda baat par mazaq mat karo."
        private val DAYS = arrayOf("Itwaar", "Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta")
        private const val SELF_HARM =
            "Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo (Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai. Tumhari zindagi bohat qeemti hai."
        private const val ABUSE =
            "Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Kisi ko bhi aap par haath uthane ka haq nahi. Agar aap is waqt khatre me hain to foran police ko call karein (Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. Kisi bharose ke rishtedaar ya dost ko bhi zaroor batayein. Kya aap is waqt mehfooz hain?"
    }
}
