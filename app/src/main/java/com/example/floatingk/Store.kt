package com.example.floatingk

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/** إعدادات زر عائم واحد */
data class BtnCfg(
    var id: String,
    var label: String = "K",
    var type: String = TYPE_KEY,
    var key: String = "K",
    var holdMode: Boolean = false,   // للمفتاح: يبقى مضغوط طالما إصبعك على الزر
    var x: Int = 0,                  // إحداثيات ضغطة الشاشة
    var y: Int = 0,
    var rawX: Int = -1,              // إحداثيات اللمس الخام (من الاختيار بالشاشة)
    var rawY: Int = -1,
    var holdMs: Int = 40,            // مدة الضغط
    var size: Int = 56,              // حجم الزر dp
    var posX: Int = 40,              // مكان الزر على الشاشة
    var posY: Int = 300
) {
    fun summary(): String =
        if (type == TYPE_TAP) "ضغطة شاشة ($x, $y)"
        else "مفتاح $key" + (if (holdMode) " (ثبات)" else "")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("type", type).put("key", key)
        .put("holdMode", holdMode).put("x", x).put("y", y)
        .put("rawX", rawX).put("rawY", rawY).put("holdMs", holdMs)
        .put("size", size).put("posX", posX).put("posY", posY)

    companion object {
        const val TYPE_KEY = "key"
        const val TYPE_TAP = "tap"

        fun fromJson(o: JSONObject) = BtnCfg(
            id = o.getString("id"),
            label = o.optString("label", "K"),
            type = o.optString("type", TYPE_KEY),
            key = o.optString("key", "K"),
            holdMode = o.optBoolean("holdMode", false),
            x = o.optInt("x", 0),
            y = o.optInt("y", 0),
            rawX = o.optInt("rawX", -1),
            rawY = o.optInt("rawY", -1),
            holdMs = o.optInt("holdMs", 40),
            size = o.optInt("size", 56),
            posX = o.optInt("posX", 40),
            posY = o.optInt("posY", 300)
        )
    }
}

object Store {
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun mode(c: Context): String =
        prefs(c).getString("mode", KeySender.MODE_SHIZUKU) ?: KeySender.MODE_SHIZUKU

    fun setMode(c: Context, m: String) = prefs(c).edit().putString("mode", m).apply()

    /** ضغطة الشاشة كنقطة لمس منفصلة (عشان ما تفك الأيم) */
    fun separate(c: Context): Boolean = prefs(c).getBoolean("separate", true)

    fun setSeparate(c: Context, v: Boolean) = prefs(c).edit().putBoolean("separate", v).apply()

    @Synchronized
    fun load(c: Context): MutableList<BtnCfg> {
        val s = prefs(c).getString("buttons", null)
        val out = mutableListOf<BtnCfg>()
        if (s != null) {
            try {
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) out.add(BtnCfg.fromJson(arr.getJSONObject(i)))
            } catch (_: Exception) {
            }
        }
        if (s == null) out.add(BtnCfg(id = "b1"))
        return out
    }

    @Synchronized
    fun save(c: Context, list: List<BtnCfg>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("buttons", arr.toString()).apply()
    }

    @Synchronized
    fun upsert(c: Context, cfg: BtnCfg) {
        val l = load(c)
        val i = l.indexOfFirst { it.id == cfg.id }
        if (i >= 0) l[i] = cfg else l.add(cfg)
        save(c, l)
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        save(c, load(c).filter { it.id != id })
    }

    // ---------- المفاتيح ----------

    /** يقبل: حرف (K)، أو اسم (VOLUME_UP / KEYCODE_VOLUME_UP)، أو رقم الكود */
    fun parseKeyCode(s: String): Int {
        val t = s.trim().uppercase()
        if (t.isEmpty()) return 0
        if (t.all { it.isDigit() }) return t.toIntOrNull() ?: 0
        val name = if (t.startsWith("KEYCODE_")) t else "KEYCODE_$t"
        return KeyEvent.keyCodeFromString(name)
    }

    private val scan: Map<Int, Int> = buildScan()

    private fun buildScan(): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        fun row(letters: String, start: Int) {
            letters.forEachIndexed { i, ch -> m[KeyEvent.KEYCODE_A + (ch - 'A')] = start + i }
        }
        row("QWERTYUIOP", 16)
        row("ASDFGHJKL", 30)
        row("ZXCVBNM", 44)
        for (d in 1..9) m[KeyEvent.KEYCODE_0 + d] = 1 + d
        m[KeyEvent.KEYCODE_0] = 11
        m[KeyEvent.KEYCODE_VOLUME_UP] = 115
        m[KeyEvent.KEYCODE_VOLUME_DOWN] = 114
        m[KeyEvent.KEYCODE_VOLUME_MUTE] = 113
        m[KeyEvent.KEYCODE_ENTER] = 28
        m[KeyEvent.KEYCODE_SPACE] = 57
        m[KeyEvent.KEYCODE_ESCAPE] = 1
        m[KeyEvent.KEYCODE_DEL] = 14
        m[KeyEvent.KEYCODE_TAB] = 15
        return m
    }

    fun scanCodeFor(keyCode: Int): Int = scan[keyCode] ?: 0
}
