package dev.gray.companion.ui

/** Route identifiers are kept for saved Navigation3 keys; editing code uses typed kinds. */
internal enum class EditorKind(val route: String) {
    CHARACTER("character"), MAIN("main"), PACKAGE("package"), GROUP("group"),
    PROFILE("profile"), CONNECTION("connection"), PERSONA("persona"), USER("user");

    companion object {
        fun fromRoute(route: String): EditorKind? = entries.firstOrNull { it.route == route }
    }
}

internal enum class ResourceKind(val routeIndex: Int, val title: String, val editor: EditorKind) {
    CHARACTER(0, "角色卡", EditorKind.CHARACTER),
    MAIN(1, "主调用", EditorKind.MAIN),
    PACKAGE(2, "提示词包", EditorKind.PACKAGE),
    GROUP(3, "调用组", EditorKind.GROUP);

    companion object {
        fun fromRouteIndex(index: Int): ResourceKind? = entries.firstOrNull { it.routeIndex == index }
    }
}

internal data class Editing(val kind: EditorKind, val id: String)
