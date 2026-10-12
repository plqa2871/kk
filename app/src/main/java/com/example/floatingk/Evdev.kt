package com.example.floatingk

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** هدف الضغطة: إحداثيات الشاشة + (اختياري) إحداثيات اللمس الخام + بيانات الشاشة الحالية */
class Target(
    val x: Int, val y: Int,
    val rawX: Int, val rawY: Int,
    val dw: Int, val dh: Int, val rot: Int
)

/** تشغيل أوامر وفتح ملفات: محلي (shell عبر Shizuku) أو عبر الروت (su). */
interface ShellApi {
    fun run(cmd: String): String
    fun openWriter(path: String): OutputStream
    fun openReader(path: String): InputStream
    fun close()
}

class LocalShell : ShellApi {
    override fun run(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return s
    }

    override fun openWriter(path: String): OutputStream = FileOutputStream(path)
    override fun openReader(path: String): InputStream = FileInputStream(path)
    override fun close() {}
}

class RootShell : ShellApi {
    private val procs = ArrayList<Process>()

    override fun run(cmd: String): String {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val s = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return s
    }

    @Synchronized
    override fun openWriter(path: String): OutputStream {
        val p = ProcessBuilder("su", "-c", "cat > $path").redirectErrorStream(true).start()
        procs.add(p)
        return p.outputStream
    }

    @Synchronized
    override fun openReader(path: String): InputStream {
        val p = ProcessBuilder("su", "-c", "cat $path").start()
        procs.add(p)
        return p.inputStream
    }

    @Synchronized
    override fun close() {
        procs.forEach { runCatching { it.destroy() } }
        procs.clear()
    }
}

object Evdev {
    const val EV_SYN = 0
    const val EV_ABS = 3
    const val SYN_REPORT = 0
    const val ABS_MT_SLOT = 0x2f
    const val ABS_MT_TOUCH_MAJOR = 0x30
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
    const val ABS_MT_TRACKING_ID = 0x39
    const val ABS_MT_PRESSURE = 0x3a

    class Info(val path: String, val maxX: Int, val maxY: Int, val maxSlot: Int)

    /** يفحص bit في قناع hex مثل: "0 0 1000003 0" */
    fun bitSet(mask: String, bit: Int): Boolean {
        val words = mask.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val wordBits = if (words.size > 1) words[1].length * 4 else 64
        val idxFromEnd = bit / wordBits
        if (idxFromEnd >= words.size) return false
        val w = try {
            java.lang.Long.parseUnsignedLong(words[words.size - 1 - idxFromEnd], 16)
        } catch (_: Exception) {
            return false
        }
        return ((w ushr (bit % wordBits)) and 1L) == 1L
    }

