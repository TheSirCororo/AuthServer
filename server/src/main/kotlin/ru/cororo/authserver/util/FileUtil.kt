package ru.cororo.authserver.util

import ru.cororo.authserver.logger
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64

fun getImageBase64(path: String): String {
    val filePath = Path.of(path)
    if (!filePath.toFile().exists()) {
        return ""
    }

    return filePath.toFile().inputStream().use { input ->
        val output = ByteArrayOutputStream()
        Base64.getEncoder().wrap(output).use { base64 ->
            input.transferTo(base64)
        }

        if (output.size() > 4096) {
            logger.error("Server icon cannot be >64x64px")
            ""
        } else {
            "data:image/${path.split(".").last()};base64," + output.toString(Charsets.UTF_8)
        }
    }
}