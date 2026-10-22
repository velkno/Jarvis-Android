package com.jarvis.agent.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import android.os.Build
import com.jarvis.agent.ai.ScreenImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.min
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * خدمة الوصول: عينا الوكيل ويداه.
 * - تقرأ شجرة عناصر الشاشة (نصوص، أزرار، مواضع).
 * - تنفّذ إيماءات: نقر، تمرير، كتابة.
 * يصل إليها بقية التطبيق عبر instance الساكن.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private var overlay: AgentOverlay? = null

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = AgentOverlay(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        overlay?.dispose()
        overlay = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlay?.dispose()
        overlay = null
        instance = null
        super.onDestroy()
    }

    // لا نحتاج الاستماع المستمر للأحداث؛ نقرأ الشجرة عند الطلب.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** اسم حزمة التطبيق الظاهر حالياً في المقدمة. */
    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * لقطة نصية لعناصر الشاشة القابلة للتفاعل، مرقّمة ليختار النموذج بينها.
     */
    fun describeScreen(): ScreenSnapshot {
        val root = rootInActiveWindow ?: return ScreenSnapshot(null, emptyList())
        val elements = mutableListOf<UiElement>()
        collect(root, elements)
        return ScreenSnapshot(root.packageName?.toString(), elements, SystemClock.elapsedRealtime())
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<UiElement>, depth: Int = 0) {
        if (node == null || depth > 60 || out.size >= 140) return
        // Password field content must not be sent to the remote AI provider.
        val text = if (node.isPassword) "•••• (password hidden)" else node.text?.toString()
        val desc = node.contentDescription?.toString()
        val label = (text ?: desc)?.trim()
        val usable = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable ||
            (!label.isNullOrBlank() && node.isVisibleToUser)
        if (usable && node.isVisibleToUser) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.width() > 0 && b.height() > 0) {
                out.add(
                    UiElement(
                        index = out.size,
                        text = label ?: "",
                        className = node.className?.toString()?.substringAfterLast('.') ?: "",
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        enabled = node.isEnabled,
                        checked = node.isCheckable && node.isChecked,
                        focused = node.isFocused,
                        centerX = b.centerX(),
                        centerY = b.centerY(),
                        bounds = b,
                        resourceId = node.viewIdResourceName.orEmpty(),
                        password = node.isPassword
                    )
                )
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    /**
     * On-demand screenshot (Android 11+, API 30). Not persisted to storage.
     * Android 14+ targets the active app window, avoiding the Jarvis overlay.
     * Protected/secure windows are intentionally not bypassed.
     */
    suspend fun captureScreenImage(): ScreenImage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val shot = withTimeoutOrNull(4500) { captureBitmap() } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val maxEdge = maxOf(shot.width, shot.height)
                val ratio = min(1f, 2000f / maxEdge)
                val w = maxOf(1, (shot.width * ratio).toInt())
                val h = maxOf(1, (shot.height * ratio).toInt())
                val scaled = if (w == shot.width && h == shot.height) shot
                    else Bitmap.createScaledBitmap(shot, w, h, true)
                val out = ByteArrayOutputStream()
                try {
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 78, out)) return@withContext null
                    ScreenImage(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), w, h)
                } finally {
                    if (scaled !== shot) scaled.recycle()
                    out.close()
                }
            } catch (_: Exception) {
                null
            } finally {
                shot.recycle()
            }
        }
    }

    private suspend fun captureBitmap(): Bitmap? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val callback = object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val copy = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        if (cont.isActive) cont.resume(copy) else copy?.recycle()
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    } finally {
                        buffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val id = rootInActiveWindow?.windowId
                    if (id != null && id >= 0) {
                        takeScreenshotOfWindow(id, mainExecutor, callback)
                    } else {
                        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                    }
                } else {
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** نقر على إحداثيات عبر إيماءة. */
    suspend fun tap(x: Int, y: Int): Boolean {
        withContext(Dispatchers.Main.immediate) { overlay?.gesture(x, y) }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatch(gesture)
    }

    /** تمرير من نقطة إلى نقطة. */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300): Boolean {
        withContext(Dispatchers.Main.immediate) { overlay?.gesture(x1, y1, x2, y2, duration) }
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatch(gesture)
    }

    /** Press and hold a point, useful for context menus and drag handles. */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 900): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = durationMs.coerceIn(400, 5000)
        withContext(Dispatchers.Main.immediate) { overlay?.gesture(x, y) }
        return dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build())
    }

    /** Two taps must be part of a single gesture to avoid a race with the UI. */
    suspend fun doubleTap(x: Int, y: Int): Boolean {
        withContext(Dispatchers.Main.immediate) { overlay?.gesture(x, y) }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65))
            .addStroke(GestureDescription.StrokeDescription(path, 150, 65))
            .build())
    }

    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 900): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        withContext(Dispatchers.Main.immediate) { overlay?.gesture(x1, y1, x2, y2, durationMs) }
        return dispatch(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(200, 5000)))
            .build())
    }

    /** Perform two simultaneous finger strokes to zoom in/out. */
    suspend fun pinch(centerX: Int, centerY: Int, zoomIn: Boolean, spread: Int = 220): Boolean {
        val distance = spread.coerceIn(40, 500)
        val close = (distance / 5f).coerceAtLeast(15f)
        val far = distance.toFloat()
        fun stroke(side: Float): GestureDescription.StrokeDescription {
            val start = if (zoomIn) close else far
            val end = if (zoomIn) far else close
            val path = Path().apply {
                moveTo(centerX + side * start, centerY.toFloat())
                lineTo(centerX + side * end, centerY.toFloat())
            }
            return GestureDescription.StrokeDescription(path, 0, 500)
        }
        return dispatch(GestureDescription.Builder()
            .addStroke(stroke(-1f)).addStroke(stroke(1f)).build())
    }

    /** Find a labeled element by accessibility text/description, allowing first-match automation. */
    fun matchElement(text: String, exact: Boolean = false): UiElement? {
        if (text.isBlank()) return null
        return describeScreen().elements.firstOrNull {
            if (exact) it.text.equals(text, ignoreCase = true)
            else it.text.contains(text, ignoreCase = true)
        }
    }

    /** Scroll the closest accessible scrolling container to a point or element. */
    fun scrollAt(x: Int, y: Int, forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (!b.contains(x, y)) return null
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child)?.let { return it }
            }
            return if (node.isScrollable) node else null
        }
        val node = walk(root) ?: return false
        return node.performAction(action)
    }

    /** Replace, append or clear a field without relying on the keyboard layout. */
    fun editText(text: String, elementIndex: Int?, mode: String): Boolean {
        val current = if (mode == "append") focusedEditableText(elementIndex) else null
        val replacement = when (mode) {
            "clear" -> ""
            "append" -> (current ?: return false) + text
            else -> text
        }
        return typeText(replacement, elementIndex)
    }

    private fun focusedEditableText(elementIndex: Int?): String? {
        val root = rootInActiveWindow ?: return null
        val target = if (elementIndex == null) findFocusedEditable(root) else {
            val element = describeScreen().elements.getOrNull(elementIndex) ?: return null
            findEditableAt(root, element.centerX, element.centerY)
        }
        return target?.text?.toString() ?: ""
    }

    /** Prefer the native accessibility action: parents can be clickable when labels are not. */
    fun clickElement(element: UiElement, longClick: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        var node = nodeForElement(root, element) ?: return false
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK
            else AccessibilityNodeInfo.ACTION_CLICK
        repeat(8) {
            if (node.isEnabled && node.performAction(action)) return true
            node = node.parent ?: return false
        }
        return false
    }

    private fun nodeForElement(node: AccessibilityNodeInfo?, element: UiElement): AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds == element.bounds &&
            node.className?.toString()?.substringAfterLast('.') == element.className &&
            (element.resourceId.isEmpty() || node.viewIdResourceName == element.resourceId)) return node
        for (i in 0 until node.childCount) {
            nodeForElement(node.getChild(i), element)?.let { return it }
        }
        return null
    }

    /** Paste into the focused editable field without reading clipboard history. */
    fun pasteFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return focused.isEditable && focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    /** كتابة نص في أول حقل قابل للتحرير مُركّز، أو في عنصر بفهرس محدد. */
    fun typeText(text: String, elementIndex: Int? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = if (elementIndex != null) {
            val list = mutableListOf<UiElement>()
            collect(root, list)
            val el = list.getOrNull(elementIndex) ?: return false
            findEditableAt(root, el.centerX, el.centerY)
        } else {
            findFocusedEditable(root)
        } ?: return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        // Search the WHOLE tree for the focused field before falling back to
        // the first available one; otherwise a sibling field steals input.
        return findActuallyFocusedEditable(node) ?: firstEditable(node)
    }

    private fun findActuallyFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            findActuallyFocusedEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun firstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            firstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findEditableAt(node: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val b = Rect().also { node.getBoundsInScreen(it) }
        if (node.isEditable && b.contains(x, y)) return node
        for (i in 0 until node.childCount) {
            findEditableAt(node.getChild(i), x, y)?.let { return it }
        }
        return null
    }

    /** زر Enter/إرسال في لوحة المفاتيح عبر الحقل المُركّز (أندرويد 11+). */
    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return focused.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        }
        return false
    }

    /** أزرار النظام العامة. */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val result = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { result.complete(true) }
            override fun onCancelled(d: GestureDescription?) { result.complete(false) }
        }, null)
        if (!ok) return false
        return withTimeoutOrNull(6000) { result.await() } ?: false
    }
}

