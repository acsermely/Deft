package app.deft.nfc

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import app.deft.DeftApp
import app.deft.catalog.Capability

/**
 * PARITY B4: payment-category card emulation for the one web app holding `nfc.hce.payment`.
 * Declared disabled; `RoleComponents` enables it only while a holder exists, and Android routes
 * payment AIDs to it only while the user picked Deft as the default payment app
 * (the special-access sheet asks). The static XML requires an unlocked phone.
 *
 * Same protocol as [DeftHostApduService]: `nfc.paymentCommand` to the holder's open page, answered
 * with `nfc.paymentRespond` within [HceRouting.RESPONSE_TIMEOUT_MS], else 6F00. A closed holder
 * is not woken: a background run cannot start within the window, and a payment needs several
 * commands in a row.
 */
class DeftPaymentApduService : RoutedApduService() {
    override val router: ApduRouter get() = ROUTER

    companion object {
        val GROUP: String = Capability.NFC_HCE_PAYMENT.exclusiveGroup!!

        private val ROUTER = ApduRouter("nfc.paymentCommand", "nfc.paymentDeactivated") {
            DeftApp.instance.repository.specialHolder(GROUP)
        }

        /** `nfc.paymentRespond`: only the app that received the command may answer it. */
        fun respond(webAppId: String, requestId: String, response: ByteArray): Boolean =
            ROUTER.respond(webAppId, requestId, response)

        fun component(context: Context) = ComponentName(context, DeftPaymentApduService::class.java)

        /** Android's card emulation, or null on a phone without NFC card emulation. */
        fun cardEmulation(context: Context): CardEmulation? {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
            val adapter = NfcAdapter.getDefaultAdapter(context) ?: return null
            return runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
        }

        /**
         * Android 15+ phones may choose the payment app through the wallet role instead of the
         * "Contactless payments" default; the string, as the constant is not in every SDK.
         */
        private const val ROLE_WALLET = "android.app.role.WALLET"

        private fun walletRoleAvailable(context: Context): Boolean = runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleAvailable(ROLE_WALLET) == true
        }.getOrDefault(false)

        /** True while Deft's payment service is the phone's default payment app. */
        fun isDefault(context: Context): Boolean {
            val viaCardEmulation = runCatching {
                cardEmulation(context)?.isDefaultServiceForCategory(component(context), CardEmulation.CATEGORY_PAYMENT) == true
            }.getOrDefault(false)
            if (viaCardEmulation) return true
            return walletRoleAvailable(context) && runCatching {
                context.getSystemService(RoleManager::class.java)?.isRoleHeld(ROLE_WALLET) == true
            }.getOrDefault(false)
        }

        /**
         * Android's own "make this the default payment app?" question, started for a result: the
         * wallet role request where the phone has it, else [CardEmulation.ACTION_CHANGE_DEFAULT]
         * for the payment category and this service. Null on a phone without card emulation.
         */
        fun requestDefaultIntent(context: Context): Intent? {
            if (cardEmulation(context) == null) return null
            if (walletRoleAvailable(context)) {
                runCatching { context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(ROLE_WALLET) }
                    .getOrNull()?.let { return it }
            }
            return Intent(CardEmulation.ACTION_CHANGE_DEFAULT)
                .putExtra(CardEmulation.EXTRA_CATEGORY, CardEmulation.CATEGORY_PAYMENT)
                .putExtra(CardEmulation.EXTRA_SERVICE_COMPONENT, component(context))
        }

        /** Drops the app's payment AIDs (no holder any more, or the holder asked). */
        fun clearAids(context: Context) {
            runCatching { cardEmulation(context)?.removeAidsForService(component(context), CardEmulation.CATEGORY_PAYMENT) }
        }
    }
}
