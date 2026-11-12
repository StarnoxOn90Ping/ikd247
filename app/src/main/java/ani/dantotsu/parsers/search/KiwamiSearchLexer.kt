package ani.dantotsu.parsers.search

sealed interface KiwamiToken {
    object LParen : KiwamiToken
    object RParen : KiwamiToken
    object Not : KiwamiToken
    object Or : KiwamiToken
    object And : KiwamiToken

    data class CompToken(
        val field: String,
        val comparator: String,
        val value: String
    ) : KiwamiToken

    data class FieldToken(
        val field: String,
        val value: String
    ) : KiwamiToken

    data class TermToken(
        val value: String
    ) : KiwamiToken
}

object KiwamiSearchLexer {
    private val regex = Regex(
        """
            (?<LParen> \( )|
            (?<RParen> \) )|
            (?<NOT> -(?![\s,]) )|
            (?<OR> \|\| )|
            (?<AND> && )|
            (?<CompField> [a-zA-Z_][a-zA-Z0-9_]* )(?<Comparator> >=|<=|>|<|= )(?: " (?<CompValQuoted> [^"]* ) " | ' (?<CompValSingleQuoted> [^']* ) ' | (?<CompVal> [^\s,()]+ ))|
            (?<Field> [a-zA-Z_][a-zA-Z0-9_]* ) : (?: " (?<FieldValQuoted> [^"]* ) " | ' (?<FieldValSingleQuoted> [^']* ) ' | (?<FieldVal> [^\s,()]+ ))|
            (?: " (?<GeneralQuoted> [^"]* ) " | ' (?<GeneralSingleQuoted> [^']* ) ' | (?<General> [^\s,()]+ ))
        """.trimIndent(),
        RegexOption.COMMENTS
    )

    fun tokenize(query: String): List<KiwamiToken> {
        val tokens = mutableListOf<KiwamiToken>()
        for (match in regex.findAll(query)) {
            val groups = match.groups
            when {
                groups["LParen"] != null -> tokens.add(KiwamiToken.LParen)
                groups["RParen"] != null -> tokens.add(KiwamiToken.RParen)
                groups["NOT"] != null -> tokens.add(KiwamiToken.Not)
                groups["OR"] != null -> tokens.add(KiwamiToken.Or)
                groups["AND"] != null -> tokens.add(KiwamiToken.And)
                groups["CompField"] != null -> {
                    val field = groups["CompField"]!!.value
                    val comp = groups["Comparator"]!!.value
                    val valStr = groups["CompValQuoted"]?.value
                        ?: groups["CompValSingleQuoted"]?.value
                        ?: groups["CompVal"]?.value
                        ?: ""
                    tokens.add(KiwamiToken.CompToken(field, comp, valStr))
                }
                groups["Field"] != null -> {
                    val field = groups["Field"]!!.value
                    val valStr = groups["FieldValQuoted"]?.value
                        ?: groups["FieldValSingleQuoted"]?.value
                        ?: groups["FieldVal"]?.value
                        ?: ""
                    tokens.add(KiwamiToken.FieldToken(field, valStr))
                }
                else -> {
                    val valStr = groups["GeneralQuoted"]?.value
                        ?: groups["GeneralSingleQuoted"]?.value
                        ?: groups["General"]?.value
                        ?: ""
                    if (valStr.isNotEmpty()) {
                        tokens.add(KiwamiToken.TermToken(valStr))
                    }
                }
            }
        }
        return tokens
    }
}
