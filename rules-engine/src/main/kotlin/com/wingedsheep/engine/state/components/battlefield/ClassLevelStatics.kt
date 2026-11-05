package com.wingedsheep.engine.state.components.battlefield

import com.wingedsheep.engine.state.components.identity.faceUpCardDefinition

import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.StaticAbility

/**
 * The printed static abilities live on the permanent [entityId]: the card's base statics plus, for a
 * Class, those of every level it has reached (CR 716.2a). A scan that reads `script.staticAbilities`
 * directly sees only the base statics, so a level-gated permission (Fortune Teller's Talent's level 2
 * "play cards from the top of your library") silently never applies.
 */
fun CardDefinition.liveStaticAbilities(state: GameState, entityId: EntityId): List<StaticAbility> =
    script.effectiveStaticAbilities(state.getEntity(entityId)?.get<ClassLevelComponent>()?.currentLevel)

/**
 * Every live static ability on the battlefield permanents [playerId] controls, paired with its
 * source: [liveStaticAbilities] per permanent, skipping a face-down permanent and one that has lost
 * all its abilities — neither has the printed statics to grant. For battlefield-wide grant scans
 * that aren't continuous effects (foretell grants, special-action cost modifiers).
 */
fun GameState.controlledLiveStaticAbilities(
    playerId: EntityId,
    cardRegistry: com.wingedsheep.engine.registry.CardRegistry,
): Sequence<Pair<EntityId, StaticAbility>> =
    controlledBattlefield(playerId).asSequence().flatMap { permanentId ->
        val container = getEntity(permanentId)
        if (container == null ||
            container.has<com.wingedsheep.engine.state.components.identity.FaceDownComponent>() ||
            projectedState.hasLostAllAbilities(permanentId)
        ) return@flatMap emptySequence()
        val def = container.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
            ?.let { cardRegistry.getCard(it.cardDefinitionId) } ?: return@flatMap emptySequence()
        def.liveStaticAbilities(this, permanentId).asSequence().map { permanentId to it }
    }

/**
 * [controlledLiveStaticAbilities] with every [ConditionalStaticAbility] resolved against its own
 * permanent: the wrapped ability while the condition holds, nothing while it doesn't. For grant scans
 * that match on a static's type, where a still-wrapped grant (`staticAbility { condition = … }`)
 * would otherwise never match and be silently inert.
 */
fun GameState.controlledActiveStaticAbilities(
    playerId: EntityId,
    cardRegistry: com.wingedsheep.engine.registry.CardRegistry,
    conditionEvaluator: ConditionEvaluator,
): Sequence<Pair<EntityId, StaticAbility>> =
    controlledLiveStaticAbilities(playerId, cardRegistry).mapNotNull { (sourceId, ability) ->
        when {
            ability !is ConditionalStaticAbility -> sourceId to ability
            conditionEvaluator.evaluate(this, ability.condition, EffectContext(sourceId = sourceId, controllerId = playerId)) ->
                sourceId to ability.ability
            else -> null
        }
    }

/**
 * The classes of the [liveStaticAbilities] of every face-up battlefield permanent, plus the class
 * wrapped by each [com.wingedsheep.sdk.scripting.ConditionalStaticAbility]. Battlefield scans for one
 * rare kind of static (Cursed Totem's prohibition, a flash grant) run for every card while listing
 * legal actions; when the kind isn't here they can skip the scan. Computed once per state.
 */
fun GameState.battlefieldStaticKinds(cardRegistry: com.wingedsheep.engine.registry.CardRegistry): Set<Class<*>> =
    derived(cardRegistry to "battlefieldStaticKinds") {
        val kinds = HashSet<Class<*>>()
        for (permanentId in getBattlefield()) {
            val def = getEntity(permanentId)
                ?.faceUpCardDefinition(cardRegistry) ?: continue
            for (ability in def.liveStaticAbilities(this, permanentId)) {
                kinds += ability.javaClass
                (ability as? com.wingedsheep.sdk.scripting.ConditionalStaticAbility)?.let { kinds += it.ability.javaClass }
            }
        }
        kinds
    }
