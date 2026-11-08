package com.bytestreamdevelopment.dataapp.transfer

/** Eigene bzw. empfangene Visitenkarte. Reine JVM-Klasse (testbar). */
data class ContactCard(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val company: String = "",
    val website: String = "",
) {
    /** Ohne Name, Telefon und E-Mail gibt es nichts Sinnvolles auszutauschen. */
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank() && email.isBlank()

    val title: String get() = name.ifBlank { phone.ifBlank { email.ifBlank { "Unbekannter Kontakt" } } }

    /** Kurze Zusammenfassung der übrigen Felder für Listen. */
    val details: String
        get() = listOf(phone, email, company, website).filter { it.isNotBlank() && it != title }.joinToString(" · ")

    fun toMap(): Map<String, String> = linkedMapOf(
        KEY_NAME to name, KEY_PHONE to phone, KEY_EMAIL to email, KEY_COMPANY to company, KEY_WEBSITE to website,
    )

    companion object {
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
        const val KEY_EMAIL = "email"
        const val KEY_COMPANY = "company"
        const val KEY_WEBSITE = "website"

        /** Unbekannte Schlüssel werden ignoriert, damit spätere Versionen Felder ergänzen können. */
        fun fromMap(m: Map<String, String>) = ContactCard(
            name = m[KEY_NAME].orEmpty(),
            phone = m[KEY_PHONE].orEmpty(),
            email = m[KEY_EMAIL].orEmpty(),
            company = m[KEY_COMPANY].orEmpty(),
            website = m[KEY_WEBSITE].orEmpty(),
        )
    }
}
