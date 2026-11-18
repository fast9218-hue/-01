package com.sko.safetyphotoexcel

import android.content.Context

data class MailConfig(
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val useSsl: Boolean = false,
    val senderEmail: String = "",
    val appPassword: String = "",
    val recipientEmail: String = ""
) {
    val isConfigured: Boolean
        get() = smtpHost.isNotBlank() &&
            senderEmail.isNotBlank() &&
            appPassword.isNotBlank() &&
            recipientEmail.isNotBlank()
}

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("safety_photo_excel_prefs", Context.MODE_PRIVATE)

    fun saveMailConfig(config: MailConfig) {
        prefs.edit()
            .putString("smtp_host", config.smtpHost.trim())
            .putInt("smtp_port", config.smtpPort)
            .putBoolean("use_ssl", config.useSsl)
            .putString("sender_email", config.senderEmail.trim())
            .putString("app_password", config.appPassword.trim())
            .putString("recipient_email", config.recipientEmail.trim())
            .apply()
    }

    fun loadMailConfig(): MailConfig {
        return MailConfig(
            smtpHost = prefs.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com",
            smtpPort = prefs.getInt("smtp_port", 587),
            useSsl = prefs.getBoolean("use_ssl", false),
            senderEmail = prefs.getString("sender_email", "") ?: "",
            appPassword = prefs.getString("app_password", "") ?: "",
            recipientEmail = prefs.getString("recipient_email", "") ?: ""
        )
    }

    fun saveDocDefaults(title: String, teamName: String, captionPrefix: String) {
        prefs.edit()
            .putString("doc_title_v2", title)
            .putString("team_name", teamName)
            .putString("caption_prefix", captionPrefix)
            .apply()
    }

    fun loadDocTitle(): String =
        prefs.getString("doc_title_v2", "엑셀 사진 보고서") ?: "엑셀 사진 보고서"

    fun loadTeamName(): String =
        prefs.getString("team_name", "유니트검증팀") ?: "유니트검증팀"

    fun loadCaptionPrefix(): String =
        prefs.getString("caption_prefix", "현장 사진") ?: "현장 사진"

    fun saveTemplateId(id: String) {
        prefs.edit().putString("template_id", id).apply()
    }

    fun loadTemplateId(): String =
        prefs.getString("template_id", "vertical_2") ?: "vertical_2"

    fun saveCustomTemplateName(name: String?) {
        if (name == null) {
            prefs.edit().remove("custom_template_name").apply()
        } else {
            prefs.edit().putString("custom_template_name", name).apply()
        }
    }

    fun loadCustomTemplateName(): String? =
        prefs.getString("custom_template_name", null)

    fun saveWatermarkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("watermark_enabled", enabled).apply()
    }

    fun loadWatermarkEnabled(): Boolean =
        prefs.getBoolean("watermark_enabled", false)

    fun saveMaxDimensionPx(px: Int) {
        prefs.edit().putInt("max_dim_px", px).apply()
    }

    fun loadMaxDimensionPx(): Int =
        prefs.getInt("max_dim_px", 1600)
}
