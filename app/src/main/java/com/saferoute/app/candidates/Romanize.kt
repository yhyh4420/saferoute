package com.saferoute.app.candidates

/** Kotlin port of pipeline/romanize.py, for names of places found on the phone (kept in sync by tests there). */
object Romanize {
    private val INITIALS = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val VOWELS = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi",
        "yu", "eu", "ui", "i")
    private val FINALS = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t",
        "ng", "t", "t", "k", "t", "p", "t")
    private val LIAISON = listOf("", "g", "kk", "ks", "n", "nj", "nh", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "bs",
        "s", "ss", "", "j", "ch", "k", "t", "p", "h")
    private val SUFFIXES = listOf(
        "도시자연공원" to "Urban Nature Park", "어린이공원" to "Children's Park", "근린공원" to "Neighborhood Park",
        "생태공원" to "Ecological Park", "문화공원" to "Culture Park", "마을공원" to "Village Park", "소공원" to "Pocket Park",
        "체육공원" to "Sports Park", "공원" to "Park", "운동장" to "Field", "광장" to "Plaza", "놀이터" to "Playground",
        "체육시설" to "Sports Facility", "축구장" to "Soccer Field", "야구장" to "Baseball Field", "정원" to "Garden",
        "초등학교" to "Elementary School", "중학교" to "Middle School", "고등학교" to "High School", "학교" to "School",
    ).sortedByDescending { it.first.length }

    private data class Syl(val ini: Int, val vow: Int, val fin: Int)

    fun word(w: String): String {
        val syl: List<Any> = w.map { ch -> val c = ch.code - 0xAC00; if (c in 0 until 11172) Syl(c / 588, (c % 588) / 28, c % 28) else ch }
        val out = StringBuilder()
        syl.forEachIndexed { i, s ->
            if (s !is Syl) { out.append(s); return@forEachIndexed }
            val prev = syl.getOrNull(i - 1) as? Syl
            val nxt = syl.getOrNull(i + 1) as? Syl
            val head = when {
                prev != null && prev.fin !in setOf(0, 21) && s.ini == 11 && LIAISON[prev.fin].isNotEmpty() -> ""
                prev != null && s.ini == 5 && prev.fin in setOf(4, 8) -> "l"
                prev != null && s.ini == 2 && prev.fin == 8 -> "l"
                prev != null && s.ini == 5 && prev.fin !in setOf(0, 8, 4) -> "n"
                else -> INITIALS[s.ini]
            }
            var tail = FINALS[s.fin]
            if (nxt != null && s.fin != 0) {
                tail = when {
                    nxt.ini == 11 && s.fin != 21 -> LIAISON[s.fin]
                    nxt.ini in setOf(2, 5, 6) && tail in setOf("k", "t", "p") -> mapOf("k" to "ng", "t" to "n", "p" to "m").getValue(tail)
                    nxt.ini == 5 && s.fin == 4 -> "l"
                    else -> tail
                }
            }
            out.append(head + VOWELS[s.vow] + tail)
        }
        return out.toString()
    }

    fun name(ko: String): String = ko.split(" ").filter { it.isNotBlank() }.joinToString(" ") { token ->
        var t = token
        val types = ArrayList<String>()
        while (true) {
            val hit = SUFFIXES.firstOrNull { t.endsWith(it.first) && t.length >= it.first.length } ?: break
            types.add(0, hit.second); t = t.dropLast(hit.first.length)
        }
        val stem = Regex("""\d+|\D+""").findAll(t).joinToString(" ") { m ->
            if (m.value.all { it.isDigit() }) m.value else word(m.value).replaceFirstChar { it.uppercase() }
        }
        (listOf(stem) + types).filter { it.isNotBlank() }.joinToString(" ")
    }
}
