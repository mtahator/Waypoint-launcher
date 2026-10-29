package app.olauncher.features.space.domain

/**
 * Space categories and an on-phone classifier (keyword scores, English + Persian). The AI analysis can
 * override it when enabled; this keeps categorisation working offline and with any provider.
 */
object Categories {
    const val OTHER = "Other"
    val ALL = listOf("Work", "Personal", "Ideas", "Shopping", "Health", "Finance", "Travel", "Learning", "Home", OTHER)

    private val KEYWORDS: Map<String, List<String>> = mapOf(
        "Work" to listOf("meeting", "client", "project", "deadline", "boss", "office", "email", "report", "presentation", "team",
            "manager", "colleague", "invoice", "contract", "sprint", "jira", "slack", "interview", "جلسه", "پروژه", "کار", "مشتری", "شرکت", "گزارش"),
        "Shopping" to listOf("buy", "shop", "shopping", "groceries", "grocery", "milk", "bread", "eggs", "store", "order", "amazon",
            "supermarket", "list", "خرید", "بخرم", "فروشگاه", "شیر", "نان"),
        "Health" to listOf("doctor", "dentist", "medicine", "pill", "gym", "workout", "exercise", "run", "sleep", "diet", "calories",
            "hospital", "appointment", "health", "weight", "دکتر", "دارو", "ورزش", "باشگاه", "سلامت", "خواب"),
        "Finance" to listOf("pay", "payment", "bill", "rent", "bank", "salary", "budget", "money", "tax", "loan", "transfer", "card",
            "invoice", "price", "£", "$", "€", "پرداخت", "قبض", "اجاره", "بانک", "حقوق", "پول", "قسط"),
        "Travel" to listOf("flight", "hotel", "trip", "travel", "airport", "train", "ticket", "passport", "visa", "booking", "holiday",
            "vacation", "سفر", "پرواز", "هتل", "بلیط", "فرودگاه", "ویزا"),
        "Learning" to listOf("learn", "study", "course", "book", "read", "lecture", "exam", "homework", "university", "class",
            "tutorial", "research", "paper", "یاد", "درس", "کتاب", "دانشگاه", "امتحان", "کلاس"),
        "Ideas" to listOf("idea", "maybe", "what if", "concept", "brainstorm", "startup", "app", "design", "invent", "plan to build",
            "ایده", "فکر", "طرح"),
        "Home" to listOf("clean", "laundry", "kitchen", "repair", "fix", "plumber", "garden", "furniture", "home", "house", "dishes",
            "خانه", "تمیز", "تعمیر", "آشپزخانه"),
        "Personal" to listOf("mom", "dad", "mum", "family", "friend", "birthday", "wife", "husband", "kids", "call", "party", "gift",
            "anniversary", "مامان", "بابا", "خانواده", "دوست", "تولد", "زنگ"),
    )

    fun categorize(text: String): String {
        val t = " " + text.lowercase() + " "
        if (t.isBlank()) return OTHER
        val scores = KEYWORDS.mapValues { (_, words) ->
            words.sumOf { w -> if (w.any { it.code > 0x600 } || w.length <= 2) countPlain(t, w) else countWord(t, w) }
        }
        val best = scores.maxByOrNull { it.value } ?: return OTHER
        return if (best.value > 0) best.key else OTHER
    }

    private fun countWord(t: String, w: String) = Regex("\\b" + Regex.escape(w) + "\\b").findAll(t).count()
    private fun countPlain(t: String, w: String): Int { var n = 0; var i = t.indexOf(w); while (i >= 0) { n++; i = t.indexOf(w, i + w.length) }; return n }

    /** Normalises a category name from the AI to one of [ALL]. */
    fun normalize(name: String?): String? = ALL.firstOrNull { it.equals(name?.trim(), true) }
}
