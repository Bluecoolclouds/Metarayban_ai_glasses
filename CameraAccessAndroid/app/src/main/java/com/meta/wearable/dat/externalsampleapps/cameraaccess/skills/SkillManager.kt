package com.meta.wearable.dat.externalsampleapps.cameraaccess.skills

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.util.Timer
import java.util.TimerTask

object SkillManager {
    private const val TAG = "SkillManager"

    private val registeredSkills = mutableListOf<Skill>()
    private var _activeSkill: Skill? = null
    val activeSkill: Skill? get() = _activeSkill

    var onSkillChanged: ((Skill?) -> Unit)? = null
    var injectMessage: ((String) -> Unit)? = null
    var frameProvider: (() -> Bitmap?)? = null
    var cameraActiveCheck: (() -> Boolean)? = null
    var onCameraCommand: ((action: String) -> Unit)? = null

    // ──────────────────────────────────────────────────────────────────────
    // Voice command phrases
    // ──────────────────────────────────────────────────────────────────────

    private val CAMERA_START_PHRASES = listOf(
        "включи камеру", "включить камеру", "запусти камеру", "открой камеру",
        "start camera", "turn on camera", "enable camera", "open camera",
        "start stream", "start streaming", "включи стрим", "запусти стрим"
    )
    private val CAMERA_STOP_PHRASES = listOf(
        "выключи камеру", "выключить камеру", "останови камеру", "закрой камеру",
        "stop camera", "turn off camera", "disable camera", "close camera",
        "stop stream", "stop streaming", "выключи стрим", "останови стрим"
    )

    // Phrases that imply the user wants a camera frame in regular conversation (no active skill)
    private val VISION_QUERY_PHRASES = listOf(
        "что ты видишь", "что видишь", "что там", "что перед тобой", "что вокруг",
        "опиши", "посмотри", "что это", "что за", "реши это", "решить это",
        "прочитай это", "что написано", "помоги с этим", "посмотри на это",
        "what do you see", "what's there", "what is this", "look at this",
        "describe what", "solve this", "read this", "help me with this",
        "what does it say", "what am i looking at"
    )

    // ──────────────────────────────────────────────────────────────────────
    // Auto-stop camera timer
    // ──────────────────────────────────────────────────────────────────────

    private const val CAMERA_AUTO_STOP_VISION_MS = 15_000L   // 15 s after lone vision query (glasses need warm-up time)
    private const val CAMERA_AUTO_STOP_MANUAL_MS = 300_000L  // 5 min after explicit "включи камеру"
    private const val CAMERA_AUTO_STOP_SKILL_MS  = 15_000L   // 15 s grace after camera-skill ends
    private const val VISION_QUERY_WINDOW_MS     = 15_000L   // 15 s send video after a vision query
    private const val VISION_QUERY_REFRESH_DELAY = 2_000L    // delay before injecting refresh nudge (let camera stabilise)

    private var cameraAutoStopTimer: Timer? = null

    // Tracks until when Gemini should receive video frames after a one-off vision question
    @Volatile private var visionQueryActiveUntil: Long = 0

    // Tracks until when Gemini should receive video frames for the active skill's snapshot window
    @Volatile private var skillVideoWindowUntil: Long = 0

    /**
     * Returns true when video frames should actually be forwarded to Gemini.
     * - Skills with videoWindowMs == Long.MAX_VALUE: always (continuous, e.g. VisionDescription)
     * - Skills with a finite videoWindowMs (e.g. CalorieSkill): only within the time window
     * - Vision queries in regular conversation: 5 s window
     */
    val shouldSendVideoToGemini: Boolean
        get() {
            val skill = _activeSkill
            if (skill != null && skill.needsCamera) {
                return if (skill.videoWindowMs == Long.MAX_VALUE) true
                else System.currentTimeMillis() < skillVideoWindowUntil
            }
            return System.currentTimeMillis() < visionQueryActiveUntil
        }

    /**
     * Call when the user speaks while a windowed skill is active, so Gemini gets fresh frames.
     */
    fun refreshSkillVideoWindow() {
        val skill = _activeSkill ?: return
        if (skill.needsCamera && skill.videoWindowMs != Long.MAX_VALUE) {
            skillVideoWindowUntil = System.currentTimeMillis() + skill.videoWindowMs
            Log.d(TAG, "Video window refreshed: ${skill.videoWindowMs}ms for ${skill.id}")
        }
    }

