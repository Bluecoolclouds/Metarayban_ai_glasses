package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

interface Skill {
    val id: String
    val name: String
    val activationPhrases: List<String>
    val deactivationPhrases: List<String>
    val systemPromptBlock: String
    val intervalMs: Long get() = 0
    val needsCamera: Boolean get() = false

    /**
     * How long (ms) to send video frames to Gemini after activation or after the user speaks.
     * Long.MAX_VALUE = unlimited (continuous). Use a small value for on-demand skills like calorie counting
     * that only need a snapshot and don't have a periodic tick.
     */
    val videoWindowMs: Long get() = Long.MAX_VALUE

    fun onActivated(params: String = "") {}
    fun onDeactivated() {}
    fun onTick(injectMessage: (String) -> Unit) {}
    fun onAiOutput(text: String) {}
    fun matchActivation(text: String): Boolean {
        val lower = text.lowercase().trim()
        return activationPhrases.any { lower.contains(it) }
    }
    fun extractParams(text: String): String = ""
}
