import { createPaseoClient } from "@getpaseo/client";

export const MAX_ACTIVE_WORKSPACE_TABS = 5;
export const BRIDGE_SOURCE_LABEL = "matrix-robobot";

export function isUsageLimitError(message) {
  return /(?:free\s+usage\s+exceeded|usage\s+(?:limit|quota)\s+(?:exceeded|reached)|quota\s+(?:exceeded|exhausted)|insufficient\s+(?:credits|balance))/i
    .test(message || "");
}

export function extractErrorMessage(event) {
  if (!event) return "";
  if (event.type === "turn_failed") return event.error || "";
  if (event.type === "error") return event.error || "";
  if (event.type === "timeline" && event.item?.type === "error") {
    return event.item.message || "";
  }
  return "";
}

async function defaultStopAgent(agentId) {
  if (!agentId) return;
  try {
    const { createHostClient } = await import("@getpaseo/client/node");
    const wsUrl = process.env.PASEO_WS_URL || "ws://127.0.0.1:6767/ws";
    const url = new URL(wsUrl.replace(/^ws/, "http"));
    const hostClient = createHostClient(url.host, {
      clientId: `cancel-${Date.now()}`,
      connectTimeoutMs: 3000,
    });
    await hostClient.connect();
    await hostClient.cancelAgent(agentId);
    await hostClient.close();
    return;
  } catch {
    // Fall back to CLI
  }
  try {
    const { spawnSync } = await import("node:child_process");
    spawnSync("paseo", ["stop", agentId], { stdio: "ignore", timeout: 5000 });
  } catch {}
}

function parseCreatedAtMs(createdAt) {
  if (typeof createdAt !== "string") return 0;
  const parsed = Date.parse(createdAt);
  return Number.isFinite(parsed) ? parsed : 0;
}

/**
 * Keeps the active workspace's matrix-robobot agent tab count within maxTabs by
 * archiving the oldest non-running tabs other than keepAgentId.
 */
export async function pruneOlderWorkspaceTabs(
  client,
  { workspaceId, keepAgentId, maxTabs = MAX_ACTIVE_WORKSPACE_TABS, logger = console } = {},
) {
  if (!workspaceId) return { archivedIds: [], totalCount: 0 };

  const agentsById = new Map();
  let cursor;
  do {
    const page = await client.agents.list({
      filter: { labels: { source: BRIDGE_SOURCE_LABEL } },
      sort: [{ key: "created_at", direction: "desc" }],
      page: { limit: 200, ...(cursor ? { cursor } : {}) },
    });
    for (const entry of page?.entries ?? []) {
      const candidate = entry?.agent;
      if (!candidate || candidate.archivedAt) continue;
      if (candidate.workspaceId !== workspaceId) continue;
      if (candidate.labels?.source !== BRIDGE_SOURCE_LABEL) continue;
      agentsById.set(candidate.id, candidate);
    }
    cursor = page?.pageInfo?.nextCursor ?? undefined;
  } while (cursor);

  // Account for the just-created agent even if directory projection lags slightly.
  if (keepAgentId && !agentsById.has(keepAgentId)) {
    agentsById.set(keepAgentId, {
      id: keepAgentId,
      workspaceId,
      status: "idle",
      createdAt: new Date().toISOString(),
      archivedAt: null,
      labels: { source: BRIDGE_SOURCE_LABEL },
    });
  }

  const totalCount = agentsById.size;
  if (totalCount <= maxTabs) {
    return { archivedIds: [], totalCount };
  }

  const excessCount = totalCount - maxTabs;
  const archivable = [...agentsById.values()]
    .filter((candidate) =>
      candidate.id !== keepAgentId
      && candidate.status !== "running"
      && candidate.status !== "initializing")
    .sort((a, b) => {
      const delta = parseCreatedAtMs(a.createdAt) - parseCreatedAtMs(b.createdAt);
      return delta !== 0 ? delta : String(a.id).localeCompare(String(b.id));
    });

  const archivedIds = [];
  for (const candidate of archivable.slice(0, excessCount)) {
    try {
      await client.agents.ref(candidate.id).archive();
      archivedIds.push(candidate.id);
    } catch (error) {
      logger.error(`Failed to archive older Paseo tab ${candidate.id}: ${error?.message || String(error)}`);
    }
  }
  return { archivedIds, totalCount };
}

