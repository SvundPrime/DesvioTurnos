package com.example.desviosturnos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class ShiftSelectionEngineTest {


    private fun baseConfig(
        mode: String? = "NORMAL",
        officeMode: Boolean? = null,
        forceHoliday: Boolean? = null,
        n1Active: Boolean? = null,
        morning: String? = "M1",
        afternoon: String? = "A1",
        night: String? = "N1",
        interLJ: String? = "I1",
        interFri: String? = "IF1",
        n2: String? = "N2"
    ): Map<String, Any?> {
        val shifts = mutableMapOf<String, Any?>(
            "morning" to mapOf("contactId" to (morning ?: NONE_ID)),
            "afternoon" to mapOf("contactId" to (afternoon ?: NONE_ID)),
            "night" to mapOf("contactId" to (night ?: NONE_ID)),
            "intershiftLJ" to mapOf("contactId" to (interLJ ?: NONE_ID)),
            "intershiftFri" to mapOf("contactId" to (interFri ?: NONE_ID))
        )

        return buildMap {
            if (mode != null) put("mode", mode)
            if (officeMode != null) put("officeMode", officeMode)
            if (forceHoliday != null) put("forceHoliday", forceHoliday)
            if (n1Active != null) put("n1Active", n1Active)
            put("shifts", shifts)
            if (n2 != null) put("n2GuardId", n2)
        }
    }

    private fun at(dateTime: String): ZonedDateTime = ZonedDateTime.parse("${dateTime}+01:00[Europe/Madrid]")

    @Test
    fun thursday0020_usesNightAssignee() {
        val cfg = baseConfig()
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-09T00:20:00")) // Thursday
        assertEquals("N1", d.targetId)
        assertEquals("Noche", d.turnoEs)
        assertEquals("NOCHE", d.reason)
    }

    @Test
    fun thursday0020_withoutNight_fallsBackToN2() {
        val cfg = baseConfig(night = null)
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-09T00:20:00"))
        assertEquals("N2", d.targetId)
        assertEquals("Guardia", d.turnoEs)
        assertEquals("NOCHE_FALLBACK_N2", d.reason)
    }

    @Test
    fun n1Inactive_doesNotForceFestivo_anymore() {
        val cfg = baseConfig(mode = null, n1Active = false, forceHoliday = false, officeMode = false)
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-09T00:20:00"))
        assertEquals("N1", d.targetId)
        assertEquals("Noche", d.turnoEs)
        assertEquals("NOCHE", d.reason)
    }

    @Test
    fun forceHoliday_forcesN2() {
        val cfg = baseConfig(mode = null, forceHoliday = true)
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-09T00:20:00"))
        assertEquals("N2", d.targetId)
        assertEquals("MODE_FESTIVO", d.reason)
    }

    @Test
    fun sunday2259_isWeekendN2Window() {
        val cfg = baseConfig()
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-12T22:59:00")) // Sunday
        assertEquals("N2", d.targetId)
        assertEquals("WEEKEND_N2_WINDOW", d.reason)
    }

    @Test
    fun sunday2300_entersNightWindow() {
        val cfg = baseConfig()
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-12T23:00:00")) // Sunday
        assertEquals("N1", d.targetId)
        assertEquals("NOCHE", d.reason)
    }

    @Test
    fun weekday_boundaries_are_respected() {
        val cfg = baseConfig()
        val morning = ShiftSelectionEngine.selectAt(cfg, at("2025-01-06T07:00:00")) // Monday
        val afternoon = ShiftSelectionEngine.selectAt(cfg, at("2025-01-06T15:00:00"))
        val night = ShiftSelectionEngine.selectAt(cfg, at("2025-01-06T23:00:00"))

        assertEquals("M1", morning.targetId)
        assertEquals("MANANA", morning.reason)

        assertEquals("A1", afternoon.targetId)
        assertEquals("TARDE", afternoon.reason)

        assertEquals("N1", night.targetId)
        assertEquals("NOCHE", night.reason)
    }

    @Test
    fun officeMode_usesIntershiftInsideWindow_andN2Outside() {
        val cfg = baseConfig(mode = "OFICINA")
        val inWindow = ShiftSelectionEngine.selectAt(cfg, at("2025-01-08T09:00:00")) // Wednesday
        val outWindow = ShiftSelectionEngine.selectAt(cfg, at("2025-01-08T19:00:00"))

        assertEquals("I1", inWindow.targetId)
        assertEquals("OFICINA", inWindow.reason)

        assertEquals("N2", outWindow.targetId)
        assertEquals("OFICINA", outWindow.reason)
    }

    @Test
    fun noneId_is_treated_as_missing_assignment() {
        val cfg = baseConfig(night = NONE_ID)
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-09T00:20:00"))
        assertEquals("N2", d.targetId)
        assertEquals("NOCHE_FALLBACK_N2", d.reason)
    }
    @Test
    fun friday0020_is_not_night_window_and_falls_default_n2() {
        val cfg = baseConfig()
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-10T00:20:00")) // Friday
        assertEquals("N2", d.targetId)
        assertEquals("DEFAULT_N2", d.reason)
    }

    @Test
    fun saturday_always_weekend_n2_window() {
        val cfg = baseConfig()
        val d = ShiftSelectionEngine.selectAt(cfg, at("2025-01-11T12:00:00")) // Saturday
        assertEquals("N2", d.targetId)
        assertEquals("WEEKEND_N2_WINDOW", d.reason)
    }

}
