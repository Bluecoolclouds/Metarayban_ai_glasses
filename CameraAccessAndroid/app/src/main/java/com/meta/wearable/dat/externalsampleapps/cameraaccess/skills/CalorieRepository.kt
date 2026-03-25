package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class FoodItem(
    val name: String,
    val portion: String,
    val calories: Int,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double?,
    val sugar: Double?,
    val healthRating: String,
)

data class MealEntry(
    val id: String,
    val timestamp: Long,
    val foods: List<FoodItem>,
    val totalCalories: Int,
    val totalProtein: Double,
    val totalFat: Double,
    val totalCarbs: Double,
    val healthScore: Int,
    val suggestions: List<String>,
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    val formattedDate: String
        get() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))

    val mealType: String
        get() {
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            return when (cal.get(Calendar.HOUR_OF_DAY)) {
                in 5..10 -> "Breakfast"
                in 11..14 -> "Lunch"
                in 15..17 -> "Snack"
                in 18..22 -> "Dinner"
                else -> "Late snack"
            }
        }
}

data class DaySummary(
    val date: String,
    val meals: List<MealEntry>,
    val totalCalories: Int,
    val totalProtein: Double,
    val totalFat: Double,
    val totalCarbs: Double,
    val avgHealthScore: Int,
    val allSuggestions: List<String>,
)

object CalorieRepository {

    private const val TAG = "CalorieRepository"
    private const val PREFS_NAME = "calorie_diary_v2"
    private const val KEY_MEALS = "meals"
    private const val MAX_MEALS = 200

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun addMeal(jsonStr: String): MealEntry? {
        return try {
            val json = JSONObject(jsonStr)
            val entry = parseMealJson(json)
            saveMeal(entry)
            Log.d(TAG, "Added meal: ${entry.foods.size} foods, ${entry.totalCalories} kcal")
            entry
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse meal JSON", e)
            null
        }
    }

    fun addMealFromLegacy(description: String) {
        val calorieMatch = Regex("(\\d+)\\s*(?:kcal|ккал|калори)", RegexOption.IGNORE_CASE).find(description)
        val estimatedCal = calorieMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val food = FoodItem(
            name = description.substringBefore(":").trim().ifEmpty { description.take(50) },
            portion = "1 serving",
            calories = estimatedCal,
            protein = 0.0,
            fat = 0.0,
            carbs = 0.0,
            fiber = null,
            sugar = null,
            healthRating = "medium",
        )
        val entry = MealEntry(
            id = "meal_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            foods = listOf(food),
            totalCalories = estimatedCal,
            totalProtein = 0.0,
            totalFat = 0.0,
            totalCarbs = 0.0,
            healthScore = 50,
            suggestions = emptyList(),
        )
        saveMeal(entry)
    }

    private fun parseMealJson(json: JSONObject): MealEntry {
        val foodsArray = json.getJSONArray("foods")
        val foods = (0 until foodsArray.length()).map { i ->
            val f = foodsArray.getJSONObject(i)
            val rating = f.optString("healthRating", "medium").lowercase()
            val validRating = if (rating in listOf("excellent", "good", "medium", "poor")) rating else "medium"
            FoodItem(
                name = f.optString("name", "Unknown food"),
                portion = f.optString("portion", ""),
                calories = f.optInt("calories", 0).coerceAtLeast(0),
                protein = f.optDouble("protein", 0.0).coerceAtLeast(0.0),
                fat = f.optDouble("fat", 0.0).coerceAtLeast(0.0),
                carbs = f.optDouble("carbs", 0.0).coerceAtLeast(0.0),
                fiber = if (f.has("fiber") && !f.isNull("fiber")) f.optDouble("fiber").coerceAtLeast(0.0) else null,
                sugar = if (f.has("sugar") && !f.isNull("sugar")) f.optDouble("sugar").coerceAtLeast(0.0) else null,
                healthRating = validRating,
            )
        }

        val suggestionsArray = json.optJSONArray("suggestions")
        val suggestions = if (suggestionsArray != null) {
            (0 until suggestionsArray.length()).map { suggestionsArray.getString(it) }
        } else emptyList()

        val computedCalories = foods.sumOf { it.calories }
        val computedProtein = foods.sumOf { it.protein }
        val computedFat = foods.sumOf { it.fat }
        val computedCarbs = foods.sumOf { it.carbs }

        return MealEntry(
            id = "meal_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            foods = foods,
            totalCalories = json.optInt("totalCalories", computedCalories).coerceAtLeast(0),
            totalProtein = json.optDouble("totalProtein", computedProtein).coerceAtLeast(0.0),
            totalFat = json.optDouble("totalFat", computedFat).coerceAtLeast(0.0),
            totalCarbs = json.optDouble("totalCarbs", computedCarbs).coerceAtLeast(0.0),
            healthScore = json.optInt("healthScore", 50).coerceIn(0, 100),
            suggestions = suggestions,
        )
    }

