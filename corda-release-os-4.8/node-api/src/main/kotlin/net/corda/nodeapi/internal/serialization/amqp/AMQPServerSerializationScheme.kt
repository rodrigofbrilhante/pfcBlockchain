package net.corda.nodeapi.internal.serialization.amqp

import net.corda.core.cordapp.Cordapp
import net.corda.core.internal.toSynchronised
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.serialization.internal.CordaSerializationMagic
import net.corda.serialization.internal.amqp.*
import net.corda.serialization.internal.amqp.custom.RxNotificationSerializer

/**
 * When set as the serialization scheme, defines the RPC Server serialization scheme as using the Corda
 * AMQP implementation.
 */
class AMQPServerSerializationScheme(
        cordappCustomSerializers: Set<SerializationCustomSerializer<*, *>>,
        cordappSerializationWhitelists: Set<SerializationWhitelist>,
        serializerFactoriesForContexts: MutableMap<SerializationFactoryCacheKey, SerializerFactory>
) : AbstractAMQPSerializationScheme(cordappCustomSerializers, cordappSerializationWhitelists, serializerFactoriesForContexts) {
    constructor(cordapps: List<Cordapp>) : this(cordapps.customSerializers, cordapps.serializationWhitelists)
    constructor(cordapps: List<Cordapp>, serializerFactoriesForContexts: MutableMap<SerializationFactoryCacheKey, SerializerFactory>)
        : this(cordapps.customSerializers, cordapps.serializationWhitelists, serializerFactoriesForContexts)
    constructor(
        cordappCustomSerializers: Set<SerializationCustomSerializer<*,*>>,
        cordappSerializationWhitelists: Set<SerializationWhitelist>
    ) : this(cordappCustomSerializers, cordappSerializationWhitelists, AccessOrderLinkedHashMap<SerializationFactoryCacheKey, SerializerFactory>(128).toSynchronised())

    @Suppress("UNUSED")
    constructor() : this(emptySet(), emptySet())

    override fun rpcClientSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun rpcServerSerializerFactory(context: SerializationContext): SerializerFactory {
        return SerializerFactoryBuilder.build(context.whitelist, context.deserializationClassLoader, context.lenientCarpenterEnabled).apply {
            register(RpcServerObservableSerializer())
            register(RpcServerCordaFutureSerializer(this))
            register(RxNotificationSerializer(this))
        }
    }

    override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
        return canDeserializeVersion(magic) &&
                (   target == SerializationContext.UseCase.P2P
                 || target == SerializationContext.UseCase.Storage
                 || target == SerializationContext.UseCase.RPCServer)
    }
}
