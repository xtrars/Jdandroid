package com.jdandroid.ui

import com.jdandroid.hoster.AccountType

/** Input rules of the "add account" dialog, kept free of Compose so they are unit-testable. */
internal object AccountForm {
    /**
     * True when "Save & check" makes sense: every field of the login type is
     * filled. A browser login has no fields; its account is created from the
     * browser session.
     */
    fun canSave(type: AccountType, username: String, password: String, apiKey: String): Boolean = when (type) {
        AccountType.USERNAME_PASSWORD -> username.isNotBlank() && password.isNotBlank()
        AccountType.API_KEY -> apiKey.isNotBlank()
        AccountType.BROWSER_LOGIN -> false
    }
}
