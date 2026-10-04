/* Controller regressions execute production controller with controlled HTTP timing.
 * Canvas pixels are separately verified in a real browser. */
const { test } = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const M = require("../app/src/main/assets/dashboard-model.js");
const source = fs.readFileSync(
  require.resolve("../app/src/main/assets/dashboard.js"),
  "utf8",
);
const current = "2026-10-03",
  archive = "2026-10-02";
const from = Date.parse("2026-10-03T21:00:00Z") / 1000;
function night(date) {
  const start = from - (date === archive ? 86400 : 0);
  return {
    date,
    from: start,
    to: start + 43200,
    timezone: "UTC",
    encoding: {
      fmin: 20,
      fmax: 200,
      bins: 64,
      seconds: 30,
      minDb: -110,
      maxDb: -20,
    },
    summary: {
      date,
      from: start,
      to: start + 43200,
      recorded: true,
      live: date === current,
      comparisonKey: "a",
      noiseSeconds: 10,
      eventsCount: 1,
      coverageSeconds: 100,
      gapCount: 0,
      gapSeconds: 0,
    },
    channels: [],
    sessions: [],
    slices: [],
    gaps: [],
    events: [],
  };
}
function deferred() {
  let resolve;
  const promise = new Promise((r) => (resolve = r));
  return { promise, resolve };
}
function harness() {
  let time = Date.parse("2026-10-04T01:00:00Z"),
    serial = 0;
  const nodes = new Map(),
    timers = [],
    intervals = [],
    requests = [],
    reports = [],
    downloads = [];
  function node(tag) {
    return {
      tag,
      events: {},
      setPointerCapture() {},
      getBoundingClientRect() {
        return { left: 0, top: 0, width: 600, height: 40 };
      },
      style: { setProperty() {} },
      classList: { add() {}, remove() {}, toggle() {} },
      dataset: {},
      clientWidth: 600,
      hidden: false,
      value: "",
      textContent: "",
      disabled: false,
      children: [],
      append(...xs) {
        this.children.push(...xs);
      },
      prepend(x) {
        this.children.unshift(x);
      },
      replaceChildren(...xs) {
        this.children = xs;
      },
      setAttribute(k, v) {
        this[k] = v;
      },
      closest() {
        return this;
      },
      addEventListener(type, fn) {
        this.events[type] = fn;
      },
      focus() {},
      getClientRects() {
        return [1];
      },
      querySelectorAll() {
        return [];
      },
      remove() {},
      click() {
        if (this.download) downloads.push(this.download);
      },
    };
  }
  const doc = {
    getElementById(id) {
      if (!nodes.has(id)) nodes.set(id, node(id));
      return nodes.get(id);
    },
    createElement: node,
    createTextNode: (t) => ({ textContent: t }),
    querySelectorAll() {
      return [];
    },
    addEventListener() {},
    body: node("body"),
    activeElement: null,
  };
  let state = {
    running: true,
    mode: "rec",
    sessionId: "session",
    now: time,
    lastDataAt: time,
    nightDate: current,
    timezone: "UTC",
    startedAt: from * 1000,
    activeBands: {},
    cfg: { bands: [] },
    batteryPct: 60,
  };
  let hook = null,
    renderer;
  class Renderer {
    constructor(a, cb) {
      this.s = a;
      this.cb = cb;
      this.target = null;
      this.reduced = true;
      renderer = this;
    }
    setNight(n) {
      this.target = { t0: n.from, t1: n.to, f0: 20, f1: 200 };
      this.view = {...this.target};
      this.cb.onView();
    }
    updateNight() {}
    invalidate() {}
    flashCursor() {}
    setView(v) {
      this.target = v;
      this.view = {...v};
      this.cb.onView();
    }
    exportReport(points) {
      reports.push({ date: this.s.night.date, points });
      return { toBlob: (cb) => cb({}) };
    }
    exportView() {
      return { toBlob: (cb) => cb({}) };
    }
    resize() {}
  }
  const timersApi = {
    setTimeout(fn, ms) {
      const id = ++serial;
      timers.push({ id, fn, due: time + ms });
      return id;
    },
    clearTimeout(id) {
      const t = timers.find((t) => t.id === id);
      if (t) t.cancelled = true;
    },
    setInterval(fn, ms) {
      intervals.push({ fn, ms, due: time + ms });
      return ++serial;
    },
  };
  class TestDate extends Date {
    static now() {
      return time;
    }
  }
  class TestURL extends URL {
    static createObjectURL() {
      return "blob:test";
    }
    static revokeObjectURL() {}
  }
  const fetch = async (address, options = {}) => {
    const u = new URL(address, "http://test");
    requests.push(u);
    let data;
    const supplied = hook?.(u, options);
    if (supplied !== undefined) data = await supplied;
    else if (u.pathname === "/api/state")
      data = { ...state, now: time, lastDataAt: time };
    else if (u.pathname === "/api/nights")
      data = [night(current).summary, night(archive).summary];
    else if (u.pathname === "/api/night")
      data = night(u.searchParams.get("date"));
    else if (u.pathname === "/api/night/levels")
      data = { points: [{ date: u.searchParams.get("date") }] };
    else data = { ok: true };
    return {
      ok: true,
      status: 200,
      json: async () => structuredClone(data),
      text: async () => "",
      blob: async () => ({}),
    };
  };
  const ctx = {
    LFHModel: M,
    LFHRenderer: Renderer,
    document: doc,
    window: { addEventListener() {} },
    location: { search: "?k=test" },
    localStorage: {
      getItem() {
        return null;
      },
      setItem() {},
    },
    fetch,
    URL: TestURL,
    URLSearchParams,
    Date: TestDate,
    Intl,
    performance: { now: () => time },
    AbortController,
    DOMException,
    structuredClone,
    ...timersApi,
  };
  vm.runInNewContext(source, ctx);
  async function flush() {
    for (let i = 0; i < 30; i++) await new Promise(setImmediate);
  }
  async function advance(ms) {
    time += ms;
    for (const it of intervals)
      if (it.due <= time) {
        it.due = time + it.ms;
        it.fn();
      }
    const due = timers.filter((t) => !t.cancelled && !t.ran && t.due <= time);
    for (const t of due) {
      t.ran = true;
      t.fn();
    }
    await flush();
  }
  return {
    nodes,
    requests,
    reports,
    downloads,
    flush,
    advance,
    get renderer() {
      return renderer;
    },
    setHook(h) {
      hook = h;
    },
  };
}
test("live refresh cannot abort navigation or retain current-night bounds for archive", async () => {
  const h = harness();
  await h.flush();
  const wait = deferred();
  let count = 0;
  h.setHook((u) => {
    if (u.pathname === "/api/night" && u.searchParams.get("date") === archive) {
      count++;
      if (count === 1) return wait.promise;
    }
  });
  h.nodes.get("previousNight").onclick();
  await h.flush();
  await h.advance(6000);
  assert.equal(
    count,
    1,
    "background poll must leave explicit selection pending",
  );
  wait.resolve(night(archive));
  await h.flush();
  assert.equal(h.renderer.target.t0, night(archive).from);
});
test("report does not combine nights when selection changes during its request", async () => {
  const h = harness();
  await h.flush();
  const wait = deferred();
  h.setHook((u) => {
    if (
      u.pathname === "/api/night/levels" &&
      u.searchParams.get("date") === current
    )
      return wait.promise;
  });
  const job = h.nodes.get("exportReport").onclick();
  await h.flush();
  h.nodes.get("previousNight").onclick();
  await h.flush();
  wait.resolve({ points: [{ date: current }] });
  await job;
  assert.equal(h.reports.length, 0);
  assert.equal(h.downloads.length, 0);
});
test("nonresponding HTTP poll becomes offline and still produces the sixty-second alert", async () => {
  const h = harness();
  await h.flush();
  h.setHook((u, options) => {
    if (u.pathname === "/api/state")
      return new Promise((_, reject) =>
        options.signal?.addEventListener(
          "abort",
          () => reject(new DOMException("aborted", "AbortError")),
          { once: true },
        ),
      );
  });
  await h.advance(1000);
  await h.advance(12000);
  assert.equal(h.nodes.get("connectionLabel").textContent, "TEL OFFLINE");
  await h.advance(60000);
  assert.ok(h.renderer.s.log.some((x) => x.type === "offline"));
});