    private fun scheduleCameraAutoStop(delayMs: Long) {
        cameraAutoStopTimer?.cancel()
        cameraAutoStopTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val skillNeedsCamera = _activeSkill?.needsCamera == true
                    if (!skillNeedsCamera) {
                        Log.d(TAG, "Auto-stopping camera after ${delayMs / 1000}s inactivity")
                        onCameraCommand?.invoke("stop")
                    }
                }
            }, delayMs)
        }
    }

    private fun cancelCameraAutoStop() {
        cameraAutoStopTimer?.cancel()
        cameraAutoStopTimer = null
    }

    private fun ensureCameraOn() {
        if (cameraActiveCheck?.invoke() == false) {
            Log.d(TAG, "Auto-starting camera")
            onCameraCommand?.invoke("start")
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    private var tickTimer: Timer? = null
    private var initialized = false
    private var lastTickFrameHash: Long? = null

    fun init(context: Context) {
        initialized = true
        registeredSkills.clear()
        registeredSkills.add(TranslatorSkill())
        registeredSkills.add(VisionDescriptionSkill())
        registeredSkills.add(TextReaderSkill())
        registeredSkills.add(TranslateSignSkill())
        registeredSkills.add(CalorieSkill())
        registeredSkills.add(CookingModeSkill())
        CalorieSkill.init(context)
        Log.d(TAG, "Initialized with ${registeredSkills.size} skills: ${registeredSkills.map { it.id }}")
    }

    fun getActiveSystemPromptBlock(): String {
        return _activeSkill?.systemPromptBlock ?: ""
    }

    // ──────────────────────────────────────────────────────────────────────
    // Main activation logic
    // ──────────────────────────────────────────────────────────────────────

    fun checkActivation(userText: String): Boolean {
        val lower = userText.lowercase().trim()

        // 1. Explicit camera ON command
        if (CAMERA_START_PHRASES.any { lower.contains(it) }) {
            Log.d(TAG, "Camera START command detected")
            ensureCameraOn()
            scheduleCameraAutoStop(CAMERA_AUTO_STOP_MANUAL_MS)
            return true
        }

        // 2. Explicit camera OFF command
        if (CAMERA_STOP_PHRASES.any { lower.contains(it) }) {
            Log.d(TAG, "Camera STOP command detected")
            cancelCameraAutoStop()
            visionQueryActiveUntil = 0
            onCameraCommand?.invoke("stop")
            return true
        }

        // 3. Vision query in regular conversation (no camera-needing skill active)
        if (_activeSkill?.needsCamera != true &&
            VISION_QUERY_PHRASES.any { lower.contains(it) }) {
            Log.d(TAG, "Vision query in regular mode — camera on for ${CAMERA_AUTO_STOP_VISION_MS / 1000}s, video window ${VISION_QUERY_WINDOW_MS / 1000}s")
            ensureCameraOn()
            scheduleCameraAutoStop(CAMERA_AUTO_STOP_VISION_MS)
            visionQueryActiveUntil = System.currentTimeMillis() + VISION_QUERY_WINDOW_MS
            // Inject a nudge after the camera warms up so Gemini uses fresh frames
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    val injector = injectMessage ?: return
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    injector("[VISION_REFRESH at $ts] Fresh camera frame is now available. Please now answer the user's visual question based on what you currently see.")
                    Log.d(TAG, "Vision refresh injected at $ts")
                }
            }, VISION_QUERY_REFRESH_DELAY)
            // Don't return true — let Gemini handle the question normally
        }

        val current = _activeSkill

        // 4. Deactivate current skill
        if (current != null) {
            if (current.deactivationPhrases.any { lower.contains(it) }) {
                Log.d(TAG, "Deactivating skill: ${current.id}")
                stopTick()
                current.onDeactivated()
                _activeSkill = null
                onSkillChanged?.invoke(null)
                if (current.needsCamera) {
                    // Give a short grace period, then auto-stop camera
                    scheduleCameraAutoStop(CAMERA_AUTO_STOP_SKILL_MS)
                }
                return true
            }
        }

        // 5. Activate a new skill
        for (skill in registeredSkills) {
            if (skill == current) continue
            if (skill.matchActivation(lower)) {
                Log.d(TAG, "Activating skill: ${skill.id}")
                stopTick()
                current?.onDeactivated()

                if (skill.needsCamera) {
                    // Auto-start camera for this skill, no auto-stop while skill is active
                    ensureCameraOn()
                    cancelCameraAutoStop()
                } else if (current?.needsCamera == true) {
                    // Switching from camera skill to non-camera skill — grace-stop camera
                    scheduleCameraAutoStop(CAMERA_AUTO_STOP_SKILL_MS)
                }

                val params = skill.extractParams(userText)
                _activeSkill = skill
                skill.onActivated(params)
                // Initialize the video window for windowed skills
                if (skill.needsCamera && skill.videoWindowMs != Long.MAX_VALUE) {
                    skillVideoWindowUntil = System.currentTimeMillis() + skill.videoWindowMs
                }
                startTick(skill)
                onSkillChanged?.invoke(skill)
                return true
            }
        }

        return false
    }

    fun processAiOutput(aiText: String) {
        if (!initialized) return
        _activeSkill?.onAiOutput(aiText)

        val regex = Regex("<calorie_log>(.*?)</calorie_log>", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(aiText)
        if (match != null) {
            val raw = match.groupValues[1].trim()
            val cleaned = raw
                .replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
                .trim()
            val jsonStart = cleaned.indexOf('{')
            val jsonEnd = cleaned.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = cleaned.substring(jsonStart, jsonEnd + 1)
                val result = CalorieRepository.addMeal(jsonStr)
                if (result == null) {
                    Log.w(TAG, "Structured parse failed, falling back to legacy: ${jsonStr.take(100)}")
                    CalorieRepository.addMealFromLegacy(raw)
                }
            } else {
                CalorieRepository.addMealFromLegacy(raw)
            }
        }
    }

    fun pauseTick() {
        stopTick()
    }

    fun resumeTick() {
        val skill = _activeSkill ?: return
        if (skill.intervalMs > 0) startTick(skill)
    }

    private var cameraUnavailableWarned = false

    private fun startTick(skill: Skill) {
        if (skill.intervalMs <= 0) return
        lastTickFrameHash = null
        cameraUnavailableWarned = false
        Log.d(TAG, "Starting tick timer: every ${skill.intervalMs}ms for ${skill.id}")
        tickTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val injector = injectMessage
                    if (injector == null || _activeSkill != skill) return

                    if (skill.needsCamera) {
                        val cameraOk = cameraActiveCheck?.invoke() ?: true
                        if (!cameraOk) {
                            if (!cameraUnavailableWarned) {
                                cameraUnavailableWarned = true
                                Log.d(TAG, "Camera unavailable during ${skill.id} — auto-restarting")
                                onCameraCommand?.invoke("start")
                            }
                            return
                        } else {
                            cameraUnavailableWarned = false
                        }

                        val frame = frameProvider?.invoke()
                        if (frame != null) {
                            val hash = computeFrameHash(frame)
                            val prev = lastTickFrameHash
                            if (prev != null && framesSimilar(hash, prev)) {
                                Log.d(TAG, "Skipping tick — scene unchanged (hash=$hash)")
                                return
                            }
                            lastTickFrameHash = hash
                        }
                    }

                    skill.onTick(injector)
                }
            }, skill.intervalMs, skill.intervalMs)
        }
    }

    private fun stopTick() {
        tickTimer?.cancel()
        tickTimer = null
        lastTickFrameHash = null
    }

    fun deactivateAll() {
        stopTick()
        val hadCamera = _activeSkill?.needsCamera == true
        _activeSkill?.onDeactivated()
        _activeSkill = null
        onSkillChanged?.invoke(null)
        if (hadCamera) scheduleCameraAutoStop(CAMERA_AUTO_STOP_SKILL_MS)
    }

    fun getAllSkills(): List<Skill> = registeredSkills.toList()

    private fun computeFrameHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, false)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = pixelLuminance(scaled.getPixel(x, y))
                val right = pixelLuminance(scaled.getPixel(x + 1, y))
                hash = (hash shl 1) or (if (left > right) 1L else 0L)
            }
        }
        if (scaled != bitmap) scaled.recycle()
        return hash
    }

    private fun pixelLuminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun framesSimilar(a: Long, b: Long): Boolean {
        val hammingDist = java.lang.Long.bitCount(a xor b)
        return hammingDist <= 10
    }
}
