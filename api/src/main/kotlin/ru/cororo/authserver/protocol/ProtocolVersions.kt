package ru.cororo.authserver.protocol

/**
 * Here you can find all supported protocol versions (from 1.12 to 1.18.2)
 */
object ProtocolVersions {
    private val versions = mutableListOf<ProtocolVersion>()

    val v1_12 = register(335, listOf("1.12"))
    val v1_12_1 = register(338, listOf("1.12.1"))
    val v1_12_2 = register(340, listOf("1.12.2"))
    val v1_13 = register(393, listOf("1.13"))
    val v1_13_1 = register(401, listOf("1.13.1"))
    val v1_13_2 = register(404, listOf("1.13.2"))
    val v1_14 = register(477, listOf("1.14"))
    val v1_14_1 = register(480, listOf("1.14.1"))
    val v1_14_2 = register(485, listOf("1.14.2"))
    val v1_14_3 = register(490, listOf("1.14.3"))
    val v1_14_4 = register(498, listOf("1.14.4"))
    val v1_15 = register(573, listOf("1.15"))
    val v1_15_1 = register(575, listOf("1.15.1"))
    val v1_15_2 = register(578, listOf("1.15.2"))
    val v1_16 = register(735, listOf("1.16")) // why mojang do 1.16 snapshots from 701 version?..
    val v1_16_1 = register(736, listOf("1.16.1")) // 737 doesn't exists and 738-750 is pre-releases and releases-candidates for 1.16.2
    val v1_16_2 = register(751, listOf("1.16.2")) // 752 is 1.16.2-rc1
    val v1_16_3 = register(753, listOf("1.16.3"))
    val v1_16_4 = register(754, listOf("1.16.4", "1.16.5"))
    val v1_17 = register(755, listOf("1.17"))
    val v1_17_1 = register(756, listOf("1.17.1"))
    val v1_18 = register(757, listOf("1.18", "1.18.1"))
    val v1_18_2 = register(758, listOf("1.18.2"))
    val v1_19 = register(759, listOf("1.19", "1.19.1"))
    val v1_19_2 = register(760, listOf("1.19.2"))

    /**
     * All default supported protocol versions in list
     */
    val defaults get() = versions.toList()

    /**
     * Default protocol version
     */
    val default = defaults.last()

    /**
     * Get protocol version by [ProtocolVersion.raw]
     */
    fun getByRaw(raw: Int) = defaults.find { it.raw == raw }

    private fun register(raw: Int, display: List<String>): ProtocolVersion {
        val version = ProtocolVersion(raw, display)
        versions.add(version)
        return version
    }
}