    /** يختار ملف شاشة اللمس من /proc/bus/input/devices */
    fun pickTouchNode(text: String): String? {
        var best: String? = null
        var bestScore = -1
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            var name = ""
            var node: String? = null
            var abs = ""
            var prop = ""
            for (line in block.lines()) {
                val l = line.trim()
                if (l.startsWith("N:")) {
                    name = Regex("Name=\"(.*)\"").find(l)?.groupValues?.get(1) ?: ""
                } else if (l.startsWith("H:")) {
                    node = Regex("event\\d+").find(l)?.value
                } else {
                    val m = Regex("^B:\\s*([A-Z]+)=(.*)$").find(l)
                    if (m != null) {
                        if (m.groupValues[1] == "ABS") abs = m.groupValues[2]
                        if (m.groupValues[1] == "PROP") prop = m.groupValues[2]
                    }
                }
            }
            if (node == null || abs.isEmpty()) continue
            if (!bitSet(abs, ABS_MT_POSITION_X) || !bitSet(abs, ABS_MT_POSITION_Y) ||
                !bitSet(abs, ABS_MT_SLOT)
            ) continue
            var score = 1
            if (prop.isNotEmpty() && bitSet(prop, 1)) score += 10
            val ln = name.lowercase()
            if (ln.contains("touch") || ln.contains("ts")) score += 5
            if (score > bestScore) {
                bestScore = score
                best = "/dev/input/$node"
            }
        }
        return best
    }

    fun parseCaps(path: String, text: String): Info? {
        fun range(label: String): Pair<Int, Int>? {
            val m = Regex(label + "\\s*:\\s*value\\s+-?\\d+,\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")
                .find(text) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        val x = range("ABS_MT_POSITION_X")
        val y = range("ABS_MT_POSITION_Y")
        val s = range("ABS_MT_SLOT")
        if (x == null || y == null) return null
        return Info(path, x.second, y.second, s?.second ?: 9)
    }

    /** يحوّل إحداثيات الشاشة الحالية لإحداثيات اللمس الخام حسب دوران الشاشة */
    fun toRaw(x: Int, y: Int, dw: Int, dh: Int, rot: Int, i: Info): Pair<Int, Int> {
        if (dw <= 0 || dh <= 0 || i.maxX <= 0 || i.maxY <= 0) {
            error("تعذر تحويل الإحداثيات. اختر الموقع بزر (اختيار الموقع من الشاشة)")
        }
        val fx = x.toFloat() / dw
        val fy = y.toFloat() / dh
        val rx: Float
        val ry: Float
        when (rot) {
            1 -> { ry = fx * i.maxY; rx = (1f - fy) * i.maxX }
            2 -> { rx = (1f - fx) * i.maxX; ry = (1f - fy) * i.maxY }
            3 -> { ry = (1f - fx) * i.maxY; rx = fy * i.maxX }
            else -> { rx = fx * i.maxX; ry = fy * i.maxY }
        }
        return rx.toInt().coerceIn(0, i.maxX) to ry.toInt().coerceIn(0, i.maxY)
    }
}

/**
 * يحقن لمس حقيقي في ملف شاشة اللمس نفسه (/dev/input/eventN) بنقطة (slot) إضافية،
 * فيندمج مع إصبعك الماسك للأيم بدون ما يقطعه. يحتاج shell أو روت.
 */
class TouchInjector(private val sh: ShellApi, private val is64: Boolean) {

    private class E(val t: Int, val c: Int, val v: Int)

    private var info: Evdev.Info? = null
    private var out: OutputStream? = null
    private val active = HashMap<Int, Int>()   // keyId -> slot
    private var tid = 5000

    @Volatile private var capX = -1
    @Volatile private var capY = -1
    @Volatile private var capRun = false
    private var capIn: InputStream? = null

    @Synchronized
    private fun discover(): Evdev.Info {
        info?.let { return it }
        val devs = sh.run("cat /proc/bus/input/devices")
        val path = Evdev.pickTouchNode(devs)
            ?: error("ما لقيت شاشة اللمس في /proc/bus/input/devices")
        var caps = sh.run("getevent -pl $path")
        var i = Evdev.parseCaps(path, caps)
        if (i == null) {
            caps = sh.run("getevent -lp $path")
            i = Evdev.parseCaps(path, caps)
        }
        val res = i ?: Evdev.Info(path, 0, 0, 9)
        info = res
        return res
    }

    @Synchronized
    private fun ensure(): Evdev.Info {
        val i = discover()
        if (out == null) out = sh.openWriter(i.path)
        return i
    }

    fun diagnose(): String {
        val i = ensure()
        return "الملف: ${i.path}\nمدى X: 0..${i.maxX}\nمدى Y: 0..${i.maxY}\nأعلى slot: ${i.maxSlot}\n" +
            "معمارية: " + (if (is64) "64" else "32") + " بت"
    }

