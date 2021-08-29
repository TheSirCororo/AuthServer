package ru.cororo.authserver.player

data class GameProfile(
    val id: String,
    val name: String,
    val properties: ArrayList<Property>
) {
}

data class Property(
    val name: String,
    val value: String,
    val sign: String
)