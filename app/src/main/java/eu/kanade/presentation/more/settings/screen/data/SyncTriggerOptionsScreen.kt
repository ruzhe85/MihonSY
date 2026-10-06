package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import kotlinx.coroutines.flow.update
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncTriggerOptionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { SyncOptionsScreenModel() }
        val state by model.state.collectAsState()

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(SYMR.strings.pref_sync_options),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_save),
                actionEnabled = true,
                onClickAction = {
                    navigator.pop()
                },
            ) {
                item {
                    SectionCard(SYMR.strings.label_triggers) {
                        Options(SyncTriggerOptions.mainOptions, state, model)
                    }
                }
            }
        }
    }

    @Composable
    private fun Options(
        options: List<SyncTriggerOptions.Entry>,
        state: SyncOptionsScreenModel.State,
        model: SyncOptionsScreenModel,
    ) {
        options.forEach { option ->
            // SY -->
            // A group titles the switches under it. Nothing is indented: the titles sit in the same
            // column as the switches, and their smaller, heavier style is what tells them apart.
            if (option.children.isEmpty()) {
                Option(option, state, model)
            } else {
                Text(
                    text = stringResource(option.label),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = MaterialTheme.padding.medium),
                )
                option.children.forEach { child ->
                    Option(child, state, model)
                }
            }
            // SY <--
        }
    }

    @Composable
    private fun Option(
        option: SyncTriggerOptions.Entry,
        state: SyncOptionsScreenModel.State,
        model: SyncOptionsScreenModel,
        modifier: Modifier = Modifier,
    ) {
        LabeledCheckbox(
            label = stringResource(option.label),
            checked = option.getter(state.options),
            onCheckedChange = {
                model.toggle(option.setter, it)
            },
            modifier = modifier,
            enabled = option.enabled(state.options),
        )
    }
}

private class SyncOptionsScreenModel(
    val syncPreferences: SyncPreferences = Injekt.get(),
) : StateScreenModel<SyncOptionsScreenModel.State>(
    State(
        syncPreferences.getSyncTriggerOptions(),
    ),
) {

    fun toggle(setter: (SyncTriggerOptions, Boolean) -> SyncTriggerOptions, enabled: Boolean) {
        mutableState.update {
            val updatedTriggerOptions = setter(it.options, enabled)
            syncPreferences.setSyncTriggerOptions(updatedTriggerOptions)
            it.copy(
                options = updatedTriggerOptions,
            )
        }
    }

    @Immutable
    data class State(
        val options: SyncTriggerOptions = SyncTriggerOptions(),
    )
}
