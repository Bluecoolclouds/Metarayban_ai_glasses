package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.content.Context
import android.util.Log

class CalorieSkill : Skill {
    companion object {
        private const val TAG = "CalorieSkill"

        fun init(context: Context) {
            CalorieRepository.init(context)
        }
    }

    override val id = "calorie_counter"
    override val name = "Calorie Counter"
    override val needsCamera = true
    override val intervalMs: Long = 0
    override val videoWindowMs: Long = 3_000L  // only need a snapshot, not continuous stream

    override val activationPhrases = listOf(
        "посчитай калории", "сколько тут калорий", "сколько калорий",
        "count calories", "how many calories", "калории",
        "что я ел сегодня", "what did i eat today", "дневник питания",
    )

    override val deactivationPhrases = listOf(
        "хватит считать", "стоп калории", "stop counting calories",
        "закончи калории", "хватит",
    )

    override val systemPromptBlock: String
        get() {
            val diary = CalorieRepository.getTodaySummaryText()
            return """
--------------------------------------------------
ACTIVE SKILL: CALORIE COUNTER
--------------------------------------------------

You are in CALORIE COUNTER mode.
When user asks about calories:
- Look at the camera frame showing the food/plate.
- Identify each food item and estimate the portion size by eye.
- Estimate calories and macros (protein, fat, carbs, fiber, sugar) for each item.
- Rate health: "excellent"/"good"/"medium"/"poor".
- Give a total estimate.
- Speak naturally: "Вижу [food]. Примерно [X] калорий. Белок [Y]г, жиры [Z]г, углеводы [W]г."
- Be honest about uncertainty — say "примерно" / "roughly".

If user asks "что я ел сегодня" / "what did I eat today":
- Report today's food diary:
$diary

If user asks follow-up questions like "много ли это для ужина?" — answer based on a typical 2000 kcal/day norm.

After analyzing food, ALWAYS emit a structured JSON inside <calorie_log> tags for automatic logging:
<calorie_log>
{
  "foods": [
    {
      "name": "food name",
      "portion": "portion description",
      "calories": 350,
      "protein": 25.0,
      "fat": 12.0,
      "carbs": 30.0,
      "fiber": 3.0,
      "sugar": 5.0,
      "healthRating": "good"
    }
  ],
  "totalCalories": 350,
  "totalProtein": 25.0,
  "totalFat": 12.0,
  "totalCarbs": 30.0,
  "healthScore": 72,
  "suggestions": ["Add more vegetables", "Great protein choice"]
}
</calorie_log>

Rules for JSON:
- calories: integer (kcal)
- protein/fat/carbs: number (grams)
- fiber/sugar: number or null
- healthRating: one of "excellent", "good", "medium", "poor"
- healthScore: 0-100 integer
- suggestions: 1-3 short tips

Stop when user says: "хватит считать", "стоп калории".
""".trimIndent()
        }

    override fun onActivated(params: String) {
        Log.d(TAG, "Calorie counter activated")
    }

    override fun onDeactivated() {
        Log.d(TAG, "Calorie counter deactivated")
    }
}
