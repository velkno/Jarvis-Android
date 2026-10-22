package com.jarvis.agent.control

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.agent.agent.AgentSession
import com.jarvis.agent.agent.LogLine
import com.jarvis.agent.ui.MainActivity
import com.jarvis.agent.util.Permissions
import com.jarvis.agent.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Overlays use TYPE_ACCESSIBILITY_OVERLAY, so they belong to the explicitly enabled
 * AccessibilityService, NOT an unrestricted SYSTEM_ALERT_WINDOW overlay.
 * All WindowManager operations run on the main thread.
 */
class AgentOverlay(private val service: JarvisAccessibilityService) {
    companion object {
        @Volatile var instance: AgentOverlay? = null
            private set
        private val BLUE = 0xFF30AFFF.toInt()
    }

    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val voice = Voice(service)
    private var bubble: FrameLayout? = null
    private var bubbleLabel: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var effects: BlueControlEffects? = null
    private var panel: LinearLayout? = null
    private var panelLog: TextView? = null
    private var panelStatus: TextView? = null
    private var panelStop: Button? = null
    private var appForeground = false
    private var active = false
    private var disposed = false
    private var temporaryHide = false
    private val screenWidth get() = service.resources.displayMetrics.widthPixels
    private val screenHeight get() = service.resources.displayMetrics.heightPixels

    init {
        instance = this
        refreshPreferences()
        scope.launch {
            AgentSession.busy.collectLatest { busy ->
                active = busy
                effects?.setRunning(busy)
                bubbleLabel?.text = if (busy) "●" else "✦"
                bubbleLabel?.setTextColor(if (busy) Color.CYAN else Color.WHITE)
                panelStatus?.text = if (busy) "● جارفيس يتحكم الآن" else "جاهز للأوامر"
                panelStop?.isEnabled = busy
                // The panel is dismissed when sending a task so it cannot cover target apps.
            }
        }
        scope.launch { AgentSession.log.collectLatest { showLog(it) } }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density + 0.5f).toInt()

