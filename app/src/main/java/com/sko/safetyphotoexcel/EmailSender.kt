package com.sko.safetyphotoexcel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility

object EmailSender {

    suspend fun sendExcelAttachment(
        config: MailConfig,
        subject: String,
        bodyText: String,
        excelFile: File
    ) = withContext(Dispatchers.IO) {
        require(config.isConfigured) { "메일 설정이 완료되지 않았습니다." }

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            if (config.useSsl) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.port", config.smtpPort.toString())
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            } else {
                put("mail.smtp.starttls.enable", "true")
            }
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(config.senderEmail, config.appPassword)
            }
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(config.senderEmail))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.recipientEmail))
            setSubject(subject, "UTF-8")

            val textPart = MimeBodyPart().apply {
                setText(bodyText, "UTF-8")
            }
            val attachmentPart = MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(excelFile))
                fileName = MimeUtility.encodeText(excelFile.name, "UTF-8", null)
            }
            val multipart = MimeMultipart().apply {
                addBodyPart(textPart)
                addBodyPart(attachmentPart)
            }
            setContent(multipart)
        }

        Transport.send(message)
    }
}
