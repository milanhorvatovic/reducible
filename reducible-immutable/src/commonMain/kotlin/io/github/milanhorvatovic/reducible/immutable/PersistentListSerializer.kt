package io.github.milanhorvatovic.reducible.immutable

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes a [PersistentList] as a plain list: kotlinx.serialization ships no support for
 * the immutable collections, so state holding one declares
 * `@Serializable(with = PersistentListSerializer::class)` on the property.
 */
public class PersistentListSerializer<T>(
    elementSerializer: KSerializer<T>,
) : KSerializer<PersistentList<T>> {
    private val delegate = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: PersistentList<T>,
    ) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): PersistentList<T> = delegate.deserialize(decoder).toPersistentList()
}
