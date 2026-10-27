package com.jdandroid

import com.jdandroid.hoster.AccountType
import com.jdandroid.hoster.HosterRegistry
import com.jdandroid.ui.AccountForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules of the "add account" dialog: which input enables "Save & check". */
class AccountFormTest {

    @Test
    fun benutzerUndPasswortBeideNoetig() {
        val typ = AccountType.USERNAME_PASSWORD
        assertFalse(AccountForm.canSave(typ, "", "", ""))
        assertFalse(AccountForm.canSave(typ, "user", " ", ""))
        assertFalse(AccountForm.canSave(typ, "", "pw", "key"))
        assertTrue(AccountForm.canSave(typ, "user", "pw", ""))
    }

    @Test
    fun apiKeyAllein() {
        assertFalse(AccountForm.canSave(AccountType.API_KEY, "user", "pw", ""))
        assertTrue(AccountForm.canSave(AccountType.API_KEY, "", "", "key"))
    }

    @Test
    fun browserLoginNieUeberSpeichern() {
        assertFalse(AccountForm.canSave(AccountType.BROWSER_LOGIN, "user", "pw", "key"))
    }

    @Test
    fun ddownloadIstBrowserLoginMitLoginSeite() {
        val ddl = HosterRegistry.byId("ddownload")!!
        assertEquals(AccountType.BROWSER_LOGIN, ddl.accountType)
        assertNotNull(ddl.webLoginUrl)
    }
}
