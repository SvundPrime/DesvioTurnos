package com.example.desviosturnos

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

object ShiftSelectionEngine {

    enum class OperationMode { NORMAL, OFICINA, FESTIVO }

    data class Decision(
        val targetId: String?,
        val turnoEs: String,
        val reason: String,
        val mode: OperationMode,
        val nightId: String?,
        val n2Id: String?
    )

    private val MON_THU = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY
    )

    private val NIGHT_DJ = setOf(
        DayOfWeek.SUNDAY,
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY
    )

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

    private fun effectiveNightDay(at: ZonedDateTime): DayOfWeek {
        val t = at.toLocalTime()
        return if (t < LocalTime.of(7, 0)) at.minusDays(1).dayOfWeek else at.dayOfWeek
    }

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

    private fun shiftLabelFromChosen(
        chosenId: String?,
        morningId: String?,
        afterId: String?,
        nightId: String?,
        interId: String?,
        n2: String?
    ): String {
        return when {
            chosenId.isNullOrBlank() -> "Guardia"
            chosenId == morningId -> "Mañana"
            chosenId == afterId -> "Tarde"
            chosenId == nightId -> "Noche"
            chosenId == interId -> "Entreturno"
            chosenId == n2 -> "Guardia"
            else -> "Guardia"
        }
    }

    fun selectAt(cfg: Map<String, Any?>, at: ZonedDateTime): Decision {
        val mode = readMode(cfg)
        val n2 = n2Id(cfg)

        val morning = shiftContactId(cfg, "morning")
        val after = shiftContactId(cfg, "afternoon")
        val night = shiftContactId(cfg, "night")

        val dow = at.dayOfWeek
        val t = at.toLocalTime()
        val interId = interIdForDay(cfg, dow)
        val isLV = isWeekday(dow)

        if (mode == OperationMode.FESTIVO) {
            return Decision(n2, "Guardia", "MODE_FESTIVO", mode, night, n2)
        }

        if (isWeekendN2Window(at)) {
            return Decision(n2, "Guardia", "WEEKEND_N2_WINDOW", mode, night, n2)
        }

        val effectiveNightDow = effectiveNightDay(at)
        if (isNightDJ(effectiveNightDow) && inRange(t, "23:00", "07:00")) {
            val chosen = night ?: n2
            return Decision(
                chosen,
                shiftLabelFromChosen(chosen, morning, after, night, interId, n2),
                if (night != null) "NOCHE" else "NOCHE_FALLBACK_N2",
                mode,
                night,
                n2
            )
        }

        if (mode == OperationMode.OFICINA) {
            val chosen = if (interId != null && inInterWindow(at)) interId else n2
            return Decision(
                chosen,
                shiftLabelFromChosen(chosen, morning, after, night, interId, n2),
                "OFICINA",
                mode,
                night,
                n2
            )
        }

        if (!isLV) {
            return Decision(n2, "Guardia", "NORMAL_NO_LV_N2", mode, night, n2)
        }

        if (inRange(t, "07:00", "15:00")) {
            if (morning != null) {
                return Decision(morning, "Mañana", "MANANA", mode, night, n2)
            }
            val chosen = if (interId != null && inInterWindow(at)) interId else n2
            return Decision(
                chosen,
                shiftLabelFromChosen(chosen, morning, after, night, interId, n2),
                if (interId != null) "MANANA_FALLBACK_INTER" else "MANANA_FALLBACK_N2",
                mode,
                night,
                n2
            )
        }

        if (inRange(t, "15:00", "23:00")) {
            if (after != null) {
                return Decision(after, "Tarde", "TARDE", mode, night, n2)
            }
            val chosen = if (interId != null && inInterWindow(at)) interId else n2
            return Decision(
                chosen,
                shiftLabelFromChosen(chosen, morning, after, night, interId, n2),
                if (interId != null) "TARDE_FALLBACK_INTER" else "TARDE_FALLBACK_N2",
                mode,
                night,
                n2
            )
        }

        return Decision(n2, "Guardia", "DEFAULT_N2", mode, night, n2)
    }
}
