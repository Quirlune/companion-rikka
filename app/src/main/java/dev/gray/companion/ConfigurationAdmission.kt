package dev.gray.companion

import dev.gray.companion.core.Issue
import dev.gray.companion.core.Library
import dev.gray.companion.core.LibraryValidator

/** The native serializer remains an Android boundary, shared by every configuration admission. */
internal object ConfigurationAdmission {
    fun validateNative(library: Library): List<Issue> = NativeProviderCodec.validate(library)

    fun validate(library: Library): List<Issue> = LibraryValidator.validate(library) + validateNative(library)
}
