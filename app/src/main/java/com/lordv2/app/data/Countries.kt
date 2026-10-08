package com.lordv2.app.data

data class Country(
    val code: String,
    val name: String,
    val lat: Float,
    val lon: Float,
    val keywords: List<String> = emptyList(),
) {
    val flag: String get() = Countries.flag(code)
}

object Countries {
    val all: List<Country> = listOf(
        Country("DE", "Germany", 51.1f, 10.4f, listOf("germany", "deutschland", "frankfurt", "berlin", "nuremberg", "falkenstein")),
        Country("US", "United States", 39.8f, -98.6f, listOf("united states", "usa", "america", "new york", "los angeles", "dallas", "miami", "seattle", "chicago", "san jose")),
        Country("GB", "United Kingdom", 52.5f, -1.5f, listOf("united kingdom", "london", "england", "britain", "manchester")),
        Country("FR", "France", 46.6f, 2.2f, listOf("france", "paris", "marseille")),
        Country("NL", "Netherlands", 52.2f, 5.3f, listOf("netherlands", "amsterdam", "holland")),
        Country("SG", "Singapore", 1.35f, 103.8f, listOf("singapore")),
        Country("JP", "Japan", 36.2f, 138.3f, listOf("japan", "tokyo", "osaka")),
        Country("TR", "Turkey", 39.0f, 35.2f, listOf("turkey", "türkiye", "turkiye", "istanbul")),
        Country("AE", "United Arab Emirates", 24.0f, 54.0f, listOf("emirates", "dubai", "uae")),
        Country("IR", "Iran", 32.4f, 53.7f, listOf("iran", "tehran")),
        Country("CA", "Canada", 56.1f, -106.3f, listOf("canada", "toronto", "montreal", "vancouver")),
        Country("FI", "Finland", 61.9f, 25.7f, listOf("finland", "helsinki")),
        Country("SE", "Sweden", 60.1f, 18.6f, listOf("sweden", "stockholm")),
        Country("NO", "Norway", 60.5f, 8.5f, listOf("norway", "oslo")),
        Country("CH", "Switzerland", 46.8f, 8.2f, listOf("switzerland", "zurich")),
        Country("AT", "Austria", 47.5f, 14.5f, listOf("austria", "vienna")),
        Country("PL", "Poland", 51.9f, 19.1f, listOf("poland", "warsaw")),
        Country("IT", "Italy", 41.9f, 12.6f, listOf("italy", "milan", "rome")),
        Country("ES", "Spain", 40.5f, -3.7f, listOf("spain", "madrid")),
        Country("RU", "Russia", 55.7f, 37.6f, listOf("russia", "moscow")),
        Country("HK", "Hong Kong", 22.3f, 114.2f, listOf("hong kong", "hongkong")),
        Country("KR", "South Korea", 36.5f, 127.9f, listOf("korea", "seoul")),
        Country("IN", "India", 21.0f, 78.0f, listOf("india", "mumbai", "bangalore")),
        Country("AU", "Australia", -25.3f, 133.8f, listOf("australia", "sydney", "melbourne")),
        Country("BR", "Brazil", -14.2f, -51.9f, listOf("brazil", "sao paulo")),
        Country("AM", "Armenia", 40.1f, 45.0f, listOf("armenia", "yerevan")),
        Country("RO", "Romania", 45.9f, 25.0f, listOf("romania", "bucharest")),
        Country("LT", "Lithuania", 55.2f, 23.9f, listOf("lithuania", "vilnius")),
        Country("LV", "Latvia", 56.9f, 24.6f, listOf("latvia", "riga")),
        Country("EE", "Estonia", 58.6f, 25.0f, listOf("estonia", "tallinn")),
        Country("CZ", "Czechia", 49.8f, 15.5f, listOf("czech", "prague")),
        Country("BG", "Bulgaria", 42.7f, 25.5f, listOf("bulgaria", "sofia")),
        Country("UA", "Ukraine", 48.4f, 31.2f, listOf("ukraine", "kyiv", "kiev")),
        Country("IE", "Ireland", 53.4f, -8.2f, listOf("ireland", "dublin")),
        Country("TW", "Taiwan", 23.7f, 121.0f, listOf("taiwan", "taipei")),
        Country("QA", "Qatar", 25.3f, 51.2f, listOf("qatar", "doha")),
        Country("SA", "Saudi Arabia", 23.9f, 45.1f, listOf("saudi", "riyadh")),
        Country("KZ", "Kazakhstan", 48.0f, 66.9f, listOf("kazakhstan", "almaty")),
        Country("HU", "Hungary", 47.2f, 19.5f, listOf("hungary", "budapest")),
        Country("IL", "Israel", 31.0f, 34.9f, listOf("israel", "tel aviv")),
        Country("ZA", "South Africa", -30.6f, 22.9f, listOf("south africa", "johannesburg")),
        Country("MX", "Mexico", 23.6f, -102.5f, listOf("mexico")),
    )

    val unknown = Country("UN", "Unknown", 0f, 0f)
    private val byCode = all.associateBy { it.code }
    private val twoLetter = Regex("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])")

    /** Order used by the Servers screen. */
    val featured = listOf("DE", "US", "GB", "FR", "NL", "SG", "JP")

    fun get(code: String?): Country = byCode[code?.uppercase()] ?: unknown

    fun flag(code: String): String {
        if (code.length != 2 || code.equals("UN", true)) return "\uD83C\uDF10"
        val c = code.uppercase()
        val a = Character.toChars(0x1F1E6 + (c[0] - 'A'))
        val b = Character.toChars(0x1F1E6 + (c[1] - 'A'))
        return String(a) + String(b)
    }

    /** Detects the country from a config name (flag emoji, keywords or ISO code). */
    fun detect(vararg texts: String): String {
        for (t in texts) {
            val cps = t.codePoints().toArray()
            for (i in 0 until cps.size - 1) {
                val a = cps[i]; val b = cps[i + 1]
                if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                    val code = "${('A'.code + a - 0x1F1E6).toChar()}${('A'.code + b - 0x1F1E6).toChar()}"
                    if (byCode.containsKey(code)) return code
                }
            }
        }
        for (t in texts) {
            val lower = t.lowercase()
            all.firstOrNull { c -> c.keywords.any { lower.contains(it) } }?.let { return it.code }
        }
        for (t in texts) {
            twoLetter.findAll(t).forEach { m ->
                val code = m.groupValues[1]
                if (code == "UK") return "GB"
                if (byCode.containsKey(code)) return code
            }
        }
        return "UN"
    }
}
