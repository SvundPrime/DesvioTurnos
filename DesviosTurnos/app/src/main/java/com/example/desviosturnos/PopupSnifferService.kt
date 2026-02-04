package com.example.desviosturnos

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

class PopupSnifferService : AccessibilityService() {

    companion object {
        private const val TAG = "POPUP_SNIFFER"

        private const val APPLY_PREFS = "apply_prefs"
        private const val SNIFFER_PREFS = "sniffer_prefs"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_TEXT = "last_text"
        private const val KEY_LAST_TS = "last_timestamp"

        private const val KEY_ARMED_SINCE = "armed_since"
        private const val KEY_ARMED_WINDOW_MS = "armed_window_ms"
        private const val KEY_EXPECTED_MMI = "expected_mmi"
        private const val DEFAULT_WINDOW_MS = 60_000L
        private const val CALL_CLICK_DELAY_MS = 300L

        private val DISMISS_BUTTON_TEXTS = listOf(
            "Aceptar", "aceptar", "OK", "ok", "Cerrar", "cerrar"
        )

        private val CALL_BUTTON_TEXTS = listOf(
            "Llamar", "CALL", "Call", "Marcar"
        )
    }
    private val PHONE_PKGS = setOf(
        "com.android.phone",
        "com.samsung.android.dialer",
        "com.samsung.android.app.dialer",
        "com.google.android.dialer"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var armedSinceMs: Long? = null
    private var armedWindowMs: Long = DEFAULT_WINDOW_MS
    private var lastCallClickArmedSince: Long = 0L
    private var lastCallClickAt: Long = 0L
    private var lastHeartbeatAt: Long = 0L
    private val HEARTBEAT_THROTTLE_MS = 1500L
    private var pendingCallClick: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.e(TAG, "onServiceConnected()")
        writeHeartbeat("connected")
        armedSinceMs = null
        armedWindowMs = DEFAULT_WINDOW_MS
    }

    override fun onInterrupt() {
        Log.e(TAG, "onInterrupt()")
        writeHeartbeat("interrupt")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in PHONE_PKGS) return

