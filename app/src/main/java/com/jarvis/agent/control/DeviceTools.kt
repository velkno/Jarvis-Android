package com.jarvis.agent.control

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.content.IntentFilter
import android.media.AudioManager
import android.view.KeyEvent
import android.os.SystemClock
import com.jarvis.agent.ai.ScreenImage
import com.jarvis.agent.ai.ParamSpec
import com.jarvis.agent.ai.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * سجلّ الأدوات: يعرّف ما يستطيع الوكيل فعله، وينفّذه فعلياً على الجهاز.
 * يجمع بين Accessibility (قراءة الشاشة + إيماءات) والروت (تحكم مطلق).
 */
class DeviceTools(private val context: android.content.Context) {

    private val apps = AppLauncher(context)

    private val json = Json { ignoreUnknownKeys = true }

    /** قائمة الأدوات التي تُعرض للنموذج. */
    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "read_screen",
            description = "اقرأ الشاشة الحية. استخدم include_image=false للشاشات النصية الواضحة لتقليل التأخير، وtrue عند وجود أيقونات أو صور غير مفهومة. إذا لم تمرر المعامل فسيُتبع إعداد Vision/SC.",
            parameters = mapOf(
                "include_image" to ParamSpec("boolean", "التقاط صورة اختيارياً عند الحاجة، إذا سمح المستخدم بـ Vision/SC")
            )
        ),
        ToolSpec(
            name = "tap",
            description = "انقر على عنصر بفهرسه (المفضّل) أو على إحداثيات محددة.",
            parameters = mapOf(
                "index" to ParamSpec("integer", "فهرس العنصر من read_screen"),
                "x" to ParamSpec("integer", "إحداثي أفقي (بديل عن index)"),
                "y" to ParamSpec("integer", "إحداثي رأسي (بديل عن index)")
            )
        ),
        ToolSpec(
            name = "type_text",
            description = "اكتب نصاً في حقل الكتابة. حدّد index للحقل إن عُرف، وإلا يُكتب في الحقل المُركّز.",
            parameters = mapOf(
                "text" to ParamSpec("string", "النص المراد كتابته"),
                "index" to ParamSpec("integer", "فهرس حقل الكتابة (اختياري)"),
                "mode" to ParamSpec("string", "استبدال أو إلحاق أو مسح", listOf("replace", "append", "clear"))
            ),
            required = listOf("text")
        ),
        ToolSpec(
            name = "swipe",
            description = "مرّر على الشاشة. اتجاهات جاهزة: up/down/left/right، أو حدّد إحداثيات.",
            parameters = mapOf(
                "direction" to ParamSpec(
                    "string", "اتجاه التمرير", listOf("up", "down", "left", "right")
                ),
                "x1" to ParamSpec("integer", "موضع البداية x (اختياري)"),
                "y1" to ParamSpec("integer", "موضع البداية y (اختياري)"),
                "x2" to ParamSpec("integer", "موضع النهاية x (اختياري)"),
                "y2" to ParamSpec("integer", "موضع النهاية y (اختياري)"),
                "ms" to ParamSpec("integer", "مدة التمرير")
            )
        ),
        ToolSpec(
            name = "long_press",
            description = "اضغط مطولاً على عنصر من آخر read_screen، أو إحداثيات الشاشة الأصلية لفتح قائمة السياق.",
            parameters = mapOf(
                "index" to ParamSpec("integer", "فهرس عنصر اختياري"),
                "x" to ParamSpec("integer", "x"), "y" to ParamSpec("integer", "y"),
                "ms" to ParamSpec("integer", "مدة الضغط من 400 إلى 5000 مللي ثانية")
            )
        ),
        ToolSpec(
            name = "double_tap",
            description = "نقر مزدوج، بإحداثيات أو فهرس عنصر.",
            parameters = mapOf(
                "index" to ParamSpec("integer", "فهرس عنصر"),
                "x" to ParamSpec("integer", "x"), "y" to ParamSpec("integer", "y")
            )
        ),
        ToolSpec(
            name = "drag",
            description = "اسحب من نقطة لأخرى (لإعادة ترتيب عنصر أو تحريك شريط). استخدم إحداثيات الشاشة الأصلية.",
            parameters = mapOf(
                "x1" to ParamSpec("integer", "بداية أفقية"), "y1" to ParamSpec("integer", "بداية رأسية"),
                "x2" to ParamSpec("integer", "نهاية أفقية"), "y2" to ParamSpec("integer", "نهاية رأسية"),
                "ms" to ParamSpec("integer", "مدة السحب")
            ), required = listOf("x1", "y1", "x2", "y2")
        ),
        ToolSpec(
            name = "pinch",
            description = "قرّب أو باعد بإصبعين للتكبير والتصغير داخل التطبيقات، لا يحتاج روت.",
            parameters = mapOf(
                "x" to ParamSpec("integer", "منتصف المنطقة"), "y" to ParamSpec("integer", "منتصف المنطقة"),
                "direction" to ParamSpec("string", "التحجيم", listOf("in", "out")),
                "spread" to ParamSpec("integer", "مسافة الأصابع 40-500")
            ), required = listOf("direction")
        ),
        ToolSpec(
            name = "tap_text",
            description = "ابحث عن نص واجهة مرئي وانقر العنصر المطابق بعد التحقق من الشاشة.",
            parameters = mapOf(
                "text" to ParamSpec("string", "الجزء المراد مطابقته"),
                "exact" to ParamSpec("boolean", "مطابقة كاملة بدلاً من احتواء")
            ), required = listOf("text")
        ),
        ToolSpec(
            name = "scroll_element",
            description = "مرر الحاوية القابلة للتمرير عند إحداثيات أو موضع عنصر، مع تفضيل تمرير واجهة الوصول.",
            parameters = mapOf(
                "index" to ParamSpec("integer", "فهرس العنصر"), "x" to ParamSpec("integer", "x"),
                "y" to ParamSpec("integer", "y"), "direction" to ParamSpec("string", "up أو down", listOf("up", "down"))
            ), required = listOf("direction")
        ),
        ToolSpec(
            name = "wait_for",
            description = "انتظر حتى يظهر نص محدّد على الشاشة؛ يجري عدة عمليات فحص خلال مهلة محدودة، بلا تصوير متكرر.",
            parameters = mapOf(
                "text" to ParamSpec("string", "النص المراد ظهوره"),
                "timeout_ms" to ParamSpec("integer", "المهلة 500-10000 مللي ثانية")
            ), required = listOf("text")
        ),
        ToolSpec(
            name = "open_url",
            description = "افتح رابط ويب من نوع HTTPS أو HTTP في المتصفح.",
            parameters = mapOf("url" to ParamSpec("string", "الرابط الكامل")), required = listOf("url")
        ),
        ToolSpec(
            name = "open_settings",
            description = "افتح صفحة إعدادات نظام محددة: wifi, bluetooth, display, accessibility, apps, battery, sound, notifications.",
            parameters = mapOf("page" to ParamSpec("string", "صفحة الإعدادات", listOf("wifi", "bluetooth", "display", "accessibility", "apps", "battery", "sound", "notifications"))),
            required = listOf("page")
        ),
        ToolSpec(
            name = "device_status",
            description = "بيانات الجهاز والبطارية وأبعاد الشاشة وتوافر الوصول والرؤية والروت؛ لا يحتاج أذونات جديدة.",
            parameters = emptyMap()
        ),
        ToolSpec(
            name = "clipboard_write",
            description = "انسخ نصاً إلى حافظة الهاتف (كتابة فقط، لا تقرأ بيانات المستخدم السابقة).",
            parameters = mapOf("text" to ParamSpec("string", "نص الحافظة")), required = listOf("text")
        ),
        ToolSpec(
            name = "clipboard_paste",
            description = "ألصق الحافظة في حقل الكتابة المحدد الآن، دون قراءة محتواها من جارفيس.",
            parameters = emptyMap()
        ),
        ToolSpec(
            name = "set_volume",
            description = "غير مستوى الصوت خطوة للأعلى أو الأسفل لقناة الوسائط أو الرنين (لا يحتاج روت).",
            parameters = mapOf(
                "channel" to ParamSpec("string", "media أو ring", listOf("media", "ring")),
                "direction" to ParamSpec("string", "up أو down", listOf("up", "down"))
            ), required = listOf("direction")
        ),
        ToolSpec(
            name = "media_key",
            description = "تحكم بمشغل الوسائط الحالي: play، pause، next، previous (إن سمح التطبيق).",
            parameters = mapOf(
                "key" to ParamSpec("string", "media key", listOf("play", "pause", "next", "previous"))
            ), required = listOf("key")
        ),
        ToolSpec(
            name = "press_key",
            description = "اضغط زر نظام: back / home / recents / notifications / enter.",
            parameters = mapOf(
                "key" to ParamSpec(
                    "string", "الزر",
                    listOf("back", "home", "recents", "notifications", "quick_settings", "enter")
                )
            ),
            required = listOf("key")
        ),
        ToolSpec(
            name = "open_app",
            description = "افتح تطبيقاً بالاسم الظاهر (مثل «واتساب» أو «YouTube») أو باسم الحزمة (مثل com.whatsapp).",
            parameters = mapOf(
                "app" to ParamSpec("string", "اسم التطبيق أو اسم حزمته")
            ),
            required = listOf("app")
        ),
        ToolSpec(
            name = "list_apps",
            description = "اعرض التطبيقات المثبتة (الاسم + الحزمة).",
            parameters = emptyMap()
        ),
        ToolSpec(
            name = "run_shell",
            description = "اعرض معلومات نظام القراءة فقط مع الروت (getprop, pm list packages, dumpsys battery, settings get). الأوامر المخصصة أو المعدّلة ممنوعة للسلامة.",
            parameters = mapOf(
                "command" to ParamSpec("string", "أمر الـ shell")
            ),
            required = listOf("command")
        ),
        ToolSpec(
            name = "wait",
            description = "انتظر مدة قصيرة (مثلاً بعد فتح تطبيق) حتى تستقر الشاشة.",
            parameters = mapOf(
                "ms" to ParamSpec("integer", "مدة الانتظار بالملّي ثانية (افتراضي 800)")
            )
        ),
        ToolSpec(
            name = "finish",
            description = "أنهِ المهمة عند اكتمالها وأبلغ المستخدم بالنتيجة.",
            parameters = mapOf(
                "summary" to ParamSpec("string", "خلاصة ما تم إنجازه للمستخدم")
            ),
            required = listOf("summary")
        )
    ).filter { it.name != "run_shell" || OverlaySettings.rootDiagnosticsEnabled(context) }

    /**
     * Lightweight on-device Accessibility observation for the beginning of a
     * user-requested UI task. It captures no screenshot and makes no API call.
     * The same snapshot backs element indices returned to the model.
     */
    fun initialObservation(): String? {
        val service = JarvisAccessibilityService.instance ?: return null
        return runCatching {
            val snapshot = service.describeScreen()
            lastSnapshot = snapshot
            "\n<untrusted_screen_data>\n" + snapshot.toPrompt().take(6500) +
                "\n</untrusted_screen_data>"
        }.getOrNull()
    }

    /** ينفّذ أداة ويعيد ناتجاً نصياً يُعاد للنموذج. */
    suspend fun execute(name: String, argsJson: String): ToolOutcome {
        val args = runCatching {
            json.parseToJsonElement(argsJson.ifEmpty { "{}" }).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))

        val acc = JarvisAccessibilityService.instance

        fun intArg(k: String): Int? = args[k]?.jsonPrimitive?.intOrNull
        fun strArg(k: String): String? = args[k]?.jsonPrimitive?.contentOrNull
        fun boolArg(k: String): Boolean = args[k]?.jsonPrimitive?.booleanOrNull ?: false
        fun coords(): Pair<Int, Int>? {
            val idx = intArg("index")
            if (idx != null) {
                val snap = lastSnapshot ?: return null
                if (acc != null && (acc.currentPackage() != snap.packageName ||
                    SystemClock.elapsedRealtime() - snap.capturedAt > 90000)) return null
                val e = snap.elements.getOrNull(idx) ?: return null
                return e.centerX to e.centerY
            }
            val x = intArg("x") ?: return null
            val y = intArg("y") ?: return null
            return x to y
        }

        return when (name) {
            "read_screen" -> {
                val snap = acc?.describeScreen()
                    ?: return ToolOutcome.text("خدمة الوصول غير مفعّلة. فعّلها من الإعدادات.")
                lastSnapshot = snap
                val imageRequested = args["include_image"]?.jsonPrimitive?.booleanOrNull ?: true
                if (!OverlaySettings.visionEnabled(context)) {
                    ToolOutcome.text(snap.toPrompt() + "\nVision/SC: غير مفعّل من الإعدادات.")
                } else if (!imageRequested) {
                    ToolOutcome.text(snap.toPrompt() + "\nVision: تم استخدام العناصر النصية فقط لتوفير الوقت.")
                } else if (android.os.Build.VERSION.SDK_INT < 30) {
                    ToolOutcome.text(snap.toPrompt() + "\nVision/SC يتطلب Android 11 أو أحدث، استخدم العناصر النصية.")
                } else {
                    val screenshot = acc.captureScreenImage()
                    if (screenshot == null) {
                        ToolOutcome.text(snap.toPrompt() + "\nتعذّر تصوير الشاشة (نافذة محمية أو إذن غير متاح). استخدم العناصر النصية.")
                    } else {
                        ToolOutcome(
                            snap.toPrompt() + "\nأُرفقت صورة حية للشاشة (" +
                                "${screenshot.width}x${screenshot.height}). استخدم مواضع العناصر الأصلية للنقر.",
                            image = screenshot
                        )
                    }
                }
            }

            "tap" -> {
                val idx = intArg("index")
                if (idx != null) {
                    val snap = lastSnapshot
                    val el = snap?.elements?.getOrNull(idx)
                        ?: return ToolOutcome.text("لا يوجد عنصر بالفهرس $idx. اقرأ الشاشة أولاً.")
                    if (acc == null || snap.packageName != acc.currentPackage() ||
                        SystemClock.elapsedRealtime() - snap.capturedAt > 90000)
                        return ToolOutcome.text("تغيرت الشاشة أو انتهت صلاحية الفهرس؛ نفّذ read_screen مجدداً.")
                    val ok = acc.clickElement(el) || acc.tap(el.centerX, el.centerY)
                    ToolOutcome.text(if (ok) "تم النقر على \"${el.text}\"" else "فشل النقر")
                } else {
                    val x = intArg("x"); val y = intArg("y")
                    if (x == null || y == null)
                        return ToolOutcome.text("حدّد index أو (x,y).")
                    val ok = acc?.tap(x, y) ?: false
                    ToolOutcome.text(if (ok) "تم النقر على ($x,$y)" else "فشل النقر")
                }
            }

            "long_press" -> {
                val (x, y) = coords() ?: return ToolOutcome.text("اقرأ الشاشة وحدد index أو x,y.")
                val idx = intArg("index")
                val el = idx?.let { lastSnapshot?.elements?.getOrNull(it) }
                val usedNative = el != null && acc != null && acc.currentPackage() == lastSnapshot?.packageName && acc.clickElement(el, true)
                val ok = usedNative || (acc?.longPress(x, y, (intArg("ms") ?: 900).toLong()) ?: false)
                ToolOutcome.text(if (ok) "تم الضغط المطول على ($x,$y)" else "تعذّر الضغط المطول. فعّل إمكانية الوصول.")
            }
            "double_tap" -> {
                val (x, y) = coords() ?: return ToolOutcome.text("حدّد index أو x,y.")
                val ok = acc?.doubleTap(x, y) ?: false
                ToolOutcome.text(if (ok) "تم النقر المزدوج" else "تعذّر النقر المزدوج")
            }
            "drag" -> {
                val x1 = intArg("x1") ?: return ToolOutcome.text("x1 مفقود")
                val y1 = intArg("y1") ?: return ToolOutcome.text("y1 مفقود")
                val x2 = intArg("x2") ?: return ToolOutcome.text("x2 مفقود")
                val y2 = intArg("y2") ?: return ToolOutcome.text("y2 مفقود")
                val ok = acc?.drag(x1, y1, x2, y2, (intArg("ms") ?: 900).toLong()) ?: false
                ToolOutcome.text(if (ok) "تم السحب" else "فشل السحب")
            }
            "pinch" -> {
                val dm = context.resources.displayMetrics
                val x = intArg("x") ?: dm.widthPixels / 2
                val y = intArg("y") ?: dm.heightPixels / 2
                val ok = acc?.pinch(x, y, strArg("direction") == "in", intArg("spread") ?: 220) ?: false
                ToolOutcome.text(if (ok) "تم التحجيم" else "تعذّر التحجيم")
            }
            "tap_text" -> {
                val text = strArg("text") ?: return ToolOutcome.text("النص مفقود")
                val el = acc?.matchElement(text, boolArg("exact"))
                    ?: return ToolOutcome.text("لم يُعثر على النص؛ استعمل read_screen")
                val ok = acc.clickElement(el) || acc.tap(el.centerX, el.centerY)
                ToolOutcome.text(if (ok) "تم النقر على ${el.text.take(80)}" else "فشل النقر")
            }
            "scroll_element" -> {
                val (x, y) = coords() ?: return ToolOutcome.text("حدّد index أو x,y بعد read_screen")
                val forward = strArg("direction") == "down"
                val ok = acc?.scrollAt(x, y, forward) ?: false
                ToolOutcome.text(if (ok) "تم تمرير الحاوية" else "لم تُعثر حاوية تدعم التمرير")
            }
            "wait_for" -> {
                val text = strArg("text")?.takeIf { it.isNotBlank() }
                    ?: return ToolOutcome.text("حدد النص المطلوب")
                val timeout = (intArg("timeout_ms") ?: 5000).coerceIn(500, 10000)
                val started = android.os.SystemClock.elapsedRealtime()
                var element: UiElement? = null
                while (android.os.SystemClock.elapsedRealtime() - started < timeout) {
                    element = acc?.matchElement(text)
                    if (element != null) break
                    delay(120)
                }
                ToolOutcome.text(if (element != null) "ظهر النص '$text' عند (${element.centerX},${element.centerY})" else "انتهت المهلة دون ظهور '$text'")
            }
            "open_url" -> {
                val url = strArg("url") ?: return ToolOutcome.text("الرابط مفقود")
                val uri = runCatching { Uri.parse(url) }.getOrNull()
                if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank())
                    return ToolOutcome.text("يُسمح فقط بروابط http أو https الصالحة")
                val success = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                ToolOutcome.text(if (success) "تم فتح الرابط" else "فشل فتح المتصفح")
            }
            "open_settings" -> {
                val action = when (strArg("page")) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    else -> return ToolOutcome.text("صفحة إعدادات غير مدعومة")
                }
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (strArg("page") == "notifications")
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                ToolOutcome.text(if (opened) "تم فتح الإعدادات" else "تعذّر فتح الصفحة")
            }
            "device_status" -> {
                val dm = context.resources.displayMetrics
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                ToolOutcome.text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), الجهاز ${Build.MANUFACTURER} ${Build.MODEL}, الشاشة ${dm.widthPixels}x${dm.heightPixels}, البطارية $pct%, الوصول ${if (acc != null) "مفعّل" else "معطّل"}, Vision/SC ${OverlaySettings.visionEnabled(context)}")
            }
            "clipboard_write" -> {
                val text = strArg("text") ?: return ToolOutcome.text("النص مفقود")
                val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                manager.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis", text))
                ToolOutcome.text("تم نسخ النص إلى الحافظة")
            }
            "clipboard_paste" -> {
                ToolOutcome.text(if (acc?.pasteFocused() == true) "تم اللصق في الحقل النشط" else "تعذر اللصق؛ ركّز على حقل كتابة أولاً")
            }
            "set_volume" -> {
                val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                val channel = if (strArg("channel") == "ring") AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
                val direction = when (strArg("direction")) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    else -> return ToolOutcome.text("اختر up أو down")
                }
                manager.adjustStreamVolume(channel, direction, 0)
                ToolOutcome.text("تم تغيير مستوى الصوت خطوة واحدة")
            }
            "media_key" -> {
                val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                val code = when (strArg("key")) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> return ToolOutcome.text("زر وسائط غير مدعوم")
                }
                val now = SystemClock.uptimeMillis()
                manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                manager.dispatchMediaKeyEvent(KeyEvent(now, now + 10, KeyEvent.ACTION_UP, code, 0))
                ToolOutcome.text("تم إرسال زر الوسائط (قد يتجاهله التطبيق الحالي)")
            }
            "type_text" -> {
                val text = strArg("text") ?: return ToolOutcome.text("نص مفقود.")
                val idx = intArg("index")
                val mode = strArg("mode") ?: "replace"
                val ok = acc?.editText(text, idx, mode) ?: false
                ToolOutcome.text(if (ok) "تمت كتابة النص." else "فشلت الكتابة.")
            }

            "swipe" -> {
                val dir = strArg("direction") ?: "up"
                val (dx1, dy1, dx2, dy2) = swipeCoords(dir)
                val x1 = intArg("x1") ?: dx1; val y1 = intArg("y1") ?: dy1
                val x2 = intArg("x2") ?: dx2; val y2 = intArg("y2") ?: dy2
                val ok = acc?.swipe(x1, y1, x2, y2, (intArg("ms") ?: 300).toLong().coerceIn(100, 5000)) ?: false
                ToolOutcome.text(if (ok) "تم التمرير $dir" else "فشل التمرير")
            }

            "press_key" -> {
                val key = strArg("key") ?: return ToolOutcome.text("زر مفقود.")
                val ok = when (key) {
                    "back" -> acc?.pressBack() ?: RootShell.keyEvent(4).success
                    "home" -> acc?.pressHome() ?: RootShell.keyEvent(3).success
                    "recents" -> acc?.openRecents() ?: RootShell.keyEvent(187).success
                    "notifications" -> acc?.openNotifications() ?: false
                    "quick_settings" -> acc?.openQuickSettings() ?: false
                    "enter" -> acc?.pressEnter() ?: RootShell.keyEvent(66).success
                    else -> false
                }
                ToolOutcome.text(if (ok) "تم الضغط على $key" else "فشل الضغط على $key")
            }

            "open_app" -> {
                val q = strArg("app") ?: strArg("package")
                    ?: return ToolOutcome.text("اسم التطبيق مفقود.")
                val launcherCtx = acc ?: context
                val msg = apps.launch(launcherCtx, q)
                // Do not sleep blindly: the next UI observation verifies loading.
                ToolOutcome.text(msg)
            }

            "list_apps" -> {
                ToolOutcome.text("التطبيقات المثبتة:\n" +
                    apps.list().joinToString("\n") { "${it.label} — ${it.pkg}" })
            }

            "run_shell" -> {
                if (!OverlaySettings.rootDiagnosticsEnabled(context))
                    return ToolOutcome.text("تشخيص الروت مُعطّل؛ فعّله يدوياً في الصلاحيات أولاً.")
                val cmd = strArg("command") ?: return ToolOutcome.text("الأمر مفقود.")
                if (!RootShell.isAvailable())
                    return ToolOutcome.text("الهاتف غير مروّت، لذلك run_shell غير متاح. أنجز المهمة بالأدوات الأخرى (open_app، tap، type_text، swipe…).")
                if (!isReadOnlyCommand(cmd))
                    return ToolOutcome.text("run_shell يسمح فقط بأوامر قراءة آمنة؛ الأوامر المؤثرة على الملفات والنظام ممنوعة.")
                ToolOutcome.text(RootShell.exec(cmd).asText())
            }

            "wait" -> {
                delay((intArg("ms") ?: 800).toLong().coerceIn(0, 5000))
                ToolOutcome.text("انتهى الانتظار.")
            }

            "finish" -> ToolOutcome.finish(strArg("summary") ?: "اكتملت المهمة.")

            else -> ToolOutcome.text("أداة غير معروفة: $name")
        }
    }

    private var lastSnapshot: ScreenSnapshot? = null

    private fun swipeCoords(dir: String): List<Int> {
        // نسب من أبعاد الشاشة الفعلية
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val cx = w / 2; val cy = h / 2
        return when (dir) {
            "up" -> listOf(cx, (h * 0.70).toInt(), cx, (h * 0.30).toInt())
            "down" -> listOf(cx, (h * 0.30).toInt(), cx, (h * 0.70).toInt())
            "left" -> listOf((w * 0.80).toInt(), cy, (w * 0.20).toInt(), cy)
            "right" -> listOf((w * 0.20).toInt(), cy, (w * 0.80).toInt(), cy)
            else -> listOf(cx, (h * 0.70).toInt(), cx, (h * 0.30).toInt())
        }
    }

    /** A strict allowlist prevents shell metacharacters and arbitrary root execution. */
    private fun isReadOnlyCommand(command: String): Boolean {
        val c = command.trim()
        return c == "getprop" ||
            c == "pm list packages" || c == "pm list packages -3" ||
            c == "dumpsys battery" || c == "dumpsys display" ||
            c.matches(Regex("settings get (system|secure|global) [a-zA-Z0-9_]+")) ||
            c.matches(Regex("getprop [a-zA-Z0-9_.-]+"))
    }
}

/** ناتج تنفيذ أداة. */
data class ToolOutcome(
    val result: String,
    val isFinish: Boolean = false,
    val image: ScreenImage? = null
) {
    companion object {
        fun text(s: String) = ToolOutcome(s, false)
        fun finish(s: String) = ToolOutcome(s, true)
    }
}

private operator fun <T> List<T>.component4(): T = this[3]
