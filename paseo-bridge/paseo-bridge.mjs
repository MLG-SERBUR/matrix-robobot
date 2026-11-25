import { createPaseoClient } from "@getpaseo/client";
import { readFileSync } from "node:fs";

const request = JSON.parse(readFileSync(0, "utf8"));
const client = createPaseoClient({
  url: process.env.PASEO_WS_URL || "ws://127.0.0.1:6767/ws",
  password: process.env.PASEO_DAEMON_PASSWORD || undefined,
});

function isUsageLimitError(message) {
  return /(?:free\s+usage\s+exceeded|usage\s+(?:limit|quota)\s+(?:exceeded|reached)|quota\s+(?:exceeded|exhausted)|insufficient\s+(?:credits|balance))/i
    .test(message || "");
}

function extractErrorMessage(event) {
  if (!event) return "";
  if (event.type === "turn_failed") return event.error || "";
  if (event.type === "error") return event.error || "";
  if (event.type === "timeline" && event.item?.type === "error") {
    return event.item.message || "";
  }
  return "";
}

async function stopAgent(agentId) {
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

let agent;

for (const signal of ["SIGTERM", "SIGINT"]) {
  process.on(signal, async () => {
    if (agent) {
      const a = agent;
      agent = null;
      try { await stopAgent(a.id); } catch {}
      try { await a.archive(); } catch {}
    }
    process.exit(1);
  });
}

try {
  await client.connect();
  const workspace = await client.workspaces.open({ cwd: request.cwd || process.cwd() });
  agent = await workspace.agents.create({
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
    labels: { source: "matrix-robobot" },
  });

  let reportUsageLimit;
  const usageLimitFailure = new Promise((resolve) => {
    reportUsageLimit = (message) => resolve({ type: "usage_limit", message });
  });

  const timeline = agent.timeline.subscribe(({ event }) => {
    const message = extractErrorMessage(event);
    if (isUsageLimitError(message)) reportUsageLimit(message);
  });

  let result;
  try {
    await timeline.ready;
    const recent = await agent.timeline.refetch({ direction: "tail", limit: 100 });
    const missedUsageLimit = recent.entries.find(({ item }) =>
      item?.type === "error" && isUsageLimitError(item.message));
    if (missedUsageLimit) reportUsageLimit(missedUsageLimit.item.message);

    const timeoutMs = typeof request.timeoutMs === "number" && request.timeoutMs > 10000
      ? request.timeoutMs - 5000
      : (request.timeoutMs || 1200000);

    while (true) {
      const outcome = await Promise.race([
        agent.waitForFinish(timeoutMs).then((finished) => ({ type: "result", finished })),
        usageLimitFailure,
      ]);

      const usageLimitMessage = outcome.type === "usage_limit"
        ? outcome.message
        : isUsageLimitError(outcome.finished?.error) ? outcome.finished.error : null;

      if (usageLimitMessage) {
        const agentToClean = agent;
        agent = null;
        await stopAgent(agentToClean.id);
        try {
          await agentToClean.archive();
        } catch (archiveError) {
          console.error(`Failed to archive quota-rejected Paseo agent: ${archiveError?.message || archiveError}`);
        }
        throw new Error(usageLimitMessage);
      }

      if (outcome.finished.status !== "timeout") {
        result = outcome.finished;
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
  try {
    await agent.archive();
    agent = null;
  } catch (error) {
    console.error(`Failed to archive Paseo agent: ${error?.message || String(error)}`);
  }
  process.stdout.write(JSON.stringify({ answer: parsed.answer }) + "\n");
} catch (error) {
  console.error(error?.message || String(error));
  process.exitCode = 1;
} finally {
  if (agent) {
    const agentToClean = agent;
    agent = null;
    try {
      await stopAgent(agentToClean.id);
    } catch {}
    try {
      await agentToClean.archive();
    } catch {}
  }
  try {
    await client.close();
  } catch {
    // Closing a failed connection must not hide the original error.
  }
}
