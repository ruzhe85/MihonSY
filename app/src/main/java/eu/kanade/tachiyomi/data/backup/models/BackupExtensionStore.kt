package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extension.model.ExtensionStore

@Serializable
class BackupExtensionStore(
    // Komiho: 见 Backup.kt —— 等于默认值时也必须写出。
    @EncodeDefault
    @ProtoNumber(1) var indexUrl: String = "",
    @EncodeDefault
    @ProtoNumber(2) var name: String = "",
    // 可空字段一律给 null 默认值：SY/Mihon 写出备份时会把 null 整条省掉，
    // 没有默认值的话我们这边解析会直接失败（同样是跨端兼容的一部分）。
    @ProtoNumber(3) var badgeLabel: String? = null,
    @EncodeDefault
    @ProtoNumber(5) var signingKey: String = "",
    @EncodeDefault
    @ProtoNumber(4) var contactWebsite: String = "",
    @ProtoNumber(6) var contactDiscord: String? = null,
    @ProtoNumber(7) var isLegacy: Boolean? = null,
    @ProtoNumber(8) var extensionListUrl: String? = null,
)

val backupExtensionStoreMapper = { store: ExtensionStore ->
    BackupExtensionStore(
        indexUrl = store.indexUrl,
        name = store.name,
        badgeLabel = store.badgeLabel,
        signingKey = store.signingKey,
        contactWebsite = store.contact.website,
        contactDiscord = store.contact.discord,
        isLegacy = store.isLegacy,
        extensionListUrl = store.extensionListUrl,
    )
}
