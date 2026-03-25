package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.util.Log

class VisionDescriptionSkill : Skill {
    companion object {
        private const val TAG = "VisionDescSkill"
        private const val DEFAULT_INTERVAL_SEC = 10
    }

    override val id = "vision_description"
    override val name = "Vision Description"
    override val needsCamera = true

    private var _intervalMs: Long = DEFAULT_INTERVAL_SEC * 1000L

    override val intervalMs: Long get() = _intervalMs

    override val activationPhrases = listOf(
        "включи скилл что видишь", "включи описание", "включи режим описания",
        "запусти описание", "начни описывать всё", "start vision skill",
        "enable vision description", "start describing everything",
    )

    override val deactivationPhrases = listOf(
        "хватит описывать", "стоп описание", "останови описание",
        "stop describing", "хватит", "enough",
    )

    override val systemPromptBlock = """
--------------------------------------------------
ACTIVE SKILL: VISION DESCRIPTION (periodic, accessibility)
--------------------------------------------------

You are acting as eyes for a visually impaired person wearing smart glasses.
Every few seconds you will receive "[VISION_TICK]" — the scene has changed and needs describing.

When you receive a "[VISION_TICK]":

PRIORITY 1 — IMMEDIATE HAZARDS (say these FIRST, urgently):
- Wall, door, or solid surface close ahead → say "Осторожно, стена примерно в [X] метре/шагах"
- Steps or drop → say "Осторожно, ступени"
- Platform edge, cliff, rooftop drop, or any fall hazard → say "СТОП! Обрыв впереди" — this is the highest urgency warning
- Metro/subway platform edge → say "Осторожно, край платформы"
- Person walking into your path → say "Человек справа/слева"
- Low ceiling, pole, or protruding object → warn immediately
- Traffic light RED or YELLOW → say "Стоп, красный свет" / "Stop, red light"
- Traffic light GREEN → say "Зелёный, можно идти" / "Green light, go"
- Oncoming vehicle or bike approaching → warn urgently

Estimate distance from visual cues: objects filling most of the frame ≈ 0.5–1 m, half the frame ≈ 1–2 m, small portion ≈ 2–4 m.

PRIORITY 2 — GENERAL SCENE:
- Describe what is directly in front in 1-2 short, natural sentences.
- Mention people, objects, text/signs, exits, open space.
- Read aloud any visible text (signs, labels, screens) if present.

RULES:
- Speak in the same language the user speaks (Russian or English).
- Speak calmly but urgently for hazards, calmly for general descriptions.
- Do NOT say "I see an image" or "the camera shows" — describe directly.
- Do NOT say "nothing changed", "same scene", or "no change".

Stop when user says: "хватит", "стоп описание", "stop describing".
""".trimIndent()

    override fun extractParams(text: String): String {
        val regex = Regex("""(\d+)""")
        val match = regex.find(text)
        return match?.value ?: ""
    }

    override fun onActivated(params: String) {
        val seconds = params.toIntOrNull() ?: DEFAULT_INTERVAL_SEC
        _intervalMs = (seconds.coerceIn(3, 120)) * 1000L
        Log.d(TAG, "Vision description activated: every ${_intervalMs / 1000}s")
    }

    override fun onDeactivated() {
        _intervalMs = DEFAULT_INTERVAL_SEC * 1000L
        Log.d(TAG, "Vision description deactivated")
    }

    override fun onTick(injectMessage: (String) -> Unit) {
        injectMessage("[VISION_TICK] Describe what you see in 1-2 sentences.")
    }
}
