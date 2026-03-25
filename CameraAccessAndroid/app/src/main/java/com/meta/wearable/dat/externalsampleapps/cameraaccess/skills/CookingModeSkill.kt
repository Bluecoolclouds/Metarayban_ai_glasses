package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.util.Log

class CookingModeSkill : Skill {
    companion object {
        private const val TAG = "CookingModeSkill"
        private const val TICK_INTERVAL_MS = 15000L
    }

    enum class CookingState {
        CHOOSING_RECIPE,
        COOKING,
        DONE
    }

    private var state = CookingState.CHOOSING_RECIPE
    private var dishName = ""
    private var currentStep = 0

    private var timerStartMs: Long = 0
    private var timerTargetMs: Long = 0
    private var timerLabel: String = ""
    private var timerAnnouncedHalf = false
    private var timerAnnouncedMinute = false
    var stateChanged = false
        private set

    override val id = "cooking_mode"
    override val name = "Cooking Mode"
    override val needsCamera = true
    override val intervalMs: Long = TICK_INTERVAL_MS

    override val activationPhrases = listOf(
        "давай приготовим", "давай готовить", "let's cook",
        "режим готовки", "cooking mode", "приготовь",
        "рецепт", "помоги приготовить",
    )

    override val deactivationPhrases = listOf(
        "закончили готовить", "выйди из готовки", "stop cooking",
        "exit cooking", "хватит готовить", "готовка окончена",
    )

    override val systemPromptBlock: String
        get() = buildPrompt()

