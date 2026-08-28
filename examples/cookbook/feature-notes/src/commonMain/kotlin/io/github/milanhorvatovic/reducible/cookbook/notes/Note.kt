package io.github.milanhorvatovic.reducible.cookbook.notes

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
public data class Note(
    public val id: String,
    public val text: String,
)

@Serializable
public sealed interface NotesError {
    @Serializable
    public data object Offline : NotesError

    @Serializable
    public data class Unexpected(
        public val message: String,
    ) : NotesError
}
