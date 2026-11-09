package app.onesvpn

/** Serializes Keystore lookup/creation across UI and service callers. Never replaces a lost key. */
internal object ProfileKeyAccess {
    @Synchronized fun <T : Any> obtain(hasCiphertext: Boolean, find: () -> T?, create: () -> T): T {
        find()?.let { return it }
        check(!hasCiphertext) { "PROFILE_KEY_UNAVAILABLE" }
        return create()
    }
}
