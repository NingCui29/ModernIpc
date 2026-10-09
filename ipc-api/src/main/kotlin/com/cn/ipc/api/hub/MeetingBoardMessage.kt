package com.cn.ipc.api.hub

import org.json.JSONObject

/** Business payload for the four-APK meeting-board example; the Binder contract is unchanged. */
data class MeetingBoardMessage(
    val kind: String,
    val commandId: String,
    val title: String,
    val room: String
) {
    fun encode(): String {
        require(isValid()) { "Invalid meeting-board payload" }
        return JSONObject().put("case", CASE).put("v", VERSION)
            .put("kind", kind).put("commandId", commandId)
            .put("title", title).put("room", room).toString()
    }

    private fun isValid(): Boolean = kind in KINDS && ID.matches(commandId) &&
        title.isNotBlank() && title.length <= 120 && room.isNotBlank() && room.length <= 120

    companion object {
        const val CASE = "meeting-board"
        const val VERSION = 1
        const val SHOW = "meeting.show"
        const val DISPLAYED = "meeting.displayed"
        const val NOTICE = "meeting.notice"
        const val AUDIT = "meeting.audit"
        private val KINDS = setOf(SHOW, DISPLAYED, NOTICE, AUDIT)
        private val ID = Regex("[A-Za-z0-9_-]{1,96}")

        fun decode(raw: String): MeetingBoardMessage? = try {
            // Reject before allocating a JSONObject for unrelated or oversized chat messages.
            if (raw.length > 4096 || !raw.startsWith("{")) null else {
                val json = JSONObject(raw)
                if (json.opt("case") != CASE || json.opt("v") != VERSION) null else {
                    val kind = json.opt("kind") as? String
                    val id = json.opt("commandId") as? String
                    val title = json.opt("title") as? String
                    val room = json.opt("room") as? String
                    if (kind == null || id == null || title == null || room == null) null
                    else MeetingBoardMessage(kind, id, title, room).takeIf { it.isValid() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
