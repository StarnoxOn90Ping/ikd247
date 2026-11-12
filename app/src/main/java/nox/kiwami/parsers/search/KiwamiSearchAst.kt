package nox.kiwami.parsers.search

sealed interface KiwamiSearchNode {
    companion object {
        fun from(query: String): KiwamiSearchNode {
            val tokens = KiwamiSearchLexer.tokenize(query)
            return KiwamiSearchParser(tokens).parse()
        }
    }
}

data class SearchAndNode(val children: List<KiwamiSearchNode>) : KiwamiSearchNode
data class SearchOrNode(val children: List<KiwamiSearchNode>) : KiwamiSearchNode
data class SearchNotNode(val child: KiwamiSearchNode) : KiwamiSearchNode
object SearchEmptyNode : KiwamiSearchNode

data class SearchGeneralNode(val value: String, val negated: Boolean) : KiwamiSearchNode

data class SearchFieldNode(
    val field: String,
    val value: String,
    val negated: Boolean
) : KiwamiSearchNode

data class SearchCompNode(
    val field: String,
    val comparator: String,
    val value: String,
    val negated: Boolean
) : KiwamiSearchNode
