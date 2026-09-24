package ru.cororo.authserver.protocol

/**
 * Every Java Edition release supported by the auth server, ordered from oldest to newest.
 *
 * Versions are [Comparable] by release order, so feature checks read as `version >= MINECRAFT_1_13`.
 *
 * @property protocol network protocol version number sent in the handshake
 * @property dataVersion world data version of the release, used for version-aware serialization
 * @property releases every release name that speaks this protocol
 */
enum class ProtocolVersion(val protocol: Int, val dataVersion: Int, vararg releases: String) {
    MINECRAFT_1_8(47, 100, "1.8", "1.8.1", "1.8.2", "1.8.3", "1.8.4", "1.8.5", "1.8.6", "1.8.7", "1.8.8", "1.8.9"),
    MINECRAFT_1_9(107, 169, "1.9"),
    MINECRAFT_1_9_1(108, 175, "1.9.1"),
    MINECRAFT_1_9_2(109, 176, "1.9.2"),
    MINECRAFT_1_9_4(110, 184, "1.9.3", "1.9.4"),
    MINECRAFT_1_10(210, 512, "1.10", "1.10.1", "1.10.2"),
    MINECRAFT_1_11(315, 819, "1.11"),
    MINECRAFT_1_11_1(316, 922, "1.11.1", "1.11.2"),
    MINECRAFT_1_12(335, 1139, "1.12"),
    MINECRAFT_1_12_1(338, 1241, "1.12.1"),
    MINECRAFT_1_12_2(340, 1343, "1.12.2"),
    MINECRAFT_1_13(393, 1519, "1.13"),
    MINECRAFT_1_13_1(401, 1628, "1.13.1"),
    MINECRAFT_1_13_2(404, 1631, "1.13.2"),
    MINECRAFT_1_14(477, 1952, "1.14"),
    MINECRAFT_1_14_1(480, 1957, "1.14.1"),
    MINECRAFT_1_14_2(485, 1963, "1.14.2"),
    MINECRAFT_1_14_3(490, 1968, "1.14.3"),
    MINECRAFT_1_14_4(498, 1976, "1.14.4"),
    MINECRAFT_1_15(573, 2225, "1.15"),
    MINECRAFT_1_15_1(575, 2227, "1.15.1"),
    MINECRAFT_1_15_2(578, 2230, "1.15.2"),
    MINECRAFT_1_16(735, 2566, "1.16"),
    MINECRAFT_1_16_1(736, 2567, "1.16.1"),
    MINECRAFT_1_16_2(751, 2578, "1.16.2"),
    MINECRAFT_1_16_3(753, 2580, "1.16.3"),
    MINECRAFT_1_16_4(754, 2586, "1.16.4", "1.16.5"),
    MINECRAFT_1_17(755, 2724, "1.17"),
    MINECRAFT_1_17_1(756, 2730, "1.17.1"),
    MINECRAFT_1_18(757, 2865, "1.18", "1.18.1"),
    MINECRAFT_1_18_2(758, 2975, "1.18.2"),
    MINECRAFT_1_19(759, 3105, "1.19"),
    MINECRAFT_1_19_1(760, 3120, "1.19.1", "1.19.2"),
    MINECRAFT_1_19_3(761, 3218, "1.19.3"),
    MINECRAFT_1_19_4(762, 3337, "1.19.4"),
    MINECRAFT_1_20(763, 3465, "1.20", "1.20.1"),
    MINECRAFT_1_20_2(764, 3578, "1.20.2"),
    MINECRAFT_1_20_3(765, 3700, "1.20.3", "1.20.4"),
    MINECRAFT_1_20_5(766, 3839, "1.20.5", "1.20.6"),
    MINECRAFT_1_21(767, 3955, "1.21", "1.21.1"),
    MINECRAFT_1_21_2(768, 4082, "1.21.2", "1.21.3"),
    MINECRAFT_1_21_4(769, 4189, "1.21.4"),
    MINECRAFT_1_21_5(770, 4325, "1.21.5"),
    MINECRAFT_1_21_6(771, 4435, "1.21.6"),
    MINECRAFT_1_21_7(772, 4440, "1.21.7", "1.21.8"),
    MINECRAFT_1_21_9(773, 4556, "1.21.9", "1.21.10"),
    MINECRAFT_1_21_11(774, 4671, "1.21.11"),
    MINECRAFT_26_1(775, 4790, "26.1", "26.1.1", "26.1.2"),
    MINECRAFT_26_2(776, 4903, "26.2"),
    MINECRAFT_26_3(777, 5023, "26.3");

    val releases: List<String> = releases.toList()

    /** Human-readable name such as `1.8-1.8.9` or `26.2`. */
    val displayName: String = if (releases.size == 1) releases[0] else "${releases.first()}-${releases.last()}"

    override fun toString(): String = displayName

    companion object {
        private val byProtocol = entries.associateBy { it.protocol }

        val OLDEST: ProtocolVersion = entries.first()
        val LATEST: ProtocolVersion = entries.last()

        /** Returns the version speaking [protocol], or `null` when it is unsupported. */
        fun byProtocol(protocol: Int): ProtocolVersion? = byProtocol[protocol]

        /** Returns the version whose [releases] contain [release], e.g. `1.20.4`. */
        fun byRelease(release: String): ProtocolVersion? = entries.firstOrNull { release in it.releases }
    }
}
