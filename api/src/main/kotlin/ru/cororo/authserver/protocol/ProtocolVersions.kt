package ru.cororo.authserver.protocol

/**
 * Here you can find all supported protocol versions (from 1.12 to 1.18.2)
 */
object ProtocolVersions {
    val v1_12 = ProtocolVersion(335, listOf("1.12"))
    val v1_12_1 = ProtocolVersion(338, listOf("1.12.1"))
    val v1_12_2 = ProtocolVersion(340, listOf("1.12.2"))
    val v1_13 = ProtocolVersion(393, listOf("1.13"))
    val v1_13_1 = ProtocolVersion(401, listOf("1.13.1"))
    val v1_13_2 = ProtocolVersion(404, listOf("1.13.2"))
    val v1_14 = ProtocolVersion(477, listOf("1.14"))
    val v1_14_1 = ProtocolVersion(480, listOf("1.14.1"))
    val v1_14_2 = ProtocolVersion(485, listOf("1.14.2"))
    val v1_14_3 = ProtocolVersion(490, listOf("1.14.3"))
    val v1_14_4 = ProtocolVersion(498, listOf("1.14.4"))
    val v1_15 = ProtocolVersion(573, listOf("1.15"))
    val v1_15_1 = ProtocolVersion(575, listOf("1.15.1"))
    val v1_15_2 = ProtocolVersion(578, listOf("1.15.2"))
    val v1_16 = ProtocolVersion(735, listOf("1.16")) // why mojang do 1.16 snapshots from 701 version?..
    val v1_16_1 = ProtocolVersion(736, listOf("1.16.1")) // 737 doesn't exists and 738-750 is pre-releases and releases-candidates for 1.16.2
    val v1_16_2 = ProtocolVersion(751, listOf("1.16.2")) // 752 is 1.16.2-rc1
    val v1_16_3 = ProtocolVersion(753, listOf("1.16.3"))
    val v1_16_4 = ProtocolVersion(754, listOf("1.16.4", "1.16.5"))
    val v1_17 = ProtocolVersion(755, listOf("1.17"))
    val v1_17_1 = ProtocolVersion(756, listOf("1.17.1"))
    val v1_18 = ProtocolVersion(757, listOf("1.18", "1.18.1"))
    val v1_18_2 = ProtocolVersion(758, listOf("1.18.2"))

    /**
     * All default supported protocol versions in list
     */
    val defaults = listOf(
        v1_12,
        v1_12_1,
        v1_12_2,
        v1_13,
        v1_13_1,
        v1_13_2,
        v1_14,
        v1_14_1,
        v1_14_2,
        v1_14_3,
        v1_14_4,
        v1_15,
        v1_15_1,
        v1_15_2,
        v1_16,
        v1_16_1,
        v1_16_2,
        v1_16_3,
        v1_16_4,
        v1_17,
        v1_17_1,
        v1_18,
        v1_18_2
    )

    /**
     * Default protocol version
     */
    val default = defaults.last()

    /**
     * Get protocol version by [ProtocolVersion.raw]
     */
    fun getByRaw(raw: Int) = defaults.find { it.raw == raw }
}