import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";

import {
  BRIDGE_SOURCE_LABEL,
  MAX_ACTIVE_WORKSPACE_TABS,
  pruneOlderWorkspaceTabs,
  runPaseoBridge,
} from "./bridge.mjs";

function makeAgentSnapshot({
  id,
  workspaceId = "wks_target",
  createdAt = "2026-11-28T00:00:00.000Z",
  status = "idle",
  archivedAt = null,
  labels = { source: BRIDGE_SOURCE_LABEL },
}) {
  return {
    id,
    workspaceId,
    cwd: "/home/robo/matrix-robobot",
    createdAt,
    updatedAt: createdAt,
    status,
    archivedAt,
    labels,
  };
}

function createFakeClient({
  workspaceId = "wks_target",
  existingAgents = [],
  createdAgentId = "agent-new",
  createdAt = "2026-11-28T01:00:00.000Z",
  includeCreatedInList = true,
  pageSize = 200,
  timelineEvents = [],
  refetchEntries = [],
  waitForFinish,
  archiveErrorForIds = new Set(),
  listError = null,
} = {}) {
  const agents = [...existingAgents];
  const archivedIds = [];
  const listCalls = [];
  let closed = false;

  const client = {
    async connect() {},
    async close() {
      closed = true;
    },
    workspaces: {
      async open() {
        return {
          id: workspaceId,
          agents: {
            async create(options) {
              const snapshot = makeAgentSnapshot({
                id: createdAgentId,
                workspaceId,
                createdAt,
                status: "running",
                labels: options.labels ?? {},
              });
              if (includeCreatedInList) {
                agents.push(snapshot);
              }
              return {
                id: createdAgentId,
                workspaceId,
                timeline: {
                  subscribe(handler) {
                    const sub = {
                      ready: Promise.resolve().then(() => {
                        for (const event of timelineEvents) {
                          handler(event);
                        }
                      }),
                      async release() {},
                    };
                    return sub;
                  },
                  async refetch() {
                    return { entries: refetchEntries };
                  },
                },
                async waitForFinish(timeoutMs) {
                  if (waitForFinish) {
                    return waitForFinish(timeoutMs, snapshot);
                  }
                  snapshot.status = "idle";
                  return {
                    status: "idle",
                    lastMessage: JSON.stringify({ answer: "ok" }),
                  };
                },
                async archive() {
                  archivedIds.push(createdAgentId);
                  snapshot.archivedAt = "2026-11-28T01:05:00.000Z";
                },
              };
            },
          },
        };
      },
    },
    agents: {
      async list(options = {}) {
        listCalls.push(options);
        if (listError) {
          throw listError;
        }
        let filtered = agents.filter((a) => !a.archivedAt);
        const labelFilter = options.filter?.labels;
        if (labelFilter) {
          filtered = filtered.filter((a) =>
            Object.entries(labelFilter).every(([k, v]) => a.labels?.[k] === v));
        }
        filtered.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));

        const offset = options.page?.cursor ? Number(options.page.cursor) : 0;
        const limit = Math.min(options.page?.limit ?? pageSize, pageSize);
        const slice = filtered.slice(offset, offset + limit);
        const nextOffset = offset + slice.length;
        const hasMore = nextOffset < filtered.length;
        return {
          entries: slice.map((agent) => ({ agent })),
          pageInfo: {
            nextCursor: hasMore ? String(nextOffset) : null,
            prevCursor: null,
            hasMore,
          },
        };
      },
      ref(agentOrId) {
        const id = typeof agentOrId === "string" ? agentOrId : agentOrId.id;
        return {
          id,
          async archive() {
            if (archiveErrorForIds.has(id)) {
              throw new Error(`archive failed for ${id}`);
            }
            archivedIds.push(id);
            const target = agents.find((a) => a.id === id);
            if (target) {
              target.archivedAt = "2026-11-28T01:05:00.000Z";
            }
          },
        };
      },
    },
  };

  return {
    client,
    agents,
    archivedIds,
    listCalls,
    isClosed: () => closed,
  };
}

function createBufferWriter() {
  let text = "";
  return {
    write(chunk) {
      text += String(chunk);
    },
    toString() {
      return text;
    },
  };
}

