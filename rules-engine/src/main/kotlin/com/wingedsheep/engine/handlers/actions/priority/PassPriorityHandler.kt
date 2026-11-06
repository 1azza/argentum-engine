package com.wingedsheep.engine.handlers.actions.priority

import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PriorityChangedEvent
import com.wingedsheep.engine.core.TurnManager
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.handlers.actions.ActionHandler
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.combat.BlockersDeclaredThisCombatComponent
import com.wingedsheep.sdk.core.Step
import kotlin.reflect.KClass

/**
 * Handler for the PassPriority action.
 *
 * When a player passes priority:
 * - If all players have passed, resolve top of stack or advance game
 * - Otherwise, pass to next player
 */
class PassPriorityHandler(
    private val turnManager: TurnManager,
    private val stackResolver: StackResolver
) : ActionHandler<PassPriority> {
    override val actionType: KClass<PassPriority> = PassPriority::class

    override fun validate(state: GameState, action: PassPriority): String? {
        if (!state.hasPriority(action.playerId)) {
            return "You don't have priority"
        }
        // Under team priority (CR 805.5) a teammate may pass out of baton order, and their pass
        // then stands until someone acts (which re-arms every seat). Passing *again* in the same
        // round does nothing — the baton stays put and the round doesn't advance — so refuse it
        // rather than let a client (an AI partner polling its legal actions) spin on it forever.
        // The baton holder is never in [GameState.priorityPassedBy], so this only ever bites a
        // seat that already declined; no non-team game can reach it.
        if (action.playerId in state.priorityPassedBy && action.playerId != state.priorityPlayerId) {
            return "You have already passed priority this round"
        }
        // Cannot pass priority while there's a pending decision
        val pendingDecision = state.pendingDecision
        if (pendingDecision != null) {
            return "Cannot pass priority while there's a pending decision - please respond to: ${pendingDecision.prompt}"
        }
        // Cannot pass priority during combat declaration steps until the declaration is submitted.
        // During DECLARE_ATTACKERS, the active player must submit DeclareAttackers before passing.
        // During DECLARE_BLOCKERS, the defending player must submit DeclareBlockers before passing.
        // CR 805.10a — every player on the active team is an attacking player and stamps their own
        // declaration, so the gate is team-wide turn ownership, not the single [activePlayerId].
        // [isActiveTurnFor] is plain equality outside a shared-turns format.
        if (state.step == Step.DECLARE_ATTACKERS && state.isActiveTurnFor(action.playerId)) {
            val attackersDeclared = state.getEntity(action.playerId)
                ?.get<AttackersDeclaredThisCombatComponent>() != null
            if (!attackersDeclared) {
                return "You must declare attackers before passing priority"
            }
        }
        if (state.step == Step.DECLARE_BLOCKERS && !state.isActiveTurnFor(action.playerId)) {
            // Only a defending player (one being attacked) must declare blockers before
            // passing. In a multiplayer combat, players who aren't being attacked pass
            // freely — they have no blocks to declare (CR 509.1).
            val isDefender = com.wingedsheep.engine.mechanics.combat.CombatDefenders
                .isDefendingPlayer(state, action.playerId)
            val blockersDeclared = state.getEntity(action.playerId)
                ?.get<BlockersDeclaredThisCombatComponent>() != null
            if (isDefender && !blockersDeclared) {
                return "You must declare blockers before passing priority"
            }
        }
        return null
    }

    override fun execute(state: GameState, action: PassPriority): ExecutionResult {
        val newState = state.withPriorityPassed(action.playerId)

        // Check if all players passed
        if (newState.allPlayersPassed()) {
            return if (newState.stack.isNotEmpty()) {
                resolveTopOfStack(newState)
            } else {
                // The settle boundary picks up the step's triggers (phase/step, delayed, and the
                // events of its turn-based actions); the step machine already handed priority on.
                turnManager.advanceStep(newState)
            }
        }

        // Pass the baton. Identical to `getNextPlayer` whenever passing is strictly round-robin —
        // every non-team game. Under team priority (CR 805.5) a teammate may pass out of baton
        // order, and then the baton stays put (its holder still owes a pass) and later skips the
        // seats that have already passed. See [GameState.nextPriorityAfterPass].
        val nextPlayer = newState.nextPriorityAfterPass(action.playerId)
        return ExecutionResult.success(
            newState.withPriorityHolder(nextPlayer),
            listOf(PriorityChangedEvent(nextPlayer))
        )
    }

    private fun resolveTopOfStack(state: GameState): ExecutionResult {
        // CR 117.3b: after a spell or ability resolves, the active player receives priority — not
        // the resolved object's controller (CR 117.3c is about casting and activating, not
        // resolving). A resolution that pauses hands this on to [SubmitDecisionHandler].
        val result = stackResolver.resolveTop(state)
        if (result.error != null || result.pendingDecision != null) return result
        return ExecutionResult.success(result.newState.withPriority(state.activePlayerId), result.events)
    }

    companion object {
        fun create(services: EngineServices): PassPriorityHandler {
            return PassPriorityHandler(
                services.turnManager,
                services.stackResolver
            )
        }
    }
}
