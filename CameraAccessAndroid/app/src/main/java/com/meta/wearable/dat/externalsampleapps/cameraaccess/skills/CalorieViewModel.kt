package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalorieUiState(
    val daySummary: DaySummary? = null,
    val selectedDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
    val expandedMealId: String? = null,
    val dailyGoal: Int = 2000,
)

class CalorieViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CalorieUiState())
    val uiState: StateFlow<CalorieUiState> = _uiState.asStateFlow()

    init {
        CalorieRepository.init(application)
        loadDay()
    }

    fun loadDay(date: String? = null) {
        val targetDate = date ?: _uiState.value.selectedDate
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { CalorieRepository.getDaySummary(targetDate) }
            _uiState.update { it.copy(daySummary = summary, selectedDate = targetDate) }
        }
    }

    fun goToPreviousDay() {
        val current = _uiState.value.selectedDate
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        try {
            val date = sdf.parse(current) ?: return
            val cal = Calendar.getInstance().apply { time = date; add(Calendar.DAY_OF_MONTH, -1) }
            loadDay(sdf.format(cal.time))
        } catch (_: Exception) {}
    }

    fun goToNextDay() {
        val current = _uiState.value.selectedDate
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (current >= today) return
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        try {
            val date = sdf.parse(current) ?: return
            val cal = Calendar.getInstance().apply { time = date; add(Calendar.DAY_OF_MONTH, 1) }
            loadDay(sdf.format(cal.time))
        } catch (_: Exception) {}
    }

    fun goToToday() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        loadDay(today)
    }

    fun toggleMealExpanded(mealId: String) {
        _uiState.update {
            it.copy(expandedMealId = if (it.expandedMealId == mealId) null else mealId)
        }
    }

    fun deleteMeal(mealId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CalorieRepository.deleteMeal(mealId) }
            loadDay()
        }
    }

    val isToday: Boolean
        get() {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return _uiState.value.selectedDate == today
        }

    fun getDisplayDate(): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(yesterday.time)
        return when (_uiState.value.selectedDate) {
            today -> "Today"
            yesterdayStr -> "Yesterday"
            else -> {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val date = sdf.parse(_uiState.value.selectedDate)
                    SimpleDateFormat("d MMMM", Locale.getDefault()).format(date!!)
                } catch (_: Exception) {
                    _uiState.value.selectedDate
                }
            }
        }
    }
}
