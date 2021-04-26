package net.corda.verification.flows

import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import co.paralleluniverse.fibers.Suspendable
import net.corda.verification.contracts.NotaryTestCommand
import net.corda.verification.contracts.NotaryTestContract
import net.corda.verification.contracts.NotaryTestState

@StartableByRPC
class TestNotaryFlow : FlowLogic<String>() {

    object ISSUING : ProgressTracker.Step("ISSUING")
    object ISSUED : ProgressTracker.Step("ISSUED")
    object DESTROYING : ProgressTracker.Step("DESTROYING")
    object FINALIZED : ProgressTracker.Step("FINALIZED")

    override val progressTracker: ProgressTracker = ProgressTracker(ISSUING, ISSUED, DESTROYING, FINALIZED)

    @Suspendable
    override fun call(): String {
        val issueBuilder = TransactionBuilder()
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        issueBuilder.notary = notary
        val myIdentity = serviceHub.myInfo.legalIdentities.first()
        issueBuilder.addOutputState(NotaryTestState(notary.name.toString(), myIdentity), NotaryTestContract::class.java.name)
        issueBuilder.addCommand(NotaryTestCommand, myIdentity.owningKey)
        val signedTx = serviceHub.signInitialTransaction(issueBuilder)
        val issueResult = subFlow(FinalityFlow(signedTx, emptyList()))
        progressTracker.currentStep = ISSUED
        val destroyBuilder = TransactionBuilder()
        destroyBuilder.notary = notary
        destroyBuilder.addInputState(issueResult.tx.outRefsOfType<NotaryTestState>().first())
        destroyBuilder.addCommand(NotaryTestCommand, myIdentity.owningKey)
        val signedDestroyT = serviceHub.signInitialTransaction(destroyBuilder)
        val result = subFlow(FinalityFlow(signedDestroyT, emptyList()))
        progressTracker.currentStep = DESTROYING
        progressTracker.currentStep = FINALIZED
        return "notarised: ${result.notary}::${result.tx.id}"
    }
}
