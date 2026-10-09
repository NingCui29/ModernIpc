package com.cn.ipc.api.hub

import org.json.JSONObject

/** Business data for the additional four-APK examples. Does not change the Binder contract. */
data class MultiAppScenarioMessage(
    val scenario: String,
    val kind: String,
    val commandId: String,
    val title: String,
    val detail: String,
    val revision: Long,
    val value: Int
) {
    fun encode(): String {
        require(isValid()) { "Invalid multi-App scenario payload" }
        return JSONObject().put("case", CASE).put("v", VERSION)
            .put("scenario", scenario).put("kind", kind).put("commandId", commandId)
            .put("title", title).put("detail", detail).put("revision", revision)
            .put("value", value).toString()
    }

    private fun isValid(): Boolean {
        if (!ID.matches(commandId) || title.isBlank() || title.length > 120 ||
            detail.isBlank() || detail.length > 120 || revision <= 0) return false
        return when (scenario) {
            WORK_ORDER -> kind in setOf(ORDER_ASSIGN, ORDER_ACCEPTED, ORDER_COMPLETED) && value == 0
            SETTINGS -> kind in setOf(SETTINGS_APPLY, SETTINGS_APPLIED) &&
                detail in setOf("dark", "light") && value in setOf(14, 18)
            TELEMETRY -> when (kind) {
                TELEMETRY_SAMPLE -> value in -40..125
                TELEMETRY_ALERT -> value in ALERT_THRESHOLD..125
                else -> false
            }
            else -> false
        }
    }

    companion object {
        const val CASE = "multi-app-scenarios"
        const val VERSION = 1
        const val WORK_ORDER = "work-order"
        const val SETTINGS = "settings"
        const val TELEMETRY = "telemetry"
        const val ORDER_ASSIGN = "order.assign"
        const val ORDER_ACCEPTED = "order.accepted"
        const val ORDER_COMPLETED = "order.completed"
        const val SETTINGS_APPLY = "settings.apply"
        const val SETTINGS_APPLIED = "settings.applied"
        const val TELEMETRY_SAMPLE = "telemetry.sample"
        const val TELEMETRY_ALERT = "telemetry.alert"
        const val ALERT_THRESHOLD = 30
        private val ID = Regex("[A-Za-z0-9_-]{1,96}")

        fun decode(raw: String): MultiAppScenarioMessage? = try {
            if (raw.length > 4096 || !raw.startsWith("{")) null else {
                val json = JSONObject(raw)
                val revision = json.opt("revision")
                val value = json.opt("value")
                if (json.opt("case") != CASE || json.opt("v") != VERSION ||
                    (revision !is Int && revision !is Long) || (value !is Int && value !is Long) ||
                    (value as Number).toLong() !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) null else {
                    val scenario = json.opt("scenario") as? String
                    val kind = json.opt("kind") as? String
                    val id = json.opt("commandId") as? String
                    val title = json.opt("title") as? String
                    val detail = json.opt("detail") as? String
                    if (scenario == null || kind == null || id == null || title == null || detail == null) null
                    else MultiAppScenarioMessage(scenario, kind, id, title, detail,
                        (revision as Number).toLong(), (value as Number).toInt()).takeIf { it.isValid() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