test("pruneOlderWorkspaceTabs keeps all tabs when workspace has 5 or fewer bot tabs", async () => {
  const existingAgents = [1, 2, 3, 4, 5].map((n) =>
    makeAgentSnapshot({
      id: `bot-${n}`,
      createdAt: `2026-11-28T00:0${n}:00.000Z`,
    }));
  const { client, archivedIds } = createFakeClient({ existingAgents });

  const result = await pruneOlderWorkspaceTabs(client, {
    workspaceId: "wks_target",
    keepAgentId: "bot-5",
  });

  assert.equal(result.totalCount, 5);
  assert.deepEqual(result.archivedIds, []);
  assert.deepEqual(archivedIds, []);
});

test("pruneOlderWorkspaceTabs archives only oldest bot tabs in target workspace beyond 5", async () => {
  const existingAgents = [
    // Older bot tabs in target workspace (out of order)
    makeAgentSnapshot({ id: "bot-3", createdAt: "2026-11-28T00:03:00.000Z" }),
    makeAgentSnapshot({ id: "bot-1", createdAt: "2026-11-28T00:01:00.000Z" }),
    makeAgentSnapshot({ id: "bot-2", createdAt: "2026-11-28T00:02:00.000Z" }),
    makeAgentSnapshot({ id: "bot-4", createdAt: "2026-11-28T00:04:00.000Z" }),
    makeAgentSnapshot({ id: "bot-5", createdAt: "2026-11-28T00:05:00.000Z" }),
    makeAgentSnapshot({ id: "bot-6", createdAt: "2026-11-28T00:06:00.000Z" }),
    makeAgentSnapshot({ id: "bot-7", createdAt: "2026-11-28T00:07:00.000Z" }),
    // User tabs in same workspace must never be counted or archived
    makeAgentSnapshot({ id: "user-1", createdAt: "2026-11-20T00:00:00.000Z", labels: {} }),
    makeAgentSnapshot({ id: "user-2", createdAt: "2026-11-21T00:00:00.000Z", labels: { source: "other" } }),
    // Bot tabs in a different workspace (even with same cwd) must never be counted or archived
    makeAgentSnapshot({
      id: "other-wks-1",
      workspaceId: "wks_other",
      createdAt: "2026-11-19T00:00:00.000Z",
    }),
    // Already-archived tab must be ignored
    makeAgentSnapshot({
      id: "bot-archived",
      createdAt: "2026-11-18T00:00:00.000Z",
      archivedAt: "2026-11-18T01:00:00.000Z",
    }),
  ];
  const { client, archivedIds } = createFakeClient({
    existingAgents,
    pageSize: 2,
  });

  const result = await pruneOlderWorkspaceTabs(client, {
    workspaceId: "wks_target",
    keepAgentId: "bot-7",
  });

  assert.equal(result.totalCount, 7);
  assert.deepEqual(archivedIds, ["bot-1", "bot-2"]);
  assert.deepEqual(result.archivedIds, ["bot-1", "bot-2"]);
});

test("pruneOlderWorkspaceTabs skips running/initializing tabs and keepAgentId", async () => {
  const existingAgents = [
    makeAgentSnapshot({ id: "bot-1-running", createdAt: "2026-11-28T00:01:00.000Z", status: "running" }),
    makeAgentSnapshot({ id: "bot-2-init", createdAt: "2026-11-28T00:02:00.000Z", status: "initializing" }),
    makeAgentSnapshot({ id: "bot-3-idle", createdAt: "2026-11-28T00:03:00.000Z", status: "idle" }),
    makeAgentSnapshot({ id: "bot-4-error", createdAt: "2026-11-28T00:04:00.000Z", status: "error" }),
    makeAgentSnapshot({ id: "bot-5-idle", createdAt: "2026-11-28T00:05:00.000Z", status: "idle" }),
    makeAgentSnapshot({ id: "bot-6-keep", createdAt: "2026-11-28T00:00:30.000Z", status: "idle" }),
  ];
  const { client, archivedIds } = createFakeClient({ existingAgents });

  const result = await pruneOlderWorkspaceTabs(client, {
    workspaceId: "wks_target",
    keepAgentId: "bot-6-keep",
  });

  assert.equal(result.totalCount, 6);
  assert.deepEqual(archivedIds, ["bot-3-idle"]);
});

