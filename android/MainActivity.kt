package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Gup Shup — offline Roman Urdu chat on the phone.
 * Based on llama.cpp's Android sample (examples/llama.android).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    private var engine: InferenceEngine? = null
    private var generationJob: Job? = null
    private var isModelReady = false
    private var busy = false

    private val messages = mutableListOf<Message>()
    private val messageAdapter = MessageAdapter(messages)
    private val prefs by lazy { getSharedPreferences("gupshup", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)

        userInputEt.isEnabled = false
        statusTv.text = "Gup Shup shuru ho raha hai..."
        statusTv.setOnLongClickListener { if (!busy) showChooser(); true }

        userActionFab.setOnClickListener {
            if (isModelReady) handleUserInput() else if (!busy) showChooser()
        }

        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
            val saved = prefs.getString(KEY_MODEL, null)?.let { File(it) }
                ?.takeIf { it.exists() && it.length() > 50_000_000 }
            withContext(Dispatchers.Main) {
                if (saved != null) loadAndStart(saved) else showChooser()
            }
        }
    }

    // ---------------- model choose / download ----------------

    private fun showChooser() {
        AlertDialog.Builder(this)
            .setTitle("Model chuno (sirf pehli dafa)")
            .setItems(
                arrayOf(
                    "🌟 Sab se samajhdar (Gemma) — 3 GB (8 GB+ RAM)",
                    "🧠 Behtar model — 1.2 GB (6 GB+ RAM)",
                    "⚡ Tez model — 447 MB (har phone)",
                    "📁 Phone se .gguf file chuno"
                )
            ) { _, which ->
                when (which) {
                    0 -> download(listOf(URL_GEMMA_0, URL_GEMMA_1), "gupshup-gemma3n.gguf")
                    1 -> download(listOf(URL_BETTER), "gupshup-better.gguf")
                    2 -> download(listOf(URL_FAST), "gupshup-fast.gguf")
                    else -> pickFile.launch(arrayOf("*/*"))
                }
            }
            .setCancelable(isModelReady)
            .show()
    }

    private fun modelsDir() = File(filesDir, "models").also { if (!it.exists()) it.mkdirs() }

    private fun download(urls: List<String>, name: String) {
        busy = true
        userActionFab.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusTv.text = "Download shuru... (WiFi behtar hai)"
        lifecycleScope.launch(Dispatchers.IO) {
            val target = File(modelsDir(), name)
            val part = File(modelsDir(), "$name.part")
            try {
                if (!target.exists()) {
                    part.delete()
                    // total size of all parts (for the progress bar)
                    var total = 0L
                    for (u in urls) {
                        val h = (URL(u).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; instanceFollowRedirects = true }
                        total += h.contentLengthLong.coerceAtLeast(0); h.disconnect()
                    }
                    var done = 0L
                    var lastPct = -1
                    FileOutputStream(part, true).use { out ->
                        for (u in urls) {
                            val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = true
                                connectTimeout = 20_000
                                readTimeout = 60_000
                            }
                            conn.connect()
                            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                            conn.inputStream.use { input ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        withContext(Dispatchers.Main) {
                                            statusTv.text = "Download: $pct%  (${done / 1_048_576} / ${total / 1_048_576} MB)"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    part.renameTo(target)
                }
                withContext(Dispatchers.Main) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    loadAndStart(target)
                }
            } catch (e: Exception) {
                part.delete()
                withContext(Dispatchers.Main) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    busy = false
                    userActionFab.isEnabled = true
                    statusTv.text = "Download nahi hua (${e.message}). Internet check kar ke ➤ dabayein."
                }
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyPicked(it) }
    }

    private fun copyPicked(uri: Uri) {
        busy = true
        userActionFab.isEnabled = false
        statusTv.text = "File copy ho rahi hai..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = File(modelsDir(), "custom-${System.currentTimeMillis()}.gguf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { input.copyTo(it) }
                } ?: error("file nahi khuli")
                withContext(Dispatchers.Main) { loadAndStart(target) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    userActionFab.isEnabled = true
                    statusTv.text = "File load nahi hui (${e.message})."
                }
            }
        }
    }

    private fun loadAndStart(file: File) {
        busy = true
        isModelReady = false
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        statusTv.text = "Model load ho raha hai..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val eng = engine ?: AiChat.getInferenceEngine(applicationContext).also { engine = it }
                // wait for the native engine to finish starting, then unload any old model
                val st = eng.state.first {
                    it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing
                }
                if (st !is InferenceEngine.State.Initialized) runCatching { eng.cleanUp() }
                eng.loadModel(file.path)
                eng.setSystemPrompt(SYSTEM_PROMPT)
                prefs.edit().putString(KEY_MODEL, file.path).apply()
                withContext(Dispatchers.Main) {
                    isModelReady = true
                    busy = false
                    userInputEt.isEnabled = true
                    userInputEt.hint = "Kuch bhi likho..."
                    userActionFab.setImageResource(R.drawable.outline_send_24)
                    userActionFab.isEnabled = true
                    statusTv.text = "✅ Gup Shup tayyar — bina internet ke chal raha hai\n(Model badalne ke liye yahan dabaye rakhein)"
                    if (messages.isEmpty()) addBot("Assalam o Alaikum! Main Gup Shup hoon 🙂 Kuch bhi poocho!")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    userActionFab.isEnabled = true
                    statusTv.text = "Model load nahi hua (${e.message}). Doosra model chunein."
                    showChooser()
                }
            }
        }
    }

    // ---------------- chat ----------------

    private fun addUser(t: String) {
        messages.add(Message(UUID.randomUUID().toString(), t, true))
        messageAdapter.notifyItemInserted(messages.size - 1)
        messagesRv.scrollToPosition(messages.size - 1)
    }

    private fun addBot(t: String) {
        messages.add(Message(UUID.randomUUID().toString(), t, false))
        messageAdapter.notifyItemInserted(messages.size - 1)
        messagesRv.scrollToPosition(messages.size - 1)
    }

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "Pehle kuch likho 🙂", Toast.LENGTH_SHORT).show()
            return
        }
        userInputEt.text = null
        addUser(userMsg)

        // Safety + quick answers are handled on the phone, instantly
        localReply(userMsg)?.let { addBot(it); return }

        val eng = engine ?: return
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        addBot("…")
        val raw = StringBuilder()
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            eng.sendUserPrompt("$userMsg /no_think", MAX_REPLY_TOKENS)
                .onCompletion {
                    withContext(Dispatchers.Main) {
                        val last = messages.size - 1
                        if (last >= 0 && !messages[last].isUser && clean(raw.toString()).isBlank()) {
                            messages[last] = messages[last].copy(content = "Hmm 🤔 dobara poocho?")
                            messageAdapter.notifyItemChanged(last)
                        }
                        userInputEt.isEnabled = true
                        userActionFab.isEnabled = true
                    }
                }
                .collect { token ->
                    raw.append(token)
                    val shown = clean(raw.toString()).ifBlank { "…" }
                    withContext(Dispatchers.Main) {
                        val last = messages.size - 1
                        if (last >= 0 && !messages[last].isUser) {
                            messages[last] = messages[last].copy(content = shown)
                            messageAdapter.notifyItemChanged(last)
                            messagesRv.scrollToPosition(last)
                        }
                    }
                }
        }
    }

    /** Remove Qwen3 "thinking" tags from what the user sees. */
    private fun clean(s: String): String {
        var t = s.replace(Regex("(?s)<think>.*?</think>"), "")
        if (t.contains("<think>")) t = t.substringBefore("<think>")
        return t.replace("[end of text]", "").trim()
    }

    // ---------------- on-phone safety + quick replies ----------------

    private fun localReply(msg: String): String? {
        val l = msg.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ")
        if (Regex("khud ?kushi|suicide|mar+ ?jana|marna chaht|je+ne ka dil nahi|jeena nahi chaht|zindagi khatam|khud ko nuqsan|apni jaan").containsMatchIn(l))
            return SELF_HARM
        if (Regex("\\b(marta|maarta|marti|maarti|marte|maarte|peet|peetta|pitai|zulm karta|tashaddud|abuse)\\b|haath uthat").containsMatchIn(l))
            return ABUSE
        val now = Calendar.getInstance()
        if (Regex("\\b(time|wa?qt|baje)\\b").containsMatchIn(l) && Regex("\\b(kya|kia|kitne|batao|hua|ho)\\b").containsMatchIn(l))
            return "Abhi " + SimpleDateFormat("h:mm a", Locale.US).format(now.time) + " baje hain 🕐"
        if (Regex("\\b(tar(e+|i)kh|date)\\b").containsMatchIn(l))
            return "Aaj " + SimpleDateFormat("d MMMM yyyy", Locale.US).format(now.time) + " hai 📅"
        if (Regex("(kaun|kon|konsa|kaunsa) ?sa din|kya din hai").containsMatchIn(l))
            return "Aaj " + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + " hai 📆"
        return null
    }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) engine?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY_MODEL = "model_path"
        private const val MAX_REPLY_TOKENS = 120
        private const val URL_FAST =
            "https://github.com/abrar1499/bolkhata-brain/releases/download/phone-model-v1/gupshup-qwen3-0.6b-Q4_0.gguf"
        private const val URL_GEMMA_0 =
            "https://github.com/abrar1499/bolkhata-brain/releases/download/gemma3n-v1/gupshup-gemma3n-e2b.gguf.part0"
        private const val URL_GEMMA_1 =
            "https://github.com/abrar1499/bolkhata-brain/releases/download/gemma3n-v1/gupshup-gemma3n-e2b.gguf.part1"
        private const val URL_BETTER =
            "https://github.com/abrar1499/bolkhata-brain/releases/download/phone-model-v1/gupshup-qwen3-1.7b-Q4_0.gguf"
        private const val SYSTEM_PROMPT =
            "Tum \"Gup Shup\" ho, ek dostana saathi. Hamesha Roman Urdu me jawab do (Latin huroof, jaise WhatsApp par likhte hain). Jawab chhote, 1-3 jumle. Apna naam har jawab me mat likho. /no_think"
        private val DAYS = arrayOf("Itwaar", "Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta")
        private const val SELF_HARM =
            "Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo (Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai. Tumhari zindagi bohat qeemti hai."
        private const val ABUSE =
            "Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Kisi ko bhi aap par haath uthane ka haq nahi. Agar aap is waqt khatre me hain to foran police ko call karein (Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. Kisi bharose ke rishtedaar ya dost ko bhi zaroor batayein. Kya aap is waqt mehfooz hain?"
    }
}
