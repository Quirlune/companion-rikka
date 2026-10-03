package dev.gray.companion.ui

/** Preserve existing load order when editing the package's reverse binding view. */
internal fun List<String>.withPackageBinding(id: String, selected: Boolean): List<String> =
    if (!selected) filterNot { it == id } else if (id in this) this else this + id