        val now = System.currentTimeMillis()
        val clazz = event.className?.toString().orEmpty()
        writeHeartbeat("event_${event.eventType}")
        val armed = ensureArmedFromPrefs(now)
        if (armed) {
            val since = armedSinceMs ?: 0L
            if (since > 0L && since != lastCallClickArmedSince) {
                if (pkg == "com.samsung.android.dialer" || pkg == "com.samsung.android.app.dialer" || pkg == "com.google.android.dialer") {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    ) {
                        scheduleCallClick(since, pkg, clazz)
                    }
                }
            }
        }
        val rawText = buildString {
            event.text?.forEach { append(it).append(" ") }
            event.contentDescription?.let { append(it).append(" ") }
        }.trim()

        if (rawText.isBlank()) return

        val norm = normalize(rawText)

        if (norm.contains("codigo mmi iniciado") || norm.contains("mmi code started")) {
            Log.e(TAG, "Ignoring intermediate MMI toast: '$rawText'")
            return
        }
        val looksLikeForwardingDialog = looksLikeForwardingResult(norm)

        if (!looksLikeForwardingDialog) {
            return
        }
        val result = classify(norm)

        Log.e(TAG, "FORWARDING_DIALOG armed=$armed result=$result pkg=$pkg class=$clazz raw='$rawText'")
        if (armed) {
            if (result == "OK" || result == "FAIL" || result == "FAIL_MIXED") {
                writeResult(result, rawText)
                armedSinceMs = null
                dismissDialog()
            } else {
                Log.e(TAG, "Result UNKNOWN -> keep armed for next dialog")
            }
        }
    }

    

    private fun ensureArmedFromPrefs(now: Long = System.currentTimeMillis()): Boolean {
        val cachedSince = armedSinceMs
        if (cachedSince != null) {
            val age = now - cachedSince
            if (age in 0..armedWindowMs) return true
            armedSinceMs = null
        }

        val p = getSharedPreferences(APPLY_PREFS, MODE_PRIVATE)
        val since = p.getLong(KEY_ARMED_SINCE, 0L)
        val win = p.getLong(KEY_ARMED_WINDOW_MS, DEFAULT_WINDOW_MS).takeIf { it > 0 } ?: DEFAULT_WINDOW_MS

        if (since <= 0L) return false
        if (now - since > win) return false

        armedSinceMs = since
        armedWindowMs = win
        return true
    }

    

    private fun looksLikeForwardingResult(t: String): Boolean {
        val signals = listOf(
            "desvio", "desvio de llamadas",
            "call forwarding", "forwarding",
            "codigo mmi", "mmi",
            "el registro", "se ha realizado",
            "service code", "registration"
        )
        val hits = signals.count { t.contains(it) }
        return hits >= 2
    }

    

    private fun clickCallButtonIfPresentGuarded(): Boolean {
        val root = rootInActiveWindow ?: return false

        if (!isExpectedMmiVisible(root)) {
            Log.e(TAG, "SAFE_GUARD: MMI not visible in dialer -> NOT clicking call")
            return false
        }
        for (t in CALL_BUTTON_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(t)
            val direct = nodes?.firstOrNull { it.isClickable }
            val parent = nodes?.firstOrNull { it.parent?.isClickable == true }?.parent
            val nodeToClick = direct ?: parent
            if (nodeToClick != null) {
                val ok = nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.e(TAG, "CALL_CLICK byText '$t' => $ok")
                return ok
            }
        }
        val byDesc = findFirstClickableByDesc(root, setOf("Llamar", "Call", "Marcar", "Llamar con"))
        if (byDesc != null) {
            val ok = byDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.e(TAG, "CALL_CLICK byDesc => $ok desc='${byDesc.contentDescription}'")
            return ok
        }
        val fab = findFirstClickableByViewIdContains(root, listOf("dialpad", "call", "fab", "floating"))
        if (fab != null) {
            val ok = fab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.e(TAG, "CALL_CLICK byViewIdContains => $ok id='${fab.viewIdResourceName}'")
            return ok
        }

        Log.e(TAG, "CALL_CLICK not found (guard passed, but no button)")
        return false
    }

    private fun findFirstClickableByViewIdContains(
        node: AccessibilityNodeInfo,
        parts: List<String>
    ): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (node.isClickable && id.isNotBlank() && parts.any { id.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findFirstClickableByViewIdContains(child, parts)
            if (hit != null) return hit
        }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo?, block: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        block(node)
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), block)
        }
    }

    private fun isExpectedMmiVisible(root: AccessibilityNodeInfo): Boolean {
        val expected = getSharedPreferences(APPLY_PREFS, MODE_PRIVATE)
            .getString(KEY_EXPECTED_MMI, "")
            .orEmpty()
            .trim()
        val needles = if (expected.isNotBlank()) listOf(expected) else listOf("*21*")
        var found = false

        walk(root) { node ->
            val cls = node.className?.toString().orEmpty()
            val txt = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()

            val candidate = (txt.ifBlank { desc }).trim()

            if (candidate.isNotBlank()
                && needles.any { candidate.contains(it) }
                && candidate.contains("#")
            ) {
                found = true
                return@walk
            }
            if ((cls.contains("EditText") || cls.contains("TextView"))
                && needles.any { candidate.contains(it) }
                && candidate.contains("#")
            ) {
                found = true
                return@walk
            }
        }

        return found
    }



    private fun findFirstClickableByDesc(
        node: AccessibilityNodeInfo,
        targets: Set<String>
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString().orEmpty()
        if (node.isClickable && desc.isNotBlank() && targets.any { desc.contains(it, ignoreCase = true) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findFirstClickableByDesc(child, targets)
            if (hit != null) return hit
        }
        return null
    }

    

    private fun classify(t: String): String {

        val successPhrases = listOf(
            "el registro se ha realizado correctamente",
            "el registro se realizo correctamente",
            "se ha realizado correctamente",
            "se realizo correctamente",
            "registro realizado correctamente",
            "service code registered",
            "registration was successful",
            "success"
        )

        val failPhrases = listOf(
            "no se realizo correctamente",
            "no se ha realizado correctamente",
            "se ha producido un problema",
            "problema de conexion",
            "codigo mmi no es valido",
            "mmi no es valido",
            "no es valido",
            "invalido",
            "error",
            "fallo",
            "failed",
            "failure",
            "invalid",
            "connection problem"
        )

        val hasSuccess = successPhrases.any { t.contains(it) }
        val hasFail = failPhrases.any { t.contains(it) }

        if (hasFail && hasSuccess) return "FAIL_MIXED"
        if (hasFail) return "FAIL"
        if (hasSuccess) return "OK"
        val failTokens = listOf("problema", "error", "fallo", "invalido", "no valido", "conexion")
        if (failTokens.any { t.contains(it) }) return "FAIL"

        return "UNKNOWN"
    }

    private fun writeResult(result: String, raw: String) {
        getSharedPreferences(APPLY_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_RESULT, result)
            .putString(KEY_LAST_TEXT, raw)
            .putLong(KEY_LAST_TS, System.currentTimeMillis())
            .apply()

        Log.e(TAG, "✅ RESULT WRITTEN: $result")
    }

    private fun writeHeartbeat(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatAt < HEARTBEAT_THROTTLE_MS) return
        lastHeartbeatAt = now

        getSharedPreferences(SNIFFER_PREFS, MODE_PRIVATE)
            .edit()
            .putLong("sniffer_heartbeat_at", now)
            .putString("sniffer_heartbeat_reason", reason)
            .apply()
    }

    private fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    

    private fun dismissDialog(retries: Int = 2) {
        val root = rootInActiveWindow
        if (root == null) {
            if (retries > 0) handler.postDelayed({ dismissDialog(retries - 1) }, 150)
            return
        }

        for (txt in DISMISS_BUTTON_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(txt)
            val node = nodes?.firstOrNull { it.isClickable }
                ?: nodes?.firstOrNull { it.parent?.isClickable == true }?.parent

            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.e(TAG, "DISMISS via '$txt' => $ok")
                return
            }
        }

        val ok = root.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
        Log.e(TAG, "DISMISS fallback ACTION_DISMISS => $ok")
    }

    private fun scheduleCallClick(armedSince: Long, pkg: String, clazz: String) {
        pendingCallClick?.let { handler.removeCallbacks(it) }
        pendingCallClick = Runnable {
            val now = System.currentTimeMillis()
            if (!ensureArmedFromPrefs(now)) return@Runnable
            if (armedSince == lastCallClickArmedSince) return@Runnable
            Log.e(TAG, "Trying SAFE_CALL_CLICK... armedSince=$armedSince pkg=$pkg class=$clazz")
            val clicked = clickCallButtonIfPresentGuarded()
            if (clicked) {
                lastCallClickArmedSince = armedSince
                lastCallClickAt = now
            }
        }
        handler.postDelayed(pendingCallClick!!, CALL_CLICK_DELAY_MS)
    }
}
