package net.corda.node.services.statemachine.transitions

import net.corda.core.flows.FlowException
import net.corda.node.services.statemachine.*

/**
 * This transition defines what should happen when a flow has errored.
 *
 * In general there are two flow-level error conditions:
 *
 *  - Internal exceptions. These may arise due to problems in the flow framework or errors during state machine
 *    transitions e.g. network or database failure.
 *  - User-raised exceptions. These are exceptions that are (re)raised in user code, allowing the user to catch them.
 *    These may come from illegal flow API calls, and FlowExceptions or other counterparty failures that are re-raised
 *    when the flow tries to use the corresponding sessions.
 *
 * Both internal exceptions and uncaught user-raised exceptions cause the flow to be errored. This flags the flow as
 *   unable to be resumed. When a flow is in this state an external source (e.g. Flow hospital) may decide to
 *
 *   1. Retry it (not implemented yet). This throws away the errored state and re-tries from the last clean checkpoint.
 *   2. Start error propagation. This seals the flow as errored permanently and propagates the associated error(s) to
 *     all live sessions. This causes these sessions to errored on the other side, which may in turn cause the
 *     counter-flows themselves to errored.
 *
 * See [net.corda.node.services.statemachine.interceptors.HospitalisingInterceptor] for how to detect flow errors.
 *
 * Note that in general we handle multiple errors at a time as several error conditions may arise at the same time and
 *   new errors may arise while the flow is in the errored state already.
 */
class ErrorFlowTransition(
        override val context: TransitionContext,
        override val startingState: StateMachineState,
        private val errorState: ErrorState.Errored
) : Transition {
    override fun transition(): TransitionResult {
        val allErrors: List<FlowError> = errorState.errors
        val remainingErrorsToPropagate: List<FlowError> = allErrors.subList(errorState.propagatedIndex, allErrors.size)
        val errorMessages: List<ErrorSessionMessage> = remainingErrorsToPropagate.map(this::createErrorMessageFromError)

        return builder {
            // If we're errored and propagating do the actual propagation and update the index.
            if (remainingErrorsToPropagate.isNotEmpty() && errorState.propagating) {
                val (initiatedSessions, newSessions) = bufferErrorMessagesInInitiatingSessions(
                        startingState.checkpoint.checkpointState.sessions,
                        errorMessages
                )
                val newCheckpoint = startingState.checkpoint.copy(
                        errorState = errorState.copy(propagatedIndex = allErrors.size),
                        checkpointState = startingState.checkpoint.checkpointState.copy(sessions = newSessions)
                )
                currentState = currentState.copy(checkpoint = newCheckpoint)
                actions += Action.PropagateErrors(errorMessages, initiatedSessions, startingState.senderUUID)
            }

            // If we're errored but not propagating keep processing events.
            if (remainingErrorsToPropagate.isNotEmpty() && !errorState.propagating) {
                return@builder FlowContinuation.ProcessEvents
            }

            // If we haven't been removed yet remove the flow.
            if (!currentState.isRemoved) {
                val newCheckpoint = startingState.checkpoint.copy(
                    status = Checkpoint.FlowStatus.FAILED,
                    flowState = FlowState.Finished,
                    checkpointState = startingState.checkpoint.checkpointState.copy(
                        numberOfCommits = startingState.checkpoint.checkpointState.numberOfCommits + 1
                    )
                )
                currentState = currentState.copy(
                    checkpoint = newCheckpoint,
                    pendingDeduplicationHandlers = emptyList(),
                    isRemoved = true
                )

                val removeOrPersistCheckpoint = if (currentState.checkpoint.checkpointState.invocationContext.clientId == null) {
                    Action.RemoveCheckpoint(context.id)
                } else {
                    Action.PersistCheckpoint(
                        context.id,
                        newCheckpoint,
                        isCheckpointUpdate = currentState.isAnyCheckpointPersisted
                    )
                }

                actions += Action.CreateTransaction
                actions += removeOrPersistCheckpoint
                actions += Action.PersistDeduplicationFacts(startingState.pendingDeduplicationHandlers)
                actions += Action.ReleaseSoftLocks(context.id.uuid)
                actions += Action.CommitTransaction(currentState)
                actions += Action.AcknowledgeMessages(startingState.pendingDeduplicationHandlers)
                actions += Action.RemoveSessionBindings(startingState.checkpoint.checkpointState.sessions.keys)
                actions += Action.RemoveFlow(context.id, FlowRemovalReason.ErrorFinish(allErrors), currentState)

                FlowContinuation.Abort
            } else {
                // Otherwise keep processing events. This branch happens when there are some outstanding initiating
                // sessions that prevent the removal of the flow.
                FlowContinuation.ProcessEvents
            }
        }
    }

    private fun createErrorMessageFromError(error: FlowError): ErrorSessionMessage {
        val exception = error.exception
        // If the exception doesn't contain an originalErrorId that means it's a fresh FlowException that should
        // propagate to the neighbouring flows. If it has the ID filled in that means it's a rethrown FlowException and
        // shouldn't be propagated.
        return if (exception is FlowException && exception.originalErrorId == null) {
            ErrorSessionMessage(flowException = exception, errorId = error.errorId)
        } else {
            ErrorSessionMessage(flowException = null, errorId = error.errorId)
        }
    }

    // Buffer error messages in Initiating sessions, return the initialised ones.
    private fun bufferErrorMessagesInInitiatingSessions(
            sessions: Map<SessionId, SessionState>,
            errorMessages: List<ErrorSessionMessage>
    ): Pair<List<SessionState.Initiated>, Map<SessionId, SessionState>> {
        val newSessions = sessions.mapValues { (sourceSessionId, sessionState) ->
            if (sessionState is SessionState.Initiating && sessionState.rejectionError == null) {
                // *prepend* the error messages in order to error the other sessions ASAP. The other messages will
                // be delivered all the same, they just won't trigger flow resumption because of dirtiness.
                val errorMessagesWithDeduplication: ArrayList<Pair<DeduplicationId, ExistingSessionMessagePayload>> = errorMessages.map {
                    DeduplicationId.createForError(it.errorId, sourceSessionId) to it
                }.toArrayList()
                sessionState.copy(bufferedMessages = errorMessagesWithDeduplication + sessionState.bufferedMessages)
            } else {
                sessionState
            }
        }
        // if we have already received error message from the other side, we don't include that session in the list to avoid propagating errors.
        val initiatedSessions = sessions.values.mapNotNull { session ->
            if (session is SessionState.Initiated && !session.otherSideErrored) {
                session
            } else {
                null
            }
        }
        return Pair(initiatedSessions, newSessions)
    }
}
