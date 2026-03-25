package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.util.Log

class TranslateSignSkill : Skill {
    companion object {
        private const val TAG = "TranslateSignSkill"
    }

    override val id = "translate_sign"
    override val name = "Sign Translator"
    override val needsCamera = true
    override val intervalMs: Long = 8000

    override val activationPhrases = listOf(
        "переведи надпись", "переведи вывеску", "translate sign",
        "translate what you see", "переведи что написано",
        "переводи надписи", "переводи вывески",
        "переведи текст на камере",
    )

    override val deactivationPhrases = listOf(
        "хватит переводить", "стоп перевод", "stop translating signs",
        "хватит", "перестань переводить",
    )

    override val systemPromptBlock = """
--------------------------------------------------
ACTIVE SKILL: SIGN/TEXT TRANSLATOR (periodic)
--------------------------------------------------

You are in SIGN TRANSLATOR mode.
Every few seconds you will receive "[SIGN_TICK] Read and translate visible text".
When you get this prompt:
- Look at the camera frame for any text (signs, menus, labels, screens, documents).
- Read the text as-is first, then translate it.
- If user specified a target language, translate to that language.
- If no target language specified, translate to Russian.
- Format: "[original text] — [translation]"
- If no text visible, stay silent (don't say anything).
- Focus on new/changed text — don't repeat the same translation.

You can also summarize long text if user says "кратко перескажи" or "summarize".

Stop when user says: "хватит переводить", "стоп перевод", "stop translating signs".
""".trimIndent()

    override fun onActivated(params: String) {
        Log.d(TAG, "Sign translator activated, params: $params")
    }

    override fun onDeactivated() {
        Log.d(TAG, "Sign translator deactivated")
    }

    override fun onTick(injectMessage: (String) -> Unit) {
        injectMessage("[SIGN_TICK] Read and translate any visible text in the camera frame. Say the original text first, then the translation.")
    }
}
