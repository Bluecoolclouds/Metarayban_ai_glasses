package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranslationEntry(
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
)

class TranslatorSkill : Skill {
    companion object {
        private const val TAG = "TranslatorSkill"
        private const val MAX_HISTORY = 50

        private val _history = MutableStateFlow<List<TranslationEntry>>(emptyList())
        val history: StateFlow<List<TranslationEntry>> = _history.asStateFlow()

        private val _liveText = MutableStateFlow("")
        val liveText: StateFlow<String> = _liveText.asStateFlow()

        fun appendLive(text: String) {
            _liveText.value = _liveText.value + text
        }

        fun commitCurrent() {
            val text = _liveText.value.trim()
            if (text.isEmpty()) return
            _liveText.value = ""
            val entry = TranslationEntry(text)
            val updated = (_history.value + entry).takeLast(MAX_HISTORY)
            _history.value = updated
        }

        fun clearAll() {
            _liveText.value = ""
            _history.value = emptyList()
        }
    }

    override val id = "translator"
    override val name = "Translator"

    override val activationPhrases = listOf(
        "переводчик", "переводи", "translate", "translator",
        "включи переводчик", "start translating", "translation mode",
    )

    override val deactivationPhrases = listOf(
        "выключи переводчик", "стоп переводчик", "stop translating",
        "stop translator", "выключи перевод", "хватит переводить",
    )

    override val systemPromptBlock = """
--------------------------------------------------
ACTIVE SKILL: TRANSLATOR
--------------------------------------------------

You are now in TRANSLATOR mode.
Translation is handled directly by you — NEVER call execute for translation.

Rules:
- Output ONLY the translated text.
- No prefixes, no explanations, no commentary, no execute calls.
- If target language is specified — translate to that language.
- If two languages are specified — translate between them automatically.
- Otherwise translate foreign speech into the user's language.
- You may remember active translation languages during this session.

Exit when user says: "выключи переводчик", "стоп", "stop translating".
Say once: "Переводчик выключен." and return to normal mode.
""".trimIndent()

    override fun onActivated(params: String) {
        Log.d(TAG, "Translator skill activated, params: $params")
        clearAll()
    }

    override fun onDeactivated() {
        Log.d(TAG, "Translator skill deactivated")
        commitCurrent()
    }
}
