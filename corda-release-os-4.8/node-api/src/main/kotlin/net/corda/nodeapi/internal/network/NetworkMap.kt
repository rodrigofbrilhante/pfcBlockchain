package net.corda.nodeapi.internal.network

import net.corda.core.crypto.SecureHash
import net.corda.core.internal.CertRole
import net.corda.core.internal.SignedDataWithCert
import net.corda.core.node.NetworkParameters
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.CordaSerializable
import net.corda.nodeapi.internal.crypto.X509Utilities
import java.security.cert.X509Certificate
import java.time.Instant

const val NETWORK_PARAMS_FILE_NAME = "network-parameters"
const val NETWORK_PARAMS_UPDATE_FILE_NAME = "network-parameters-update"

typealias SignedNetworkMap = SignedDataWithCert<NetworkMap>
typealias SignedNetworkParameters = SignedDataWithCert<NetworkParameters>

/**
 * Data structure representing the network map available from the HTTP network map service as a serialised blob.
 * @property nodeInfoHashes list of network participant's [NodeInfo] hashes
 * @property networkParameterHash hash of the current active [NetworkParameters]
 * @property parametersUpdate if present means that network operator has scheduled an update of the network parameters
 */
@CordaSerializable
data class NetworkMap(
        val nodeInfoHashes: List<SecureHash>,
        val networkParameterHash: SecureHash,
        val parametersUpdate: ParametersUpdate?
) {
    override fun toString(): String {
        return """NetworkMap {
  nodeInfoHashes {
    ${nodeInfoHashes.asSequence().take(10).joinToString("\n    ")}
    ${if (nodeInfoHashes.size > 10) "... ${nodeInfoHashes.size - 10} more" else ""}
  }
  networkParameterHash=$networkParameterHash
  parametersUpdate=$parametersUpdate
}"""
    }
}

/**
 * Data class representing scheduled network parameters update.
 * @property newParametersHash Hash of the new [NetworkParameters] which can be requested from the network map
 * @property description Short description of the update
 * @property updateDeadline deadline by which new network parameters need to be accepted, after this date network operator
 *          can switch to new parameters which will result in getting nodes with old parameters out of the network
 */
@CordaSerializable
data class ParametersUpdate(
        val newParametersHash: SecureHash,
        val description: String,
        val updateDeadline: Instant
)

/** Verify that a certificate path and its [CertRole] is correct. */
fun <T : Any> SignedDataWithCert<T>.verifiedCertWithRole(rootCerts: Set<X509Certificate>, vararg certRoles: CertRole): T {
    require(CertRole.extract(sig.by) in certRoles) { "Incorrect cert role: ${CertRole.extract(sig.by)}" }
    val path = if (sig.parentCertsChain.isEmpty()) {
        val rootCandidate = rootCerts.firstOrNull { it.subjectX500Principal == sig.by.issuerX500Principal }
        listOf(sig.by, rootCandidate ?: rootCerts.first())
    } else {
        sig.fullCertChain
    }
    X509Utilities.validateCertificateChain(rootCerts, path)
    return verified()
}

/** Verify that a Network Map certificate path and its [CertRole] is correct. */
fun <T : Any> SignedDataWithCert<T>.verifiedNetworkMapCert(rootCerts: Set<X509Certificate>): T {
    return verifiedCertWithRole(rootCerts, CertRole.NETWORK_MAP)
}

/** Verify that a Network Parameters certificate path and its [CertRole] is correct. */
fun <T : Any> SignedDataWithCert<T>.verifiedNetworkParametersCert(rootCerts: Set<X509Certificate>): T {
    // for backwards compatibility we allow network parameters to be signed with
    // the networkmap cert, but going forwards we also accept the specific netparams cert as well
    return verifiedCertWithRole(rootCerts, CertRole.NETWORK_PARAMETERS, CertRole.NETWORK_MAP)
}