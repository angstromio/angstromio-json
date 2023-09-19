package angstromio.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.databind.node.ValueNode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string

internal object JSONGenerator {
    private val jsonNodeFactory: JsonNodeFactory = JsonNodeFactory(false)

    /** Generate either a ArrayNode or a ObjectNode */
    fun jsonNode(depth: Int): Arb<JsonNode> = Arb.choice(arrayNode(depth), objectNode(depth))

    /** Generate a ArrayNode */
    fun arrayNode(depth: Int): Arb<ArrayNode> = Arb.int(1, 4).flatMap { n ->
        values(n, depth).map { values ->
            ArrayNode(jsonNodeFactory).addAll(values)
        }
    }

    /** Generate a ObjectNode */
    fun objectNode(depth: Int): Arb<ObjectNode> = Arb.int(1, 4).flatMap { n ->
        keys(n).flatMap { keys ->
            values(n, depth).map { values ->
                val kids: Map<String, JsonNode> = keys.zip(values).associate { it.first to it.second }
                ObjectNode(jsonNodeFactory, kids)
            }
        }
    }

    /** Generate a list of keys to be used in the map of a ObjectNode */
    fun keys(n: Int): Arb<List<String>> =
        Arb.list(
            gen = Arb.string().filterNot { it.isEmpty() }.
            map { it.lowercase() }
                .map { randomString ->
                    if (randomString.length > 4) randomString.substring(0, 3) else randomString
                },
            range = 1..n)

    /**
     * Generate a list of values to be used in the map of a ObjectNode or in the list of an ArrayNode.
     */
    fun values(n: Int, depth: Int): Arb<List<JsonNode>> = Arb.list(value(depth), 1..n)

    /**
     * Generate a value to be used in the map of a ObjectNode or in the list of an ArrayNode.
     */
    fun value(depth: Int): Arb<JsonNode> =
        if (depth == 0) terminalNode()
        else Arb.choice(jsonNode(depth - 1), terminalNode())

    /** Generate a terminal node */
    private fun terminalNode(): Arb<ValueNode> =
        Arb.of(
            IntNode(4),
            IntNode(2),
            TextNode("b"),
            TextNode("i"),
            TextNode("r"),
            TextNode("d")
        )
}