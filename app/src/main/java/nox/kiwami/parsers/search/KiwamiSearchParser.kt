package nox.kiwami.parsers.search

class KiwamiSearchParser(private val tokens: List<KiwamiToken>) {
    private var index = 0

    fun parse(): KiwamiSearchNode {
        if (tokens.isEmpty()) return SearchEmptyNode
        val result = parseOr()
        return if (index < tokens.size) result else result
    }

    private fun parseOr(): KiwamiSearchNode {
        val nodes = mutableListOf<KiwamiSearchNode>()
        nodes.add(parseAnd())

        while (match(KiwamiToken.Or)) {
            nodes.add(parseAnd())
        }

        return if (nodes.size == 1) nodes[0] else SearchOrNode(nodes)
    }

    private fun parseAnd(): KiwamiSearchNode {
        val nodes = mutableListOf<KiwamiSearchNode>()
        nodes.add(parseTerm())

        while (index < tokens.size && peek() !is KiwamiToken.RParen && peek() !is KiwamiToken.Or) {
            match(KiwamiToken.And) // Optional explicit &&
            nodes.add(parseTerm())
        }

        return if (nodes.size == 1) nodes[0] else SearchAndNode(nodes)
    }

    private fun parseTerm(): KiwamiSearchNode {
        var negated = false
        if (match(KiwamiToken.Not)) {
            negated = true
        }

        val node = when (val current = next()) {
            is KiwamiToken.LParen -> {
                val subNode = parseOr()
                match(KiwamiToken.RParen)
                subNode
            }
            is KiwamiToken.FieldToken -> {
                SearchFieldNode(current.field, current.value, negated)
            }
            is KiwamiToken.CompToken -> {
                SearchCompNode(current.field, current.comparator, current.value, negated)
            }
            is KiwamiToken.TermToken -> {
                SearchGeneralNode(current.value, negated)
            }
            else -> SearchEmptyNode
        }

        return if (negated && node !is SearchFieldNode && node !is SearchCompNode && node !is SearchGeneralNode) {
            SearchNotNode(node)
        } else {
            node
        }
    }

    private fun peek(): KiwamiToken? = tokens.getOrNull(index)

    private fun next(): KiwamiToken? = tokens.getOrNull(index++)

    private fun match(expected: KiwamiToken): Boolean {
        if (peek() == expected) {
            index++
            return true
        }
        return false
    }
}
