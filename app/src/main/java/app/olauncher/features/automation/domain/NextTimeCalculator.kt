package app.olauncher.features.automation.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

data class NextFire(val at: Instant, val ruleIds: Set<String>, val precise: Boolean)

/**
 * Computes the single earliest upcoming fire across all enabled time rules, so exactly one alarm is
 * ever scheduled. DST-safe: a local time inside a spring-forward gap fires at the shifted instant,
 * and an ambiguous fall-back time fires once (the earlier offset).
 */
object NextTimeCalculator {

    fun nextFire(rules: List<AutomationRule>, now: Instant, deviceZone: ZoneId): NextFire? {
        var best: Instant? = null
        val due = mutableSetOf<String>()
        var precise = false
        for (rule in rules) {
            if (!rule.isEnabled) continue
            val trigger = rule.trigger as? TriggerSpec.Time ?: continue
            val zone = trigger.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone
            val at = nextFor(trigger, now, zone) ?: continue
            when {
                best == null || at < best -> {
                    best = at; due.clear(); due += rule.id; precise = trigger.precise
                }
                at == best -> {
                    due += rule.id; precise = precise || trigger.precise
                }
            }
        }
        return best?.let { NextFire(it, due, precise) }
    }

    /** First instant strictly after [now] matching the trigger's days and local time, or null. */
    fun nextFor(trigger: TriggerSpec.Time, now: Instant, zone: ZoneId): Instant? {
        if (trigger.days.isEmpty()) return null
        val start = now.atZone(zone).toLocalDate()
        for (i in 0..8) {
            val date = start.plusDays(i.toLong())
            if (date.dayOfWeek !in trigger.days) continue
            val candidate = ZonedDateTime.of(date, trigger.localTime, zone).toInstant()
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }
}