    private fun buildPrompt(): String {
        val stateBlock = when (state) {
            CookingState.CHOOSING_RECIPE -> """
CURRENT STATE: CHOOSING_RECIPE
${if (dishName.isNotBlank()) "User wants to cook: $dishName" else "User hasn't specified a dish yet."}

Your job:
- If user specified a dish, propose a complete recipe with numbered steps.
- If user said something vague like "что-нибудь из того что есть" — ask what ingredients they have (look at the camera if possible), then suggest 2-3 options.
- After the recipe is agreed upon, say "Отлично, начинаем! Шаг 1:" and read the first step.
- After reading step 1, emit: <cooking_state>COOKING:1</cooking_state>
"""
            CookingState.COOKING -> """
CURRENT STATE: COOKING (step $currentStep)

Your job:
- You are on step $currentStep of the recipe.
- Wait for user to say "готово", "дальше", "next", "done" to advance.
- When advancing, say "Шаг [N+1]:" and read the next instruction clearly and concisely.
- After reading the new step, emit: <cooking_state>COOKING:[new step number]</cooking_state>
- If user says "повтори" / "repeat" — re-read the current step.
- If user says "назад" / "back" — go to previous step and emit state tag.
- If user asks a question mid-step, answer it, then remind them of the current step.
- If a step requires waiting (e.g., "варить 15 минут"), emit: <cooking_timer>MINUTES:LABEL</cooking_timer> (e.g., <cooking_timer>15:варить бульон</cooking_timer>)
- You can glance at the camera to check progress (is food in the pan? is the oven on?), but keep comments brief.
- When all steps are done, congratulate the user and emit: <cooking_state>DONE</cooking_state>
"""
            CookingState.DONE -> """
CURRENT STATE: DONE

The recipe is complete! Congratulate the user.
Ask if they want to cook something else or exit cooking mode.
If they want another recipe, emit: <cooking_state>CHOOSING_RECIPE</cooking_state>
If they want to exit, say "Приятного аппетита!" and wait for deactivation.
"""
        }

        val timerBlock = if (timerTargetMs > 0) {
            val remainSec = ((timerTargetMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            val remainMin = remainSec / 60
            val remainSecPart = remainSec % 60
            if (remainSec > 0) {
                "\nACTIVE TIMER: $timerLabel — ${remainMin}m ${remainSecPart}s remaining"
            } else {
                "\nTIMER DONE: $timerLabel — time's up! Remind the user."
            }
        } else ""

        return """
--------------------------------------------------
ACTIVE SKILL: COOKING MODE 🍳
--------------------------------------------------

You are a COOKING ASSISTANT / recipe navigator.
You guide the user through a recipe step by step, like a patient cooking instructor.

VOICE STYLE:
- Speak clearly and concisely — hands are busy, user can't read.
- Use short sentences. Don't dump the entire recipe at once.
- One step at a time. Wait for confirmation before moving on.
- If a step has a timer, announce it and the system will track it.
- Be encouraging: "Отлично!", "Хорошо идём!", "Почти готово!"

CAMERA USE:
- If camera is available, glance at it to verify progress when relevant.
- Example: "Вижу, что лук уже золотистый — можно добавлять морковь."
- Don't force camera checks — only when it adds value.

NAVIGATION COMMANDS (user voice):
- "готово" / "дальше" / "done" / "next" → advance to next step
- "повтори" / "repeat" → re-read current step
- "назад" / "back" → go to previous step
- "сколько осталось" / "how much time" → report timer status
- "пропусти" / "skip" → skip to next step without confirming
- "какой шаг" / "what step" → tell current step number and total

STATE MANAGEMENT:
Always emit state tags so the system can track progress:
- <cooking_state>CHOOSING_RECIPE</cooking_state>
- <cooking_state>COOKING:N</cooking_state> (N = step number)
- <cooking_state>DONE</cooking_state>

TIMER:
To start a cooking timer, emit: <cooking_timer>MINUTES:LABEL</cooking_timer>
Example: <cooking_timer>10:тушить овощи</cooking_timer>
The system will count down and remind the user when time is up.
Only one timer at a time. New timer replaces the old one.
$timerBlock
$stateBlock
""".trimIndent()
    }

    override fun extractParams(text: String): String {
        for (phrase in activationPhrases) {
            val idx = text.lowercase().indexOf(phrase)
            if (idx >= 0) {
                val after = text.substring(idx + phrase.length).trim()
                if (after.isNotBlank()) return after
            }
        }
        return ""
    }

    override fun onActivated(params: String) {
        dishName = params
        state = CookingState.CHOOSING_RECIPE
        currentStep = 0
        clearTimer()
        Log.d(TAG, "Cooking mode activated, dish: '$dishName'")
    }

    override fun onDeactivated() {
        state = CookingState.CHOOSING_RECIPE
        dishName = ""
        currentStep = 0
        clearTimer()
        Log.d(TAG, "Cooking mode deactivated")
    }

    override fun onAiOutput(text: String) {
        stateChanged = false
        val oldState = state
        val oldStep = currentStep
        parseStateTag(text)
        parseTimerTag(text)
        stateChanged = (state != oldState || currentStep != oldStep)
    }

    fun consumeStateChanged(): Boolean {
        val changed = stateChanged
        stateChanged = false
        return changed
    }

    override fun onTick(injectMessage: (String) -> Unit) {
        if (timerTargetMs <= 0) return

        val now = System.currentTimeMillis()
        val remainMs = timerTargetMs - now
        val remainSec = (remainMs / 1000).coerceAtLeast(0)

        if (remainSec <= 0) {
            injectMessage("[TIMER_DONE] Таймер '$timerLabel' завершён! Время вышло. Сообщи пользователю и переходи к следующему действию.")
            clearTimer()
            return
        }

        val totalDurationMs = timerTargetMs - timerStartMs
        val elapsedMs = now - timerStartMs
        val remainMin = remainSec / 60

        if (!timerAnnouncedHalf && totalDurationMs > 120000 && elapsedMs >= totalDurationMs / 2) {
            timerAnnouncedHalf = true
            injectMessage("[TIMER_UPDATE] $timerLabel: осталось ${remainMin} мин. Половина времени прошла.")
        }

        if (!timerAnnouncedMinute && remainSec in 45..75) {
            timerAnnouncedMinute = true
            injectMessage("[TIMER_UPDATE] $timerLabel: осталась примерно 1 минута!")
        }
    }

    private fun parseStateTag(text: String) {
        val regex = Regex("<cooking_state>(.*?)</cooking_state>")
        val match = regex.find(text) ?: return
        val value = match.groupValues[1].trim()

        when {
            value == "CHOOSING_RECIPE" -> {
                state = CookingState.CHOOSING_RECIPE
                currentStep = 0
                Log.d(TAG, "State → CHOOSING_RECIPE")
            }
            value.startsWith("COOKING:") -> {
                state = CookingState.COOKING
                currentStep = value.substringAfter("COOKING:").toIntOrNull() ?: 1
                Log.d(TAG, "State → COOKING step $currentStep")
            }
            value == "DONE" -> {
                state = CookingState.DONE
                clearTimer()
                Log.d(TAG, "State → DONE")
            }
        }
    }

    private fun parseTimerTag(text: String) {
        val regex = Regex("<cooking_timer>(\\d+):(.*?)</cooking_timer>")
        val match = regex.find(text) ?: return
        val minutes = match.groupValues[1].toIntOrNull() ?: return
        timerLabel = match.groupValues[2].trim()
        timerStartMs = System.currentTimeMillis()
        timerTargetMs = timerStartMs + minutes * 60 * 1000L
        timerAnnouncedHalf = false
        timerAnnouncedMinute = false
        Log.d(TAG, "Timer started: ${minutes}m for '$timerLabel'")
    }

    private fun clearTimer() {
        timerStartMs = 0
        timerTargetMs = 0
        timerLabel = ""
        timerAnnouncedHalf = false
        timerAnnouncedMinute = false
    }
}
