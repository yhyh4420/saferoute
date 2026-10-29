package com.saferoute.app.qa

import android.database.sqlite.SQLiteDatabase

data class QaEntry(val hazard: String, val intent: String, val lang: String, val question: String, val answer: String, val keywords: List<String>)

/**
 * Offline Q&A without a model on the phone: answers were generated and safety-checked at build
 * time (pipeline/gen_answers.py, Muse Glimmer); here we only match the question to an intent.
 * A small on-device LLM gave unsafe answers (VALIDATION V13), so free generation is not used.
 */
class OfflineQa(db: SQLiteDatabase) {
    private val entries: List<QaEntry> = runCatching {
        db.rawQuery("SELECT hazard, intent, lang, question, answer, keywords FROM qa", null).use { c ->
            buildList {
                while (c.moveToNext()) add(QaEntry(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5).split("|").filter { it.isNotBlank() }))
            }
        }
    }.getOrDefault(emptyList()) // packs built before Q&A existed have no qa table

    fun questions(hazard: String, lang: String): List<QaEntry> = entries.filter { it.hazard == hazard && it.lang == lang }

    /** Best intent by matched keyword length; null when nothing matches (the UI then shows the rule text). */
    fun answer(query: String, hazard: String): QaEntry? {
        val lang = if (isKorean(query)) "ko" else "en"
        val q = query.lowercase()
        return questions(hazard, lang)
            .map { e -> e to e.keywords.filter { q.contains(it.lowercase()) }.sumOf { it.length } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    companion object {
        fun isKorean(text: String) = text.any { it in '가'..'힣' }

        private val SHELTER_RO = Regex("""\{SHELTER\}\s*(으로|로)""")

        fun fill(answer: String, shelterName: String?, korean: Boolean = true): String {
            val name = shelterName ?: if (korean) "가장 가까운 대피소" else "the nearest shelter"
            // Generated text cannot know the name, so pick 으로/로 here (로 after a vowel or ㄹ).
            return answer.replace(SHELTER_RO) { name + roParticle(name) }.replace("{SHELTER}", name)
        }

        fun roParticle(word: String): String {
            val c = word.lastOrNull { it in '가'..'힣' } ?: return "로"
            val jong = (c - '가') % 28
            return if (jong == 0 || jong == 8) "로" else "으로"
        }
    }
}
