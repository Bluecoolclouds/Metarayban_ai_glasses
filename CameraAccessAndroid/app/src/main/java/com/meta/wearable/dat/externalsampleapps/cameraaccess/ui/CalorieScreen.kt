package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.CalorieViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.DaySummary
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.FoodItem
import com.meta.wearable.dat.externalsampleapps.cameraaccess.skills.MealEntry

@Composable
fun CalorieScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalorieViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val summary = uiState.daySummary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColor.SurfaceBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Calories",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.size(48.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { viewModel.goToPreviousDay() }) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Previous day",
                        tint = Color.White,
                    )
                }
                Text(
                    text = viewModel.getDisplayDate(),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { viewModel.goToToday() }
                        .padding(horizontal = 16.dp),
                )
                IconButton(
                    onClick = { viewModel.goToNextDay() },
                    enabled = !viewModel.isToday,
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Next day",
                        tint = if (!viewModel.isToday) Color.White else AppColor.SubtleText.copy(alpha = 0.3f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (summary != null) {
                    item { DayOverviewCard(summary, uiState.dailyGoal) }

                    item { MacrosCard(summary) }

                    if (summary.meals.isNotEmpty()) {
                        item {
                            Text(
                                text = "MEALS",
                                color = AppColor.SubtleText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }

                        items(summary.meals, key = { it.id }) { meal ->
                            MealCard(
                                meal = meal,
                                isExpanded = uiState.expandedMealId == meal.id,
                                onToggle = { viewModel.toggleMealExpanded(meal.id) },
                                onDelete = { viewModel.deleteMeal(meal.id) },
                            )
                        }
                    }

                    if (summary.allSuggestions.isNotEmpty()) {
                        item {
                            SuggestionsCard(summary.allSuggestions)
                        }
                    }

                    if (summary.meals.isEmpty()) {
                        item {
                            EmptyState()
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DayOverviewCard(summary: DaySummary, dailyGoal: Int) {
    val progress = (summary.totalCalories.toFloat() / dailyGoal).coerceIn(0f, 1.5f)
    val remaining = dailyGoal - summary.totalCalories
    val progressColor = when {
        progress > 1f -> AppColor.Red
        progress > 0.8f -> AppColor.Orange
        else -> AppColor.Green
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppColor.CardDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = progressColor,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${summary.totalCalories}",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "kcal",
                    color = AppColor.SubtleText,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = AppColor.Divider,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (remaining > 0) "$remaining kcal remaining" else "${-remaining} kcal over limit",
                color = if (remaining > 0) AppColor.SubtleText else AppColor.Red,
                fontSize = 13.sp,
            )

            if (summary.avgHealthScore > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = healthScoreColor(summary.avgHealthScore),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Health score: ${summary.avgHealthScore}/100",
                        color = healthScoreColor(summary.avgHealthScore),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MacrosCard(summary: DaySummary) {
    if (summary.totalProtein == 0.0 && summary.totalFat == 0.0 && summary.totalCarbs == 0.0) return

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColor.CardDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MacroColumn("Protein", summary.totalProtein, AppColor.MetaBlue)
            MacroColumn("Fat", summary.totalFat, AppColor.Orange)
            MacroColumn("Carbs", summary.totalCarbs, AppColor.Green)
        }
    }
}

@Composable
private fun MacroColumn(label: String, grams: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${grams.toInt()}g",
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = AppColor.SubtleText,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun MealCard(
    meal: MealEntry,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColor.CardDark),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(mealTypeColor(meal.mealType).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = null,
                        tint = mealTypeColor(meal.mealType),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${meal.mealType} · ${meal.formattedTime}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = meal.foods.joinToString(", ") { it.name },
                        color = AppColor.SubtleText,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "${meal.totalCalories}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text(
                    text = " kcal",
                    color = AppColor.SubtleText,
                    fontSize = 12.sp,
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = AppColor.SubtleText,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AppColor.Divider)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    meal.foods.forEach { food ->
                        FoodRow(food)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row {
                            MacroPill("P", meal.totalProtein, AppColor.MetaBlue)
                            Spacer(modifier = Modifier.width(6.dp))
                            MacroPill("F", meal.totalFat, AppColor.Orange)
                            Spacer(modifier = Modifier.width(6.dp))
                            MacroPill("C", meal.totalCarbs, AppColor.Green)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (meal.healthScore > 0) {
                                Text(
                                    text = "${meal.healthScore}",
                                    color = healthScoreColor(meal.healthScore),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = healthScoreColor(meal.healthScore),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .padding(start = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete meal",
                                    tint = AppColor.SubtleText,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodRow(food: FoodItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(healthRatingColor(food.healthRating))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = food.name,
                color = Color.White,
                fontSize = 14.sp,
            )
            Text(
                text = food.portion,
                color = AppColor.SubtleText,
                fontSize = 12.sp,
            )
        }
        Text(
            text = "${food.calories} kcal",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun MacroPill(label: String, grams: Double, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "$label ${grams.toInt()}g",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SuggestionsCard(suggestions: List<String>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColor.CardDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.TipsAndUpdates,
                    contentDescription = null,
                    tint = AppColor.Yellow,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tips",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            suggestions.forEach { suggestion ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(text = "•", color = AppColor.SubtleText, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = suggestion,
                        color = AppColor.SubtleText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColor.CardDark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Restaurant,
                contentDescription = null,
                tint = AppColor.SubtleText.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No meals logged",
                color = AppColor.SubtleText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Activate the calorie counter skill\nand point the camera at your food",
                color = AppColor.SubtleText.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun healthScoreColor(score: Int): Color {
    return when {
        score >= 75 -> AppColor.Green
        score >= 50 -> AppColor.Orange
        else -> AppColor.Red
    }
}

private fun healthRatingColor(rating: String): Color {
    return when (rating.lowercase()) {
        "excellent" -> AppColor.Green
        "good" -> AppColor.MetaBlue
        "medium" -> AppColor.Orange
        "poor" -> AppColor.Red
        else -> AppColor.SubtleText
    }
}

private fun mealTypeColor(type: String): Color {
    return when (type) {
        "Breakfast" -> AppColor.Orange
        "Lunch" -> AppColor.Green
        "Snack" -> AppColor.Teal
        "Dinner" -> AppColor.Purple
        "Late snack" -> AppColor.Pink
        else -> AppColor.MetaBlue
    }
}