test("controller selects the actual clicked event lane", async () => {
  const h = harness();
  await h.flush();
  const n = h.renderer.s.night,
    start = n.from;
  n.channels = [
    { id: "A", key: "a", label: "A", unit: "dBFS" },
    { id: "B", key: "b", label: "B", unit: "dBFS" },
  ];
  n.events = [
    { band: "a", startT: start + 100, endT: start + 200 },
    { band: "b", startT: start + 150, endT: start + 160 },
  ];
  const c = h.nodes.get("eventLanes"),
    e = {
      button: 0,
      pointerId: 1,
      clientX: (155 / 43200) * 600,
      clientY: 24,
      preventDefault() {},
    };
  c.events.pointerdown(e);
  c.events.pointerup(e);
  assert.equal(h.renderer.target.t0, start + 90);
});
test("CSV keeps its original night filename after navigation", async () => {
  const h = harness();
  await h.flush();
  const wait = deferred();
  h.setHook((u) => {
    if (u.pathname === "/api/night/eventi.csv") return wait.promise;
  });
  const job = h.nodes.get("exportCsv").onclick();
  h.nodes.get("previousNight").onclick();
  await h.flush();
  wait.resolve({});
  await job;
  assert.equal(h.downloads[0], `LFH_${current}_eventi.csv`);
});
