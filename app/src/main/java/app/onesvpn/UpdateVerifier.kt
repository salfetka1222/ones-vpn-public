package app.onesvpn

internal object UpdateVerifier {
    fun requireCompatible(installedPackage: String, installedVersion: Long, offeredVersion: Int,
                          archivePackage: String, archiveVersion: Long,
                          installedSigners: Set<String>, archiveSigners: Set<String>) {
        require(archivePackage == installedPackage) { "UPDATE_PACKAGE_MISMATCH" }
        require(archiveVersion > installedVersion && archiveVersion == offeredVersion.toLong()) { "UPDATE_VERSION_MISMATCH" }
        require(installedSigners.isNotEmpty() && archiveSigners == installedSigners) { "UPDATE_SIGNATURE_MISMATCH" }
    }
}
