package com.example.floatingk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var dlg: AlertDialog? = null

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun EditText.intOr(d: Int): Int = text.toString().trim().toIntOrNull() ?: d

    private fun label(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(label("الأزرار العائمة", 26f, true))
        root.addView(label("أضف أزرار عائمة وخصّص كل زر: يضغط مفتاح، أو رفع صوت، أو ضغطة شاشة بالإحداثيات.", 15f))

        status = label("", 15f)
        root.addView(status)

        // ----- طريقة الإرسال
        root.addView(label("طريقة الإرسال:", 16f, true))
        val rg = RadioGroup(this)
        val rbShizuku = RadioButton(this).apply { text = "Shizuku (بدون روت)"; id = View.generateViewId() }
        val rbRoot = RadioButton(this).apply { text = "روت (su)"; id = View.generateViewId() }
        rg.addView(rbShizuku)
        rg.addView(rbRoot)
        rg.check(if (Store.mode(this) == KeySender.MODE_ROOT) rbRoot.id else rbShizuku.id)
        rg.setOnCheckedChangeListener { _, id ->
            Store.setMode(this, if (id == rbRoot.id) KeySender.MODE_ROOT else KeySender.MODE_SHIZUKU)
        }
        root.addView(rg)

        val sepCb = CheckBox(this).apply {
            text = "احتياطي: لو فشل اللمس الخام، استخدم نقطة لمس منفصلة"
            isChecked = Store.separate(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Store.setSeparate(this@MainActivity, v) }
        }
        root.addView(sepCb)

        // ----- الصلاحيات
        root.addView(btn("1) إعطاء صلاحية العرض فوق التطبيقات") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(btn("2) طلب صلاحية Shizuku") { requestShizuku() })
        root.addView(btn("اختبار الروت") { testRoot() })
        root.addView(btn("فحص شاشة اللمس (تشخيص)") { diagnose() })

        // ----- قائمة الأزرار
        root.addView(label("أزرارك:", 18f, true))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(btn("+ إضافة زر جديد") {
            val n = Store.load(this).size
            editButton(BtnCfg(id = "b" + System.currentTimeMillis(), label = "K", posX = dp(16) + n * dp(64), posY = dp(220)), true)
        })

        // ----- التشغيل
        root.addView(label("التشغيل:", 18f, true))
        root.addView(btn("تشغيل الأزرار العائمة") { startFloating() })
        root.addView(btn("وضع تحريك الأزرار / تثبيتها") {
            if (FloatingService.running) notifyService(FloatingService.ACTION_EDIT)
            else toast("شغّل الأزرار أول")
        })
        root.addView(btn("إيقاف الأزرار") {
            stopService(Intent(this, FloatingService::class.java))
        })

        root.addView(label("حقل تجربة: اضغط هنا ثم اضغط زر مفتاح، لازم يطلع الحرف:", 14f))
        root.addView(EditText(this).apply { hint = "اكتب هنا للتجربة" })

        setContentView(ScrollView(this).apply { addView(root) })

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)

        renderList()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        renderList()
    }

    override fun onDestroy() {
        dlg?.dismiss()
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------ قائمة الأزرار

    private fun renderList() {
        list.removeAllViews()
        Store.load(this).forEach { cfg ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = "● ${cfg.label}  —  ${cfg.summary()}"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(btn("تعديل") { editButton(cfg, false) })
            row.addView(btn("حذف") {
                Store.delete(this, cfg.id)
                notifyService(FloatingService.ACTION_RELOAD)
                renderList()
            })
            list.addView(row)
        }
    }

    private val presets = listOf(
        "اختر مفتاح جاهز…" to "",
        "الحرف K" to "K",
        "رفع الصوت" to "VOLUME_UP",
        "خفض الصوت" to "VOLUME_DOWN",
        "كتم الصوت" to "VOLUME_MUTE",
        "تشغيل / إيقاف الوسائط" to "MEDIA_PLAY_PAUSE",
        "Enter" to "ENTER",
        "Space" to "SPACE",
        "رجوع" to "BACK",
        "Home" to "HOME"
    )

    private fun editButton(cfg: BtnCfg, isNew: Boolean) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val labelEt = EditText(this).apply {
            setText(cfg.label)
            hint = "الاسم على الزر (حتى 4 أحرف)"
        }
        v.addView(label("اسم الزر:", 14f))
        v.addView(labelEt)

        v.addView(label("نوع الزر:", 14f, true))
        val rgType = RadioGroup(this)
        val rbKey = RadioButton(this).apply { text = "ضغط مفتاح / زر صوت"; id = View.generateViewId() }
        val rbTap = RadioButton(this).apply { text = "ضغطة شاشة بإحداثيات"; id = View.generateViewId() }
        rgType.addView(rbKey)
        rgType.addView(rbTap)
        rgType.check(if (cfg.type == BtnCfg.TYPE_TAP) rbTap.id else rbKey.id)
        v.addView(rgType)

        // مجموعة المفتاح
        val keyGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keyEt = EditText(this).apply {
            setText(cfg.key)
            hint = "حرف مثل K، أو VOLUME_UP، أو رقم الكود"
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets.map { it.first }
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    if (pos > 0) keyEt.setText(presets[pos].second)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val holdCb = CheckBox(this).apply {
            text = "ثبّت طالما إصبعك على الزر (للمفتاح أو للمسة)"
            isChecked = cfg.holdMode
        }
        keyGroup.addView(label("المفتاح:", 14f))
        keyGroup.addView(spinner)
        keyGroup.addView(keyEt)
        v.addView(keyGroup)

        // مجموعة الضغطة
        val tapGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val xEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.x.toString())
            hint = "X"
        }
        val yEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.y.toString())
            hint = "Y"
        }
        tapGroup.addView(label("إحداثيات الضغطة (بكسل). الأفضل تختارها بالزر تحت:", 14f))
        tapGroup.addView(xEt)
        tapGroup.addView(yEt)
        v.addView(tapGroup)
        v.addView(holdCb)

        v.addView(label("مدة الضغط بالمللي ثانية:", 14f))
        val holdEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.holdMs.toString())
        }
        v.addView(holdEt)

        v.addView(label("حجم الزر (30 إلى 140):", 14f))
        val sizeEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.size.toString())
        }
        v.addView(sizeEt)

        fun applyType() {
            keyGroup.visibility = if (rbKey.isChecked) View.VISIBLE else View.GONE
            tapGroup.visibility = if (rbTap.isChecked) View.VISIBLE else View.GONE
        }
        applyType()
        rgType.setOnCheckedChangeListener { _, _ -> applyType() }

        fun collect() {
            cfg.label = labelEt.text.toString().trim().ifBlank { "K" }.take(4)
            cfg.type = if (rbTap.isChecked) BtnCfg.TYPE_TAP else BtnCfg.TYPE_KEY
            cfg.key = keyEt.text.toString().trim().ifBlank { "K" }
            cfg.holdMode = holdCb.isChecked
            val nx = xEt.intOr(0)
            val ny = yEt.intOr(0)
            if (nx != cfg.x || ny != cfg.y) {
                cfg.rawX = -1
                cfg.rawY = -1
            }
            cfg.x = nx
            cfg.y = ny
            cfg.holdMs = holdEt.intOr(40).coerceIn(10, 2000)
            cfg.size = sizeEt.intOr(56).coerceIn(30, 140)
            Store.upsert(this, cfg)
            notifyService(FloatingService.ACTION_RELOAD)
            renderList()
        }

        tapGroup.addView(btn("اختيار الموقع من الشاشة") {
            if (!Settings.canDrawOverlays(this)) {
                toast("أعطِ صلاحية العرض فوق التطبيقات أول")
            } else {
                collect()
                startForegroundService(
                    Intent(this, FloatingService::class.java)
                        .setAction(FloatingService.ACTION_PICK)
                        .putExtra("id", cfg.id)
                )
                dlg?.dismiss()
                moveTaskToBack(true)
            }
        })

        dlg = AlertDialog.Builder(this)
            .setTitle(if (isNew) "زر جديد" else "تعديل الزر")
            .setView(ScrollView(this).apply { addView(v) })
            .setPositiveButton("حفظ") { _, _ ->
                collect()
                if (cfg.type == BtnCfg.TYPE_KEY && Store.parseKeyCode(cfg.key) == 0) {
                    toast("تنبيه: المفتاح \"${cfg.key}\" غير معروف")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun notifyService(action: String) {
        if (FloatingService.running) {
            startService(Intent(this, FloatingService::class.java).setAction(action))
        }
    }

    // ------------------------------------------------------------ حالة وصلاحيات

    private fun shizukuState(): String = try {
        when {
            !Shizuku.pingBinder() -> "❌ Shizuku غير شغّال (افتح تطبيق Shizuku وشغّله)"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "✅ Shizuku جاهز"
            else -> "⚠️ Shizuku شغّال لكن يحتاج صلاحية (اضغط الزر 2)"
        }
    } catch (t: Throwable) {
        "❌ تعذر قراءة حالة Shizuku"
    }

    private fun refresh() {
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        status.text = "صلاحية العرض فوق التطبيقات: $overlay\n${shizukuState()}\nآخر طريقة إرسال: ${KeySender.lastPath}\nآخر خطأ: ${KeySender.lastErr.ifBlank { "—" }}"
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("شغّل Shizuku أول")
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("الصلاحية موجودة")
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("أنت رفضت الصلاحية. فعّلها من إعدادات تطبيق Shizuku")
            } else {
                Shizuku.requestPermission(100)
            }
        } catch (t: Throwable) {
            toast("خطأ: ${t.message}")
        }
    }

    private fun diagnose() {
        val mode = Store.mode(this)
        toast("جاري الفحص…")
        Thread {
            if (mode == KeySender.MODE_SHIZUKU) {
                KeySender.bindShizuku(this)
                KeySender.waitReady(4000)
            }
            val res = KeySender.diagnose(mode)
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("نتيجة الفحص")
                    .setMessage(res)
                    .setPositiveButton("تمام", null)
                    .show()
            }
        }.start()
    }

    private fun testRoot() {
        Thread {
            val ok = try {
                ProcessBuilder("su", "-c", "id").start().waitFor() == 0
            } catch (t: Throwable) {
                false
            }
            runOnUiThread { toast(if (ok) "✅ الروت شغّال" else "❌ الروت غير متوفر") }
        }.start()
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            toast("أعطِ صلاحية العرض فوق التطبيقات أول")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (FloatingService.running) {
            notifyService(FloatingService.ACTION_RELOAD)
        } else {
            startForegroundService(Intent(this, FloatingService::class.java))
        }
        toast("تم تشغيل الأزرار")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
