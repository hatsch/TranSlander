package com.voicekeyboard.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages custom word replacement rules for post-processing transcribed text.
 * Rules are persisted to a JSON file and applied after speech recognition.
 */
class DictionaryManager(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryManager"
        private const val RULES_FILE = "replacements.json"
        private const val JSON_VERSION = 1
    }

    data class ReplacementRule(
        val from: String,
        val to: String,
        val caseSensitive: Boolean = false
    )

    private val rulesFile = File(context.filesDir, RULES_FILE)
    private val mutex = Mutex()

    private val _rules = MutableStateFlow<List<ReplacementRule>>(emptyList())
    val rules: StateFlow<List<ReplacementRule>> = _rules

    init {
        // Load rules synchronously on init to ensure they're available immediately
        loadRulesSync()
    }

    private fun loadRulesSync() {
        try {
            if (!rulesFile.exists()) {
                _rules.value = emptyList()
                return
            }

            val json = rulesFile.readText()
            val jsonObject = JSONObject(json)
            val rulesArray = jsonObject.optJSONArray("rules") ?: JSONArray()

            val loadedRules = mutableListOf<ReplacementRule>()
            for (i in 0 until rulesArray.length()) {
                val ruleObj = rulesArray.getJSONObject(i)
                loadedRules.add(
                    ReplacementRule(
                        from = ruleObj.getString("from"),
                        to = ruleObj.getString("to"),
                        caseSensitive = ruleObj.optBoolean("caseSensitive", false)
                    )
                )
            }
            _rules.value = loadedRules
            Log.i(TAG, "Loaded ${loadedRules.size} replacement rules")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rules", e)
            _rules.value = emptyList()
        }
    }

    private suspend fun saveRules() = withContext(Dispatchers.IO) {
        try {
            val jsonObject = JSONObject().apply {
                put("version", JSON_VERSION)
                put("rules", JSONArray().apply {
                    _rules.value.forEach { rule ->
                        put(JSONObject().apply {
                            put("from", rule.from)
                            put("to", rule.to)
                            put("caseSensitive", rule.caseSensitive)
                        })
                    }
                })
            }
            rulesFile.writeText(jsonObject.toString(2))
            Log.i(TAG, "Saved ${_rules.value.size} replacement rules")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rules", e)
        }
    }

    /**
     * Add a new replacement rule.
     * @param from The text to find (will be matched as whole word)
     * @param to The replacement text
     * @param caseSensitive Whether matching should be case-sensitive
     */
    suspend fun addRule(from: String, to: String, caseSensitive: Boolean = false) = mutex.withLock {
        if (from.isBlank() || from == to) return@withLock

        // Remove existing rule with same 'from' if exists
        val currentRules = _rules.value.toMutableList()
        currentRules.removeAll { it.from.equals(from, ignoreCase = true) }
        currentRules.add(ReplacementRule(from.trim(), to.trim(), caseSensitive))
        _rules.value = currentRules
        saveRules()
        Log.i(TAG, "Added rule: '$from' -> '$to'")
    }

    /**
     * Remove a replacement rule by its 'from' value.
     */
    suspend fun removeRule(from: String) = mutex.withLock {
        val currentRules = _rules.value.toMutableList()
        val removed = currentRules.removeAll { it.from == from }
        if (removed) {
            _rules.value = currentRules
            saveRules()
            Log.i(TAG, "Removed rule for: '$from'")
        }
    }

    /**
     * Apply all replacement rules to the given text.
     * Uses whole-word matching with word boundaries.
     */
    fun applyReplacements(text: String): String {
        if (text.isBlank() || _rules.value.isEmpty()) return text

        var result = text
        for (rule in _rules.value) {
            val regex = if (rule.caseSensitive) {
                "\\b${Regex.escape(rule.from)}\\b".toRegex()
            } else {
                "\\b${Regex.escape(rule.from)}\\b".toRegex(RegexOption.IGNORE_CASE)
            }
            result = result.replace(regex, rule.to)
        }
        return result
    }

    /**
     * Export rules to a JSON string for sharing/backup.
     */
    fun exportToJson(): String {
        return JSONObject().apply {
            put("version", JSON_VERSION)
            put("rules", JSONArray().apply {
                _rules.value.forEach { rule ->
                    put(JSONObject().apply {
                        put("from", rule.from)
                        put("to", rule.to)
                        put("caseSensitive", rule.caseSensitive)
                    })
                }
            })
        }.toString(2)
    }

    /**
     * Import rules from a JSON string.
     * @param json The JSON string to import
     * @param merge If true, merge with existing rules; if false, replace all
     */
    suspend fun importFromJson(json: String, merge: Boolean = true) = mutex.withLock {
        try {
            val jsonObject = JSONObject(json)
            val rulesArray = jsonObject.optJSONArray("rules") ?: return@withLock

            val importedRules = mutableListOf<ReplacementRule>()
            for (i in 0 until rulesArray.length()) {
                val ruleObj = rulesArray.getJSONObject(i)
                importedRules.add(
                    ReplacementRule(
                        from = ruleObj.getString("from"),
                        to = ruleObj.getString("to"),
                        caseSensitive = ruleObj.optBoolean("caseSensitive", false)
                    )
                )
            }

            if (merge) {
                val currentRules = _rules.value.toMutableList()
                importedRules.forEach { newRule ->
                    currentRules.removeAll { it.from.equals(newRule.from, ignoreCase = true) }
                    currentRules.add(newRule)
                }
                _rules.value = currentRules
            } else {
                _rules.value = importedRules
            }
            saveRules()
            Log.i(TAG, "Imported ${importedRules.size} rules (merge=$merge)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import rules", e)
        }
    }

    /**
     * Clear all replacement rules.
     */
    suspend fun clearAllRules() = mutex.withLock {
        _rules.value = emptyList()
        saveRules()
        Log.i(TAG, "Cleared all rules")
    }
}