/** عنصر واجهة واحد مرئي على الشاشة. */
data class UiElement(
    val index: Int,
    val text: String,
    val className: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val centerX: Int,
    val centerY: Int,
    val bounds: Rect,
    val resourceId: String = "",
    val password: Boolean = false
)

/** لقطة كاملة لحالة الشاشة. */
data class ScreenSnapshot(
    val packageName: String?,
    val elements: List<UiElement>,
    val capturedAt: Long = 0L
) {
    /** تحويلها إلى نص يفهمه النموذج. */
    fun toPrompt(): String {
        if (elements.isEmpty()) return "الشاشة فارغة أو لا يمكن قراءتها. التطبيق: ${packageName ?: "غير معروف"}"
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val sb = StringBuilder("التطبيق الحالي: ${packageName ?: "غير معروف"}; أبعاد العرض: ${dm.widthPixels}x${dm.heightPixels}\nعناصر الشاشة:\n")
        elements.take(80).forEach { e ->
            val kind = when {
                e.editable -> "حقل كتابة"
                e.clickable -> "زر/قابل للنقر"
                else -> "نص"
            }
            val flags = buildList {
                if (e.scrollable) add("scroll")
                if (e.checked) add("checked")
                if (e.focused) add("focused")
                if (!e.enabled) add("disabled")
            }.joinToString(",")
            sb.append("[${e.index}] $kind: \"${e.text.take(160)}\" @(${e.centerX},${e.centerY}) ")
            sb.append("bounds=${e.bounds.left},${e.bounds.top},${e.bounds.right},${e.bounds.bottom} id=${e.resourceId.take(85)} $flags\n")
        }
        return sb.toString()
    }
}
