package net.jbglenn.telltype.core.model.settings

/**
 * Which hosts count as private, the only ones plain HTTP may go to (D60): RFC 1918 (10/8, 172.16/12, 192.168/16),
 * loopback (127/8, `::1`, `localhost`), link-local (169.254/16, fe80::/10), the shared address space Tailscale uses
 * (100.64.0.0/10, RFC 6598), IPv6 unique local addresses (fc00::/7), IPv4-mapped IPv6 forms of those, and
 * multicast-DNS names ending in `.local`. Any other host name counts as public, because nothing proves where it
 * resolves; `:network` also refuses a plain HTTP connection whose resolved address is not private
 * ([isPrivateAddress]). Pure.
 */
public object PrivateAddresses {
    /** Whether [host] (an IP literal without brackets, or a name) is private. */
    public fun isPrivateHost(host: String): Boolean {
        val name = host.trim().lowercase().removeSuffix(".")
        val privateName = name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")
        return privateName || (parseIpv4(name) ?: parseIpv6(name))?.let(::isPrivateAddress) == true
    }

    /** Whether the raw address [bytes] (4 for IPv4, 16 for IPv6) is private. */
    public fun isPrivateAddress(bytes: ByteArray): Boolean {
        val values = bytes.map { it.toInt() and Ranges.BYTE_MASK }
        val ranges = when (values.size) {
            Ranges.IPV4_BYTES -> Ranges.V4
            Ranges.IPV6_BYTES -> Ranges.V6
            else -> emptyList()
        }
        return ranges.any { it.contains(values) }
    }

    private fun parseIpv4(text: String): ByteArray? {
        val values = text.split('.').map { part ->
            part.takeIf { it.length in 1..Ranges.MAX_V4_DIGITS && it.all(Char::isDigit) }?.toInt()
                ?.takeIf { it <= Ranges.BYTE_MASK }
        }
        if (values.size != Ranges.IPV4_BYTES || values.any { it == null }) return null
        return values.filterNotNull().map(Int::toByte).toByteArray()
    }

    private fun parseIpv6(literal: String): ByteArray? {
        val text = if (literal.count { it == ':' } >= 2) embeddedIpv4AsGroups(literal) else null
        return text?.let(::allGroups)
            ?.flatMap { listOf(it shr Byte.SIZE_BITS, it and Ranges.BYTE_MASK) }
            ?.map(Int::toByte)
            ?.toByteArray()
    }

    /** The eight groups of an IPv6 literal, `::` expanded; null when it is not one. */
    private fun allGroups(text: String): List<Int>? {
        val compressed = "::" in text
        val head = groups(if (compressed) text.substringBefore("::") else text)
        val tail = if (compressed) groups(text.substringAfter("::")) else emptyList()
        if (head == null || tail == null) return null
        val missing = Ranges.IPV6_GROUPS - head.size - tail.size
        val fits = if (compressed) missing >= 0 else missing == 0
        return if (fits) head + List(missing) { 0 } + tail else null
    }

    /** `::ffff:192.168.1.20` as `::ffff:c0a8:114`; text without a dotted tail unchanged; null when the tail is bad. */
    private fun embeddedIpv4AsGroups(text: String): String? {
        val tail = text.substringAfterLast(':')
        if ('.' !in tail) return text
        return parseIpv4(tail)
            ?.map { it.toInt() and Ranges.BYTE_MASK }
            ?.chunked(2)
            ?.joinToString(":") { (high, low) -> ((high shl Byte.SIZE_BITS) or low).toString(Ranges.HEX) }
            ?.let { text.substringBeforeLast(':') + ":" + it }
    }

    private fun groups(text: String): List<Int>? = if (text.isEmpty()) {
        emptyList()
    } else {
        text.split(':').map { group ->
            group.takeIf { it.length in 1..Ranges.MAX_V6_DIGITS && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?.toInt(radix = Ranges.HEX)
        }.takeIf { all -> all.none { it == null } }?.filterNotNull()
    }

    /** An address range: the first [bits] bits equal those of [prefix]. */
    private class Cidr(private val prefix: List<Int>, private val bits: Int) {
        fun contains(address: List<Int>): Boolean = address.size == prefix.size &&
            (0 until bits).all { bit -> address.bit(bit) == prefix.bit(bit) }

        private fun List<Int>.bit(index: Int): Int =
            (this[index / Byte.SIZE_BITS] shr (Byte.SIZE_BITS - 1 - index % Byte.SIZE_BITS)) and 1
    }

    /** The private ranges, as listed in the KDoc of [PrivateAddresses]. */
    private object Ranges {
        const val BYTE_MASK = 0xff
        const val IPV4_BYTES = 4
        const val IPV6_BYTES = 16
        const val IPV6_GROUPS = 8
        const val MAX_V4_DIGITS = 3
        const val MAX_V6_DIGITS = 4
        const val HEX = 16

        private val mapped = List(10) { 0 } + listOf(0xff, 0xff)

        val V4: List<Cidr> = listOf(
            Cidr(listOf(10, 0, 0, 0), 8),
            Cidr(listOf(127, 0, 0, 0), 8),
            Cidr(listOf(172, 16, 0, 0), 12),
            Cidr(listOf(192, 168, 0, 0), 16),
            Cidr(listOf(169, 254, 0, 0), 16),
            Cidr(listOf(100, 64, 0, 0), 10),
        )

        val V6: List<Cidr> = listOf(
            Cidr(List(15) { 0 } + 1, 128),
            Cidr(listOf(0xfc) + List(15) { 0 }, 7),
            Cidr(listOf(0xfe, 0x80) + List(14) { 0 }, 10),
            Cidr(mapped + listOf(10, 0, 0, 0), 104),
            Cidr(mapped + listOf(127, 0, 0, 0), 104),
            Cidr(mapped + listOf(172, 16, 0, 0), 108),
            Cidr(mapped + listOf(192, 168, 0, 0), 112),
            Cidr(mapped + listOf(169, 254, 0, 0), 112),
            Cidr(mapped + listOf(100, 64, 0, 0), 106),
        )
    }
}
