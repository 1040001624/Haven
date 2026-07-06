package sh.haven.app.agent

/**
 * A cohesive domain's slice of the MCP tool registry (#mcp-backbone Stage 5,
 * Layer E). [McpTools] aggregates providers together with its own
 * not-yet-extracted tools into one registry, so consent, capability gating,
 * and dispatch stay uniform across every tool regardless of where it lives.
 *
 * Extracting a domain into a provider is how the 11.7k-line God file is
 * dismantled incrementally: a provider owns its tools' registrations, their
 * handler implementations, and only the dependencies those tools need —
 * without changing [McpTools]'s public surface, so the transport and every
 * server test stay untouched. It also relieves the JVM 64 KiB per-method
 * bytecode limit the monolithic registry initializer bumps into.
 */
internal interface ToolProvider {
    /** This domain's tools, keyed by MCP tool name. */
    fun tools(): Map<String, ToolHandler>
}
