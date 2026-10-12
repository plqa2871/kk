package com.example.floatingk

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * يشتغل بصلاحية shell عن طريق Shizuku (UserService).
 * الأكواد:
 *  1 ضغطة مفتاح | 2 ضغطة شاشة (InputManager) | 3 مفتاح DOWN | 4 مفتاح UP
 *  5 لمس خام DOWN | 6 لمس خام UP | 7 ضغطة لمس خام | 8 بدء التقاط إحداثيات | 9 إيقاف وإرجاع X,Y | 10 تشخيص
 * الرد دائماً: Int(نجاح) + String(رسالة) + بيانات إضافية.
 */
class InjectService : Binder() {

    private val injector: Pair<Any, Method> by lazy { findInjector() }
    private val downTimes = ConcurrentHashMap<Int, Long>()
    private val touch: TouchInjector by lazy {
        TouchInjector(LocalShell(), android.os.Process.is64Bit())
    }

    private fun readTarget(d: Parcel): Pair<Int, Target> {
        val key = d.readInt()
        val t = Target(
            d.readInt(), d.readInt(), d.readInt(), d.readInt(),
            d.readInt(), d.readInt(), d.readInt()
        )
        return key to t
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        var msg = ""
        var extra: IntArray? = null
        val ok = try {
            when (code) {
                1 -> {
                    val k = data.readInt()
                    val hold = data.readInt()
                    val s = data.readInt()
                    keyDown(k, s)
                    Thread.sleep(hold.toLong().coerceAtLeast(10L))
                    keyUp(k, s)
                    true
                }
                2 -> {
                    val x = data.readFloat()
                    val y = data.readFloat()
                    val hold = data.readInt()
                    val dev = data.readInt()
                    val separate = data.readInt() == 1
                    tapInputManager(x, y, hold.toLong().coerceAtLeast(10L), dev, separate)
                    true
                }
                3 -> {
                    keyDown(data.readInt(), data.readInt())
                    true
                }
                4 -> {
                    keyUp(data.readInt(), data.readInt())
                    true
                }
                5 -> {
                    val (k, t) = readTarget(data)
                    touch.down(k, t)
                    true
                }
                6 -> {
                    touch.up(data.readInt())
                    true
                }
                7 -> {
                    val (k, t) = readTarget(data)
                    val hold = data.readInt()
                    touch.tap(k, t, hold.toLong())
                    true
                }
                8 -> {
                    touch.startCapture()
                    true
                }
                9 -> {
                    extra = touch.stopCapture()
                    true
                }
                10 -> {
                    msg = touch.diagnose()
                    true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        } catch (t: Throwable) {
            msg = t.cause?.message ?: t.message ?: t.javaClass.simpleName
            false
        }
        reply?.writeInt(if (ok) 1 else 0)
        reply?.writeString(msg)
        extra?.forEach { reply?.writeInt(it) }
        return true
    }

    // ---------------------------------------------------------------- InputManager (احتياطي)

    private fun findInjector(): Pair<Any, Method> {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            val m = stub.javaClass.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            return stub to m
        } catch (_: Throwable) {
        }
        val im = Class.forName("android.hardware.input.InputManager")
            .getMethod("getInstance").invoke(null)!!
        val m = im.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        return im to m
    }

    private fun inject(ev: InputEvent) {
        val (obj, m) = injector
        val r = m.invoke(obj, ev, 0)
        if (r is Boolean && !r) error("النظام رفض الحدث (InputManager)")
    }

    private fun key(action: Int, down: Long, now: Long, code: Int, scan: Int) =
        KeyEvent(down, now, action, code, 0, 0, -1, scan, 0, InputDevice.SOURCE_KEYBOARD)

    private fun keyDown(code: Int, scan: Int) {
        val t = SystemClock.uptimeMillis()
        downTimes[code] = t
        inject(key(KeyEvent.ACTION_DOWN, t, t, code, scan))
    }

    private fun keyUp(code: Int, scan: Int) {
        val d = downTimes.remove(code) ?: SystemClock.uptimeMillis()
        inject(key(KeyEvent.ACTION_UP, d, SystemClock.uptimeMillis(), code, scan))
    }

    private fun motion(action: Int, down: Long, now: Long, x: Float, y: Float, dev: Int, pid: Int) {
        val pp = arrayOf(MotionEvent.PointerProperties().apply {
            id = pid
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val pc = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(
            down, now, action, 1, pp, pc, 0, 0, 1f, 1f, dev, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            inject(ev)
        } finally {
            ev.recycle()
        }
    }

    private fun tapInputManager(x: Float, y: Float, hold: Long, dev: Int, separate: Boolean) {
        val t = SystemClock.uptimeMillis()
        if (separate) {
            motion(MotionEvent.ACTION_POINTER_DOWN, t, t, x, y, dev, 9)
            Thread.sleep(hold)
            motion(MotionEvent.ACTION_POINTER_UP, t, SystemClock.uptimeMillis(), x, y, dev, 9)
        } else {
            motion(MotionEvent.ACTION_DOWN, t, t, x, y, dev, 0)
            Thread.sleep(hold)
            motion(MotionEvent.ACTION_UP, t, SystemClock.uptimeMillis(), x, y, dev, 0)
        }
    }
}
