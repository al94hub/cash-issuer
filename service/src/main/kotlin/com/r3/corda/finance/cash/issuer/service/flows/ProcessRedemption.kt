package com.r3.corda.finance.cash.issuer.service.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrThrow
import com.r3.corda.lib.tokens.contracts.utilities.sumTokenStatesOrZero
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.workflows.utilities.toParty
import com.r3.corda.sdk.issuer.common.contracts.NodeTransactionContract
import com.r3.corda.sdk.issuer.common.contracts.states.NodeTransactionState
import com.r3.corda.sdk.issuer.common.contracts.types.NodeTransactionType
import com.r3.corda.sdk.issuer.common.workflows.utilities.GenerationScheme
import com.r3.corda.sdk.issuer.common.workflows.utilities.generateRandomString
import net.corda.core.contracts.AmountTransfer
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByService
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.time.Instant

@StartableByService
class ProcessRedemption(val signedTransaction: SignedTransaction) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Calculate the redemption amount.
        val ledgerTx = signedTransaction.tx.toLedgerTransaction(serviceHub)
        val inputAmount = ledgerTx.inputsOfType<FungibleToken<FiatCurrency>>().sumTokenStatesOrThrow()
        val inputIssuedTokenType = inputAmount.token
        val outputAmount = ledgerTx.outputsOfType<FungibleToken<FiatCurrency>>().sumTokenStatesOrZero(inputIssuedTokenType)
        val redemptionAmount = inputAmount - outputAmount
        val redeemingParty = ledgerTx.inputsOfType<FungibleToken<FiatCurrency>>()
                .map { it.holder.toParty(serviceHub) }
                .toSet()
                .single()
        // Create the internal record. This is only used by the issuer.
        val nodeTransactionState = NodeTransactionState(
                amountTransfer = AmountTransfer(
                        quantityDelta = -redemptionAmount.quantity / 100, // Hack. Fix this properly.
                        token = inputIssuedTokenType.tokenType,
                        source = ourIdentity,
                        destination = redeemingParty
                ),
                notes = generateRandomString(10, GenerationScheme.NUMBERS),
                createdAt = Instant.now(),
                participants = listOf(ourIdentity),
                type = NodeTransactionType.REDEMPTION
        )
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val tx = TransactionBuilder(notary = notary).apply {
            addOutputState(nodeTransactionState, NodeTransactionContract.CONTRACT_ID)
            addCommand(NodeTransactionContract.Create(), listOf(ourIdentity.owningKey))
        }
        val partiallySignedTransaction = serviceHub.signInitialTransaction(tx)
        return subFlow(FinalityFlow(partiallySignedTransaction, emptyList()))
    }
}