export async function runPaseoBridge(request, deps = {}) {
  const createClient = deps.createClient ?? createPaseoClient;
  const stopAgent = deps.stopAgent ?? defaultStopAgent;
  const stdout = deps.stdout ?? process.stdout;
  const stderr = deps.stderr ?? process.stderr;
  const exit = deps.exit ?? ((code) => process.exit(code));
  const signalTarget = deps.signalTarget ?? process;
  const maxTabs = deps.maxTabs ?? MAX_ACTIVE_WORKSPACE_TABS;
  const logError = (message) => stderr.write(`${message}\n`);

  const client = createClient({
    url: process.env.PASEO_WS_URL || "ws://127.0.0.1:6767/ws",
    password: process.env.PASEO_DAEMON_PASSWORD || undefined,
  });

  let workspace = null;
  let createdAgent = null;
  let runningAgent = null;
  let prunePromise = null;
  let closePromise = null;
  let shuttingDown = false;

  const pruneOnce = () => {
    if (!prunePromise) {
      const workspaceId = workspace?.id || createdAgent?.workspaceId;
      if (!workspaceId || !createdAgent) {
        prunePromise = Promise.resolve();
      } else {
        prunePromise = pruneOlderWorkspaceTabs(client, {
          workspaceId,
          keepAgentId: createdAgent.id,
          maxTabs,
          logger: { error: logError },
        }).catch((error) => {
          logError(`Failed to prune older Paseo tabs: ${error?.message || String(error)}`);
        });
      }
    }
    return prunePromise;
  };

  const closeClientOnce = () => {
    if (!closePromise) {
      closePromise = Promise.resolve()
        .then(() => client.close())
        .catch(() => {
          // Closing a failed connection must not hide the original error.
        });
    }
    return closePromise;
  };

  const cleanupOnSignal = async () => {
    if (shuttingDown) return;
    shuttingDown = true;
    const active = runningAgent;
    runningAgent = null;
    if (active) {
      try { await stopAgent(active.id); } catch {}
    }
    await pruneOnce();
    await closeClientOnce();
    exit(1);
  };

  const signalListeners = [];
  if (signalTarget) {
    for (const signal of ["SIGTERM", "SIGINT"]) {
      const listener = () => {
        void cleanupOnSignal();
      };
      signalTarget.on(signal, listener);
      signalListeners.push([signal, listener]);
    }
  }

  try {
    await client.connect();
    workspace = await client.workspaces.open({ cwd: request.cwd || process.cwd() });
    createdAgent = await workspace.agents.create({
      config: {
        provider: request.provider,
        systemPrompt: request.systemPrompt || "",
        options: {
          permission: {
            read: "deny",
            list: "deny",
            glob: "deny",
            grep: "deny",
            edit: "deny",
            bash: "deny",
            task: "deny",
            external_directory: "deny",
            lsp: "deny",
            skill: { "*": "deny" },
            todowrite: "deny",
            question: "deny",
            webfetch: "deny",
            websearch: "allow",
            codesearch: "deny",
            doom_loop: "deny",
            repo_clone: "deny",
            repo_overview: "deny",
          },
        },
      },
      prompt: request.prompt,
      images: request.images?.length ? request.images : undefined,
      outputSchema: {
        type: "object",
        properties: { answer: { type: "string" } },
        required: ["answer"],
        additionalProperties: false,
      },
      labels: { source: BRIDGE_SOURCE_LABEL },
    });
    runningAgent = createdAgent;

    let reportUsageLimit;
    const usageLimitFailure = new Promise((resolve) => {
      reportUsageLimit = (message) => resolve({ type: "usage_limit", message });
    });

    const timeline = createdAgent.timeline.subscribe(({ event }) => {
      const message = extractErrorMessage(event);
      if (isUsageLimitError(message)) reportUsageLimit(message);
    });

    let result;
    try {
      await timeline.ready;
      const recent = await createdAgent.timeline.refetch({ direction: "tail", limit: 100 });
      const missedUsageLimit = recent.entries.find(({ item }) =>
        item?.type === "error" && isUsageLimitError(item.message));
      if (missedUsageLimit) reportUsageLimit(missedUsageLimit.item.message);

      const timeoutMs = typeof request.timeoutMs === "number" && request.timeoutMs > 10000
        ? request.timeoutMs - 5000
        : (request.timeoutMs || 1200000);

      while (true) {
        const outcome = await Promise.race([
          createdAgent.waitForFinish(timeoutMs).then((finished) => ({ type: "result", finished })),
          usageLimitFailure,
        ]);

        const usageLimitMessage = outcome.type === "usage_limit"
          ? outcome.message
          : isUsageLimitError(outcome.finished?.error) ? outcome.finished.error : null;

        if (usageLimitMessage) {
          const agentToStop = runningAgent;
          runningAgent = null;
          if (agentToStop) {
            await stopAgent(agentToStop.id);
          }
          throw new Error(usageLimitMessage);
        }

        if (outcome.finished.status !== "timeout") {
          result = outcome.finished;
          if (result.status === "idle" || result.status === "error") {
            runningAgent = null;
          }
          break;
        } else {
          // waitForFinish hit the internal timeoutMs
          throw new Error(`Paseo turn timed out after ${Math.round(timeoutMs / 1000)}s`);
        }
      }
    } finally {
      await timeline.release();
    }

    if (result.status !== "idle" || !result.lastMessage) {
      throw new Error(result.error || `Paseo turn ended with status ${result.status}`);
    }
    const parsed = JSON.parse(result.lastMessage);
    if (typeof parsed.answer !== "string" || !parsed.answer.trim()) {
      throw new Error("Paseo returned no schema answer");
    }

    await pruneOnce();
    stdout.write(JSON.stringify({ answer: parsed.answer }) + "\n");
    return { ok: true, answer: parsed.answer };
  } catch (error) {
    const message = error?.message || String(error);
    logError(message);
    return { ok: false, error: message };
  } finally {
    if (signalTarget) {
      for (const [signal, listener] of signalListeners) {
        signalTarget.off(signal, listener);
      }
    }
    if (runningAgent && !shuttingDown) {
      const agentToStop = runningAgent;
      runningAgent = null;
      try {
        await stopAgent(agentToStop.id);
      } catch {}
    }
    if (!shuttingDown) {
      await pruneOnce();
      await closeClientOnce();
    }
  }
}