    @Synchronized
    private fun saveMeal(entry: MealEntry) {
        val all = loadAllMeals().toMutableList()
        all.add(entry)
        while (all.size > MAX_MEALS) all.removeAt(0)
        val arr = JSONArray()
        all.forEach { arr.put(mealToJson(it)) }
        prefs?.edit()?.putString(KEY_MEALS, arr.toString())?.apply()
    }

    private fun mealToJson(meal: MealEntry): JSONObject {
        val json = JSONObject()
        json.put("id", meal.id)
        json.put("timestamp", meal.timestamp)
        json.put("totalCalories", meal.totalCalories)
        json.put("totalProtein", meal.totalProtein)
        json.put("totalFat", meal.totalFat)
        json.put("totalCarbs", meal.totalCarbs)
        json.put("healthScore", meal.healthScore)

        val foodsArr = JSONArray()
        meal.foods.forEach { food ->
            val fj = JSONObject()
            fj.put("name", food.name)
            fj.put("portion", food.portion)
            fj.put("calories", food.calories)
            fj.put("protein", food.protein)
            fj.put("fat", food.fat)
            fj.put("carbs", food.carbs)
            food.fiber?.let { fj.put("fiber", it) }
            food.sugar?.let { fj.put("sugar", it) }
            fj.put("healthRating", food.healthRating)
            foodsArr.put(fj)
        }
        json.put("foods", foodsArr)

        val sugArr = JSONArray()
        meal.suggestions.forEach { sugArr.put(it) }
        json.put("suggestions", sugArr)

        return json
    }

    fun loadAllMeals(): List<MealEntry> {
        val raw = prefs?.getString(KEY_MEALS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val json = arr.getJSONObject(i)
                    val foodsArr = json.getJSONArray("foods")
                    val foods = (0 until foodsArr.length()).map { fi ->
                        val f = foodsArr.getJSONObject(fi)
                        FoodItem(
                            name = f.getString("name"),
                            portion = f.optString("portion", ""),
                            calories = f.optInt("calories", 0),
                            protein = f.optDouble("protein", 0.0),
                            fat = f.optDouble("fat", 0.0),
                            carbs = f.optDouble("carbs", 0.0),
                            fiber = if (f.has("fiber") && !f.isNull("fiber")) f.optDouble("fiber") else null,
                            sugar = if (f.has("sugar") && !f.isNull("sugar")) f.optDouble("sugar") else null,
                            healthRating = f.optString("healthRating", "medium"),
                        )
                    }
                    val sugArr = json.optJSONArray("suggestions")
                    val suggestions = if (sugArr != null) {
                        (0 until sugArr.length()).map { sugArr.getString(it) }
                    } else emptyList()

                    MealEntry(
                        id = json.getString("id"),
                        timestamp = json.getLong("timestamp"),
                        foods = foods,
                        totalCalories = json.optInt("totalCalories", 0),
                        totalProtein = json.optDouble("totalProtein", 0.0),
                        totalFat = json.optDouble("totalFat", 0.0),
                        totalCarbs = json.optDouble("totalCarbs", 0.0),
                        healthScore = json.optInt("healthScore", 50),
                        suggestions = suggestions,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse meal entry", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load meals", e)
            emptyList()
        }
    }

    fun getTodayMeals(): List<MealEntry> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return loadAllMeals().filter { meal ->
            todayFormat.format(Date(meal.timestamp)) == today
        }
    }

    fun getDaySummary(dateStr: String? = null): DaySummary {
        val targetDate = dateStr ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val meals = loadAllMeals().filter { meal ->
            dateFormat.format(Date(meal.timestamp)) == targetDate
        }.sortedBy { it.timestamp }

        return DaySummary(
            date = targetDate,
            meals = meals,
            totalCalories = meals.sumOf { it.totalCalories },
            totalProtein = meals.sumOf { it.totalProtein },
            totalFat = meals.sumOf { it.totalFat },
            totalCarbs = meals.sumOf { it.totalCarbs },
            avgHealthScore = if (meals.isNotEmpty()) meals.map { it.healthScore }.average().toInt() else 0,
            allSuggestions = meals.flatMap { it.suggestions }.distinct(),
        )
    }

    @Synchronized
    fun deleteMeal(mealId: String) {
        val all = loadAllMeals().filter { it.id != mealId }
        val arr = JSONArray()
        all.forEach { arr.put(mealToJson(it)) }
        prefs?.edit()?.putString(KEY_MEALS, arr.toString())?.apply()
    }

    fun getTodaySummaryText(): String {
        val summary = getDaySummary()
        if (summary.meals.isEmpty()) return "No food logged today."
        val lines = mutableListOf<String>()
        lines.add("Today: ${summary.totalCalories} kcal | P:${summary.totalProtein.toInt()}g F:${summary.totalFat.toInt()}g C:${summary.totalCarbs.toInt()}g")
        summary.meals.forEach { meal ->
            val foodNames = meal.foods.joinToString(", ") { it.name }
            lines.add("${meal.formattedTime} (${meal.mealType}): $foodNames — ${meal.totalCalories} kcal")
        }
        return lines.joinToString("\n")
    }
}
