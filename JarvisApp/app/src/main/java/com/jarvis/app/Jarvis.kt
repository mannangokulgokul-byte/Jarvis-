package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun recognizerIntent(lang: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)

class Jarvis(private val ctx: Context, private val onReply: (String) -> Unit) {
private val prefs = ctx.getSharedPreferences("jarvis", 0)
private val main = Handler(Looper.getMainLooper())
private var ready = false
private var tts: TextToSpeech? = null
var onCamera: ((String) -> Unit)? = null

// Fast first, then fallbacks. Whole chain is capped at ~9 seconds.
private val models = listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash")

init {
tts = TextToSpeech(ctx) { st ->
ready = st == TextToSpeech.SUCCESS
tts?.setAudioAttributes(AudioAttributes.Builder()
.setUsage(AudioAttributes.USAGE_MEDIA)
.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
tts?.setSpeechRate(0.95f)
}
}

fun speaking() = tts?.isSpeaking == true
fun shutdown() { tts?.shutdown() }

fun say(t: String) {
onReply(t)
if (!ready) return
val ta = t.any { it in '\u0B80'..'\u0BFF' }
val r = tts?.setLanguage(if (ta) Locale("ta", "IN") else Locale("en", "IN"))
if (ta && (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED))
tts?.setLanguage(Locale("en", "IN"))
var first = true
t.split(Regex("(?<=[.!?\u0964])\\s+")).filter { it.isNotBlank() }.forEach {
tts?.speak(it, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "j")
tts?.playSilentUtterance(180, TextToSpeech.QUEUE_ADD, "p")
first = false
}
}

private fun go(i: Intent) {
i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
try { ctx.startActivity(i) } catch (e: Exception) { say("Ennala mudiyala: ${e.message}") }
}

private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun lines(k: String) = prefs.getString(k, "").orEmpty().lines().filter { it.isNotBlank() }
private fun addLine(k: String, v: String) =
prefs.edit().putString(k, (prefs.getString(k, "") ?: "") + v + "\n").apply()

private fun bg(work: () -> String) {
Thread {
val r = try { work() } catch (e: Exception) { "Sorry, ${e.message}" }
main.post { say(r) }
}.start()
}

private fun get(u: String): String {
val c = URL(u).openConnection() as HttpURLConnection
c.connectTimeout = 4000; c.readTimeout = 4000
c.setRequestProperty("User-Agent", "Jarvis/2.0")
return c.inputStream.bufferedReader().readText()
}

fun handle(raw: String) {
val t = raw.trim().lowercase(Locale.ROOT)
when {
t.startsWith("open ") -> openApp(t.removePrefix("open ").trim())
t.startsWith("call ") -> call(t.removePrefix("call ").trim())
t.startsWith("send sms") || t.startsWith("message ") || t.startsWith("text ") -> sms(t)
t.contains("alarm") -> alarm(t)
t.contains("remind me") -> remind(t)
t.contains("timer") -> timer(t)
t.contains("total expense") || t == "expenses" -> totalExpense()
t.startsWith("expense ") || t.startsWith("spent ") -> addExpense(t)
t.contains("clear shopping") -> { prefs.edit().remove("shop").apply(); say("Shopping list clear") }
t.startsWith("add ") && t.contains("shopping") -> {
val item = t.removePrefix("add ").substringBefore(" to ").trim()
addLine("shop", item); say("$item list-la add pannitten")
}
t.contains("shopping list") -> say(lines("shop").joinToString(", ").ifBlank { "List khaali" })
t.startsWith("note ") || t.startsWith("take note ") -> {
addLine("notes", t.removePrefix("take ").removePrefix("note ").trim()); say("Note save panniten")
}
t.contains("read notes") || t.contains("my notes") -> say(lines("notes").joinToString(". ").ifBlank { "Notes onnum illa" })
t.contains("clear notes") -> { prefs.edit().remove("notes").apply(); say("Notes alichitten") }
t.startsWith("wiki ") || t.startsWith("wikipedia ") -> wiki(t.substringAfter(" "))
t.contains("news") -> news()
t.contains("camera") || t.contains("what is this") -> onCamera?.invoke(raw) ?: say("App-la camera button use pannunga")
else -> ask(raw)
}
}

private fun openApp(name: String) {
val pm = ctx.packageManager
val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
val m = pm.queryIntentActivities(q, 0).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(name) }
val i = m?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
if (i == null) say("$name app kedaikala") else { say("Opening $name"); go(i) }
}

private fun resolve(target: String): String? {
val digits = target.replace(" ", "")
if (Regex("^\\+?\\d{6,}$").matches(digits)) return digits
if (!has(Manifest.permission.READ_CONTACTS)) return null
ctx.contentResolver.query(
ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
"${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
arrayOf("%$target%"), null
)?.use { if (it.moveToFirst()) return it.getString(0) }
return null
}

private fun call(target: String) {
val n = resolve(target) ?: return say("$target number kedaikala")
if (!has(Manifest.permission.CALL_PHONE)) return say("Call permission kudunga")
say("Calling $target")
go(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n")))
}

private fun sms(t: String) {
val m = Regex("(?:sms to|message|text)\\s+(.+?)\\s+(?:saying|that says|that)\\s+(.+)").find(t)
?: return say("Format: send sms to <name> saying <message>")
val n = resolve(m.groupValues[1]) ?: return say("${m.groupValues[1]} number kedaikala")
if (!has(Manifest.permission.SEND_SMS)) return say("SMS permission kudunga")
try {
@Suppress("DEPRECATION")
SmsManager.getDefault().sendTextMessage(n, null, m.groupValues[2], null, null)
say("SMS anupitten")
} catch (e: Exception) { say("SMS fail: ${e.message}") }
}

private fun seconds(t: String): Int {
val m = Regex("(\\d+)\\s*(hour|hr|minute|min|second|sec)").find(t) ?: return 0
val n = m.groupValues[1].toInt()
return when (m.groupValues[2]) { "hour", "hr" -> n * 3600; "minute", "min" -> n * 60; else -> n }
}

private fun timer(t: String, label: String = "Jarvis") {
val s = seconds(t)
if (s == 0) return say("Evlo neram nu sollunga, example: timer 5 minutes")
say("Timer set")
go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
.putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
}

private fun remind(t: String) {
val what = t.substringAfter("remind me").removePrefix(" to").substringBefore(" in ").trim()
timer(t, what.ifBlank { "Reminder" })
}

private fun alarm(t: String) {
val m = Regex("(\\d{1,2})(?:[:\\s](\\d{2}))?\\s*(am|pm)?").find(t.substringAfter("alarm"))
?: return say("Neram sollunga, example: alarm 7 30 am")
var h = m.groupValues[1].toInt()
val min = m.groupValues[2].ifBlank { "0" }.toInt()
if (m.groupValues[3] == "pm" && h < 12) h += 12
if (m.groupValues[3] == "am" && h == 12) h = 0
say("Alarm set for $h:${"%02d".format(min)}")
go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
.putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
.putExtra(AlarmClock.EXTRA_SKIP_UI, true))
}

private fun addExpense(t: String) {
val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:rs|rupees|on|for)?\\s*(.*)").find(t.substringAfter(" "))
?: return say("Example: expense 200 food")
val cat = m.groupValues[2].ifBlank { "general" }
addLine("exp", "${today()}|${m.groupValues[1]}|$cat")
say("${m.groupValues[1]} rupees $cat-ku save pannitten")
}

