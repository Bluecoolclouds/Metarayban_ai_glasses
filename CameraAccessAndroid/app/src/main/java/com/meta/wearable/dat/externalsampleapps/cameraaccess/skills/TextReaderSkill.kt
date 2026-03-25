package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.util.Log

class TextReaderSkill : Skill {
    companion object {
        private const val TAG = "TextReaderSkill"
    }

    override val id = "text_reader"
    override val name = "Text Reader"
    override val needsCamera = true
    override val intervalMs: Long = 6000

    override val activationPhrases = listOf(
        "читай что видишь", "читай текст", "читай всё что видишь",
        "read text", "read what you see", "читатель текста",
        "что тут написано", "прочитай",
    )

    override val deactivationPhrases = listOf(
        "хватит читать", "стоп чтение", "stop reading",
        "перестань читать", "хватит",
    )

    override val systemPromptBlock = """
--------------------------------------------------
ACTIVE SKILL: TEXT READER (periodic)
--------------------------------------------------

You are in TEXT READER mode.
Every few seconds you will receive "[TEXT_TICK] Read any text you see".
When you get this prompt:
- Look at the camera frame carefully.
- If you see ANY text (signs, menus, labels, screens, documents, books) — read it aloud EXACTLY as written.
- If text is in a foreign language, still read it as-is first.
- If no text visible, say "текста не вижу" and wait.
- Do NOT describe the scene — ONLY read text.
- Keep it concise: read the most prominent/important text first.

Stop when user says: "хватит читать", "стоп чтение", "stop reading".
""".trimIndent()

    override fun onActivated(params: String) {
        Log.d(TAG, "Text reader activated")
    }

    override fun onDeactivated() {
        Log.d(TAG, "Text reader deactivated")
    }

    override fun onTick(injectMessage: (String) -> Unit) {
        injectMessage("[TEXT_TICK] Read any text you see in the camera frame. Read it exactly as written.")
    }
}