    private fun bg(color: Int, radius: Int = 16, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun layout(width: Int, height: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

    fun refreshPreferences() = onMain {
        if (disposed) return@onMain
        if (OverlaySettings.effectsEnabled(service)) {
            if (effects == null) {
                val effect = BlueControlEffects(service)
                val p = layout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                p.gravity = Gravity.TOP or Gravity.LEFT
                if (addSafely(effect, p)) effects = effect
            }
            effects?.setRunning(active)
        } else {
            effects?.let(::removeSafely)
            effects = null
        }
        if (OverlaySettings.bubbleEnabled(service)) {
            if (bubble == null) createBubble()
            updateBubbleVisibility()
        } else {
            closePanel()
            bubble?.let(::removeSafely)
            bubble = null
            bubbleLabel = null
            bubbleParams = null
        }
    }

    fun setAppForeground(foreground: Boolean) = onMain {
        appForeground = foreground
        if (foreground) closePanel()
        updateBubbleVisibility()
    }

    private fun updateBubbleVisibility() {
        bubble?.visibility = if (appForeground || temporaryHide) View.GONE else View.VISIBLE
    }

    /** Hide only the touchable bubble while a gesture is dispatched, never the blue effects. */
    fun gesture(x1: Int, y1: Int, x2: Int = x1, y2: Int = y1, duration: Long = 50) = onMain {
        if (disposed) return@onMain
        effects?.showGesture(x1, y1, x2, y2, duration)
        temporaryHide = true
        updateBubbleVisibility()
        handler.removeCallbacks(restoreBubble)
        handler.postDelayed(restoreBubble, duration + 160L)
    }

    private val restoreBubble = Runnable {
        temporaryHide = false
        updateBubbleVisibility()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val size = dp(56)
        val root = FrameLayout(service).apply {
            background = bg(0xEE103359.toInt(), 28, BLUE)
            elevation = dp(9).toFloat()
            contentDescription = "فقاعة جارفيس، اضغط لفتح الدردشة أو اسحب لنقلها"
        }
        val icon = TextView(service).apply {
            text = if (active) "●" else "✦"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        bubbleLabel = icon
        root.addView(icon, FrameLayout.LayoutParams(-1, -1))
        val params = layout(size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = screenWidth - size - dp(10)
            y = OverlaySettings.bubbleY(service).coerceIn(dp(60),
                (screenHeight - size - dp(60)).coerceAtLeast(dp(60)))
        }
        var downX = 0f
        var downY = 0f
        var originalX = 0
        var originalY = 0
        var moved = false
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originalX = params.x; originalY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (abs(deltaX) > dp(7) || abs(deltaY) > dp(7)) moved = true
                    if (moved) {
                        params.x = (originalX + deltaX.toInt()).coerceIn(0, (screenWidth - size).coerceAtLeast(0))
                        params.y = (originalY + deltaY.toInt()).coerceIn(0, (screenHeight - size).coerceAtLeast(0))
                        updateSafely(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // Snap to the nearest edge without covering the center of the display.
                        params.x = if (params.x + size / 2 < screenWidth / 2) dp(6) else screenWidth - size - dp(6)
                        updateSafely(root, params)
                        OverlaySettings.setBubbleY(service, params.y)
                    } else {
                        if (panel == null) showPanel() else closePanel()
                    }
                    true
                }
                else -> true
            }
        }
        if (addSafely(root, params)) {
            bubble = root
            bubbleParams = params
            updateBubbleVisibility()
        }
    }

    fun showPanel() = onMain {
        if (disposed || panel != null || !OverlaySettings.bubbleEnabled(service) || appForeground) return@onMain
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg(0xF9152035.toInt(), 22, BLUE)
            elevation = dp(14).toFloat()
        }
        fun label(text: String, size: Float, color: Int): TextView = TextView(service).apply {
            this.text = text; textSize = size; setTextColor(color)
        }
        val title = label("✦ JARVIS", 19f, Color.WHITE)
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        val collapse = Button(service).apply {
            text = "✕"; contentDescription = "إغلاق الفقاعة"; setOnClickListener { closePanel() }
        }
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(collapse, LinearLayout.LayoutParams(dp(55), dp(48)))
        }
        container.addView(header)
        val status = label(if (active) "● جارفيس يتحكم الآن" else "جاهز للأوامر", 12f, BLUE)
        panelStatus = status
        container.addView(status)
        val scroller = ScrollView(service).apply { isFillViewport = true }
        val logView = label("", 12f, 0xFFE0E7FF.toInt()).apply {
            setPadding(dp(4), dp(9), dp(4), dp(8))
        }
        panelLog = logView
        scroller.addView(logView)
        container.addView(scroller, LinearLayout.LayoutParams(-1, dp(155)))
        val input = EditText(service).apply {
            hint = "اكتب أمرك لجارفيس…"
            setHintTextColor(0xFF8897B0.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            minLines = 1; maxLines = 3
            setSingleLine(false)
            background = bg(0xFF111B2D.toInt(), 12, 0xFF3A658B.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        container.addView(input, LinearLayout.LayoutParams(-1, -2))
        val send = Button(service).apply {
            text = "إرسال"
            setOnClickListener {
                val request = input.text.toString().trim()
                if (request.isNotEmpty() && !AgentSession.busy.value) {
                    closePanel()
                    AgentSession.send(service.applicationContext, request)
                }
            }
        }
        val mic = Button(service).apply {
            text = "🎙"; contentDescription = "أمر صوتي"
            setOnClickListener {
                if (!Permissions.micGranted(service)) {
                    AgentSession.note("امنح إذن الميكروفون من صفحة صلاحيات جارفيس.")
                    showLog(AgentSession.log.value)
                } else {
                    input.hint = "أستمع…"
                    voice.stopSpeaking()
                    voice.listen(
                        onPartial = { input.setText(it); input.setSelection(input.text.length) },
                        onResult = { result ->
                            input.setText(result)
                            if (!AgentSession.busy.value) {
                                closePanel()
                                AgentSession.send(service.applicationContext, result)
                            }
                        },
                        onError = { error ->
                            input.hint = "اكتب أمرك لجارفيس…"
                            AgentSession.note(error)
                        }
                    )
                }
            }
        }
        val stop = Button(service).apply {
            text = "■ إيقاف"; isEnabled = active
            setOnClickListener { AgentSession.stop() }
        }
        panelStop = stop
        val home = Button(service).apply {
            text = "↗ التطبيق"
            setOnClickListener {
                closePanel()
                service.startActivity(Intent(service, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }
        val buttons = LinearLayout(service).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(send, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mic, LinearLayout.LayoutParams(0, -2, 0.8f))
            addView(stop, LinearLayout.LayoutParams(0, -2, 1.25f))
        }
        container.addView(buttons)
        container.addView(home, LinearLayout.LayoutParams(-1, -2))
        val width = min(screenWidth - dp(24), dp(370)).coerceAtLeast(dp(250))
        val p = layout(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if (addSafely(container, p)) {
            panel = container
            showLog(AgentSession.log.value)
            bubble?.visibility = View.GONE
        } else {
            panelLog = null; panelStatus = null; panelStop = null
        }
    }

    fun closePanel() = onMain {
        voice.stopListening()
        panel?.let(::removeSafely)
        panel = null
        panelLog = null
        panelStatus = null
        panelStop = null
        updateBubbleVisibility()
    }

    private fun showLog(log: List<LogLine>) {
        val display = log.takeLast(8).joinToString("\n\n") { line ->
            val tag = when (line.kind) {
                LogLine.Kind.USER -> "أنت"
                LogLine.Kind.AGENT -> "جارفيس"
                LogLine.Kind.ACTION -> "⌖ إجراء"
                LogLine.Kind.OBSERVATION -> "↳ نتيجة"
                LogLine.Kind.ERROR -> "⚠ تنبيه"
            }
            "$tag: ${line.text.take(230)}"
        }
        panelLog?.text = display.ifBlank { "اطلب مهمة من هنا دون الرجوع إلى التطبيق." }
    }

    /** Keep voice output working when MainActivity is no longer visible. */
    fun takeOverSpeech() = onMain {
        if (!disposed) AgentSession.onFinalReply = { text ->
            if (OverlaySettings.speechEnabled(service)) voice.speak(text)
        }
    }

    fun dispose() = onMain {
        if (disposed) return@onMain
        disposed = true
        handler.removeCallbacks(restoreBubble)
        closePanel()
        bubble?.let(::removeSafely)
        bubble = null
        effects?.let(::removeSafely)
        effects = null
        voice.release()
        if (instance === this) instance = null
        AgentSession.onFinalReply = null
        scope.cancel()
    }

    private fun addSafely(view: View, params: WindowManager.LayoutParams): Boolean = try {
        manager.addView(view, params); true
    } catch (error: Exception) {
        android.util.Log.w("JarvisOverlay", "Cannot create overlay", error)
        false
    }

    private fun removeSafely(view: View) {
        try { manager.removeView(view) } catch (_: Exception) { }
    }

    private fun updateSafely(view: View, params: WindowManager.LayoutParams) {
        try { manager.updateViewLayout(view, params) } catch (_: Exception) { }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** Draws a pulsing blue edge and visible cursor, but does not intercept any screen taps. */
    private class BlueControlEffects(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var running = false
        private var lastActionTime = 0L
        private var startX = -1f
        private var startY = -1f
        private var endX = -1f
        private var endY = -1f
        private var moveDuration = 0L
        private val origin = IntArray(2)
        private val density = resources.displayMetrics.density
        private fun d(n: Float) = n * density

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun setRunning(value: Boolean) {
            running = value
            invalidate()
        }

        fun showGesture(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) {
            startX = x1.toFloat(); startY = y1.toFloat()
            endX = x2.toFloat(); endY = y2.toFloat()
            moveDuration = duration.coerceAtLeast(1)
            lastActionTime = SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = SystemClock.uptimeMillis()
            val actionAge = now - lastActionTime
            if (!running && actionAge > 900) return
            if (running) {
                val pulse = (kotlin.math.sin(now / 350.0) * 0.5 + 0.5).toFloat()
                val bounds = RectF(d(3f), d(3f), width - d(3f), height - d(3f))
                for (i in 3 downTo 0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = d(1.5f + i * 2f)
                    paint.color = Color.argb(((26 + pulse * 43) / (i + 1)).toInt(), 35, 161, 255)
                    canvas.drawRoundRect(bounds, d(22f), d(22f), paint)
                }
                paint.strokeWidth = d(2.2f)
                paint.color = Color.argb((135 + 110 * pulse).toInt(), 65, 191, 255)
                canvas.drawRoundRect(bounds, d(22f), d(22f), paint)
            }
            if (startX >= 0 && (running || actionAge < 900)) {
                val progress = (actionAge.toFloat() / moveDuration).coerceIn(0f, 1f)
                val x = startX + (endX - startX) * progress
                val y = startY + (endY - startY) * progress
                getLocationOnScreen(origin)
                canvas.save()
                canvas.translate(x - origin[0], y - origin[1])
                // Classic visible mouse pointer, including white outline.
                path.reset()
                path.moveTo(0f, 0f)
                path.lineTo(0f, d(25f))
                path.lineTo(d(7f), d(19f))
                path.lineTo(d(13f), d(29f))
                path.lineTo(d(18f), d(26f))
                path.lineTo(d(12f), d(16f))
                path.lineTo(d(21f), d(15f))
                path.close()
                paint.style = Paint.Style.FILL
                paint.color = BLUE
                paint.setShadowLayer(d(7f), 0f, 0f, BLUE)
                canvas.drawPath(path, paint)
                paint.clearShadowLayer()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = d(1.5f)
                paint.color = Color.WHITE
                canvas.drawPath(path, paint)
                if (actionAge < 650) {
                    val radius = d(12f) + d(34f) * actionAge / 650f
                    paint.strokeWidth = d(2f)
                    paint.color = Color.argb(((1 - actionAge / 650f) * 220).toInt(), 50, 175, 255)
                    canvas.drawCircle(0f, 0f, radius, paint)
                }
                canvas.restore()
            }
            // Only animate while the agent is active or the latest click ripple is fading.
            if (running || actionAge < 900) postInvalidateDelayed(33)
        }
    }
}
