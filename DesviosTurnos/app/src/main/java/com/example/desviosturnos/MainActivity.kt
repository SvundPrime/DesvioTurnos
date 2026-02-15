package com.example.desviosturnos

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private enum class OperationMode { NORMAL, OFICINA, FESTIVO }

    private enum class ShiftLabel(val es: String) {
        MANANA("Mañana"),
        TARDE("Tarde"),
        ENTRETURN0("Entreturno"),
        NOCHE("Noche"),
        GUARDIA("Guardia")
    }

    private data class Selection(
        val targetId: String?,
        val turnoEs: String,
        val reason: String
    )

    private data class ApplyContext(
        val source: String,
        val trigger: String,
        val id: String // IMPORTANTE: aquí guardamos lockId cuando viene de web
    )

    private companion object {
        const val REQ_PERMS = 1001
        const val AUTO_ALARM_REQ_CODE = 2001

        // --- LOCK DISTRIBUIDO (Firestore devices/{id}.applyLock)
        const val APPLY_LOCK_TTL_MS = 90_000L
        const val COMMAND_ACK_TTL_MS = 300_000L

        const val DEVICE_ID = "rediris"

        const val MOBILE_EMAIL = "movil-rediris@minsait.com"
        const val MOBILE_PASS = "RedIRIS123.,"

        val ZONE: ZoneId = ZoneId.of("Europe/Madrid")

        private val MON_THU = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY
        )
        private val NIGHT_DJ = setOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY
        )

        const val APPLY_PREFS = "apply_prefs"
        const val KEY_LAST_RESULT = "last_result"
        const val KEY_LAST_TEXT = "last_text"
        const val KEY_LAST_TS = "last_timestamp"
        const val KEY_ARMED_SINCE = "armed_since"
        const val KEY_ARMED_WINDOW_MS = "armed_window_ms"
        const val KEY_EXPECTED_MMI = "expected_mmi"

        const val OK_TIMEOUT_MS = 60_000L
        const val RETRY_DELAY_MS = 1_200L

        const val APPLY_SOURCE_AUTO = "AUTO"
        const val APPLY_SOURCE_MANUAL = "MANUAL"
        const val APPLY_SOURCE_FORCED = "FORCED"

        const val APPLY_TRIGGER_AUTO_BOUNDARY = "AUTO_BOUNDARY"
        const val APPLY_TRIGGER_REMOTE_CMD = "REMOTE_CMD"
        const val APPLY_TRIGGER_RETRY = "RETRY"
    }

    private fun nowMs() = System.currentTimeMillis()

    private fun lockRef() = db.collection("devices").document(DEVICE_ID)
    private fun cmdRef() = db.collection("commands").document(DEVICE_ID)

    /**
     * Intenta adquirir un lock global para este dispositivo.
     * Devuelve true si lo adquiere, false si ya estaba lockeado por otro (o por ti).
     */
    private fun tryAcquireApplyLock(lockId: String, reason: String, onDone: (Boolean) -> Unit) {
        val ref = lockRef()
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val data = snap.data
            val lock = data?.get("applyLock") as? Map<*, *>

            val locked = lock?.get("locked") as? Boolean ?: false
            val expiresAt = (lock?.get("expiresAt") as? Number)?.toLong() ?: 0L

            val active = locked && expiresAt > nowMs()
            if (active) return@runTransaction false

            val payload = mapOf(
                "applyLock" to mapOf(
                    "locked" to true,
                    "lockId" to lockId,
                    "lockedBy" to (auth.currentUser?.email ?: MOBILE_EMAIL),
                    "lockedAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to (nowMs() + APPLY_LOCK_TTL_MS),
                    "reason" to reason
                ),
                "status" to "running",
                "statusReason" to "Aplicando...",
                "lastAt" to FieldValue.serverTimestamp()
            )

            tx.set(ref, payload, SetOptions.merge())
            true
        }.addOnSuccessListener { ok ->
            onDone(ok == true)
        }.addOnFailureListener {
            onDone(false)
        }
    }

    private data class ApplyLock(
        val locked: Boolean,
        val lockId: String?,
        val expiresAt: Long?,
        val lockedBy: String?
    )

    private fun parseApplyLock(data: Map<String, Any?>?): ApplyLock? {
        val lock = (data?.get("applyLock") as? Map<*, *>) ?: return null
        val locked = (lock["locked"] as? Boolean) ?: false
        val lockId = (lock["lockId"] as? String)?.trim()
        val expiresAt = when (val v = lock["expiresAt"]) {
            is Number -> v.toLong()
            else -> null
        }
        val lockedBy = (lock["lockedBy"] as? String)?.trim()
        return ApplyLock(locked, lockId, expiresAt, lockedBy)
    }

    private fun isLockActive(lock: ApplyLock?): Boolean {
        if (lock == null) return false
        val now = System.currentTimeMillis()
        return lock.locked && (lock.expiresAt ?: 0L) > now
    }

    private fun isMyLockActive(lock: ApplyLock?, commandLockId: String?): Boolean {
        if (!isLockActive(lock)) return false
        val a = lock?.lockId?.trim().orEmpty()
        val b = commandLockId?.trim().orEmpty()
        return a.isNotBlank() && b.isNotBlank() && a == b
    }

    private fun releaseApplyLock(lockId: String, reason: String? = null) {
        val ref = lockRef()
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val lock = (snap.get("applyLock") as? Map<*, *>) ?: emptyMap<String, Any?>()
            val curId = lock["lockId"] as? String

            // Solo sueltas si eres el dueño
            if (curId != lockId) return@runTransaction null

            val payload = mutableMapOf<String, Any?>(
                "applyLock" to mapOf(
                    "locked" to false,
                    "lockId" to curId,
                    "releasedAt" to FieldValue.serverTimestamp(),
                    "releaseReason" to (reason ?: "done"),
                    "expiresAt" to 0L
                ),
                "lastAt" to FieldValue.serverTimestamp()
            )
            tx.set(ref, payload, SetOptions.merge())
            null
        }
    }

    private fun ackCommandHandled(requestId: String, status: String, note: String? = null) {
        // status: "ACCEPTED" | "IGNORED_BUSY" | "IGNORED_LOCKED" | "DONE" | "FAILED"
        if (requestId.isBlank()) return
        val payload = mapOf(
            "ack" to mapOf(
                "requestId" to requestId,
                "status" to status,
                "note" to (note ?: ""),
                "at" to FieldValue.serverTimestamp(),
                "expiresAt" to (nowMs() + COMMAND_ACK_TTL_MS)
            )
        )
        cmdRef().set(payload, SetOptions.merge())
    }

    private lateinit var tvDebug: TextView

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private val prefs: SharedPreferences by lazy { getSharedPreferences("forwarding_prefs", MODE_PRIVATE) }
    private val applyPrefs: SharedPreferences by lazy { getSharedPreferences(APPLY_PREFS, MODE_PRIVATE) }

    private val handler = Handler(Looper.getMainLooper())
    private var applyInProgress = false
    private var applyStartMs: Long = 0L
    private var timeoutRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var lastStatusFingerprint: String? = null

    private var lastProcessedRequestId: String?
        get() = prefs.getString("last_request_id", null)
        set(value) = prefs.edit { putString("last_request_id", value) }

    // ✅ NUEVO: guardamos requestId para ACK (NO usar lockId)
    private var lastCommandRequestId: String? = null

    private var cachedConfig: Map<String, Any?>? = null
    private var lastTargetId: String? = null
    private var lastTargetName: String? = null
    private var nextTargetId: String? = null
    private var nextTargetName: String? = null
    private var nextChangeAt: ZonedDateTime? = null
    private var lastShiftEs: String? = null
    private var lastWasForced: Boolean = false
    private var lastForcedReason: String? = null
    private var currentApplyContext: ApplyContext? = null
    private val autoTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val snifferListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        Log.e("SNIFFER_LISTENER", "TRIGGER key=$key applyInProgress=$applyInProgress applyStartMs=$applyStartMs")

        if (!applyInProgress) {
            Log.e("SNIFFER_LISTENER", "DROP applyInProgress=false (ignoring prefs change)")
            return@OnSharedPreferenceChangeListener
        }

        if (
            key != KEY_LAST_RESULT &&
            key != KEY_LAST_TS &&
            key != KEY_LAST_TEXT &&
            key != KEY_EXPECTED_MMI &&
            key != KEY_ARMED_SINCE
        ) {
            Log.e("SNIFFER_LISTENER", "DROP key not relevant: $key")
            return@OnSharedPreferenceChangeListener
        }

        val result = applyPrefs.getString(KEY_LAST_RESULT, null)?.trim().orEmpty()
        val ts = applyPrefs.getLong(KEY_LAST_TS, 0L)
        val txt = applyPrefs.getString(KEY_LAST_TEXT, "") ?: ""

        Log.e("SNIFFER_LISTENER", "READ result='$result' ts=$ts txtLen=${txt.length} (now=${System.currentTimeMillis()})")

        if (result.isBlank() || ts <= 0L) {
            Log.e("SNIFFER_LISTENER", "DROP empty result or ts<=0 (result='$result', ts=$ts)")
            return@OnSharedPreferenceChangeListener
        }

        if (ts < applyStartMs) {
            Log.e("SNIFFER_LISTENER", "DROP old result (ts=$ts < applyStartMs=$applyStartMs)")
            return@OnSharedPreferenceChangeListener
        }

        when (result) {
            "OK" -> {
                Log.e("SNIFFER_LISTENER", "CASE OK -> stopApplyWindow + releaseLock + report")

                stopApplyWindow()

                // ✅ suelta lock real (id = lockId cuando viene de web)
                currentApplyContext?.id?.let { lockId ->
                    if (lockId.isNotBlank()) releaseApplyLock(lockId, reason = "OK")
                }

                // ✅ ACK con requestId real
                ackCommandHandled(lastCommandRequestId ?: "", "DONE")

                reportDeviceStatus(
                    status = "idle",
                    resultCode = "APPLY_OK",
                    resultadoEs = "Aplicado correctamente",
                    motivoEs = lastForcedReason ?: "Aplicar",
                    lastTargetId = lastTargetId,
                    lastTargetName = lastTargetName,
                    nextTargetId = nextTargetId,
                    nextTargetName = nextTargetName,
                    nextChangeAt = nextChangeAt,
                    turnoEs = refreshShiftNow(),
                    forced = lastWasForced,
                    forcedReason = lastForcedReason
                )

                bringAppToFront()
            }

            "FAIL", "FAIL_MIXED", "UNKNOWN" -> {
                Log.e("SNIFFER_LISTENER", "CASE $result -> stopApplyWindow + releaseLock + retry")

                stopApplyWindow()

                currentApplyContext?.id?.let { lockId ->
                    if (lockId.isNotBlank()) releaseApplyLock(lockId, reason = result)
                }

                // ✅ ACK con requestId real
                ackCommandHandled(lastCommandRequestId ?: "", "FAILED", result)

                scheduleRetry(
                    reasonCode = "POPUP_$result",
                    reasonEs = "Fallo al aplicar",
                    detail = txt
                )

                bringAppToFront()
            }

            else -> {
                Log.e("SNIFFER_LISTENER", "DROP unexpected result='$result'")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDebug = findViewById(R.id.tvDebug)

        findViewById<Button>(R.id.btnRequestPerms).setOnClickListener { requestNeededPermissions() }
        findViewById<Button>(R.id.btnTestCall).setOnClickListener { applyForwardingByContactName("Sebas") }
        findViewById<Button>(R.id.btnScheduleTest).setOnClickListener { scheduleForwardingInSeconds(seconds = 5) }

        applyPrefs.registerOnSharedPreferenceChangeListener(snifferListener)

        handleAlarmIntent(intent)
        ensureFirebaseAuthThenListen()
    }

    override fun onDestroy() {
        super.onDestroy()
        applyPrefs.unregisterOnSharedPreferenceChangeListener(snifferListener)
        stopApplyWindow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAlarmIntent(intent)
    }

    private fun readMode(cfg: Map<String, Any?>): OperationMode {
        val raw = (cfg["mode"] as? String)?.uppercase()?.trim()
        if (raw == "NORMAL" || raw == "OFICINA" || raw == "FESTIVO") {
            return OperationMode.valueOf(raw)
        }
        val officeMode = (cfg["officeMode"] as? Boolean) ?: false
        val forceHoliday = (cfg["forceHoliday"] as? Boolean) ?: false
        val n1Active = (cfg["n1Active"] as? Boolean) ?: true

        return when {
            forceHoliday -> OperationMode.FESTIVO
            officeMode -> OperationMode.OFICINA
            n1Active -> OperationMode.NORMAL
            else -> OperationMode.NORMAL
        }
    }

    private fun selectionDecision(
        reason: String,
        at: ZonedDateTime,
        mode: String,
        chosenId: String?,
        nightId: String?,
        n2Id: String?
    ): String {
        val why = when {
            chosenId == nightId -> "night"
            chosenId == n2Id -> "n2"
            chosenId.isNullOrBlank() -> "none"
            else -> "other"
        }
        val msg = "SELECT at=$at mode=$mode reason=$reason chosen=$chosenId night=$nightId n2=$n2Id winner=$why"
        Log.e("SHIFT_SELECT", msg)
        return msg
    }

    private fun normalizeId(raw: Any?): String? {
        val s = (raw as? String)?.trim()
        if (s.isNullOrEmpty()) return null
        if (s == NONE_ID) return null
        return s
    }

    private fun shiftContactId(cfg: Map<String, Any?>, key: String): String? {
        val shifts = cfg["shifts"] as? Map<*, *> ?: return null
        val obj = shifts[key] as? Map<*, *> ?: return null
        return normalizeId(obj["contactId"])
    }

    private fun isFri(dow: DayOfWeek) = dow == DayOfWeek.FRIDAY
    private fun isMonThu(dow: DayOfWeek) = dow in MON_THU
    private fun isNightDJ(dow: DayOfWeek) = dow in NIGHT_DJ
    private fun isWeekday(dow: DayOfWeek) = dow.value in 1..5

    private fun inRange(t: LocalTime, start: String, end: String): Boolean {
        val s = LocalTime.parse(start)
        val e = LocalTime.parse(end)
        return if (s <= e) (t >= s && t < e) else (t >= s || t < e)
    }

    private fun inInterWindow(at: ZonedDateTime): Boolean {
        val dow = at.dayOfWeek
        val t = at.toLocalTime()
        return (isMonThu(dow) && inRange(t, "08:30", "17:30")) ||
                (isFri(dow) && inRange(t, "08:00", "15:00"))
    }

    private fun interIdForDay(cfg: Map<String, Any?>, dow: DayOfWeek): String? {
        val interLJ = shiftContactId(cfg, "intershiftLJ")
        val interFri = shiftContactId(cfg, "intershiftFri")
        return when {
            isMonThu(dow) -> interLJ
            isFri(dow) -> interFri
            else -> null
        }
    }

    private fun n2Id(cfg: Map<String, Any?>): String? =
        (cfg["n2GuardId"] as? String)?.trim()?.takeIf { it.isNotBlank() }

    private fun shiftLabelFromChosen(
        chosenId: String?,
        morningId: String?,
        afterId: String?,
        nightId: String?,
        interId: String?,
        n2: String?
    ): String {
        return when {
            chosenId.isNullOrBlank() -> ShiftLabel.GUARDIA.es
            chosenId == morningId -> ShiftLabel.MANANA.es
            chosenId == afterId -> ShiftLabel.TARDE.es
            chosenId == nightId -> ShiftLabel.NOCHE.es
            chosenId == interId -> ShiftLabel.ENTRETURN0.es
            chosenId == n2 -> ShiftLabel.GUARDIA.es
            else -> ShiftLabel.GUARDIA.es
        }
    }

    private fun selectAt(cfg: Map<String, Any?>, at: ZonedDateTime): Selection {
        val d = ShiftSelectionEngine.selectAt(cfg, at)
        return Selection(
            d.targetId,
            d.turnoEs,
            selectionDecision(d.reason, at, d.mode.name, d.targetId, d.nightId, d.n2Id)
        )
    }

    private fun computeNextBoundaryAt(cfg: Map<String, Any?>, now: ZonedDateTime): ZonedDateTime {
        if (isWeekendN2Window(now)) {
            val today = now.toLocalDate()
            val sunday = when (now.dayOfWeek) {
                DayOfWeek.FRIDAY -> today.plusDays(2)
                DayOfWeek.SATURDAY -> today.plusDays(1)
                DayOfWeek.SUNDAY -> today
                else -> today
            }
            val target = sunday.atTime(23, 0).atZone(ZONE)
            return if (target.isAfter(now.plusSeconds(2))) target else sunday.plusDays(1).atStartOfDay(ZONE)
        }

        val today = now.toLocalDate()
        val candidates = mutableListOf<ZonedDateTime>()

        val morning = shiftContactId(cfg, "morning")
        val after = shiftContactId(cfg, "afternoon")

        for (d in 0L..1L) {
            val day = today.plusDays(d)
            val dow = day.dayOfWeek

            candidates += day.atTime(7, 0).atZone(ZONE)

            if (dow.value in 1..5) {
                candidates += day.atTime(15, 0).atZone(ZONE)
            }

            candidates += day.atTime(23, 0).atZone(ZONE)

            if (dow in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) {
                if (morning == null) candidates += day.atTime(8, 30).atZone(ZONE)
                if (after == null) candidates += day.atTime(17, 30).atZone(ZONE)
            }

            if (dow == DayOfWeek.FRIDAY && morning == null) {
                candidates += day.atTime(8, 0).atZone(ZONE)
            }
        }

        val safeAfter = now.plusSeconds(2)
        return candidates.filter { it.isAfter(safeAfter) }.minOrNull()
            ?: today.plusDays(1).atStartOfDay(ZONE)
    }

    private fun setUiTarget(targetName: String?, nextTargetName: String?, turnoEs: String?) {
        val t = targetName?.takeIf { it.isNotBlank() } ?: "—"
        val n = nextTargetName?.takeIf { it.isNotBlank() } ?: "—"
        val s = turnoEs?.takeIf { it.isNotBlank() } ?: "—"
        tvDebug.text = "Target: $t\nPróximo target: $n\nTurno: $s"
    }

    private fun ensureFirebaseAuthThenListen() {
        if (auth.currentUser != null) {
            startFirestoreListeners()
            return
        }

        auth.signInWithEmailAndPassword(MOBILE_EMAIL, MOBILE_PASS)
            .addOnSuccessListener { startFirestoreListeners() }
            .addOnFailureListener {
                reportIdleStatus(
                    resultCode = "AUTH_FAIL",
                    resultadoEs = "Fallo de autenticación",
                    motivoEs = "Inicio",
                    lastTargetIdOverride = null,
                    lastTargetNameOverride = null,
                    nextTargetIdOverride = null,
                    nextTargetNameOverride = null,
                    nextChangeAtOverride = null,
                    turnoEsOverride = null,
                    forced = false,
                    forcedReason = null
                )
                setUiTarget("ERROR AUTH", null, null)
            }
    }

    private fun startFirestoreListeners() {
        listenConfig()
        listenCommands()

        reportDeviceStatus(
            status = "idle",
            resultCode = "READY",
            resultadoEs = "Listo",
            motivoEs = "Inicio",
            lastTargetId = null,
            lastTargetName = null,
            nextTargetId = null,
            nextTargetName = null,
            nextChangeAt = null,
            turnoEs = null,
            forced = false,
            forcedReason = null
        )
        setUiTarget("READY", null, null)
    }

    private fun listenConfig() {
        db.collection("config").document(DEVICE_ID)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null || !snap.exists()) {
                    reportIdleStatus(
                        resultCode = "CONFIG_LISTENER_FAIL",
                        resultadoEs = "Fallo escuchando configuración",
                        motivoEs = "Listener"
                    )
                    setUiTarget(lastTargetName, nextTargetName, refreshShiftNow())
                    return@addSnapshotListener
                }

                cachedConfig = snap.data
                if (applyInProgress) {
                    Log.d("CONFIG", "Config changed while applying -> skip refreshTargetAndSchedule()")
                    return@addSnapshotListener
                }

                refreshTargetAndSchedule(from = "config_change")
            }
    }

    private fun refreshShiftNow(): String? {
        val cfg = cachedConfig ?: return lastShiftEs
        val now = ZonedDateTime.now(ZONE)
        val sel = selectAt(cfg, now)
        lastShiftEs = sel.turnoEs
        return sel.turnoEs
    }

    private fun listenCommands() {
        db.collection("commands").document(DEVICE_ID)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    reportIdleStatus(
                        resultCode = "COMMANDS_LISTENER_FAIL",
                        resultadoEs = "Fallo escuchando comandos",
                        motivoEs = "Listener"
                    )
                    setUiTarget(lastTargetName, nextTargetName, refreshShiftNow())
                    return@addSnapshotListener
                }

                if (snap == null || !snap.exists()) return@addSnapshotListener

                val action = snap.getString("action") ?: return@addSnapshotListener
                if (action != ACTION_APPLY_NOW) return@addSnapshotListener

                val requestId = snap.getString("requestId") ?: return@addSnapshotListener
                if (requestId.isBlank()) return@addSnapshotListener
                if (requestId == lastProcessedRequestId) return@addSnapshotListener

                val cmdLockId = snap.getString("lockId")?.trim()
                val lockIdToUse = cmdLockId?.takeIf { it.isNotBlank() } // ✅ ESTE ES EL ID QUE TIENE EL applyLock

                // 👇 Leemos el estado del dispositivo para evaluar lock
                db.collection("devices").document(DEVICE_ID).get()
                    .addOnSuccessListener { devSnap ->
                        val devData = devSnap.data as? Map<String, Any?>
                        val lock = parseApplyLock(devData)

                        val lockActive = isLockActive(lock)
                        val myLock = isMyLockActive(lock, cmdLockId)

                        if (applyInProgress) {
                            Log.d("APPLY", "IGNORE APPLY_NOW requestId=$requestId (applyInProgress=true)")
                            return@addOnSuccessListener
                        }

                        if (lockActive && myLock) {
                            Log.d("APPLY", "ACCEPT APPLY_NOW requestId=$requestId (my lockId=$cmdLockId)")
                        } else if (lockActive && !myLock) {
                            val who = lock?.lockedBy ?: "otro"
                            val until = lock?.expiresAt ?: 0L
                            Log.d("APPLY", "IGNORE APPLY_NOW requestId=$requestId (locked by $who until $until)")
                            return@addOnSuccessListener
                        } else {
                            Log.d("APPLY", "ACCEPT APPLY_NOW requestId=$requestId (no active lock)")
                        }

                        // ✅ GUARDAMOS requestId (para ACK) y lastProcessed
                        lastCommandRequestId = requestId
                        lastProcessedRequestId = requestId

                        lastWasForced = false
                        lastForcedReason = "Aplicación manual"

                        // ✅ CRÍTICO: context.id = lockId (si viene) para poder liberar el lock REAL
                        setApplyContext(
                            trigger = APPLY_TRIGGER_REMOTE_CMD,
                            triggerRequestId = lockIdToUse ?: requestId
                        )

                        reportDeviceStatus(
                            status = "running",
                            resultCode = "COMMAND_RECEIVED",
                            resultadoEs = "Comando recibido",
                            motivoEs = "Manual",
                            lastTargetId = lastTargetId,
                            lastTargetName = lastTargetName,
                            nextTargetId = nextTargetId,
                            nextTargetName = nextTargetName,
                            nextChangeAt = nextChangeAt,
                            turnoEs = refreshShiftNow(),
                            forced = lastWasForced,
                            forcedReason = lastForcedReason
                        )

                        wakeScreen()
                        bringAppToFront()
                        handler.postDelayed(
                            {
                                applyNowFromConfig(
                                    trigger = APPLY_TRIGGER_REMOTE_CMD,
                                    triggerRequestId = requestId // aquí puedes seguir pasando requestId como "tracking"
                                )
                            },
                            400
                        )
                    }
                    .addOnFailureListener {
                        Log.e("APPLY", "WARN cannot read devices/$DEVICE_ID lock -> proceed")

                        if (applyInProgress) return@addOnFailureListener
                        if (requestId == lastProcessedRequestId) return@addOnFailureListener

                        lastCommandRequestId = requestId
                        lastProcessedRequestId = requestId

                        lastWasForced = false
                        lastForcedReason = "Aplicación manual"

                        setApplyContext(
                            trigger = APPLY_TRIGGER_REMOTE_CMD,
                            triggerRequestId = lockIdToUse ?: requestId
                        )

                        wakeScreen()
                        bringAppToFront()
                        handler.postDelayed(
                            { applyNowFromConfig(trigger = APPLY_TRIGGER_REMOTE_CMD, triggerRequestId = requestId) },
                            400
                        )
                    }
            }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun refreshTargetAndSchedule(from: String) {
        val cfg = cachedConfig ?: return
        val now = ZonedDateTime.now(ZONE)
        lastWasForced = false
        lastForcedReason = null

        val nextAt = computeNextBoundaryAt(cfg, now)
        scheduleAlarm(nextAt)

        val curSel = selectAt(cfg, now)
        val nextSel = selectAt(cfg, nextAt.plusSeconds(1))

        lastShiftEs = curSel.turnoEs

        resolveDisplayName(curSel.targetId) { curName ->
            resolveDisplayName(nextSel.targetId) { nxtName ->
                lastTargetId = curSel.targetId
                lastTargetName = curName
                nextTargetId = nextSel.targetId
                nextTargetName = nxtName
                nextChangeAt = nextAt

                setUiTarget(curName ?: curSel.targetId, nxtName ?: nextSel.targetId, curSel.turnoEs)

                reportDeviceStatus(
                    status = "idle",
                    resultCode = "REFRESH",
                    resultadoEs = "Actualizado",
                    motivoEs = if (from == "config_change") "Cambio de configuración" else "Actualización",
                    lastTargetId = curSel.targetId,
                    lastTargetName = curName,
                    nextTargetId = nextSel.targetId,
                    nextTargetName = nxtName,
                    nextChangeAt = nextAt,
                    turnoEs = curSel.turnoEs,
                    forced = false,
                    forcedReason = null
                )
            }
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleAlarm(at: ZonedDateTime) {
        val intent = buildAutoBoundaryIntent()

        val pending = PendingIntent.getBroadcast(
            this,
            AUTO_ALARM_REQ_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending)

        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at.toInstant().toEpochMilli(),
            pending
        )

        Log.d("ALARM", "AUTO_BOUNDARY scheduled at=$at epoch=${at.toInstant().toEpochMilli()}")
    }

    private fun applyNowFromConfig(trigger: String, triggerRequestId: String? = null) {
        ensureScreenOn()
        dumpLockState("APPLY_NOW_START")

        if (applyInProgress) {
            Log.d("APPLY", "Skip applyNowFromConfig: apply already in progress")
            return
        }
        cancelRetry()

        setApplyContext(trigger = trigger, triggerRequestId = triggerRequestId)

        val cfg = cachedConfig
        if (cfg == null) {
            reportIdleStatus(
                resultCode = "FAIL_NO_CONFIG",
                resultadoEs = "Sin configuración",
                motivoEs = "Manual"
            )
            setUiTarget(lastTargetName, nextTargetName, refreshShiftNow())
            return
        }

        val now = ZonedDateTime.now(ZONE)
        val ov = readOverrideUi(cfg)
        setApplyContext(trigger = trigger, triggerRequestId = triggerRequestId, overrideUi = ov)

        val nextAt = computeNextBoundaryAt(cfg, now)
        scheduleAlarm(nextAt)

        val curSel = if (ov.forced) {
            Log.e("OVERRIDE", "FORCE_NEXT_TURN active requestId=${ov.requestId} uiReason=${ov.uiReason}")
            consumeForceNextTurn(ov.requestId)
            selectNextTurn(cfg, now)
        } else {
            selectAt(cfg, now)
        }

        val nextSel = selectAt(cfg, nextAt.plusSeconds(1))
        lastShiftEs = curSel.turnoEs

        val targetId = curSel.targetId
        val nextId = nextSel.targetId

        if (targetId.isNullOrBlank()) {
            lastWasForced = ov.forced
            lastForcedReason = ov.uiReason

            reportIdleStatus(
                resultCode = "FAIL_NO_TARGET",
                resultadoEs = "Sin destino",
                motivoEs = ov.uiReason ?: "Manual",
                lastTargetIdOverride = null,
                lastTargetNameOverride = null,
                nextTargetIdOverride = nextId,
                nextTargetNameOverride = null,
                nextChangeAtOverride = nextAt,
                turnoEsOverride = curSel.turnoEs,
                forced = ov.forced,
                forcedReason = ov.uiReason
            )
            setUiTarget("—", "—", curSel.turnoEs)
            return
        }

        applyInProgress = true

        resolveContactData(targetId) { label, displayName ->
            val baseName = displayName ?: targetId
            val prefix = if (ov.forced) "(Forzado) " else ""
            val curNameUi = prefix + baseName

            lastTargetId = targetId
            lastTargetName = curNameUi
            lastWasForced = ov.forced
            lastForcedReason = ov.uiReason

            if (label.isNullOrBlank()) {
                reportIdleStatus(
                    resultCode = "FAIL_NO_LABEL",
                    resultadoEs = "Sin etiqueta para este móvil",
                    motivoEs = ov.uiReason ?: "Manual",
                    lastTargetIdOverride = targetId,
                    lastTargetNameOverride = curNameUi,
                    nextTargetIdOverride = nextId,
                    nextTargetNameOverride = null,
                    nextChangeAtOverride = nextAt,
                    turnoEsOverride = curSel.turnoEs,
                    forced = ov.forced,
                    forcedReason = ov.uiReason
                )
                setUiTarget(curNameUi, nextId ?: "—", curSel.turnoEs)
                stopApplyWindow()
                return@resolveContactData
            }

            resolveDisplayName(nextId) { nxtName ->
                nextTargetId = nextId
                nextTargetName = nxtName
                nextChangeAt = nextAt

                setUiTarget(curNameUi, (nxtName ?: nextId), curSel.turnoEs)

                reportDeviceStatus(
                    status = "running",
                    resultCode = "APPLYING",
                    resultadoEs = "Aplicando...",
                    motivoEs = ov.uiReason ?: "Manual",
                    lastTargetId = targetId,
                    lastTargetName = curNameUi,
                    nextTargetId = nextId,
                    nextTargetName = nxtName,
                    nextChangeAt = nextAt,
                    turnoEs = curSel.turnoEs,
                    forced = ov.forced,
                    forcedReason = ov.uiReason
                )

                applyForwardingByContactName(label)
            }
        }
    }

    private fun resolveDisplayName(contactId: String?, cb: (String?) -> Unit) {
        if (contactId.isNullOrBlank()) { cb(null); return }
        db.collection("contacts").document(contactId).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) cb(null) else cb(snap.getString("displayName"))
            }
            .addOnFailureListener { cb(null) }
    }

    private fun resolveContactData(contactId: String, cb: (label: String?, displayName: String?) -> Unit) {
        db.collection("contacts").document(contactId).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) { cb(null, null); return@addOnSuccessListener }

                val labels = snap.get("labelsByDevice") as? Map<*, *>

                val rawLabel = labels?.get(DEVICE_ID)
                val label = (rawLabel as? String)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it != NONE_ID }

                val displayName = snap.getString("displayName")
                cb(label, displayName)
            }
            .addOnFailureListener { cb(null, null) }
    }

    private fun wakeScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Log.d("WAKE SCREEN", "Estoy intentando encender la pantalla")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
    }

    private fun handleAlarmIntent(intent: Intent) {
        val shouldApply =
            intent.getStringExtra(EXTRA_ALARM_TYPE) == ALARM_TYPE_AUTO_BOUNDARY ||
                    intent.getBooleanExtra(EXTRA_ALARM_APPLY_NOW, false)

        if (!shouldApply) return
        lastWasForced = false
        lastForcedReason = "Cambio automático de turno"
        setApplyContext(trigger = APPLY_TRIGGER_AUTO_BOUNDARY)

        wakeScreen()
        acquireWakeLock()
        bringAppToFront()
        handler.postDelayed(
            { applyNowFromConfig(trigger = APPLY_TRIGGER_AUTO_BOUNDARY) },
            600
        )
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }

        if (needed.isEmpty()) {
            Toast.makeText(this, "Permisos OK", Toast.LENGTH_SHORT).show()
            return
        }

        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
    }

    @Deprecated("Deprecated; kept for simplicity in this prototype.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, if (allGranted) "Permisos concedidos" else "Faltan permisos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyForwardingByContactName(contactName: String) {
        dumpLockState("BEFORE_DIAL")
        val raw = ContactsRepo.findPhoneByExactName(this, contactName)
        Log.e("CONTACTS_DEBUG", "contactName='$contactName' raw='$raw'")

        if (raw == null) {
            Toast.makeText(this, "No encuentro el contacto: $contactName", Toast.LENGTH_LONG).show()
            stopApplyWindow()

            reportIdleStatus(
                resultCode = "FAIL_CONTACT_NOT_FOUND",
                resultadoEs = "Contacto no encontrado",
                motivoEs = "Aplicar"
            )
            return
        }

        val canonicalMmi = buildForwardMmi(raw)
        if (canonicalMmi.isNullOrBlank()) {
            Log.e("APPLY", "FAIL: cannot build canonical MMI from raw='$raw'")
            stopApplyWindow()

            reportIdleStatus(
                resultCode = "FAIL_BAD_NUMBER",
                resultadoEs = "Número inválido",
                motivoEs = "Aplicar"
            )
            return
        }

        beginApplyWindow(canonicalMmi)

        dismissSwipeKeyguardIfNeeded { unlocked ->
            handler.post {
                dumpLockState("AFTER_DISMISS_BEFORE_DIAL")

                if (!unlocked) {
                    Log.e("APPLY", "ABORT: still locked after dismiss -> scheduleRetry")
                    stopApplyWindow()
                    scheduleRetry(
                        reasonCode = "KEYGUARD_LOCKED",
                        reasonEs = "Pantalla bloqueada",
                        detail = "No se pudo desbloquear para marcar"
                    )
                    return@post
                }

                dialForwarding(canonicalMmi, raw)
            }
        }
    }

    private fun dialForwarding(canonicalMmi: String, rawFromContact: String) {
        Log.e("APPLY", "Dialing canonical MMI='$canonicalMmi' (raw='$rawFromContact')")

        val uri = Uri.parse("tel:" + Uri.encode(canonicalMmi))
        val intent = Intent(Intent.ACTION_DIAL, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        startActivity(intent)

        reportDeviceStatus(
            status = "running",
            resultCode = "WAITING_POPUP",
            resultadoEs = "Esperando confirmación...",
            motivoEs = "Aplicar",
            lastTargetId = lastTargetId,
            lastTargetName = lastTargetName,
            nextTargetId = nextTargetId,
            nextTargetName = nextTargetName,
            nextChangeAt = nextChangeAt,
            turnoEs = refreshShiftNow(),
            forced = lastWasForced,
            forcedReason = lastForcedReason
        )
    }

    private fun beginApplyWindow(expectedCanonicalMmi: String) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val now = System.currentTimeMillis()

        applyStartMs = now
        applyInProgress = true

        applyPrefs.edit {
            putString(KEY_EXPECTED_MMI, expectedCanonicalMmi)
            putLong(KEY_ARMED_SINCE, now)
            putLong(KEY_ARMED_WINDOW_MS, OK_TIMEOUT_MS)
            putString(KEY_LAST_RESULT, "")
            putString(KEY_LAST_TEXT, "")
            putLong(KEY_LAST_TS, 0L)
        }

        Log.e("APPLY", "BEGIN_WINDOW applyInProgress=$applyInProgress armedSince=$now expectedMmi='$expectedCanonicalMmi'")

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = Runnable {
            if (!applyInProgress) return@Runnable

            // ✅ TIMEOUT: suelta lock real y ACK
            currentApplyContext?.id?.let { lockId ->
                if (lockId.isNotBlank()) releaseApplyLock(lockId, reason = "TIMEOUT")
            }
            ackCommandHandled(lastCommandRequestId ?: "", "FAILED", "TIMEOUT")

            Log.e("APPLY", "TIMEOUT reached -> bringAppToFront + scheduleRetry")
            bringAppToFront()
            scheduleRetry(
                reasonCode = "TIMEOUT_1MIN",
                reasonEs = "Timeout 1 minuto",
                detail = "No OK/FAIL en 60s"
            )
        }
        handler.postDelayed(timeoutRunnable!!, OK_TIMEOUT_MS)
    }

    private fun stopApplyWindow() {
        applyInProgress = false
        applyStartMs = 0L
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        applyPrefs.edit {
            putLong(KEY_ARMED_SINCE, 0L)
            putString(KEY_EXPECTED_MMI, "")
        }

        releaseWakeLock()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun bringAppToFront() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        startActivity(i)
    }

    private fun scheduleRetry(reasonCode: String, reasonEs: String, detail: String) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null

        // ✅ suelta lock (si existe)
        currentApplyContext?.id?.let { lockId ->
            if (lockId.isNotBlank()) releaseApplyLock(lockId, reason = "RETRY_SCHEDULED:$reasonCode")
        }

        setApplyContext(trigger = APPLY_TRIGGER_RETRY)

        reportDeviceStatus(
            status = "running",
            resultCode = "RETRYING",
            resultadoEs = "Reintentando...",
            motivoEs = reasonEs,
            lastTargetId = lastTargetId,
            lastTargetName = lastTargetName,
            nextTargetId = nextTargetId,
            nextTargetName = nextTargetName,
            nextChangeAt = nextChangeAt,
            turnoEs = refreshShiftNow(),
            forced = lastWasForced,
            forcedReason = lastForcedReason
        )

        bringAppToFront()
        wakeScreen()
        acquireWakeLock(15_000L)

        stopApplyWindow()

        retryRunnable = Runnable {
            acquireWakeLock(15_000L)
            bringAppToFront()
            handler.postDelayed({
                applyNowFromConfig(trigger = APPLY_TRIGGER_RETRY)
            }, 250)
        }
        handler.postDelayed(retryRunnable!!, RETRY_DELAY_MS)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun reportDeviceStatus(
        status: String,
        resultCode: String,
        resultadoEs: String,
        motivoEs: String?,
        lastTargetId: String?,
        lastTargetName: String?,
        nextTargetId: String?,
        nextTargetName: String?,
        nextChangeAt: ZonedDateTime?,
        turnoEs: String?,
        forced: Boolean,
        forcedReason: String?
    ) {
        val applyContext = currentApplyContext
        val derivedApplySource = applyContext?.source ?: when {
            forced -> APPLY_SOURCE_FORCED
            resultCode.startsWith("AUTO") || motivoEs == "Automático" -> APPLY_SOURCE_AUTO
            else -> APPLY_SOURCE_MANUAL
        }
        val derivedApplyTrigger = applyContext?.trigger ?: when {
            resultCode.startsWith("AUTO") -> APPLY_TRIGGER_AUTO_BOUNDARY
            else -> null
        }
        val statusReason = if (motivoEs == "Cambio de configuración" || motivoEs == "Actualización"
            || motivoEs == "Inicio" || motivoEs == "Listener"
        ) {
            motivoEs
        } else {
            buildStatusReason(derivedApplySource, motivoEs)
        }

        val payload = hashMapOf<String, Any?>(
            "status" to status,
            "resultado" to resultadoEs,
            "motivo" to statusReason,
            "statusReason" to statusReason,
            "turno" to turnoEs,
            "resultCode" to resultCode,
            "lastResult" to resultadoEs,
            "lastReason" to statusReason,
            "lastTargetId" to lastTargetId,
            "lastTargetName" to lastTargetName,
            "nextTargetId" to nextTargetId,
            "nextTargetName" to nextTargetName,
            "nextChangeAt" to (nextChangeAt?.toInstant()?.toEpochMilli()),
            "lastAt" to FieldValue.serverTimestamp(),
            "forced" to forced,
            "forcedReason" to forcedReason,
            "applySource" to derivedApplySource,
            "applyTrigger" to derivedApplyTrigger,
            "applyId" to applyContext?.id
        )

        val fp = payload.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
        if (fp == lastStatusFingerprint) return
        lastStatusFingerprint = fp

        db.collection("devices").document(DEVICE_ID).set(payload, SetOptions.merge())
    }

    private fun reportIdleStatus(
        resultCode: String,
        resultadoEs: String,
        motivoEs: String?,
        lastTargetIdOverride: String? = lastTargetId,
        lastTargetNameOverride: String? = lastTargetName,
        nextTargetIdOverride: String? = nextTargetId,
        nextTargetNameOverride: String? = nextTargetName,
        nextChangeAtOverride: ZonedDateTime? = nextChangeAt,
        turnoEsOverride: String? = refreshShiftNow(),
        forced: Boolean = lastWasForced,
        forcedReason: String? = lastForcedReason
    ) {
        reportDeviceStatus(
            status = "idle",
            resultCode = resultCode,
            resultadoEs = resultadoEs,
            motivoEs = motivoEs,
            lastTargetId = lastTargetIdOverride,
            lastTargetName = lastTargetNameOverride,
            nextTargetId = nextTargetIdOverride,
            nextTargetName = nextTargetNameOverride,
            nextChangeAt = nextChangeAtOverride,
            turnoEs = turnoEsOverride,
            forced = forced,
            forcedReason = forcedReason
        )
    }

    private fun setApplyContext(
        trigger: String,
        triggerRequestId: String? = null,
        overrideUi: OverrideUi? = null
    ) {
        val existing = currentApplyContext

        val baseId = when {
            // ✅ REMOTE_CMD: si te pasan lockId, aquí se queda como id (para releaseApplyLock)
            trigger == APPLY_TRIGGER_REMOTE_CMD && !triggerRequestId.isNullOrBlank() -> triggerRequestId
            trigger == APPLY_TRIGGER_AUTO_BOUNDARY -> newApplyId("auto")
            trigger == APPLY_TRIGGER_RETRY -> existing?.id ?: newApplyId("retry")
            else -> existing?.id ?: newApplyId("apply")
        }

        val source = when {
            overrideUi?.forced == true -> APPLY_SOURCE_FORCED
            trigger == APPLY_TRIGGER_AUTO_BOUNDARY -> APPLY_SOURCE_AUTO
            trigger == APPLY_TRIGGER_RETRY -> existing?.source ?: APPLY_SOURCE_MANUAL
            else -> APPLY_SOURCE_MANUAL
        }

        val resolvedId = when {
            overrideUi?.forced == true && !overrideUi.requestId.isNullOrBlank() -> overrideUi.requestId
            else -> baseId
        }

        currentApplyContext = ApplyContext(source, trigger, resolvedId)
    }

    private fun newApplyId(prefix: String): String {
        return "$prefix-${System.currentTimeMillis()}"
    }

    private fun buildStatusReason(applySource: String?, fallback: String?): String {
        return when (applySource) {
            APPLY_SOURCE_AUTO -> {
                val time = ZonedDateTime.now(ZONE).toLocalTime().format(autoTimeFormatter)
                "Cambio automático por turno ($time)"
            }
            APPLY_SOURCE_MANUAL -> "Aplicado manualmente desde la web"
            APPLY_SOURCE_FORCED -> "Forzado manual (ignora horario)"
            else -> fallback ?: "Aplicar"
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleForwardingInSeconds(seconds: Int) {
        val triggerAt = System.currentTimeMillis() + seconds * 1000L
        val intent = buildAutoBoundaryIntent()

        val pending = PendingIntent.getBroadcast(
            this,
            AUTO_ALARM_REQ_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pending)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)

        Toast.makeText(this, "AUTO_BOUNDARY en ${seconds}s", Toast.LENGTH_SHORT).show()
        Log.d("ALARM", "TEST AUTO_BOUNDARY scheduled in ${seconds}s at=$triggerAt")
    }

    private var wl: PowerManager.WakeLock? = null

    private fun acquireWakeLock(ms: Long = 12_000L) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl?.let { if (it.isHeld) it.release() }
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "desviosturnos:apply").apply { acquire(ms) }
    }

    private fun ensureScreenOn() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            wakeScreen()
            bringAppToFront()
        }
    }

    private fun releaseWakeLock() {
        wl?.let { if (it.isHeld) it.release() }
        wl = null
    }

    private fun dumpLockState(tag: String) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        Log.e(
            "LOCK_STATE",
            "$tag interactive=${pm.isInteractive} keyguardLocked=${km.isKeyguardLocked} keyguardSecure=${km.isKeyguardSecure}"
        )
    }

    private fun dismissSwipeKeyguardIfNeeded(onDone: (unlocked: Boolean) -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        fun checkUnlocked(): Boolean = !km.isKeyguardLocked

        if (!km.isKeyguardLocked || km.isKeyguardSecure) {
            onDone(true)
            return
        }

        Log.e("KEYGUARD", "Requesting dismiss (swipe lock)")

        var finished = false
        val handler = Handler(Looper.getMainLooper())

        fun finish(tag: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            val unlocked = checkUnlocked()
            Log.e("KEYGUARD", "Finish($tag) unlocked=$unlocked keyguardLocked=${km.isKeyguardLocked}")
            onDone(unlocked)
        }

        handler.postDelayed({ finish("timeout") }, 2500L)

        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { Log.e("KEYGUARD", "Dismiss succeeded"); finish("succeeded") }
            override fun onDismissCancelled() { Log.e("KEYGUARD", "Dismiss cancelled"); finish("cancelled") }
            override fun onDismissError() { Log.e("KEYGUARD", "Dismiss error"); finish("error") }
        })
    }

    private fun isWeekendN2Window(at: ZonedDateTime): Boolean {
        val dow = at.dayOfWeek
        val t = at.toLocalTime()

        return when (dow) {
            DayOfWeek.FRIDAY -> t >= LocalTime.of(23, 0)
            DayOfWeek.SATURDAY -> true
            DayOfWeek.SUNDAY -> t < LocalTime.of(23, 0)
            else -> false
        }
    }

    private data class OverrideUi(
        val forced: Boolean,
        val requestId: String?,
        val uiTag: String?,
        val uiReason: String?
    )

    private fun readOverrideUi(cfg: Map<String, Any?>): OverrideUi {
        val o = cfg["override"] as? Map<*, *> ?: return OverrideUi(false, null, null, null)

        val forced = (o["forceNextTurn"] as? Boolean) ?: false
        val rid = (o["forceRequestId"] as? String)?.trim()

        val tag = (o["uiTag"] as? String)?.trim()
        val reason = (o["uiReason"] as? String)?.trim()

        return OverrideUi(forced, rid, tag, reason)
    }

    private fun consumeForceNextTurn(requestId: String?) {
        if (requestId.isNullOrBlank()) return

        db.collection("config").document(DEVICE_ID)
            .set(
                mapOf(
                    "override" to mapOf(
                        "forceNextTurn" to false,
                        "forceRequestId" to requestId,
                        "consumedAt" to FieldValue.serverTimestamp()
                    )
                ),
                SetOptions.merge()
            )
    }

    private fun buildForwardMmi(rawFromContact: String): String? {
        val raw = rawFromContact.trim()
        val mmiMatch = Regex("""\*{1,2}21\*([^#]+)#?""").find(raw)
        val extracted = mmiMatch?.groupValues?.getOrNull(1)?.trim()
        val candidate = (extracted ?: raw).trim()
        val cleaned = candidate.replace(Regex("""[^0-9+]"""), "")

        if (cleaned.isBlank()) return null
        if (cleaned.length < 6) return null
        if (cleaned == "900442290") {
            Log.e("APPLY", "⛔ BLOCKED destination 900442290 (from raw='$rawFromContact')")
            return null
        }
        return "*21*$cleaned#"
    }

    private fun buildAutoBoundaryIntent() = Intent(this, CallAlarmReceiver::class.java).apply {
        putExtra(EXTRA_ALARM_TYPE, ALARM_TYPE_AUTO_BOUNDARY)
        putExtra(EXTRA_ALARM_APPLY_NOW, true)
    }

    private fun selectNextTurn(cfg: Map<String, Any?>, now: ZonedDateTime): Selection {
        val nextAt = computeNextBoundaryAt(cfg, now)
        return selectAt(cfg, nextAt.plusSeconds(1))
    }
}