import { createPaseoClient } from "@getpaseo/client";
import { readFileSync } from "node:fs";

const request = JSON.parse(readFileSync(0, "utf8"));
const client = createPaseoClient({
  url: process.env.PASEO_WS_URL || "ws://127.0.0.1:6767/ws",
  password: process.env.PASEO_DAEMON_PASSWORD || undefined,
});

let agent;
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
          websearch: "deny",
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

  const result = await agent.waitForFinish(request.timeoutMs || 1200000);
  if (result.status !== "idle" || !result.lastMessage) {
    throw new Error(result.error || `Paseo turn ended with status ${result.status}`);
  }
  const parsed = JSON.parse(result.lastMessage);
  if (typeof parsed.answer !== "string" || !parsed.answer.trim()) {
    throw new Error("Paseo returned no schema answer");
  }
  try {
    await agent.archive();
  } catch (error) {
    console.error(`Failed to archive Paseo agent: ${error?.message || String(error)}`);
  }
  process.stdout.write(JSON.stringify({ answer: parsed.answer }) + "\n");
} catch (error) {
  console.error(error?.message || String(error));
  process.exitCode = 1;
} finally {
  try {
    await client.close();
  } catch {
    // Closing a failed connection must not hide the original error.
  }
}