    private fun frame(vararg e: E): ByteArray {
        val sz = if (is64) 24 else 16
        val b = ByteBuffer.allocate(sz * e.size).order(ByteOrder.nativeOrder())
        for (x in e) {
            if (is64) {
                b.putLong(0L); b.putLong(0L)
            } else {
                b.putInt(0); b.putInt(0)
            }
            b.putShort(x.t.toShort()); b.putShort(x.c.toShort()); b.putInt(x.v)
        }
        return b.array()
    }

    private fun write(bytes: ByteArray) {
        val o = out ?: error("ملف اللمس غير مفتوح")
        try {
            o.write(bytes)
            o.flush()
        } catch (t: java.io.IOException) {
            out = null
            throw t
        }
    }

    private fun resolve(t: Target, i: Evdev.Info): Pair<Int, Int> =
        if (t.rawX >= 0 && t.rawY >= 0) t.rawX to t.rawY
        else Evdev.toRaw(t.x, t.y, t.dw, t.dh, t.rot, i)

    @Synchronized
    fun down(keyId: Int, t: Target) {
        val i = ensure()
        if (i.maxSlot <= 0) error("شاشة اللمس ما تدعم لمس متعدد")
        val (rx, ry) = resolve(t, i)
        var slot = i.maxSlot
        while (slot > 0 && active.containsValue(slot)) slot--
        active[keyId]?.let { up(keyId) }
        tid = if (tid > 60000) 5000 else tid + 1
        active[keyId] = slot
        write(
            frame(
                E(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, slot),
                E(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, tid),
                E(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_X, rx),
                E(Evdev.EV_ABS, Evdev.ABS_MT_POSITION_Y, ry),
                E(Evdev.EV_ABS, Evdev.ABS_MT_TOUCH_MAJOR, 6),
                E(Evdev.EV_ABS, Evdev.ABS_MT_PRESSURE, 80),
                E(Evdev.EV_SYN, Evdev.SYN_REPORT, 0)
            )
        )
    }

    @Synchronized
    fun up(keyId: Int) {
        val slot = active.remove(keyId) ?: return
        write(
            frame(
                E(Evdev.EV_ABS, Evdev.ABS_MT_SLOT, slot),
                E(Evdev.EV_ABS, Evdev.ABS_MT_TRACKING_ID, -1),
                E(Evdev.EV_SYN, Evdev.SYN_REPORT, 0)
            )
        )
    }

    fun tap(keyId: Int, t: Target, holdMs: Long) {
        down(keyId, t)
        try {
            Thread.sleep(holdMs.coerceAtLeast(10L))
        } finally {
            up(keyId)
        }
    }

    // ---------- التقاط إحداثيات اللمس الخام (للاختيار من الشاشة) ----------
    @Synchronized
    fun startCapture() {
        val i = discover()
        stopCapture()
        capX = -1
        capY = -1
        val inp = sh.openReader(i.path)
        capIn = inp
        capRun = true
        val size = if (is64) 24 else 16
        val th = Thread {
            try {
                val buf = ByteArray(size)
                while (capRun) {
                    var n = 0
                    while (n < size) {
                        val r = inp.read(buf, n, size - n)
                        if (r < 0) return@Thread
                        n += r
                    }
                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder())
                    bb.position(if (is64) 16 else 8)
                    val type = bb.short.toInt() and 0xFFFF
                    val code = bb.short.toInt() and 0xFFFF
                    val value = bb.int
                    if (type == Evdev.EV_ABS) {
                        if (code == Evdev.ABS_MT_POSITION_X) capX = value
                        if (code == Evdev.ABS_MT_POSITION_Y) capY = value
                    }
                }
            } catch (_: Throwable) {
            }
        }
        th.isDaemon = true
        th.start()
    }

    @Synchronized
    fun stopCapture(): IntArray {
        capRun = false
        runCatching { capIn?.close() }
        capIn = null
        return intArrayOf(capX, capY)
    }

    @Synchronized
    fun close() {
        capRun = false
        runCatching { out?.close() }
        out = null
        runCatching { capIn?.close() }
        capIn = null
        sh.close()
    }
}