test("pruneOlderWorkspaceTabs counts keepAgentId even if missing from directory response and continues past archive failures", async () => {
  const existingAgents = [
    makeAgentSnapshot({ id: "bot-1", createdAt: "2026-11-28T00:01:00.000Z" }),
    makeAgentSnapshot({ id: "bot-2", createdAt: "2026-11-28T00:02:00.000Z" }),
    makeAgentSnapshot({ id: "bot-3", createdAt: "2026-11-28T00:03:00.000Z" }),
    makeAgentSnapshot({ id: "bot-4", createdAt: "2026-11-28T00:04:00.000Z" }),
    makeAgentSnapshot({ id: "bot-5", createdAt: "2026-11-28T00:05:00.000Z" }),
    makeAgentSnapshot({ id: "bot-6", createdAt: "2026-11-28T00:06:00.000Z" }),
  ];
  const errors = [];
  const { client, archivedIds } = createFakeClient({
    existingAgents,
    archiveErrorForIds: new Set(["bot-1"]),
  });

  const result = await pruneOlderWorkspaceTabs(client, {
    workspaceId: "wks_target",
    keepAgentId: "bot-new-unlisted",
    logger: { error: (msg) => errors.push(msg) },
  });

  assert.equal(result.totalCount, 7);
  assert.deepEqual(archivedIds, ["bot-2"]);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /bot-1/);
});

test("runPaseoBridge keeps newly created tab active on success and does not archive when total <= 5", async () => {
  const existingAgents = [1, 2, 3, 4].map((n) =>
    makeAgentSnapshot({
      id: `bot-${n}`,
      createdAt: `2026-11-28T00:0${n}:00.000Z`,
    }));
  const fake = createFakeClient({
    existingAgents,
    createdAgentId: "bot-5",
  });
  const stdout = createBufferWriter();
  const stderr = createBufferWriter();
  const stoppedIds = [];
  const signalTarget = new EventEmitter();

  const outcome = await runPaseoBridge(
    { provider: "opencode/hy4-flash-free", prompt: "Describe image", cwd: "/home/robo/matrix-robobot" },
    {
      createClient: () => fake.client,
      stopAgent: async (id) => { stoppedIds.push(id); },
      stdout,
      stderr,
      signalTarget,
    },
  );

  assert.equal(outcome.ok, true);
  assert.deepEqual(JSON.parse(stdout.toString()), { answer: "ok" });
  assert.equal(stderr.toString(), "");
  assert.deepEqual(fake.archivedIds, []);
  assert.deepEqual(stoppedIds, []);
  assert.equal(fake.isClosed(), true);
  assert.equal(signalTarget.listenerCount("SIGTERM"), 0);
  assert.equal(signalTarget.listenerCount("SIGINT"), 0);
});

test("runPaseoBridge keeps newly created tab active on success and archives older tabs beyond 5", async () => {
  const existingAgents = [1, 2, 3, 4, 5, 6].map((n) =>
    makeAgentSnapshot({
      id: `bot-${n}`,
      createdAt: `2026-11-28T00:0${n}:00.000Z`,
    }));
  const fake = createFakeClient({
    existingAgents,
    createdAgentId: "bot-7",
  });
  const stdout = createBufferWriter();
  const stderr = createBufferWriter();

  const outcome = await runPaseoBridge(
    { provider: "opencode/hy4-flash-free", prompt: "Describe image", cwd: "/home/robo/matrix-robobot" },
    {
      createClient: () => fake.client,
      stopAgent: async () => {},
      stdout,
      stderr,
      signalTarget: new EventEmitter(),
    },
  );

  assert.equal(outcome.ok, true);
  assert.deepEqual(JSON.parse(stdout.toString()), { answer: "ok" });
  assert.deepEqual(fake.archivedIds, ["bot-1", "bot-2"]);
  assert.equal(fake.agents.find((a) => a.id === "bot-7")?.archivedAt, null);
});

test("runPaseoBridge still returns answer when tab pruning fails", async () => {
  const fake = createFakeClient({
    createdAgentId: "bot-new",
    listError: new Error("daemon list unavailable"),
  });
  const stdout = createBufferWriter();
  const stderr = createBufferWriter();

  const outcome = await runPaseoBridge(
    { provider: "opencode/hy4-flash-free", prompt: "Describe image" },
    {
      createClient: () => fake.client,
      stopAgent: async () => {},
      stdout,
      stderr,
      signalTarget: new EventEmitter(),
    },
  );

  assert.equal(outcome.ok, true);
  assert.deepEqual(JSON.parse(stdout.toString()), { answer: "ok" });
  assert.match(stderr.toString(), /Failed to prune older Paseo tabs/);
});

