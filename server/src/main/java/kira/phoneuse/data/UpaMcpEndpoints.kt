package kira.phoneuse.data

// 18793: a port of its own, so other agent-mode servers on the phone (18792) do not collide.
internal const val UpaMcpHttpPort = 18793
internal const val UpaMcpHttpPathPrefix = "/mcp/"
internal const val UpaMcpProtocolVersion = "2025-11-25"

internal fun upaMcpHttpUrl(pluginId: String): String =
    "http://127.0.0.1:$UpaMcpHttpPort$UpaMcpHttpPathPrefix${pluginId.trim()}"

internal fun kimiMcpServerKey(pluginId: String): String =
    pluginId.trim().replace('.', '-').ifBlank { "upa-plugin" }
