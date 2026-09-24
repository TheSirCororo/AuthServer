package ru.cororo.authserver.server.mail

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import org.junit.jupiter.api.extension.RegisterExtension
import ru.cororo.authserver.server.config.EmailConfig
import ru.cororo.authserver.server.config.MailSecurity
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtpMailSenderTest {
    @JvmField
    @RegisterExtension
    val mail = GreenMailExtension(ServerSetupTest.SMTP).withConfiguration(
        com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig().withUser("server@example.com", "smtp-user", "smtp-pass"),
    )

    @Test
    fun `sends a utf-8 message with authentication`() {
        val sender = SmtpMailSender(EmailConfig(
            enabled = true, host = "127.0.0.1", port = ServerSetupTest.SMTP.port, security = MailSecurity.NONE,
            username = "smtp-user", password = "smtp-pass", from = "server@example.com", fromName = "Сервер",
        ))
        sender.send("steve@example.com", "Код подтверждения", "Ваш код: 123456")
        val received = mail.receivedMessages.single()
        assertEquals("Код подтверждения", received.subject)
        assertEquals("steve@example.com", received.allRecipients.single().toString())
        assertEquals("Ваш код: 123456", received.content.toString().trim())
    }
}
