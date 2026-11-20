package com.abrar.gupshup

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
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
import androidx.appcompat.app.AppCompatDelegate
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
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.ImageView
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gup Shup v5 — modern dark chat UI. The model, chat and work live in [Brain] and keep running in the background. */
class MainActivity : AppCompatActivity() {

    // ---- palette ----
    private val BG = 0xFF0D0F12.toInt()
    private val SURF = 0xFF16191E.toInt()
    private val SURF2 = 0xFF1F232A.toInt()
    private val LINE = 0xFF2A2F38.toInt()
    private val TEXT = 0xFFECEEF0.toInt()
    private val MUTED = 0xFF8E959F.toInt()
    private val ACCENT = 0xFF19C37D.toInt()
    private val ACCENT2 = 0xFF0E8F6A.toInt()
    private val CODE_BG = 0xFF0A0C0F.toInt()
    private val GOLD = 0xFFF5C26B.toInt()

    private lateinit var status: TextView
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private var emptyView: View? = null
    private val views = ArrayList<View>()
    private var streamBox: LinearLayout? = null
    private var typing: ObjectAnimator? = null
    private var liveDialog: Dialog? = null
    private var liveCode: TextView? = null
    private var liveScroll: ScrollView? = null
    private var liveHead: TextView? = null
    private var lastStreamUi = 0L
    /** Typewriter: how many characters of the streaming reply are shown so far. */
    private var typed = 0
    private val dp by lazy { resources.displayMetrics.density }
    private val prefs get() = Brain.prefs
    private val gh get() = Brain.gh

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        Brain.init(this)
        Brain.activity = WeakReference(this)
        @Suppress("DEPRECATION") run { window.statusBarColor = SURF; window.navigationBarColor = BG }
        buildUi()
        renderAll()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Brain.status.collect { status.text = it } }
                launch { Brain.ready.collect { setSendEnabled(it) } }
                launch { Brain.events.collect { onEvent(it) } }
                launch { while (true) { syncNew(); maybeCheck(); delay(1500) } }
                launch {   // typewriter: reveal the reply letter by letter, catching up smoothly with the model
                    while (true) {
                        delay(28)
                        val st = Brain.streaming ?: continue
                        if (st == "…" || typed >= st.length) continue
                        val gap = st.length - typed
                        typed += maxOf(1, gap / 6)
                        updateStream(force = true)
                    }
                }
            }
        }
        if (!Brain.hasModel && !Brain.busy) showModelChooser()
    }

    override fun onResume() { super.onResume(); Brain.activity = WeakReference(this); renderAll(); maybeCheck() }

    override fun onDestroy() {
        if (Brain.activity?.get() === this) Brain.activity = null
        runCatching { tts?.shutdown() }; tts = null
        closeLive(); super.onDestroy()
    }

    // ===================== design helpers =====================
    private fun pad(v: Int) = (v * dp).toInt()
    private fun shape(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = r * dp; setColor(color); stroke?.let { setStroke(pad(1), it) }
    }
    private fun gradient(r: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT2)).apply { cornerRadius = r * dp }
    private fun ripple(d: Drawable) = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), d, null)

    /** Flat rounded "chip" button. */
    private fun chip(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(if (primary) Color.BLACK else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER; setPadding(pad(12), pad(7), pad(12), pad(7))
        background = ripple(if (primary) shape(ACCENT, 18f) else shape(SURF2, 18f, LINE))
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun iconBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 17f; gravity = Gravity.CENTER; setTextColor(TEXT)
        background = ripple(shape(SURF2, 20f, LINE)); isClickable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(pad(40), pad(40)).apply { marginStart = pad(6) }
    }

    private fun avatar(size: Int) = TextView(this).apply {
        text = "G"; gravity = Gravity.CENTER; setTextColor(Color.BLACK); textSize = size * 0.42f
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD); background = gradient(size / 2f)
        layoutParams = LinearLayout.LayoutParams(pad(size), pad(size))
    }

    private fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; v.forEach { addView(it) } }
    private fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    // ===================== layout =====================
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURF); setPadding(pad(14), pad(10), pad(10), pad(10)); elevation = 6 * dp
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(10), 0, pad(6), 0) }
        titles.addView(TextView(this).apply {
            text = "Gup Shup"; textSize = 18f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 11.5f; setTextColor(MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        titles.addView(status)
        header.addView(iconBtn("☰") { chatsPage() }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(iconBtn("📄") { artifactsPage() })
        header.addView(iconBtn("⚙️") { if (!Brain.busy) modelMenu() })
        header.addView(iconBtn("✎") { Brain.newChat() })

        // chat
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(14), pad(12), pad(14), pad(18)) }
        scroll.addView(chat, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // composer
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(pad(12), pad(8), pad(12), pad(12)); setBackgroundColor(BG)
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; background = shape(SURF2, 26f, LINE)
            setPadding(pad(16), pad(4), pad(5), pad(5))
        }
        input = EditText(this).apply {
            hint = "Gup Shup se poocho…"; setTextColor(TEXT); setHintTextColor(MUTED); textSize = 15.5f
            background = null; maxLines = 6; minHeight = pad(44); setPadding(0, pad(8), pad(8), pad(8))
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        send = TextView(this).apply {
            text = "↑"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD; isClickable = true
            setOnClickListener { if (Brain.streaming != null) Brain.stop() else onSend() }
        }
        val clip = TextView(this).apply {
            text = "＋"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(MUTED); isClickable = true
            background = ripple(shape(0x00000000, 20f)); setOnClickListener { attachMenu() }
        }
        val mic = TextView(this).apply {
            text = "🎤"; textSize = 18f; gravity = Gravity.CENTER; isClickable = true
            background = ripple(shape(0x00000000, 20f)); setOnClickListener { startVoice() }
            setOnLongClickListener { voiceLangMenu(); true }
        }
        pill.setPadding(pad(4), pad(4), pad(5), pad(5))
        pill.addView(clip, LinearLayout.LayoutParams(pad(40), pad(40)))
        pill.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pill.addView(mic, LinearLayout.LayoutParams(pad(40), pad(40)))
        pill.addView(send, LinearLayout.LayoutParams(pad(40), pad(40)))
        attachBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(pad(6), 0, pad(6), pad(6)) }
        bar.orientation = LinearLayout.VERTICAL
        bar.addView(attachBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        bar.addView(pill, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setSendEnabled(false)

        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom); insets
        }
        setContentView(root)
    }

    private var sendMode = ""
    private fun setSendEnabled(on: Boolean) {
        if (!::send.isInitialized) return
        input.isEnabled = on
        val mode = if (on) "send" else if (Brain.streaming != null) "stop" else "wait"
        if (mode == sendMode) return
        sendMode = mode
        send.isEnabled = mode != "wait"
        when (mode) {
            "send" -> { send.background = ripple(gradient(20f)); send.text = "↑"; send.textSize = 20f; send.setTextColor(Color.BLACK) }
            "stop" -> { send.background = ripple(shape(0xFFE5484D.toInt(), 20f)); send.text = "■"; send.textSize = 16f; send.setTextColor(Color.WHITE) }
            else -> { send.background = shape(LINE, 20f); send.text = "•••"; send.textSize = 12f; send.setTextColor(MUTED) }
        }
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    private fun snapshot(): List<Msg> = synchronized(Brain.messages) { Brain.messages.map { it.copy() } }

    // ===================== empty state =====================
    private fun showEmpty() {
        if (emptyView != null) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, pad(48), 0, 0) }
        box.addView(avatar(64))
        box.addView(TextView(this).apply {
            text = "Assalam o Alaikum 👋"; textSize = 24f; setTextColor(TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setPadding(0, pad(16), 0, pad(4))
        })
        box.addView(TextView(this).apply {
            text = "Main aap ka bina-internet AI saathi hoon.\nGup shup, code, apps aur GitHub — sab yahin."; textSize = 14f; setTextColor(MUTED)
            gravity = Gravity.CENTER; setPadding(pad(20), 0, pad(20), pad(22))
        })
        listOf(
            "🧮  Calculator ki app banao" to "Ek khoobsurat calculator app banao (mobile HTML)",
            "🎮  Chhota sa game banao" to "Ek mazedar chhota game banao jo phone par touch se khele (mobile HTML)",
            "🌸  Phool wala design banao" to "Canvas par gol phool aur bel-boote wala khoobsurat design banao",
            "🐍  Python se hisaab" to "Python code likho jo 1 se 100 tak ke prime numbers print kare",
        ).forEach { (label, prompt) ->
            box.addView(TextView(this).apply {
                text = label; textSize = 14.5f; setTextColor(TEXT); setPadding(pad(16), pad(13), pad(16), pad(13))
                background = ripple(shape(SURF, 16f, LINE)); isClickable = true
                setOnClickListener { if (Brain.ready.value) Brain.send(prompt) else Toast.makeText(this@MainActivity, "Model tayyar ho raha hai…", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(8) })
        }
        emptyView = box; chat.addView(box)
    }

    private fun hideEmpty() { emptyView?.let { chat.removeView(it) }; emptyView = null }

    // ===================== rendering from Brain =====================
    private fun renderAll() {
        if (!::chat.isInitialized) return
        chat.removeAllViews(); views.clear(); streamBox = null; emptyView = null
        val msgs = snapshot()
        lastBotIndex = msgs.indexOfLast { it.role == "bot" }
        if (msgs.none { it.role != "note" } && Brain.streaming == null) showEmpty()
        msgs.forEachIndexed { i, m -> addMsgView(m, i) }
        updateStream(force = true); scrollDown()
    }

    private fun syncNew() {
        val msgs = snapshot()
        if (msgs.size < views.size) { renderAll(); return }
        if (msgs.size > views.size) {
            if (msgs.any { it.role != "note" }) hideEmpty()
            val wasLive = liveDialog?.isShowing == true && Brain.streaming == null
            removeStream()
            val oldLastBot = lastBotIndex
            for (i in views.size until msgs.size) addMsgView(msgs[i], i)
            lastBotIndex = msgs.indexOfLast { it.role == "bot" }
            if (oldLastBot in 0 until views.size && oldLastBot != lastBotIndex) onEvent(oldLastBot)
            scrollDown()
            if (wasLive) {
                closeLive()
                segments(msgs.last().text).lastOrNull { it.first && it.third.isNotBlank() }?.let { (_, lang, code) -> codePage(guessLang(lang, code), code) }
            }
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
                    chat.removeViewAt(idx); val v = msgView(m, e); chat.addView(v, idx); views[e] = v
                } else syncNew()
            }
            else -> syncNew()
        }
        maybeCheck()
    }

    private var lastBotIndex = -1
    private fun addMsgView(m: Msg, i: Int) { val v = msgView(m, i); chat.addView(v); views += v }

    private fun msgView(m: Msg, index: Int): View = when (m.role) {
        "user" -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            setPadding(pad(48), pad(10), 0, pad(4))
            if (m.img.isNotBlank()) thumb(m.img)?.let { addView(it, LinearLayout.LayoutParams(pad(180), WRAP_CONTENT).apply { gravity = Gravity.END; bottomMargin = pad(4) }) }
            addView(TextView(this@MainActivity).apply {
                text = m.text; textSize = 15.5f; setTextColor(Color.BLACK)
                setOnLongClickListener { userMenu(m, index); true }
                setPadding(pad(14), pad(10), pad(14), pad(10))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2BD99A.toInt(), ACCENT)).apply {
                    cornerRadii = floatArrayOf(20f, 20f, 20f, 20f, 6f, 6f, 20f, 20f).map { it * dp }.toFloatArray()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        "note" -> LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad(36), pad(6), pad(8), pad(2))
            val inner = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; background = shape(SURF, 12f, LINE); setPadding(pad(12), pad(8), pad(12), pad(8))
            }
            renderAnswer(inner, m.text, small = true); apkButton(inner, m.text)
            addView(inner)
        }
        else -> assistantRow().also { (_, col) ->
            renderingArt = m.art.substringBefore(':').toIntOrNull() ?: -1
            renderAnswer(col, m.text); apkButton(col, m.text)
            renderingArt = -1
            artCard(col, m.art)
            col.addView(row(
                miniBtn("⧉ Copy") { copy(m.text) },
                miniBtn("🔊 Suno") { speak(m.text) },
            ).apply { setPadding(0, pad(6), 0, 0); if (index == lastBotIndex) addView(miniBtn("🔁 Dobara") { if (Brain.ready.value) Brain.regenerate() }) })
            if (m.info.isNotBlank()) col.addView(TextView(this).apply {
                text = m.info; textSize = 11f; setTextColor(0xFF6B727C.toInt()); setPadding(0, pad(2), 0, 0)
            })
        }.first
    }

    /** Assistant message layout: small avatar + full-width content (Claude style, no bubble). */
    private fun assistantRow(): Pair<LinearLayout, LinearLayout> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, pad(14), 0, pad(4))
            addView(avatar(26).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = pad(2) })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = pad(10) })
        }
        return row to col
    }

    private fun apkButton(box: LinearLayout, text: String) {
        Regex("https://\\S+\\.apk").find(text)?.value?.let { link ->
            box.addView(chip("⬇  APK download karein", primary = true) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = pad(8) })
        }
    }

    private fun removeStream() {
        typing?.cancel(); typing = null
        streamBox?.let { chat.removeView(it) }; streamBox = null
    }

    private fun updateStream(force: Boolean = false) {
        setSendEnabled(Brain.ready.value)
        val s = Brain.streaming
        if (s == null) { removeStream(); return }
        val now = System.currentTimeMillis()
        if (!force && streamBox != null && now - lastStreamUi < 200) return
        lastStreamUi = now
        hideEmpty()
        val col: LinearLayout
        if (streamBox == null) {
            val (row, c) = assistantRow(); streamBox = row; chat.addView(row); col = c; typed = 0
        } else col = streamBox!!.getChildAt(1) as LinearLayout
        col.removeAllViews()
        if (s == "…") {   // thinking dots
            val dots = TextView(this).apply { text = "● ● ●"; textSize = 12f; setTextColor(ACCENT); setPadding(0, pad(6), 0, pad(6)) }
            col.addView(dots)
            typing?.cancel()
            typing = ObjectAnimator.ofFloat(dots, "alpha", 1f, 0.25f).apply {
                duration = 550; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
            scrollDown(); return
        }
        typing?.cancel(); typing = null
        var lastCode: Pair<String, String>? = null
        val shown = s.take(typed.coerceIn(0, s.length))
        val segs = segments(shown)
        for ((idx, seg) in segs.withIndex()) {
            val (isCode, lang, t) = seg
            if (!isCode) { addText(col, if (idx == segs.lastIndex) "$t▍" else t); continue }
            val l = guessLang(lang, t); lastCode = l to t
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(CODE_BG, 14f, LINE) }
            val head = row(
                TextView(this).apply { text = "  ✍️  ${l.ifBlank { "code" }} likh raha hoon · ${t.lines().size} lines"; setTextColor(GOLD); textSize = 12f },
                space(),
                chip("⛶") { openLive() }
            ).apply { setPadding(pad(6), pad(6), pad(6), pad(6)); background = shape(SURF, 14f) }
            card.addView(head)
            card.addView(TextView(this).apply {
                text = t.lines().takeLast(12).joinToString("\n"); typeface = Typeface.MONOSPACE; textSize = 12f
                setTextColor(0xFFCFE8DA.toInt()); maxLines = 12; setPadding(pad(12), pad(8), pad(12), pad(10))
            })
            col.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(8) })
        }
        if (col.childCount == 0) addText(col, "▍")
        lastCode?.let { (l, code) ->
            liveHead?.text = "  ✍️ ${l.ifBlank { "code" }} · ${code.lines().size} lines…"
            liveCode?.text = numbered(code)
            liveScroll?.post { liveScroll?.fullScroll(View.FOCUS_DOWN) }
        }
        scrollDown()
    }

    private fun numbered(code: String): CharSequence {
        val sb = SpannableStringBuilder()
        code.lines().forEachIndexed { i, l ->
            val start = sb.length
            sb.append((i + 1).toString().padStart(3)).append("  ")
            sb.setSpan(ForegroundColorSpan(0xFF4B525C.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(l).append('\n')
        }
        return sb
    }

    // ===================== text (light markdown) =====================
    private fun md(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        src.trim().lines().forEachIndexed { i, raw ->
            if (i > 0) sb.append('\n')
            var line = raw; var lineStart = sb.length; var big = false
            when {
                line.startsWith("### ") || line.startsWith("## ") || line.startsWith("# ") -> { line = line.trimStart('#', ' '); big = true }
                Regex("^\\s*[-*] ").containsMatchIn(line) -> line = line.replaceFirst(Regex("^\\s*[-*] "), "  •  ")
            }
            lineStart = sb.length
            // inline **bold** and `code`
            val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
            var last = 0
            for (m in re.findAll(line)) {
                sb.append(line.substring(last, m.range.first))
                val s0 = sb.length
                if (m.groupValues[1].isNotEmpty()) {
                    sb.append(m.groupValues[1]); sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    sb.append(" ${m.groupValues[2]} ")
                    sb.setSpan(TypefaceSpan("monospace"), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(SURF2), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(GOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                last = m.range.last + 1
            }
            sb.append(line.substring(last))
            if (big) {
                sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.15f), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun addText(box: LinearLayout, t: String, small: Boolean = false) {
        if (t.isBlank()) return
        box.addView(TextView(this).apply {
            text = md(t); textSize = if (small) 13.5f else 15.5f; setTextColor(if (small) MUTED else TEXT); setTextIsSelectable(true)
            setLineSpacing(0f, 1.18f); setPadding(0, pad(3), 0, pad(3))
        })
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

    private fun renderAnswer(box: LinearLayout, answer: String, small: Boolean = false) {
        for ((isCode, lang, t) in segments(answer)) {
            if (isCode) { if (t.isNotBlank()) addCodeCard(box, guessLang(lang, t), t) } else addText(box, t, small)
        }
        if (box.childCount == 0) addText(box, answer.ifBlank { "…" }, small)
    }

    private fun guessLang(lang: String, code: String): String = when {
        lang.isNotBlank() -> lang
        code.trimStart().startsWith("<") -> "html"
        Regex("^\\s*(def |import |print\\(|for |from )", RegexOption.MULTILINE).containsMatchIn(code) -> "python"
        else -> ""
    }

    // ===================== code card =====================
    private fun addCodeCard(box: LinearLayout, lang: String, code: String) {
        val lines = code.lines()
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(CODE_BG, 14f, LINE) }
        val langChip = TextView(this).apply {
            text = lang.ifBlank { "code" }; textSize = 11.5f; setTextColor(ACCENT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = shape(0xFF0F2A20.toInt(), 8f); setPadding(pad(8), pad(3), pad(8), pad(3))
        }
        val head = row(
            langChip,
            TextView(this).apply { text = "  ${lines.size} lines"; textSize = 11.5f; setTextColor(MUTED) },
            space(),
            chip("⧉ Copy") { copy(code) },
            View(this).apply { layoutParams = LinearLayout.LayoutParams(pad(6), 1) },
            chip("⛶") { codePage(lang, code) },
        ).apply { setPadding(pad(10), pad(7), pad(7), pad(7)); background = shape(SURF, 14f) }
        card.addView(head)
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(TextView(this).apply {
            text = numbered(lines.take(14).joinToString("\n")).trimEnd()
            typeface = Typeface.MONOSPACE; textSize = 12.5f; setTextColor(0xFFCFE8DA.toInt())
            setPadding(pad(8), pad(10), pad(14), pad(6)); setOnClickListener { codePage(lang, code) }
        })
        card.addView(hs)
        if (lines.size > 14) card.addView(TextView(this).apply {
            text = "  ⌄  ${lines.size - 14} aur lines — poora dekhne ke liye ⛶"; textSize = 12f; setTextColor(MUTED)
            setPadding(pad(10), 0, pad(10), pad(6)); setOnClickListener { codePage(lang, code) }
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad(8), pad(4), pad(8), pad(10)) }
        fun a(label: String, primary: Boolean = false, f: () -> Unit) =
            actions.addView(chip(label, primary, f), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = pad(6) })
        if (lang in PY) a("▶ Chalao", true) { runPythonCard(box, code) }
        if (lang in HTML) a("👁 Dekho", true) { preview(code) }
        val myArt = renderingArt
        a("✨ Behtar") { improve(myArt) }
        if (lang in HTML) a("📦 APK") { askApkName(code) }
        a("💾") { saveToGitHubDialog(code, lang) }
        card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        box.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(8); bottomMargin = pad(4) })
    }

    /** One tap: the model finds mistakes in the artifact and fixes them with small edits (no full rewrite). */
    private var renderingArt = -1
    private fun improve(artId: Int = Brain.currentArt) {
        if (!Brain.ready.value) { Toast.makeText(this, "Pehle jawab poora hone dein", Toast.LENGTH_SHORT).show(); return }
        if (artId >= 0 && Brain.art(artId) != null)
            Brain.edit(artId, "Is file me ghaltiyan dhoondo aur theek karo (JavaScript errors, kaam na karne wale buttons, adhoora hissa, placeholder). Phir design aur features thore behtar karo.", "✨ Ghaltiyan dhoond kar theek karo")
        else Brain.send("Apna upar wala code khud check karo: koi galti, adhoora hissa ya placeholder ho to theek karo, aur poora final version do.")
    }

    // ===================== artifacts =====================
    private fun artCard(box: LinearLayout, tag: String) {
        val id = tag.substringBefore(':').toIntOrNull() ?: return
        val v = tag.substringAfter(':').toIntOrNull() ?: return
        val a = Brain.art(id) ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ripple(shape(SURF, 14f, ACCENT2)); setPadding(pad(12), pad(10), pad(8), pad(10))
            isClickable = true; setOnClickListener { artifactView(id, v) }
        }
        card.addView(TextView(this).apply { text = "📄"; textSize = 22f })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad(10), 0, pad(6), 0)
            addView(TextView(this@MainActivity).apply { text = a.title; textSize = 15f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(TextView(this@MainActivity).apply { text = "Artifact · ${a.lang.ifBlank { "code" }} · v$v"; textSize = 12f; setTextColor(MUTED) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (a.lang in HTML) card.addView(chip("👁", true) { a.versions.getOrNull(v - 1)?.let { preview(it) } })
        card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(pad(6), 1) })
        card.addView(chip("Kholo") { artifactView(id, v) })
        box.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(8) })
    }

    private fun artifactsPage() {
        lateinit var d: Dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(pageHeader("📄 Artifacts", { d.dismiss() }))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(14), pad(12), pad(14), pad(20)) }
        val arts = Brain.artList()
        if (arts.isEmpty()) list.addView(TextView(this).apply {
            text = "Abhi koi artifact nahi.\nKahein: \"calculator ki app banao\" — jo bhi banega yahan mehfooz ho jayega."
            setTextColor(MUTED); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, pad(60), 0, 0)
        })
        arts.forEach { a ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ripple(shape(SURF, 14f, if (a.id == Brain.currentArt) ACCENT2 else LINE)); setPadding(pad(14), pad(12), pad(12), pad(12))
                isClickable = true; setOnClickListener { d.dismiss(); artifactView(a.id, a.versions.size) }
                addView(TextView(this@MainActivity).apply { text = if (a.lang in HTML) "🌐" else if (a.lang in PY) "🐍" else "📄"; textSize = 22f })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(pad(12), 0, 0, 0)
                    addView(TextView(this@MainActivity).apply { text = a.title; textSize = 15.5f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
                    addView(TextView(this@MainActivity).apply { text = "${a.lang.ifBlank { "code" }} · ${a.versions.size} version · ${a.versions.last().lines().size} lines"; textSize = 12f; setTextColor(MUTED) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(8) })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d = fullDialog(root); d.show()
    }

    /** Artifact screen: versions, live preview / code tabs, and actions (edit, copy, APK, save). */
    private fun artifactView(id: Int, startVersion: Int) {
        val a = Brain.art(id) ?: return
        var v = startVersion.coerceIn(1, a.versions.size)
        lateinit var d: Dialog
        val isHtml = a.lang in HTML
        var showCode = !isHtml
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(CODE_BG) }
        val body = android.widget.FrameLayout(this)
        val web = if (isHtml) android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; webChromeClient = android.webkit.WebChromeClient()
        } else null
        val codeText = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(0xFFCFE8DA.toInt()); setTextIsSelectable(true); setPadding(pad(8), pad(10), pad(14), pad(10)) }
        val codeView = ScrollView(this).apply { addView(HorizontalScrollView(this@MainActivity).apply { addView(codeText) }) }
        web?.let { body.addView(it) }; body.addView(codeView)
        val versions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad(10), pad(6), pad(10), pad(6)) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad(10), 0, pad(10), pad(6)) }
        fun code() = a.versions[v - 1]
        fun refresh() {
            versions.removeAllViews()
            for (i in 1..a.versions.size) versions.addView(chip("v$i", i == v) { v = i; refresh() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = pad(6) })
            tabs.removeAllViews()
            if (isHtml) {
                tabs.addView(chip("👁 Nateeja", !showCode) { showCode = false; refresh() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = pad(6) })
                tabs.addView(chip("‹/› Code", showCode) { showCode = true; refresh() })
            }
            codeText.text = numbered(code())
            codeView.visibility = if (showCode) View.VISIBLE else View.GONE
            web?.visibility = if (showCode) View.GONE else View.VISIBLE
            if (!showCode) web?.loadDataWithBaseURL("https://gupshup.local/", withViewport(code()), "text/html", "utf-8", null)
        }
        root.addView(pageHeader("📄 ${a.title}", { d.dismiss() }, chip("⧉") { copy(code()) }))
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(versions); setBackgroundColor(SURF) })
        root.addView(tabs.apply { setBackgroundColor(SURF) })
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(SURF); setPadding(pad(10), pad(10), pad(10), pad(10)) }
        fun b(t: String, primary: Boolean = false, f: () -> Unit) = bottom.addView(chip(t, primary, f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = pad(6) })
        b("✏️ Badlo", true) {
            val et = EditText(this).apply { hint = "Kya badalna hai? (jaise: dark mode daal do)" }
            AlertDialog.Builder(this).setTitle("✏️ ${a.title} me tabdeeli").setView(et).setPositiveButton("Badlo") { _, _ ->
                val ask = et.text.toString().trim()
                if (ask.isNotEmpty() && Brain.ready.value) { d.dismiss(); Brain.edit(a.id, ask) }
            }.setNegativeButton("Rehne do", null).show()
        }
        b("✨ Theek") { d.dismiss(); improve(a.id) }
        if (a.lang in PY) b("▶") { d.dismiss(); codePage(a.lang, code()) }
        if (isHtml) b("📦") { askApkName(code()) }
        b("💾") { saveToGitHubDialog(code(), a.lang) }
        root.addView(bottom)
        d = fullDialog(root); d.setOnDismissListener { web?.destroy() }
        refresh(); d.show()
    }

    private fun copy(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", t))
        Toast.makeText(this, "Copy ho gaya ✅", Toast.LENGTH_SHORT).show()
    }

    private fun pageHeader(title: String, onBack: () -> Unit, vararg extra: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(SURF)
        setPadding(pad(8), pad(8), pad(8), pad(8)); elevation = 6 * dp
        addView(chip("←") { onBack() })
        addView(TextView(this@MainActivity).apply {
            text = title; setTextColor(TEXT); textSize = 15f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(pad(12), 0, pad(8), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        extra.forEach { addView(it); (it.layoutParams as? LinearLayout.LayoutParams)?.marginStart = pad(6) }
    }

    private fun fullDialog(content: View): Dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        setContentView(content)
        @Suppress("DEPRECATION") window?.apply { statusBarColor = SURF; navigationBarColor = BG }
    }

    /** Live code page — only when the user taps ⛶ while the model is writing. */
    private fun openLive() {
        if (liveDialog?.isShowing == true) return
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(CODE_BG) }
        liveHead = TextView(this).apply { setTextColor(GOLD) }
        val head = pageHeader("Code likha ja raha hai…", { closeLive() })
        val sv = ScrollView(this); val hs = HorizontalScrollView(this)
        liveCode = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(0xFFCFE8DA.toInt()); setPadding(pad(8), pad(10), pad(14), pad(10)) }
        hs.addView(liveCode); sv.addView(hs); liveScroll = sv
        root.addView(head); root.addView(liveHead); root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val d = fullDialog(root); d.setOnCancelListener { closeLive() }
        liveDialog = d; d.show(); updateStream(force = true)
    }

    private fun closeLive() {
        runCatching { liveDialog?.dismiss() }
        liveDialog = null; liveCode = null; liveScroll = null; liveHead = null
    }

    /** Separate full-screen code page: line numbers + actions. */
    private fun codePage(lang: String, code: String) {
        lateinit var d: Dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(CODE_BG) }
        val head = pageHeader("${lang.ifBlank { "code" }} · ${code.lines().size} lines", { d.dismiss() }, chip("⧉ Copy") { copy(code) })
        val output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(GOLD); setTextIsSelectable(true)
            setPadding(pad(14), pad(10), pad(14), pad(10)); visibility = View.GONE; setBackgroundColor(0xFF15140E.toInt())
        }
        val sv = ScrollView(this); val hs = HorizontalScrollView(this)
        hs.addView(TextView(this).apply {
            text = numbered(code); typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(0xFFCFE8DA.toInt()); setTextIsSelectable(true)
            setPadding(pad(8), pad(10), pad(14), pad(10))
        })
        sv.addView(hs)
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(SURF); setPadding(pad(10), pad(10), pad(10), pad(10)) }
        fun b(t: String, primary: Boolean = false, f: () -> Unit) = bottom.addView(chip(t, primary, f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = pad(6) })
        if (lang in PY) b("▶ Chalao", true) {
            output.visibility = View.VISIBLE; output.text = "▶ chal raha hai..."
            lifecycleScope.launch(Dispatchers.IO) {
                val r = try { Brain.runPython(code) } catch (e: Throwable) { "Error: ${e.message}" }
                withContext(Dispatchers.Main) { output.text = "Nateeja:\n$r" }
            }
        }
        if (lang in HTML) b("👁 Dekho", true) { preview(code) }
        b("✨ Behtar") { d.dismiss(); improve() }
        if (lang in HTML) b("📦 APK") { d.dismiss(); askApkName(code) }
        b("💾") { saveToGitHubDialog(code, lang) }
        root.addView(head)
        root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bottom)
        d = fullDialog(root); d.show()
    }

    /** Run HTML/JS and show the real result full screen. */
    private fun preview(html: String) {
        lateinit var d: Dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val web = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webChromeClient = android.webkit.WebChromeClient()
        }
        val page = withViewport(html)
        val reload = chip("⟳") { web.loadDataWithBaseURL("https://gupshup.local/", page, "text/html", "utf-8", null) }
        root.addView(pageHeader("👁 Nateeja", { d.dismiss() }, reload))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d = fullDialog(root)
        d.setOnDismissListener { web.destroy() }
        web.loadDataWithBaseURL("https://gupshup.local/", page, "text/html", "utf-8", null)
        d.show()
    }

    // ===================== automatic self-test of HTML answers =====================
    private var checking = false

    private fun withViewport(html: String): String =
        if (html.contains("name=\"viewport\"", true) || html.contains("name='viewport'", true)) html
        else Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { m ->
            html.replaceRange(m.range.last + 1, m.range.last + 1, VIEWPORT)
        } ?: (VIEWPORT + html)

    private fun maybeCheck() {
        val i = Brain.pendingCheck
        if (i < 0 || checking || Brain.busy) return
        Brain.pendingCheck = -1
        val m = snapshot().getOrNull(i) ?: return
        val artId = m.art.substringBefore(':').toIntOrNull() ?: -1
        val ver = m.art.substringAfter(':').toIntOrNull() ?: 0
        val html = Brain.art(artId)?.versions?.getOrNull(ver - 1)
            ?: segments(m.text).lastOrNull { it.first && guessLang(it.second, it.third) in HTML }?.third ?: return
        checking = true
        val n = Brain.note("🔍 Khud test kar raha hoon (har button daba kar)…")
        val round = Brain.lastRound
        selfTest(html) { issues ->
            checking = false
            when {
                issues.isEmpty() -> Brain.change(n, "✅ Test pass: koi error ya ghalti nahi mili. 👁 dabayein.")
                round < 1 && Brain.ready.value -> {
                    Brain.change(n, "🔧 Test me ${issues.size} masle mile, sirf wohi hisse theek kar raha hoon:\n" + issues.joinToString("\n") { "• $it" })
                    val ask = "Test me ye masle mile, sirf inhein theek karo:\n" + issues.joinToString("\n") { "- $it" }
                    if (artId >= 0) Brain.edit(artId, ask, shown = null, round = 1)
                    else Brain.send(ask + "\nPoora final code ek ```html block me do.", hidden = true, round = 1)
                }
                else -> Brain.change(n, "⚠️ Abhi bhi kuch masle hain:\n" + issues.joinToString("\n") { "• $it" } + "\n✨ Behtar dabayein.")
            }
        }
    }

    /** Load the HTML in a hidden WebView, click every button, collect JS errors and common mistakes. */
    private fun selfTest(html: String, done: (List<String>) -> Unit) {
        val issues = ArrayList<String>()
        val src = html.lowercase()
        if (!src.contains("</html>") && src.contains("<html")) issues += "Code adhoora hai (</html> nahi)"
        if (!src.contains("viewport")) issues += "Mobile viewport meta tag nahi, phone par chhota dikhta hai"
        val guard = "<script>window.__e=[];window.onerror=function(m,s,l){__e.push('JS error: '+m+' (line '+l+')');return true};" +
            "window.alert=function(){};window.confirm=function(){return true};window.prompt=function(){return ''};" +
            "var __ce=console.error;console.error=function(){__e.push('console error: '+[].join.call(arguments,' '))};</script>"
        val probe = """(function(){var r=[];try{
            var b=[].slice.call(document.querySelectorAll('button,input[type=button],input[type=submit]'));
            var t={};b.forEach(function(x){var k=(x.innerText||x.value||'').trim();if(k){t[k]=(t[k]||0)+1}});
            for(var k in t){if(t[k]>1&&k.length<4)r.push('Button "'+k+'" '+t[k]+' dafa bana hai');}
            b.slice(0,60).forEach(function(x){try{x.click()}catch(e){r.push('Button "'+(x.innerText||x.value||'').trim()+'" dabane par error: '+e.message)}});
            var tx=(document.body&&document.body.innerText)||'';
            if(/placeholder|imagine|yahan .{0,20}(design|hoga|ayega)/i.test(tx))r.push('Asal cheez ki jagah placeholder text hai');
            if(tx.trim().length<3&&!document.querySelector('canvas,svg,img'))r.push('Page khaali dikhta hai');
            }catch(e){r.push('Test error: '+e.message)}
            return JSON.stringify(r.concat(window.__e||[]).slice(0,10));})()"""
        val web = android.webkit.WebView(this).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; alpha = 0f }
        val host = window.decorView as android.view.ViewGroup
        host.addView(web, android.view.ViewGroup.LayoutParams(pad(360), pad(640)))
        var finished = false
        fun finish(extra: List<String>) {
            if (finished) return; finished = true
            runCatching { host.removeView(web); web.destroy() }
            done((issues + extra).distinct())
        }
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                view.postDelayed({
                    view.evaluateJavascript(probe) { res ->
                        val list = runCatching {
                            val a = org.json.JSONArray(org.json.JSONTokener(res).nextValue() as String)
                            (0 until a.length()).map { a.getString(it) }
                        }.getOrElse { emptyList() }
                        view.postDelayed({
                            view.evaluateJavascript("JSON.stringify(window.__e||[])") { r2 ->
                                val late = runCatching {
                                    val a = org.json.JSONArray(org.json.JSONTokener(r2).nextValue() as String)
                                    (0 until a.length()).map { a.getString(it) }
                                }.getOrElse { emptyList() }
                                finish(list + late)
                            }
                        }, 600)
                    }
                }, 900)
            }
        }
        val withGuard = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)?.let { html.replaceRange(it.range.last + 1, it.range.last + 1, guard) } ?: (guard + html)
        web.loadDataWithBaseURL("https://gupshup.local/", withViewport(withGuard), "text/html", "utf-8", null)
        web.postDelayed({ finish(listOf()) }, 9000)
    }

    private fun runPythonCard(box: LinearLayout, code: String) {
        val out = TextView(this).apply {
            text = "▶ chal raha hai..."; typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(GOLD)
            background = shape(0xFF15140E.toInt(), 12f, LINE); setPadding(pad(12), pad(9), pad(12), pad(9)); setTextIsSelectable(true)
        }
        box.addView(out, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(6) })
        scrollDown()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = try { Brain.runPython(code) } catch (e: Throwable) { "Error: ${e.message}" }
            withContext(Dispatchers.Main) { out.text = "Nateeja:\n$r"; scrollDown() }
        }
    }

    private fun onSend() {
        var msg = input.text.toString().trim()
        val img = pendingImage; val file = pendingFile
        if ((msg.isEmpty() && img == null && file == null) || !Brain.ready.value) return
        input.setText("")
        pendingImage = null; pendingFile = null; showAttach()
        when {
            img != null -> Brain.send(msg.ifEmpty { "Is tasveer me kya hai? Tafseel se batao." }, display = msg.ifEmpty { "🖼 Tasveer" }, image = img)
            file != null -> {
                val (name, text) = file
                if (msg.isEmpty()) msg = "Is file ko parh kar mukhtasar khulasa batao."
                Brain.send("User ne file bheji hai: $name\n```\n$text\n```\n\n$msg", display = "📎 $name\n$msg")
            }
            else -> Brain.send(msg)
        }
    }

    // ===================== attachments, voice, speech, chats, memory =====================
    private lateinit var attachBar: LinearLayout
    private var pendingImage: String? = null
    private var pendingFile: Pair<String, String>? = null

    private fun attachMenu() {
        AlertDialog.Builder(this).setTitle("Kya bhejna hai?")
            .setItems(arrayOf("🖼  Tasveer (gallery)", "📄  File (txt, code, csv, html…)")) { _, w ->
                if (w == 0) pickImage.launch(arrayOf("image/*")) else pickText.launch(arrayOf("text/*", "application/json", "application/javascript", "application/xml", "*/*"))
            }.show()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val path = runCatching {
                val raw = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                val scale = minOf(1f, 1024f / maxOf(raw.width, raw.height))
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
                val f = File(File(filesDir, "images").also { it.mkdirs() }, "img_${System.currentTimeMillis()}.jpg")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                f.path
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (path == null) Toast.makeText(this@MainActivity, "Tasveer nahi khuli", Toast.LENGTH_SHORT).show()
                else { pendingImage = path; pendingFile = null; showAttach() }
            }
        }
    }

    private val pickText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val name = runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null }
            }.getOrNull() ?: "file.txt"
            val text = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() }.decodeToString() }.getOrNull()
            withContext(Dispatchers.Main) {
                when {
                    text == null -> Toast.makeText(this@MainActivity, "File nahi khuli", Toast.LENGTH_SHORT).show()
                    text.count { it == '\u0000' } > 0 -> Toast.makeText(this@MainActivity, "Ye text file nahi lagti (PDF/Word abhi nahi parh sakta)", Toast.LENGTH_LONG).show()
                    else -> {
                        val cut = if (text.length > 6000) text.take(6000) + "\n…(baqi file kaat di gayi)" else text
                        pendingFile = name to cut; pendingImage = null; showAttach()
                        if (text.length > 6000) Toast.makeText(this@MainActivity, "Lambi file: sirf pehle 6000 harf bheje jayenge", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showAttach() {
        attachBar.removeAllViews()
        val img = pendingImage; val file = pendingFile
        if (img == null && file == null) { attachBar.visibility = View.GONE; return }
        attachBar.visibility = View.VISIBLE
        if (img != null) thumb(img, 56)?.let { attachBar.addView(it, LinearLayout.LayoutParams(pad(56), pad(56)).apply { marginEnd = pad(8) }) }
        attachBar.addView(TextView(this).apply {
            text = if (img != null) "🖼 Tasveer tayyar — sawal likhein ya seedha bhejein" else "📎 ${file!!.first} (${file.second.length} harf)"
            setTextColor(TEXT); textSize = 13f; gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        attachBar.addView(chip("✕") { pendingImage = null; pendingFile = null; showAttach() })
    }

    private fun thumb(path: String, maxDp: Int = 180): ImageView? {
        val bmp = runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(path, o)
        }.getOrNull() ?: return null
        return ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP
            maxHeight = pad(maxDp * 4 / 3); clipToOutline = true; background = shape(SURF, 14f)
            setOnClickListener { fullImage(path) }
        }
    }

    private fun fullImage(path: String) {
        lateinit var d: Dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        root.addView(pageHeader("🖼 Tasveer", { d.dismiss() }))
        root.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(path)); adjustViewBounds = true }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d = fullDialog(root); d.show()
    }

    private fun miniBtn(label: String, f: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; setTextColor(MUTED); setPadding(pad(8), pad(4), pad(8), pad(4))
        background = ripple(shape(0x00000000, 10f)); isClickable = true; setOnClickListener { f() }
    }

    private fun userMenu(m: Msg, index: Int) {
        AlertDialog.Builder(this).setItems(arrayOf("⧉  Copy", "✏️  Edit kar ke dobara bhejo")) { _, w ->
            if (w == 0) copy(m.text)
            else if (!Brain.ready.value) Toast.makeText(this, "Pehle jawab poora hone dein", Toast.LENGTH_SHORT).show()
            else Brain.editFrom(index)?.let { t -> input.setText(t); input.setSelection(t.length); input.requestFocus() }
        }.show()
    }

    // ---- voice input (Android speech recognizer) ----
    private val voiceLangs = listOf("ur-PK" to "Urdu", "en-US" to "English", "hi-IN" to "Hindi", "ar-SA" to "Arabic")
    private fun voiceLang() = prefs.getString("voice_lang", "ur-PK")!!
    private fun voiceLangMenu() {
        AlertDialog.Builder(this).setTitle("🎤 Bolne ki zaban").setItems(voiceLangs.map { it.second }.toTypedArray()) { _, w ->
            prefs.edit().putString("voice_lang", voiceLangs[w].first).apply()
            Toast.makeText(this, "🎤 ${voiceLangs[w].second}", Toast.LENGTH_SHORT).show()
        }.show()
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
        val cur = input.text.toString()
        input.setText(if (cur.isBlank()) t else "$cur $t"); input.setSelection(input.text.length)
    }
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLang())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye… (zaban badalne ke liye 🎤 dabaa kar rakhein)")
        }
        runCatching { voice.launch(i) }.onFailure { Toast.makeText(this, "Is phone par bolne wala keyboard/Google app nahi mila", Toast.LENGTH_LONG).show() }
    }

    // ---- read answers aloud ----
    private var tts: TextToSpeech? = null
    private fun speak(text: String) {
        val clean = text.replace(Regex("```[\\s\\S]*?(```|\\z)"), " (code) ").replace(Regex("[*#`>•]"), "").trim()
        val t = tts
        if (t != null) { if (t.isSpeaking) t.stop() else t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "g"); return }
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "g")
            } else Toast.makeText(this, "Is phone me awaz (TTS) nahi chal saki", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- chats list ----
    private fun chatsPage() {
        lateinit var d: Dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(pageHeader("💬 Baat-cheet", { d.dismiss() }, chip("＋ Nayi", true) { d.dismiss(); Brain.newChat() }))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(12), pad(10), pad(12), pad(20)) }
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", Locale.US)
        Brain.chatList().forEach { c ->
            val cur = c.id == Brain.chatId
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = ripple(shape(if (cur) 0xFF10251C.toInt() else SURF, 14f, if (cur) ACCENT2 else LINE))
                setPadding(pad(14), pad(11), pad(14), pad(11))
                addView(TextView(this@MainActivity).apply { text = c.title; textSize = 15f; setTextColor(TEXT); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(TextView(this@MainActivity).apply { text = fmt.format(java.util.Date(c.updated)); textSize = 11.5f; setTextColor(MUTED) })
                setOnClickListener { if (Brain.busy) Toast.makeText(this@MainActivity, "Pehle jawab poora hone dein", Toast.LENGTH_SHORT).show() else { d.dismiss(); Brain.switchChat(c.id) } }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(c.title).setItems(arrayOf("✏️ Naam badlo", "🗑 Delete")) { _, w ->
                        if (w == 0) {
                            val et = EditText(this@MainActivity).apply { setText(c.title) }
                            AlertDialog.Builder(this@MainActivity).setView(et).setPositiveButton("Save") { _, _ -> Brain.renameChat(c.id, et.text.toString()); d.dismiss(); chatsPage() }.show()
                        } else { Brain.deleteChat(c.id); d.dismiss(); chatsPage() }
                    }.show(); true
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(6) })
        }
        list.addView(TextView(this).apply { text = "Lamba dabayein: naam badlo ya delete"; textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, pad(14), 0, 0) })
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d = fullDialog(root); d.show()
    }

    // ---- memory page ----
    private fun memoryPage() {
        lateinit var d: Dialog
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(pageHeader("🧠 Yaaddasht", { d.dismiss() }, chip("＋", true) {
            val et = EditText(this).apply { hint = "Jaise: mera naam Abrar hai, main Riyadh me rehta hoon" }
            AlertDialog.Builder(this).setTitle("Kya yaad rakhun?").setView(et).setPositiveButton("Save") { _, _ ->
                et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { Brain.addMemory(it) }; d.dismiss(); memoryPage()
            }.show()
        }))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(12), pad(10), pad(12), pad(20)) }
        list.addView(TextView(this).apply {
            text = "Gup Shup har baat-cheet me ye baatein yaad rakhta hai. Chat me \"yaad rakho: …\" likh kar bhi daal sakte hain."
            textSize = 13f; setTextColor(MUTED); setPadding(pad(4), 0, pad(4), pad(8))
        })
        Brain.memories().forEachIndexed { i, t ->
            list.addView(row(
                TextView(this).apply { text = "• $t"; textSize = 15f; setTextColor(TEXT) }.also { it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
                chip("🗑") { Brain.removeMemory(i); d.dismiss(); memoryPage() },
            ).apply { background = shape(SURF, 12f, LINE); setPadding(pad(12), pad(8), pad(8), pad(8)) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad(6) })
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d = fullDialog(root); d.show()
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
            .setView(et).setPositiveButton("Banao") { _, _ -> Brain.buildApk(et.text.toString().trim().ifBlank { "Meri App" }, withViewport(html)) }
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
    private fun modelMenu() {
        val items = listOf(
            "🔄 Model badlo (E2B tez hai, E4B taqatwar)" to { showModelChooser() },
            "🧠 Yaaddasht (${Brain.memories().size})" to { memoryPage() },
            "🐙 GitHub" to { githubMenu() },
            "⚙️ CPU / GPU chuno (abhi: ${Brain.currentModelName()?.let { Brain.backendMode(it) }?.let { mapOf("auto" to "Auto", "gpu" to "GPU", "cpu" to "CPU")[it] } ?: "Auto"})" to { if (Brain.hasModel) backendChooser() },
            "🎤 Bolne ki zaban (${voiceLangs.firstOrNull { it.first == voiceLang() }?.second ?: "Urdu"})" to { voiceLangMenu() },
            (if (Brain.checkOn) "🔍 Khud-test: ON (band karo)" else "🔍 Khud-test: OFF (on karo)") to {
                Brain.checkOn = !Brain.checkOn; Brain.note(if (Brain.checkOn) "🔍 Khud-test on." else "🔍 Khud-test off.") },
            (if (Brain.toolsOn) "🛠 Tools: ON (tez mode ke liye band karo)" else "⚡ Tez mode: ON (tools on karo)") to {
                Brain.toolsOn = !Brain.toolsOn; Brain.note(if (Brain.toolsOn) "🛠 Tools on." else "⚡ Tez mode on.") },
        )
        AlertDialog.Builder(this).setTitle("⚙️ Settings")
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }.show()
    }

    private fun backendChooser() {
        val cur = Brain.currentModelName()?.let { Brain.backendMode(it) } ?: "auto"
        val modes = arrayOf("auto", "gpu", "cpu")
        AlertDialog.Builder(this).setTitle("⚙️ Is model ko kis par chalayein?")
            .setSingleChoiceItems(arrayOf(
                "Auto — pehle GPU, na chale to CPU (behtar)",
                "GPU — tez (bare models ke liye)",
                "CPU — har phone par chalta hai (chhote models ke liye achha)"
            ), modes.indexOf(cur)) { d, w ->
                d.dismiss()
                if (modes[w] != cur) { Brain.setBackend(modes[w]); Brain.note("⚙️ Model ${modes[w].uppercase()} par dobara load ho raha hai…") }
            }.show()
    }

    private fun showModelChooser() {
        AlertDialog.Builder(this).setTitle("Model chuno")
            .setItems(arrayOf(
                "🌟 Gemma 4 E4B — taqatwar, coding ke liye (3.7 GB, 8 GB+ RAM)",
                "⚡ Gemma 4 E2B — tez (2.6 GB, 6 GB+ RAM)",
                "🐣 Chhota Qwen3 0.6B — 350 MB, bohat tez lekin kamzor (Roman Urdu theek nahi)",
                "📁 Phone se .litertlm file chuno"
            )) { _, w ->
                when (w) {
                    0 -> Brain.download("gemma4-e4b-litert-v1", "gemma4-e4b.litertlm")
                    1 -> Brain.download("gemma4-litert-v1", "gemma4-e2b.litertlm")
                    2 -> Brain.download("small-litert-v1", "qwen3-0.6b.litertlm")
                    else -> pickFile.launch(arrayOf("*/*"))
                }
            }.show()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) Brain.importFile { contentResolver.openInputStream(uri) }
    }

    companion object {
        private val PY = setOf("python", "py", "python3")
        private val HTML = setOf("html", "htm", "svg")
        private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
    }
}

/** GitHub OAuth Client ID baked at build time; if empty the app asks once. */
object BuildDefaults { const val GITHUB_CLIENT_ID = "" }
