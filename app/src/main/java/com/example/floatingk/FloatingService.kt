package com.example.floatingk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private val views = HashMap<String, TextView>()
    private val cfgs = HashMap<String, BtnCfg>()
    private var editMode = false
    private var picker: View? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        rebuild()
        if (Store.mode(this) == KeySender.MODE_SHIZUKU) KeySender.bindShizuku(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> if (::wm.isInitialized) rebuild()
            ACTION_EDIT -> if (::wm.isInitialized) {
                editMode = !editMode
                rebuild()
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                toast(if (editMode) "وضع التحريك: اسحب الأزرار وين تبي" else "تم تثبيت الأزرار")
            }
            ACTION_PICK -> {
                val id = intent?.getStringExtra("id")
                if (id != null && ::wm.isInitialized) startPick(id)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::wm.isInitialized) {
            views.values.forEach { runCatching { wm.removeView(it) } }
            picker?.let { runCatching { wm.removeView(it) } }
        }
        views.clear()
        picker = null
        KeySender.unbindShizuku()
        io.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------- الأزرار

    private fun rebuild() {
        views.values.forEach { runCatching { wm.removeView(it) } }
        views.clear()
        cfgs.clear()
        Store.load(this).forEach { addButton(it) }
    }

    private fun circle(cfg: BtnCfg): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (cfg.type == BtnCfg.TYPE_TAP) 0xDDE65100.toInt() else 0xDD1E88E5.toInt())
        if (editMode) setStroke(dp(3), 0xFFFFEB3B.toInt()) else setStroke(dp(2), Color.WHITE)
    }

    private fun addButton(cfg: BtnCfg) {
        cfgs[cfg.id] = cfg
        val size = dp(cfg.size)
        val tv = TextView(this).apply {
            text = cfg.label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = when (cfg.label.length) { 1 -> 22f; 2 -> 18f; else -> 14f }
            typeface = Typeface.DEFAULT_BOLD
            background = circle(cfg)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // مهم: يخلي الزر يستقبل لمستك حتى لو إصبعك الثاني ماسك اللعبة (الأيم)
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cfg.posX
            y = cfg.posY
        }

        tv.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var active = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val c = cfgs[cfg.id] ?: return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        active = true
                        sx = lp.x; sy = lp.y
                        tx = e.rawX; ty = e.rawY
                        if (!editMode) {
                            v.alpha = 0.6f
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            fire(c, e.deviceId, true)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (editMode && active) {
                            lp.x = sx + (e.rawX - tx).toInt()
                            lp.y = sy + (e.rawY - ty).toInt()
                            wm.updateViewLayout(v, lp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (active) {
                            active = false
                            v.alpha = 1f
                            if (editMode) {
                                c.posX = lp.x
                                c.posY = lp.y
                                Store.upsert(this@FloatingService, c)
                            } else {
                                fire(c, e.deviceId, false)
                            }
                        }
                    }
                }
                return true
            }
        })

        wm.addView(tv, lp)
        views[cfg.id] = tv
    }

    @Suppress("DEPRECATION")
    private fun target(c: BtnCfg): Target {
        val dm = android.util.DisplayMetrics()
        val d = wm.defaultDisplay
        d.getRealMetrics(dm)
        return Target(c.x, c.y, c.rawX, c.rawY, dm.widthPixels, dm.heightPixels, d.rotation)
    }

    /** ينفذ إجراء الزر. down=true عند لمس الزر، false عند رفع الإصبع. */
    private fun fire(cfg: BtnCfg, deviceId: Int, down: Boolean) {
        val mode = Store.mode(this)
        val separate = Store.separate(this)
        if (mode == KeySender.MODE_SHIZUKU) KeySender.bindShizuku(this)
        val tgt = if (cfg.type == BtnCfg.TYPE_TAP && down) target(cfg) else null
        val keyId = cfg.id.hashCode()
        io.execute {
            try {
                when (cfg.type) {
                    BtnCfg.TYPE_TAP -> {
                        if (down) {
                            if (cfg.holdMode) KeySender.touchDown(mode, keyId, tgt!!, cfg.holdMs, separate)
                            else KeySender.touch(mode, keyId, tgt!!, cfg.holdMs, separate)
                        } else if (cfg.holdMode) {
                            KeySender.touchUp(mode, keyId)
                        }
                    }
                    else -> {
                        val code = Store.parseKeyCode(cfg.key)
                        if (code == 0) error("مفتاح غير معروف: ${cfg.key}")
                        val scan = Store.scanCodeFor(code)
                        if (cfg.holdMode) {
                            if (down) KeySender.keyDown(mode, code, scan) else KeySender.keyUp(mode, code, scan)
                        } else if (down) {
                            KeySender.pressKey(mode, code, scan, cfg.holdMs)
                        }
                    }
                }
            } catch (t: Throwable) {
                val msg = t.cause?.message ?: t.message ?: "خطأ غير معروف"
                toast("تعذر التنفيذ: $msg")
            }
        }
    }

    // ---------------------------------------------------------------- اختيار الموقع

    private fun startPick(id: String) {
        toast("بعد 5 ثواني يظهر غطاء الاختيار. افتح اللعبة الحين!")
        main.postDelayed({ showPicker(id) }, 5000)
    }

    private fun showPicker(id: String) {
        picker?.let { runCatching { wm.removeView(it) } }
        val hint = "اضغط على مكان الهدف ثم ارفع إصبعك"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(3).toFloat()
            style = Paint.Style.STROKE
            textAlign = Paint.Align.CENTER
        }
        val v = object : View(this) {
            var cx = -1f
            var cy = -1f

            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(0x66000000)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = dp(18).toFloat()
                    textAlign = Paint.Align.CENTER
                    style = Paint.Style.FILL
                }
                canvas.drawText(hint, width / 2f, dp(80).toFloat(), tp)
                if (cx >= 0) {
                    canvas.drawCircle(cx, cy, dp(16).toFloat(), paint)
                    canvas.drawLine(cx - dp(30), cy, cx + dp(30), cy, paint)
                    canvas.drawLine(cx, cy - dp(30), cx, cy + dp(30), paint)
                }
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        cx = e.x; cy = e.y
                        invalidate()
                    }
                    MotionEvent.ACTION_UP -> {
                        val px = e.rawX.toInt()
                        val py = e.rawY.toInt()
                        runCatching { wm.removeView(this) }
                        picker = null
                        val mode = Store.mode(this@FloatingService)
                        io.execute {
                            try {
                                Thread.sleep(80)
                            } catch (_: Throwable) {
                            }
                            val raw = try {
                                KeySender.stopCapture(mode)
                            } catch (t: Throwable) {
                                null
                            }
                            val c = Store.load(this@FloatingService).firstOrNull { it.id == id }
                            if (c != null) {
                                c.x = px
                                c.y = py
                                c.rawX = raw?.get(0) ?: -1
                                c.rawY = raw?.get(1) ?: -1
                                Store.upsert(this@FloatingService, c)
                                cfgs[id] = c
                            }
                            toast(
                                "تم حفظ الموقع: ($px, $py)" +
                                    (if (raw != null) "\nلمس خام: (${raw[0]}, ${raw[1]}) ✅"
                                    else "\nبدون إحداثيات خام (راح يتحول تلقائياً)")
                            )
                        }
                    }
                }
                return true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        picker = v
        val mode = Store.mode(this)
        io.execute {
            try {
                if (mode == KeySender.MODE_SHIZUKU) KeySender.bindShizuku(this)
                if (mode == KeySender.MODE_SHIZUKU) KeySender.waitReady(3000)
                KeySender.startCapture(mode)
            } catch (t: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- إشعار

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "الأزرار العائمة", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val edit = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingService::class.java).setAction(ACTION_EDIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "إيقاف", stop
        ).build()
        val editAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_edit),
            if (editMode) "تثبيت الأزرار" else "تحريك الأزرار", edit
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("الأزرار العائمة شغّالة")
            .setContentText(if (editMode) "وضع التحريك: اسحب الأزرار" else "الأزرار ثابتة وجاهزة")
            .setContentIntent(open)
            .addAction(editAction)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.floatingk.STOP"
        const val ACTION_RELOAD = "com.example.floatingk.RELOAD"
        const val ACTION_EDIT = "com.example.floatingk.EDIT"
        const val ACTION_PICK = "com.example.floatingk.PICK"
        private const val CHANNEL = "floating_k"
        private const val NOTIF_ID = 1

        @Volatile var running = false
    }
}
