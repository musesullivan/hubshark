package com.hubshark.client

import android.app.Application
import com.hubshark.client.data.AccountRepository
import com.hubshark.client.data.EncryptedPrefsStore

class HubSharkApplication : Application() {
    val accountRepository: AccountRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AccountRepository(EncryptedPrefsStore(this))
    }
}
