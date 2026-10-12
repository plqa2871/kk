package com.example.floatingk

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** يرسل المفاتيح وضغطات الشاشة: Shizuku (حقن) أو روت. الأفضل للمس: evdev الخام. */
object KeySender {
    const val MODE_SHIZUKU = "shizuku"
    const val MODE_ROOT = "root"

    @Volatile var lastPath: String = "—"
    @Volatile var lastErr: String = ""
    @Volatile private var lastMsg: String = ""

    @Volatile private var injector: IBinder? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var bound = false
    private val held = ConcurrentHashMap<Int, Boolean>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            injector = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injector = null
            bound = false
        }
    }

    private var rootProc: Process? = null
    private var rootOut: OutputStream? = null
    private var rootTouchInj: TouchInjector? = null

    private fun rootTouch(): TouchInjector =
        rootTouchInj ?: TouchInjector(RootShell(), Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
            .also { rootTouchInj = it }

    // ------------------------------------------------------------ ربط Shizuku

    fun bindShizuku(ctx: Context) {
        if (bound) return
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            val a = Shizuku.UserServiceArgs(
                ComponentName(ctx.applicationContext.packageName, InjectService::class.java.name)
            ).processNameSuffix("inject").daemon(false).version(3)
            args = a
            Shizuku.bindUserService(a, conn)
            bound = true
        } catch (_: Throwable) {
        }
    }

    fun unbindShizuku() {
        try {
            args?.let { Shizuku.unbindUserService(it, conn, true) }
        } catch (_: Throwable) {
        }
        bound = false
        injector = null
        runCatching { rootTouchInj?.close() }
        rootTouchInj = null
    }

    fun waitReady(ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (injector == null && System.currentTimeMillis() < end) Thread.sleep(50)
        return injector != null
    }

    private fun call(code: Int, write: (Parcel) -> Unit, read: ((Parcel) -> Unit)? = null): Boolean {
        val b = injector
        if (b == null) {
            lastErr = "حاقن Shizuku غير جاهز"
            return false
        }
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            write(d)
            b.transact(code, d, r, 0)
            val ok = r.readInt() == 1
            val msg = r.readString() ?: ""
            lastMsg = msg
            if (ok) read?.invoke(r) else lastErr = msg
            ok
        } catch (t: Throwable) {
            lastErr = t.message ?: t.javaClass.simpleName
            false
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    // ------------------------------------------------------------ مفاتيح

    fun pressKey(mode: String, keyCode: Int, scan: Int, holdMs: Int) {
        if (mode == MODE_SHIZUKU && call(1, {
                it.writeInt(keyCode); it.writeInt(holdMs); it.writeInt(scan)
            })) {
            lastPath = "حقن مباشر (مفتاح)"
            return
        }
        runCmd(mode, "input", "keyevent", keyCode.toString())
    }

    fun keyDown(mode: String, keyCode: Int, scan: Int) {
        if (mode == MODE_SHIZUKU && call(3, { it.writeInt(keyCode); it.writeInt(scan) })) {
            lastPath = "حقن مباشر (مفتاح مثبّت)"
            return
        }
        runCmd(mode, "input", "keyevent", keyCode.toString())
    }

    fun keyUp(mode: String, keyCode: Int, scan: Int) {
        if (mode == MODE_SHIZUKU) call(4, { it.writeInt(keyCode); it.writeInt(scan) })
    }

    // ------------------------------------------------------------ لمس الشاشة

    private fun writeTarget(p: Parcel, key: Int, t: Target) {
        p.writeInt(key)
        p.writeInt(t.x); p.writeInt(t.y)
        p.writeInt(t.rawX); p.writeInt(t.rawY)
        p.writeInt(t.dw); p.writeInt(t.dh); p.writeInt(t.rot)
    }

    private fun evdev(mode: String, keyId: Int, t: Target, holdMs: Int, downOnly: Boolean): Boolean {
        return try {
            if (mode == MODE_ROOT) {
                val ti = rootTouch()
                if (downOnly) ti.down(keyId, t) else ti.tap(keyId, t, holdMs.toLong())
                true
            } else {
                call(if (downOnly) 5 else 7, {
                    writeTarget(it, keyId, t)
                    if (!downOnly) it.writeInt(holdMs)
                })
            }
        } catch (e: Throwable) {
            lastErr = e.cause?.message ?: e.message ?: e.javaClass.simpleName
            false
        }
    }

    /** ضغطة لمس عادية (تنزل وترفع بعد holdMs) */
    fun touch(mode: String, keyId: Int, t: Target, holdMs: Int, separate: Boolean) {
        if (evdev(mode, keyId, t, holdMs, false)) {
            lastPath = "لمس خام evdev ✅"
            return
        }
        val evErr = lastErr
        if (mode == MODE_SHIZUKU && call(2, {
                it.writeFloat(t.x.toFloat()); it.writeFloat(t.y.toFloat())
                it.writeInt(holdMs); it.writeInt(0); it.writeInt(if (separate) 1 else 0)
            })) {
            lastPath = (if (separate) "InputManager: نقطة منفصلة" else "InputManager: عادية") +
                " | evdev فشل: $evErr"
            return
        }
        runCmd(mode, "input", "tap", t.x.toString(), t.y.toString())
        lastPath += " | evdev فشل: $evErr"
    }

    /** لمس مثبّت: ينزل عند لمس الزر ويبقى لين ترفع إصبعك */
    fun touchDown(mode: String, keyId: Int, t: Target, holdMs: Int, separate: Boolean) {
        if (evdev(mode, keyId, t, holdMs, true)) {
            held[keyId] = true
            lastPath = "لمس خام evdev ✅ (مثبّت)"
            return
        }
        touch(mode, keyId, t, holdMs, separate)
    }

    fun touchUp(mode: String, keyId: Int) {
        if (held.remove(keyId) == null) return
        try {
            if (mode == MODE_ROOT) rootTouch().up(keyId) else call(6, { it.writeInt(keyId) })
        } catch (e: Throwable) {
            lastErr = e.message ?: e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------ التقاط الإحداثيات الخام وتشخيص

    fun startCapture(mode: String) {
        if (mode == MODE_ROOT) rootTouch().startCapture() else call(8, {})
    }

    /** يرجع [rawX, rawY] أو null */
    fun stopCapture(mode: String): IntArray? {
        if (mode == MODE_ROOT) {
            val r = rootTouch().stopCapture()
            return if (r[0] >= 0 && r[1] >= 0) r else null
        }
        var res: IntArray? = null
        call(9, {}, { res = intArrayOf(it.readInt(), it.readInt()) })
        val r = res
        return if (r != null && r[0] >= 0 && r[1] >= 0) r else null
    }

    fun diagnose(mode: String): String {
        return try {
            if (mode == MODE_ROOT) {
                rootTouch().diagnose()
            } else if (call(10, {})) {
                lastMsg
            } else {
                "فشل التشخيص: $lastErr"
            }
        } catch (e: Throwable) {
            "فشل التشخيص: " + (e.cause?.message ?: e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------ أوامر احتياطية

    private fun runCmd(mode: String, vararg a: String) {
        if (mode == MODE_ROOT) {
            sendRootLine(a.joinToString(" "))
            lastPath = "روت: أمر input"
        } else {
            shizukuProcess(arrayOf(*a))
            lastPath = "Shizuku: أمر input (احتياطي)"
        }
    }

    private fun shizukuProcess(cmd: Array<String>) {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, cmd, null, null) as Process
        p.waitFor()
        p.destroy()
    }

    @Synchronized
    private fun sendRootLine(line: String) {
        val cur = rootProc
        if (cur == null || !cur.isAlive) {
            val proc = ProcessBuilder("su").redirectErrorStream(true).start()
            rootProc = proc
            rootOut = proc.outputStream
            Thread {
                try {
                    val buf = ByteArray(1024)
                    while (proc.inputStream.read(buf) >= 0) { }
                } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()
        }
        val out = rootOut!!
        out.write((line + "\n").toByteArray())
        out.flush()
    }
}
