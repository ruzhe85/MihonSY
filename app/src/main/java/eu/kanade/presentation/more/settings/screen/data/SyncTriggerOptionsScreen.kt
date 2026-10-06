package eu.kanade.presentation.more.settings.screen.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import tachiyomi.presentation.core.components.LabeledRadioButton
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
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
                // SY -->
                // One card per kind of moment: the plain switches share the first card, and every
                // moment that offers a choice gets a card of its own, so its title is what tells the
                // options what they belong to.
                item {
                    SectionCard(SYMR.strings.label_triggers) {
                        SwitchRows(SyncTriggerOptions.switchOptions, state, model)
                    }
                }
                SyncTriggerOptions.singleChoiceGroups.forEach { group ->
                    item {
                        SectionCard(group.label) {
                            ChoiceRows(group.children, state, model)
                        }
                    }
                }
                // SY <--
            }
        }
    }

    @Composable
    private fun SwitchRows(
        entries: List<SyncTriggerOptions.Entry>,
        state: SyncOptionsScreenModel.State,
        model: SyncOptionsScreenModel,
    ) {
        entries.forEach { entry ->
            LabeledCheckbox(
                label = stringResource(entry.label),
                checked = entry.getter(state.options),
                onCheckedChange = {
                    model.toggle(entry.setter, it)
                },
                enabled = entry.enabled(state.options),
            )
        }
    }

    /**
     * The options of one moment. They exclude each other, so a click only ever moves the selection:
     * that is why "do nothing" is one of them instead of relying on everything being unticked.
     */
    @Composable
    private fun ChoiceRows(
        entries: List<SyncTriggerOptions.Entry>,
        state: SyncOptionsScreenModel.State,
        model: SyncOptionsScreenModel,
    ) {
        entries.forEach { entry ->
            LabeledRadioButton(
                label = stringResource(entry.label),
                selected = entry.getter(state.options),
                onClick = {
                    model.select(entry.setter)
                },
                enabled = entry.enabled(state.options),
            )
        }
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

    // SY -->
    /** A radio states a choice rather than a switch: the clicked option is the one that becomes true. */
    fun select(setter: (SyncTriggerOptions, Boolean) -> SyncTriggerOptions) = toggle(setter, true)
    // SY <--

    @Immutable
    data class State(
        val options: SyncTriggerOptions = SyncTriggerOptions(),
    )
}