private fun totalExpense() {
var all = 0.0; var day = 0.0
lines("exp").forEach {
val p = it.split("|"); val x = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
all += x; if (p[0] == today()) day += x
}
say("Innaiku %.0f rupees, mothama %.0f rupees".format(day, all))
}

private fun news() = bg {
val xml = get("https://news.google.com/rss?hl=ta&gl=IN&ceid=IN:ta")
val h = Regex("<title>(.*?)</title>").findAll(xml).drop(1).take(3).map {
it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&")
.replace("&quot;", "\"").replace("&#39;", "'").substringBeforeLast(" - ")
}.toList()
if (h.isEmpty()) "News kedaikala" else "Top news. " + h.joinToString(". ")
}

private fun wiki(q: String) = bg {
val j = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/" +
URLEncoder.encode(q.trim().replace(" ", "_"), "UTF-8")))
j.getString("extract").split(Regex("(?<=\\.)\\s")).take(2).joinToString(" ")
}

private fun part(s: String) = JSONObject().put("text", s)

private fun ask(q: String) = bg { gemini(JSONArray().put(part(q))) }

fun askImage(bmp: Bitmap, q: String) = bg {
val o = ByteArrayOutputStream()
bmp.compress(Bitmap.CompressFormat.JPEG, 80, o)
val img = JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
.put("data", Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)))
gemini(JSONArray().put(part(q.ifBlank { "Idhu enna? Briefly sollunga." })).put(img))
}

private fun gemini(parts: JSONArray): String {
val key = prefs.getString("key", "").orEmpty()
if (key.isBlank()) return "Gemini key app-la save pannunga"
val end = System.currentTimeMillis() + 9000
val sys = "You are JARVIS, a witty voice assistant. Reply in the same language the user used (Tamil script for Tamil). Maximum 2 short sentences, no markdown."
for (m in models) {
val left = (end - System.currentTimeMillis()).toInt()
if (left < 1500) break
try {
val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key")
.openConnection() as HttpURLConnection
c.requestMethod = "POST"
c.setRequestProperty("Content-Type", "application/json")
c.connectTimeout = 3000
c.readTimeout = minOf(left, 4500)
c.doOutput = true
val cfg = JSONObject().put("maxOutputTokens", 150).put("temperature", 0.7)
if (m.contains("2.5")) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
val body = JSONObject()
.put("system_instruction", JSONObject().put("parts", JSONArray().put(part(sys))))
.put("contents", JSONArray().put(JSONObject().put("parts", parts)))
.put("generationConfig", cfg)
c.outputStream.use { it.write(body.toString().toByteArray()) }
if (c.responseCode == 200) {
val r = c.inputStream.bufferedReader().readText()
return JSONObject(r).getJSONArray("candidates").getJSONObject(0)
.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
}
} catch (e: Exception) { }
}
return "Server busy, konjam apram try pannunga"
}
}