test("runPaseoBridge stops turn on usage limit without archiving new tab, and prunes older tabs > 5", async () => {
  const existingAgents = [1, 2, 3, 4, 5].map((n) =>
    makeAgentSnapshot({
      id: `bot-${n}`,
      createdAt: `2026-11-28T00:0${n}:00.000Z`,
    }));
  const fake = createFakeClient({
    existingAgents,
    createdAgentId: "bot-6",
    timelineEvents: [{ event: { type: "error", error: "Free usage exceeded" } }],
    waitForFinish: () => new Promise(() => {}),
  });
  const stdout = createBufferWriter();
  const stderr = createBufferWriter();
  const stoppedIds = [];

  const outcome = await runPaseoBridge(
    { provider: "opencode/hy4-flash-free", prompt: "Describe image" },
    {
      createClient: () => fake.client,
      stopAgent: async (id) => {
        stoppedIds.push(id);
        const created = fake.agents.find((a) => a.id === id);
        if (created) created.status = "idle";
      },
      stdout,
      stderr,
      signalTarget: new EventEmitter(),
    },
  );

  assert.equal(outcome.ok, false);
  assert.deepEqual(stoppedIds, ["bot-6"]);
  assert.deepEqual(fake.archivedIds, ["bot-1"]);
  assert.equal(fake.agents.find((a) => a.id === "bot-6")?.archivedAt, null);
  assert.match(stderr.toString(), /Free usage exceeded/);
  assert.equal(stdout.toString(), "");
  assert.equal(fake.isClosed(), true);
});

test("runPaseoBridge stops running turn on timeout without archiving new tab, and prunes older tabs > 5", async () => {
  const existingAgents = [1, 2, 3, 4, 5].map((n) =>
    makeAgentSnapshot({
      id: `bot-${n}`,
      createdAt: `2026-11-28T00:0${n}:00.000Z`,
    }));
  const fake = createFakeClient({
    existingAgents,
    createdAgentId: "bot-6",
    waitForFinish: async () => ({ status: "timeout", lastMessage: null }),
  });
  const stdout = createBufferWriter();
  const stderr = createBufferWriter();
  const stoppedIds = [];

  const outcome = await runPaseoBridge(
    { provider: "opencode/hy4-flash-free", prompt: "Describe image", timeoutMs: 25000 },
    {
      createClient: () => fake.client,
      stopAgent: async (id) => {
        stoppedIds.push(id);
        const created = fake.agents.find((a) => a.id === id);
        if (created) created.status = "idle";
      },
      stdout,
      stderr,
      signalTarget: new EventEmitter(),
    },
  );

  assert.equal(outcome.ok, false);
  assert.deepEqual(stoppedIds, ["bot-6"]);
  assert.deepEqual(fake.archivedIds, ["bot-1"]);
  assert.equal(fake.agents.find((a) => a.id === "bot-6")?.archivedAt, null);
  assert.match(stderr.toString(), /timed out/i);
});

test("runPaseoBridge keeps tab active on SIGTERM, stops running turn, prunes older tabs > 5, and exits", async () => {
  const existingAgents = [1, 2, 3, 4, 5].map((n) =>
    makeAgentSnapshot({
      id: `bot-${n}`,
      createdAt: `2026-11-28T00:0${n}:00.000Z`,
    }));
  const signalTarget = new EventEmitter();
  let resolveWait;
  let exitCode = null;
  let signalDoneResolve;
  const signalDone = new Promise((resolve) => {
    signalDoneResolve = resolve;
  });

  const fake = createFakeClient({
    existingAgents,
    createdAgentId: "bot-6",
    waitForFinish: () => new Promise((resolve) => {
      resolveWait = resolve;
      signalTarget.emit("SIGTERM");
    }),
  });
  const stoppedIds = [];

  const runPromise = runPaseoBridge(
    { provider: "opencode/hy4-flash-free", prompt: "Describe image" },
    {
      createClient: () => fake.client,
      stopAgent: async (id) => {
        stoppedIds.push(id);
        const created = fake.agents.find((a) => a.id === id);
        if (created) created.status = "idle";
      },
      stdout: createBufferWriter(),
      stderr: createBufferWriter(),
      signalTarget,
      exit: (code) => {
        exitCode = code;
        signalDoneResolve();
        resolveWait({ status: "idle", lastMessage: JSON.stringify({ answer: "cancelled" }) });
      },
    },
  );

  await signalDone;
  await runPromise;

  assert.equal(exitCode, 1);
  assert.deepEqual(stoppedIds, ["bot-6"]);
  assert.deepEqual(fake.archivedIds, ["bot-1"]);
  assert.equal(fake.agents.find((a) => a.id === "bot-6")?.archivedAt, null);
  assert.equal(fake.isClosed(), true);
  assert.equal(MAX_ACTIVE_WORKSPACE_TABS, 5);
});
