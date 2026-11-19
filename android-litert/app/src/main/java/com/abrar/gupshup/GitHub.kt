package com.abrar.gupshup

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small GitHub REST client (device-flow login, repos, files, build APK from HTML). All calls are blocking: use off the main thread. */
class GitHub(var token: String?) {

    data class Resp(val code: Int, val body: String)

    private fun call(method: String, url: String, json: JSONObject? = null, form: Map<String, String>? = null, auth: Boolean = true): Resp {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.requestMethod = method
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "application/json")
        c.setRequestProperty("User-Agent", "GupShup-App")
        if (auth && token != null) c.setRequestProperty("Authorization", "Bearer $token")
        val payload = when {
            json != null -> { c.setRequestProperty("Content-Type", "application/json"); json.toString() }
            form != null -> { c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" } }
            else -> null
        }
        if (payload != null) { c.doOutput = true; c.outputStream.use { it.write(payload.toByteArray()) } }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return Resp(code, body)
    }

    // ---------- login (OAuth device flow: user just taps Authorize) ----------
    fun startDeviceLogin(clientId: String): JSONObject {
        val r = call("POST", "https://github.com/login/device/code", form = mapOf("client_id" to clientId, "scope" to "repo workflow"), auth = false)
        val j = JSONObject(r.body); if (!j.has("device_code")) error(j.optString("error_description", r.body)); return j
    }

    /** Returns token, null if still waiting; throws on denial/expiry. */
    fun pollDeviceLogin(clientId: String, deviceCode: String): String? {
        val r = call("POST", "https://github.com/login/oauth/access_token", form = mapOf(
            "client_id" to clientId, "device_code" to deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), auth = false)
        val j = JSONObject(r.body)
        if (j.has("access_token")) return j.getString("access_token")
        return when (j.optString("error")) { "authorization_pending", "slow_down" -> null; else -> error(j.optString("error_description", r.body)) }
    }

    fun me(): String = JSONObject(call("GET", "https://api.github.com/user").body).getString("login")

    fun listRepos(): List<String> {
        val r = call("GET", "https://api.github.com/user/repos?per_page=100&sort=updated")
        val a = JSONArray(r.body); return (0 until a.length()).map { a.getJSONObject(it).getString("full_name") }
    }

    fun listFiles(repo: String, path: String = ""): String {
        val r = call("GET", "https://api.github.com/repos/$repo/contents/${path.trim('/')}")
        if (r.code != 200) return "Error ${r.code}: ${r.body.take(200)}"
        val t = r.body.trim()
        if (t.startsWith("{")) return "Ye folder nahi, file hai: $path"
        val a = JSONArray(t)
        return (0 until a.length()).joinToString("\n") { val o = a.getJSONObject(it); (if (o.getString("type") == "dir") "📁 " else "📄 ") + o.getString("path") }
    }

    fun readFile(repo: String, path: String): String {
        val r = call("GET", "https://api.github.com/repos/$repo/contents/${path.trim('/')}")
        if (r.code != 200) return "Error ${r.code}: ${r.body.take(200)}"
        val o = JSONObject(r.body)
        return String(Base64.decode(o.getString("content"), Base64.DEFAULT))
    }

    fun writeFile(repo: String, path: String, content: String, message: String): String {
        val p = path.trim('/')
        val existing = call("GET", "https://api.github.com/repos/$repo/contents/$p")
        val body = JSONObject().put("message", message.ifBlank { "Update $p via Gup Shup" })
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
        if (existing.code == 200) body.put("sha", JSONObject(existing.body).getString("sha"))
        val r = call("PUT", "https://api.github.com/repos/$repo/contents/$p", json = body)
        return if (r.code in 200..201) "✅ Save ho gaya: $repo/$p" else "Error ${r.code}: ${r.body.take(300)}"
    }

    fun createRepo(name: String): String {
        val r = call("POST", "https://api.github.com/user/repos", json = JSONObject().put("name", name).put("auto_init", true)
            .put("description", "Gup Shup se bani app"))
        if (r.code !in 200..201) error("Repo nahi bana (${r.code}): ${r.body.take(200)}")
        return JSONObject(r.body).getString("full_name")
    }

    /** Creates a new repo with an Android WebView app wrapping [html] + a build workflow. Returns repo full name. */
    fun startHtmlApk(appName: String, html: String): String {
        val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "app" }.take(30)
        val repo = createRepo("gupshup-$slug-${System.currentTimeMillis() / 1000}")
        Thread.sleep(2500)
        val pkg = "com.gupshup." + slug.replace("-", "").ifEmpty { "app" }.take(20)
        for ((path, text) in Templates.webViewApp(appName, pkg, html)) {
            val res = writeFile(repo, path, text, "Add $path")
            if (!res.startsWith("✅")) error(res)
        }
        return repo
    }

    /** Release download link once the build has published it, else null. */
    fun apkLink(repo: String): String? {
        val r = call("GET", "https://api.github.com/repos/$repo/releases/tags/apk-v1")
        if (r.code != 200) return null
        val a = JSONObject(r.body).getJSONArray("assets")
        return if (a.length() > 0) a.getJSONObject(0).getString("browser_download_url") else null
    }

    /** Status of the latest workflow run: e.g. "in_progress", "completed:success", "completed:failure". */
    fun buildStatus(repo: String): String {
        val r = call("GET", "https://api.github.com/repos/$repo/actions/runs?per_page=1")
        if (r.code != 200) return "unknown"
        val runs = JSONObject(r.body).getJSONArray("workflow_runs")
        if (runs.length() == 0) return "queued"
        val o = runs.getJSONObject(0)
        return o.getString("status") + (if (o.isNull("conclusion")) "" else ":" + o.getString("conclusion"))
    }

    /** Public release assets of a model release (no login needed). */
    fun releaseAssets(repo: String, tag: String): List<String> {
        val r = call("GET", "https://api.github.com/repos/$repo/releases/tags/$tag", auth = false)
        val a = JSONObject(r.body).getJSONArray("assets")
        return (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.getString("name") }.map { it.getString("browser_download_url") }
    }

    /** Model parts with sizes: (download url, bytes), sorted by name. */
    fun releaseAssetSizes(repo: String, tag: String): List<Pair<String, Long>> {
        val r = call("GET", "https://api.github.com/repos/$repo/releases/tags/$tag", auth = false)
        if (r.code != 200) error("release nahi mili (${r.code})")
        val a = JSONObject(r.body).getJSONArray("assets")
        return (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.getString("name") }
            .map { it.getString("browser_download_url") to it.getLong("size") }
    }
}
