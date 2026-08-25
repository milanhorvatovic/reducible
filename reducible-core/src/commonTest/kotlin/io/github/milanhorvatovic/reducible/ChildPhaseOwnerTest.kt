package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class PhaseHostState(
    val job: PhaseJobState? = null,
    val jobs: List<PhaseJobState> = emptyList(),
)

private sealed interface PhaseJobState {
    val id: String

    data class Idle(
        override val id: String,
    ) : PhaseJobState

    data class Working(
        override val id: String,
    ) : PhaseJobState
}

private sealed interface PhaseHostAction {
    data class Job(
        val action: PhaseJobAction,
    ) : PhaseHostAction

    data class Row(
        val action: IdentifiedAction<String, PhaseJobAction>,
    ) : PhaseHostAction
}

private sealed interface PhaseJobAction {
    data object Begin : PhaseJobAction
}

private data object PhaseWork

private val jobReducer =
    Reducer<PhaseJobState, PhaseJobAction, PhaseWork> { state, action ->
        when (action) {
            PhaseJobAction.Begin -> PhaseJobState.Working(state.id).withEffect(PhaseWork)
        }
    }

private val jobOptional: Optional<PhaseHostState, PhaseJobState> =
    optional(getOrNull = { host -> host.job }, set = { host, job ->
        if (host.job == null) {
            host
        } else {
            host.copy(job = job)
        }
    })

private val jobsLens: Lens<PhaseHostState, List<PhaseJobState>> =
    lens(get = { host -> host.jobs }, set = { host, jobs -> host.copy(jobs = jobs) })

private val hostReducer: Reducer<PhaseHostState, PhaseHostAction, PhaseWork> =
    combine(
        jobReducer.ifPresent(
            state = jobOptional,
            action =
                prism(getOrNull = { action ->
                    (action as? PhaseHostAction.Job)?.action
                }, embed = { jobAction -> PhaseHostAction.Job(jobAction) }),
            effect = { effect -> effect },
        ),
        jobReducer.forEachIdentified(
            list = jobsLens,
            identity = { row -> row.id },
            action =
                prism(getOrNull = { action ->
                    (action as? PhaseHostAction.Row)?.action
                }, embed = { rowAction -> PhaseHostAction.Row(rowAction) }),
            effect = { _, effect -> effect },
        ),
    )

@Suppress("UNCHECKED_CAST")
private fun EffectEnvelope<*>.resolvesIn(state: PhaseHostState): Boolean = (owner as EffectOwner<PhaseHostState>).resolvesIn(state)

// A child reducer that sets no owner gets the same implicit one a root reducer gets from the
// store — the state class its reduction entered — evaluated inside the focus. Leaving the
// phase cancels the effect whether the child sits behind an optional or in an identified list.
class ChildPhaseOwnerTest {
    @Test
    fun an_optional_child_effect_is_owned_by_the_phase_the_child_entered() {
        val envelope =
            hostReducer
                .reduce(
                    PhaseHostState(job = PhaseJobState.Idle("a")),
                    PhaseHostAction.Job(PhaseJobAction.Begin),
                ).effects
                .single()

        assertTrue(envelope.resolvesIn(PhaseHostState(job = PhaseJobState.Working("a"))))
        assertFalse(envelope.resolvesIn(PhaseHostState(job = PhaseJobState.Idle("a"))), "leaving the phase must stop resolution")
        assertFalse(envelope.resolvesIn(PhaseHostState(job = null)), "losing the focus must stop resolution")
    }

    @Test
    fun a_row_effect_is_owned_by_its_identity_and_the_phase_the_row_entered() {
        val rows = PhaseHostState(jobs = listOf(PhaseJobState.Idle("a"), PhaseJobState.Idle("b")))
        val envelope = hostReducer.reduce(rows, PhaseHostAction.Row(IdentifiedAction("a", PhaseJobAction.Begin))).effects.single()

        assertTrue(envelope.resolvesIn(PhaseHostState(jobs = listOf(PhaseJobState.Working("a"), PhaseJobState.Idle("b")))))
        assertTrue(
            envelope.resolvesIn(PhaseHostState(jobs = listOf(PhaseJobState.Working("a")))),
            "a sibling's removal is not this row's business",
        )
        assertFalse(
            envelope.resolvesIn(PhaseHostState(jobs = listOf(PhaseJobState.Idle("a"), PhaseJobState.Idle("b")))),
            "leaving the phase must stop resolution",
        )
        assertFalse(envelope.resolvesIn(PhaseHostState(jobs = listOf(PhaseJobState.Idle("b")))), "removing the row must stop resolution")
    }

    @Test
    fun an_explicit_child_owner_is_kept_and_only_re_scoped() {
        val strict =
            Reducer<PhaseJobState, PhaseJobAction, PhaseWork> { state, _ ->
                Reduced(
                    PhaseJobState.Working(state.id),
                    listOf(EffectEnvelope(PhaseWork, owner = EffectOwner<PhaseJobState> { job -> job.id == "a" })),
                )
            }.ifPresent(
                state = jobOptional,
                action =
                    prism(getOrNull = { action ->
                        (action as? PhaseHostAction.Job)?.action
                    }, embed = { jobAction -> PhaseHostAction.Job(jobAction) }),
                effect = { effect -> effect },
            )

        val envelope =
            strict
                .reduce(
                    PhaseHostState(job = PhaseJobState.Idle("a")),
                    PhaseHostAction.Job(PhaseJobAction.Begin),
                ).effects
                .single()

        // The explicit owner ignores the phase and cares about the id only.
        assertTrue(envelope.resolvesIn(PhaseHostState(job = PhaseJobState.Idle("a"))))
        assertFalse(envelope.resolvesIn(PhaseHostState(job = PhaseJobState.Working("b"))))
    }
}
