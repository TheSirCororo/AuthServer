package ru.cororo.authserver.server.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import ru.cororo.authserver.server.config.EmailConfig
import ru.cororo.authserver.server.config.MailSecurity
import java.util.Date
import java.util.Properties

/** Sends plain-text emails. Blocking: call it from an I/O thread. */
fun interface MailSender {
    /** @throws Exception when the message could not be handed to the mail server */
    fun send(to: String, subject: String, text: String)
}

/** SMTP through Jakarta Mail, configured by the `email` section. */
class SmtpMailSender(private val config: EmailConfig) : MailSender {
    private val session = Session.getInstance(Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", config.host)
        put("mail.smtp.port", config.port.toString())
        put("mail.smtp.auth", config.username.isNotEmpty().toString())
        put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS)
        put("mail.smtp.timeout", TIMEOUT_MILLIS)
        put("mail.smtp.writetimeout", TIMEOUT_MILLIS)
        when (config.security) {
            MailSecurity.NONE -> Unit
            MailSecurity.STARTTLS -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }
            MailSecurity.SSL -> {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }
        }
    })

    override fun send(to: String, subject: String, text: String) {
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.from, config.fromName, Charsets.UTF_8.name()))
            setRecipient(Message.RecipientType.TO, InternetAddress(to, true))
            setSubject(subject, Charsets.UTF_8.name())
            setText(text, Charsets.UTF_8.name())
            sentDate = Date()
        }
        if (config.username.isEmpty()) Transport.send(message) else Transport.send(message, config.username, config.password)
    }

    private companion object {
        const val TIMEOUT_MILLIS = "10000"
    }
}
