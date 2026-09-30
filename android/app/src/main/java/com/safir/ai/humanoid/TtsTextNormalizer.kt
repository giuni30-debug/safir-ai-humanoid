package com.safir.ai.humanoid

/**
 * Converts Romanian text into a form that TTS engines pronounce more reliably.
 *
 * The visible AI reply is left untouched; this class is used only on the audio path.
 */
object TtsTextNormalizer {
    private val romanianHint = Regex(
        "(?iu)([ăâîșşțţ]|\\b(?:ora|ore|minut(?:e)?|azi|mâine|poimâine|programar(?:e|ea)|este|sunt|și|pentru|până|în|din|luni|marți|miercuri|joi|vineri|sâmbătă|duminică)\\b)"
    )
    private val explicitRomanianHour = Regex("(?iu)\\bora\\s+([01]?\\d|2[0-3])(?::([0-5]\\d))?\\b")
    private val clockTime = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val numericDate = Regex("\\b([0-3]?\\d)[./]([01]?\\d)[./](\\d{4})\\b")
    private val percentage = Regex("\\b(\\d{1,6}(?:[,.]\\d{1,2})?)\\s*%")
    private val euroAmount = Regex("\\b(\\d{1,6}(?:[,.]\\d{1,2})?)\\s*€")
    private val decimalNumber = Regex("\\b(\\d{1,6})[,.](\\d{1,2})\\b")
    private val integerNumber = Regex("(?<![\\p{L}\\d])\\d{1,6}(?![\\p{L}\\d])")

    fun normalize(text: String): String {
        val source = text.trim()
        if (source.isBlank()) return source

        val looksRomanian =
            romanianHint.containsMatchIn(source) ||
                explicitRomanianHour.containsMatchIn(source)

        if (!looksRomanian) return source

        var result = source

        result = explicitRomanianHour.replace(result) { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].takeIf { it.isNotBlank() }?.toInt()
            spokenTime(hour, minute)
        }

        result = clockTime.replace(result) { match ->
            spokenTime(
                hour = match.groupValues[1].toInt(),
                minute = match.groupValues[2].toInt(),
            )
        }

        result = numericDate.replace(result) { match ->
            val day = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            val year = match.groupValues[3].toIntOrNull()
            if (day == null || month == null || year == null || day !in 1..31 || month !in 1..12) {
                match.value
            } else {
                "${numberToWords(day)} ${MONTHS[month - 1]} ${numberToWords(year)}"
            }
        }

        result = euroAmount.replace(result) { match ->
            "${decimalToWords(match.groupValues[1])} euro"
        }

        result = percentage.replace(result) { match ->
            "${decimalToWords(match.groupValues[1])} la sută"
        }

        result = decimalNumber.replace(result) { match ->
            val whole = match.groupValues[1].toInt()
            val fraction = match.groupValues[2].toInt()
            "${numberToWords(whole)} virgulă ${numberToWords(fraction)}"
        }

        result = integerNumber.replace(result) { match ->
            numberToWords(match.value.toInt())
        }

        result = result
            .replace("&", " și ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.;!?])"), "\$1")
            .trim()

        return result
    }

    private fun spokenTime(hour: Int, minute: Int?): String {
        val hourWords = numberToWords(hour)
        if (minute == null || minute == 0) return "ora $hourWords"

        return when (minute) {
            15 -> "ora $hourWords și un sfert"
            30 -> "ora $hourWords și jumătate"
            45 -> "ora $hourWords și patruzeci și cinci de minute"
            else -> "ora $hourWords și ${numberToWords(minute)} de minute"
        }
    }

    private fun decimalToWords(value: String): String {
        val normalized = value.replace(',', '.')
        val pieces = normalized.split('.', limit = 2)
        val whole = pieces[0].toIntOrNull() ?: return value
        if (pieces.size == 1) return numberToWords(whole)

        val fractionRaw = pieces[1]
        val fraction = fractionRaw.toIntOrNull() ?: return value
        if (fraction == 0) return numberToWords(whole)
        return "${numberToWords(whole)} virgulă ${numberToWords(fraction)}"
    }

    private fun numberToWords(value: Int): String {
        if (value == 0) return "zero"
        if (value < 0) return "minus ${numberToWords(-value)}"
        if (value > 999_999) return value.toString()

        val parts = mutableListOf<String>()
        var remaining = value

        if (remaining >= 1000) {
            val thousands = remaining / 1000
            parts += when (thousands) {
                1 -> "o mie"
                2 -> "două mii"
                else -> {
                    val prefix = underThousand(thousands)
                    val separator = if (thousands >= 20) " de mii" else " mii"
                    prefix + separator
                }
            }
            remaining %= 1000
        }

        if (remaining > 0) parts += underThousand(remaining)
        return parts.joinToString(" ")
    }

    private fun underThousand(value: Int): String {
        require(value in 1..999)
        val parts = mutableListOf<String>()
        var remaining = value

        if (remaining >= 100) {
            val hundreds = remaining / 100
            parts += when (hundreds) {
                1 -> "o sută"
                2 -> "două sute"
                else -> "${UNITS[hundreds]} sute"
            }
            remaining %= 100
        }

        if (remaining > 0) parts += underHundred(remaining)
        return parts.joinToString(" ")
    }

    private fun underHundred(value: Int): String {
        require(value in 1..99)
        if (value < 20) return SMALL[value]

        val tens = value / 10
        val units = value % 10
        val base = TENS[tens]
        return if (units == 0) base else "$base și ${UNITS[units]}"
    }

    private val SMALL = arrayOf(
        "",
        "unu",
        "doi",
        "trei",
        "patru",
        "cinci",
        "șase",
        "șapte",
        "opt",
        "nouă",
        "zece",
        "unsprezece",
        "doisprezece",
        "treisprezece",
        "paisprezece",
        "cincisprezece",
        "șaisprezece",
        "șaptesprezece",
        "optsprezece",
        "nouăsprezece",
    )

    private val UNITS = arrayOf(
        "",
        "unu",
        "doi",
        "trei",
        "patru",
        "cinci",
        "șase",
        "șapte",
        "opt",
        "nouă",
    )

    private val TENS = arrayOf(
        "",
        "",
        "douăzeci",
        "treizeci",
        "patruzeci",
        "cincizeci",
        "șaizeci",
        "șaptezeci",
        "optzeci",
        "nouăzeci",
    )

    private val MONTHS = arrayOf(
        "ianuarie",
        "februarie",
        "martie",
        "aprilie",
        "mai",
        "iunie",
        "iulie",
        "august",
        "septembrie",
        "octombrie",
        "noiembrie",
        "decembrie",
    )
}
