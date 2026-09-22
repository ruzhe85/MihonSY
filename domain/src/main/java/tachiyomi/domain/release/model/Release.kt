package tachiyomi.domain.release.model

import android.os.Build

/**
 * Contains information about the latest release.
 */
data class Release(
    val version: String,
    val info: String,
    val releaseLink: String,
    private val assets: List<String>,
) {

    /**
     * Get download link of latest release from the assets.
     * @return download link of latest release.
     */
    fun getDownloadLink(): String {
        val apkVariant = when (Build.SUPPORTED_ABIS[0]) {
            "arm64-v8a" -> "-arm64-v8a"
            "armeabi-v7a" -> "-armeabi-v7a"
            "x86" -> "-x86"
            "x86_64" -> "-x86_64"
            else -> ""
        }

        // SY -->
        // MihonSY: assets are named "mihonsy-{version}-{abi}.apk", not upstream's
        // "TachiyomiSY-{abi}-*.apk" — match on the ABI suffix and fall back to the
        // first APK asset (the universal build) if nothing matches.
        return assets.firstOrNull { it.endsWith("$apkVariant.apk") }
            ?: assets.firstOrNull { it.endsWith(".apk") }
            ?: assets[0]
        // SY <--
    }

    /**
     * Assets class containing download url.
     */
    data class Assets(val downloadLink: String)
}
