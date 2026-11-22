package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupPreference(
    // Komiho（对齐上游 TachiyomiSY 13f1c31216）: 见 Backup.kt —— 等于默认值时也必须写出。
    // 这里尤其要紧：preference 的 key/值常常就是默认值（false / 0 / ""），被省掉之后
    // 恢复端根本看不到这条偏好，表现就是「备份恢复了但设置没变」。
    @EncodeDefault
    @ProtoNumber(1) val key: String = "",
    @ProtoNumber(2) val value: PreferenceValue,
)

@Serializable
data class BackupSourcePreferences(
    @EncodeDefault
    @ProtoNumber(1) val sourceKey: String = "",
    @EncodeDefault
    @ProtoNumber(2) val prefs: List<BackupPreference> = emptyList(),
)

@Serializable
sealed class PreferenceValue

@Serializable
data class IntPreferenceValue(@EncodeDefault val value: Int = 0) : PreferenceValue()

@Serializable
data class LongPreferenceValue(@EncodeDefault val value: Long = 0) : PreferenceValue()

@Serializable
data class FloatPreferenceValue(@EncodeDefault val value: Float = 0F) : PreferenceValue()

@Serializable
data class StringPreferenceValue(@EncodeDefault val value: String = "") : PreferenceValue()

@Serializable
data class BooleanPreferenceValue(@EncodeDefault val value: Boolean = false) : PreferenceValue()

@Serializable
data class StringSetPreferenceValue(@EncodeDefault val value: Set<String> = emptySet()) : PreferenceValue()
