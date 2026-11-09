package com.github.kr328.clash.design.util

/**
 * Resolve a proxy node to a country purely from its display name.
 *
 * Per the product decision, the location shown on the home map is the
 * country's capital (e.g. any Japan node is shown as Tokyo). No IP
 * lookup is performed. When no country can be recognised from the
 * name, [resolve] returns null and the UI shows an unknown location
 * instead of guessing.
 */
data class NodeCountry(
    val displayName: String,
    val capital: String,
    val latitude: Double,
    val longitude: Double,
    val flag: String,
)

object NodeGeo {
    private data class Entry(
        val country: NodeCountry,
        val keywords: List<String>,
    )

    private fun entry(
        displayName: String,
        capital: String,
        lat: Double,
        lon: Double,
        flag: String,
        vararg keywords: String,
    ) = Entry(NodeCountry(displayName, capital, lat, lon, flag), keywords.toList())

    // Order matters: more specific entries must come before broader ones
    // (e.g. Hong Kong / Taiwan before China).
    private val entries = listOf(
        entry("香港", "Hong Kong", 22.32, 114.17, "🇭🇰", "香港", "hong kong", "hongkong", "hk"),
        entry("台湾", "Taipei", 25.03, 121.57, "🇹🇼", "台湾", "台灣", "taiwan", "taipei", "台北", "tw"),
        entry("日本", "Tokyo", 35.68, 139.69, "🇯🇵", "日本", "japan", "tokyo", "东京", "東京", "osaka", "大阪", "jp"),
        entry("韩国", "Seoul", 37.57, 126.98, "🇰🇷", "韩国", "韓國", "korea", "seoul", "首尔", "首爾", "kr"),
        entry("新加坡", "Singapore", 1.35, 103.82, "🇸🇬", "新加坡", "singapore", "sg"),
        entry("美国", "Washington", 38.90, -77.04, "🇺🇸", "美国", "美國", "america", "united states", "usa", "los angeles", "洛杉矶", "new york", "纽约", "san jose", "硅谷", "seattle", "西雅图", "chicago", "芝加哥", "dallas", "达拉斯", "us"),
        entry("加拿大", "Ottawa", 45.42, -75.70, "🇨🇦", "加拿大", "canada", "toronto", "多伦多", "vancouver", "温哥华", "ca"),
        entry("英国", "London", 51.51, -0.13, "🇬🇧", "英国", "英國", "united kingdom", "britain", "london", "伦敦", "uk", "gb"),
        entry("爱尔兰", "Dublin", 53.35, -6.26, "🇮🇪", "爱尔兰", "ireland", "dublin", "ie"),
        entry("法国", "Paris", 48.86, 2.35, "🇫🇷", "法国", "法國", "france", "paris", "巴黎", "fr"),
        entry("德国", "Berlin", 52.52, 13.40, "🇩🇪", "德国", "德國", "germany", "frankfurt", "法兰克福", "berlin", "柏林", "de"),
        entry("荷兰", "Amsterdam", 52.37, 4.90, "🇳🇱", "荷兰", "荷蘭", "netherlands", "amsterdam", "阿姆斯特丹", "nl"),
        entry("瑞士", "Bern", 46.95, 7.45, "🇨🇭", "瑞士", "switzerland", "zurich", "苏黎世", "ch"),
        entry("奥地利", "Vienna", 48.21, 16.37, "🇦🇹", "奥地利", "austria", "vienna", "维也纳", "at"),
        entry("比利时", "Brussels", 50.85, 4.35, "🇧🇪", "比利时", "belgium", "brussels", "be"),
        entry("意大利", "Rome", 41.90, 12.50, "🇮🇹", "意大利", "italy", "milan", "米兰", "rome", "罗马", "it"),
        entry("西班牙", "Madrid", 40.42, -3.70, "🇪🇸", "西班牙", "spain", "madrid", "马德里", "es"),
        entry("葡萄牙", "Lisbon", 38.72, -9.14, "🇵🇹", "葡萄牙", "portugal", "lisbon", "里斯本", "pt"),
        entry("希腊", "Athens", 37.98, 23.73, "🇬🇷", "希腊", "greece", "athens", "雅典", "gr"),
        entry("瑞典", "Stockholm", 59.33, 18.07, "🇸🇪", "瑞典", "sweden", "stockholm", "se"),
        entry("挪威", "Oslo", 59.91, 10.75, "🇳🇴", "挪威", "norway", "oslo", "no"),
        entry("芬兰", "Helsinki", 60.17, 24.94, "🇫🇮", "芬兰", "finland", "helsinki", "fi"),
        entry("丹麦", "Copenhagen", 55.68, 12.57, "🇩🇰", "丹麦", "denmark", "copenhagen", "dk"),
        entry("波兰", "Warsaw", 52.23, 21.01, "🇵🇱", "波兰", "poland", "warsaw", "华沙", "pl"),
        entry("捷克", "Prague", 50.08, 14.42, "🇨🇿", "捷克", "czech", "prague", "布拉格", "cz"),
        entry("匈牙利", "Budapest", 47.50, 19.04, "🇭🇺", "匈牙利", "hungary", "budapest", "hu"),
        entry("罗马尼亚", "Bucharest", 44.43, 26.10, "🇷🇴", "罗马尼亚", "romania", "bucharest", "ro"),
        entry("乌克兰", "Kyiv", 50.45, 30.52, "🇺🇦", "乌克兰", "ukraine", "kyiv", "kiev", "ua"),
        entry("俄罗斯", "Moscow", 55.76, 37.62, "🇷🇺", "俄罗斯", "俄羅斯", "russia", "moscow", "莫斯科", "ru"),
        entry("土耳其", "Ankara", 39.93, 32.86, "🇹🇷", "土耳其", "turkey", "türkiye", "istanbul", "伊斯坦布尔", "tr"),
        entry("以色列", "Jerusalem", 31.77, 35.21, "🇮🇱", "以色列", "israel", "il"),
        entry("阿联酋", "Abu Dhabi", 24.45, 54.37, "🇦🇪", "阿联酋", "迪拜", "dubai", "emirates", "uae", "ae"),
        entry("沙特", "Riyadh", 24.71, 46.68, "🇸🇦", "沙特", "saudi", "riyadh", "sa"),
        entry("印度", "New Delhi", 28.61, 77.21, "🇮🇳", "印度", "india", "mumbai", "孟买", "delhi", "in"),
        entry("巴基斯坦", "Islamabad", 33.73, 73.04, "🇵🇰", "巴基斯坦", "pakistan", "pk"),
        entry("孟加拉", "Dhaka", 23.81, 90.41, "🇧🇩", "孟加拉", "bangladesh", "bd"),
        entry("泰国", "Bangkok", 13.76, 100.50, "🇹🇭", "泰国", "泰國", "thailand", "bangkok", "曼谷", "th"),
        entry("越南", "Hanoi", 21.03, 105.85, "🇻🇳", "越南", "vietnam", "viet nam", "hanoi", "河内", "vn"),
        entry("马来西亚", "Kuala Lumpur", 3.14, 101.69, "🇲🇾", "马来西亚", "malaysia", "kuala lumpur", "吉隆坡", "my"),
        entry("印度尼西亚", "Jakarta", -6.21, 106.85, "🇮🇩", "印度尼西亚", "印尼", "indonesia", "jakarta", "雅加达", "id"),
        entry("菲律宾", "Manila", 14.60, 120.98, "🇵🇭", "菲律宾", "philippines", "manila", "马尼拉", "ph"),
        entry("哈萨克斯坦", "Astana", 51.17, 71.43, "🇰🇿", "哈萨克", "kazakhstan", "kz"),
        entry("澳大利亚", "Canberra", -35.28, 149.13, "🇦🇺", "澳大利亚", "澳洲", "australia", "sydney", "悉尼", "melbourne", "墨尔本", "au"),
        entry("新西兰", "Wellington", -41.29, 174.78, "🇳🇿", "新西兰", "new zealand", "nz"),
        entry("埃及", "Cairo", 30.04, 31.24, "🇪🇬", "埃及", "egypt", "cairo", "eg"),
        entry("南非", "Pretoria", -25.75, 28.19, "🇿🇦", "南非", "south africa", "za"),
        entry("尼日利亚", "Abuja", 9.06, 7.49, "🇳🇬", "尼日利亚", "nigeria", "ng"),
        entry("肯尼亚", "Nairobi", -1.29, 36.82, "🇰🇪", "肯尼亚", "kenya", "ke"),
        entry("墨西哥", "Mexico City", 19.43, -99.13, "🇲🇽", "墨西哥", "mexico", "mx"),
        entry("巴西", "Brasília", -15.79, -47.88, "🇧🇷", "巴西", "brazil", "brasil", "br"),
        entry("阿根廷", "Buenos Aires", -34.60, -58.38, "🇦🇷", "阿根廷", "argentina", "ar"),
        entry("智利", "Santiago", -33.45, -70.67, "🇨🇱", "智利", "chile", "cl"),
        entry("哥伦比亚", "Bogotá", 4.71, -74.07, "🇨🇴", "哥伦比亚", "colombia", "co"),
        entry("秘鲁", "Lima", -12.05, -77.04, "🇵🇪", "秘鲁", "peru", "pe"),
        entry("中国", "Beijing", 39.90, 116.40, "🇨🇳", "中国", "中國", "china", "beijing", "北京", "shanghai", "上海", "cn"),
    )

    fun resolve(nodeName: String?): NodeCountry? {
        if (nodeName.isNullOrBlank()) return null
        val name = nodeName.lowercase()
        for (e in entries) {
            for (kw in e.keywords) {
                if (kw.length == 2 && kw.all { it in 'a'..'z' }) {
                    // Two-letter codes must appear as a delimited token so
                    // that e.g. "us" does not match inside other words.
                    if (containsCodeToken(name, kw)) return e.country
                } else if (name.contains(kw)) {
                    return e.country
                }
            }
        }
        return null
    }

    private fun containsCodeToken(name: String, code: String): Boolean {
        var idx = name.indexOf(code)
        while (idx >= 0) {
            val before = if (idx == 0) null else name[idx - 1]
            val after = if (idx + code.length >= name.length) null else name[idx + code.length]
            val beforeOk = before == null || !(before in 'a'..'z')
            val afterOk = after == null || !(after in 'a'..'z')
            if (beforeOk && afterOk) return true
            idx = name.indexOf(code, idx + 1)
        }
        return false
    }
}
