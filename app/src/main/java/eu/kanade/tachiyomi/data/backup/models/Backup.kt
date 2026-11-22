package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class Backup(
    // Komiho（对齐上游 TachiyomiSY 13f1c31216）: 这些字段值等于默认值时 protobuf 会**整条省掉**，
    // 而 Mihon 侧按「必须有这个字段」解析，于是 SY↔Mihon 互刷备份会丢内容。@EncodeDefault 强制
    // 即使等于默认值也写出。
    @EncodeDefault
    @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    // SY specific values
    @ProtoNumber(600) var backupSavedSearches: List<BackupSavedSearch> = emptyList(),

    // SY -->
    @ProtoNumber(601) var backupBookmarks: List<BackupBookmark> = emptyList(),
    // SY <--
)
