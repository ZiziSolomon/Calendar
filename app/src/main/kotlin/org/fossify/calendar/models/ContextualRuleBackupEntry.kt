package org.fossify.calendar.models

/**
 * One rule as written to the settings export file (see ContextualRulesBackup). Lives in models/
 * because proguard keeps this package, and Gson needs the field names unobfuscated.
 */
data class ContextualRuleBackupEntry(
    val matchType: Int,
    val pattern: String = "",
    val enabled: Boolean = true,
    val calendarName: String? = null,
    val caldavCalendarId: Int? = null,
    val importId: String? = null,
    // Phase 16; absent in older exports, which Gson leaves null
    val keyName: String? = null,
    val keyColor: Int? = null,
)
