package com.phnem.vap.network

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/** SSHJ looks providers up by name. Android's built-in BC lacks modern SSH algorithms.
 * Register the bundled implementation under a private name; retain Android's TLS providers. */
internal object SshCrypto {
    @Synchronized fun prepare() {
        if (Security.getProvider(NAME) == null) Security.addProvider(object : Provider(NAME, 1.0, "VAP SSH algorithms") {
            init { for (entry in BouncyCastleProvider().entries) if (!entry.key.toString().startsWith("Provider.")) put(entry.key, entry.value) }
        })
        SecurityUtils.setSecurityProvider(NAME)
        SecurityUtils.setRegisterBouncyCastle(false)
    }
    private const val NAME = "VAP-BC"
}
