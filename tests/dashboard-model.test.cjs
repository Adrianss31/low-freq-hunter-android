const { test } = require("node:test");
const assert = require("node:assert/strict");
const M = require("../app/src/main/assets/dashboard-model.js");
const enc = {
  fmin: 20,
  fmax: 200,
  bins: 64,
  seconds: 30,
  minDb: -110,
  maxDb: -20,
};
const make = (start, end, db = -60) => ({
  startT: start,
  endT: end,
  t: end,
  b64: Buffer.alloc(64, Math.round(((db + 110) / 90) * 255)).toString("base64"),
});
test("stored slices remain absent outside native frequency and within gaps", () => {
  const data = M.decodeSlices({
    encoding: enc,
    slices: [make(100, 130)],
    gaps: [{ startT: 110, endT: 115 }],
  });
  assert.equal(M.valueAt(data, 105, 10), null);
  assert.equal(M.valueAt(data, 112, 62), null);
  assert.equal(M.valueAt(data, 140, 62), null);
  assert.ok(Math.abs(M.valueAt(data, 105, 62) + 60) < 0.4);
});
test("contrast ignores missing/future data and keeps 18 dB minimum", () => {
  const data = M.decodeSlices({
    encoding: enc,
    slices: [make(100, 130)],
    gaps: [],
  });
  const c = M.visibleContrast(data, { t0: 100, t1: 200, f0: 0, f1: 250 });
  assert.ok(c.max - c.min >= 18);
  assert.deepEqual(
    M.visibleContrast(data, { t0: 140, t1: 200, f0: 20, f1: 200 }),
    { min: -110, max: -60 },
  );
});
test("events use their union after clipping and gap subtraction", () => {
  assert.equal(
    M.eventUnion(
      [
        { startT: 90, endT: 120 },
        { startT: 110, endT: 140 },
      ],
      { t0: 100, t1: 130 },
      [{ startT: 115, endT: 120 }],
    ),
    25,
  );
});
test("zoom is clamped to the real night length including clock changes", () => {
  const v = M.clampView(
    { t0: -30, t1: 10, f0: -10, f1: 280 },
    { t0: 0, t1: 13 * 3600 },
  );
  assert.equal(v.t0, 0);
  assert.equal(v.t1, 120);
  assert.equal(v.f0, 0);
  assert.equal(v.f1, 250);
});
test("power mean is computed in power domain, excludes gaps and does not create unsupported bins", () => {
  const d = M.decodeSlices({
    encoding: enc,
    slices: [make(100, 130, -80), make(130, 160, -40)],
    gaps: [],
  });
  const p = M.profile(d, { t0: 100, t1: 160, f0: 0, f1: 250 });
  const peak = p.find((x) => x.hz > 60);
  assert.ok(peak.db > -44 && peak.db < -42);
  assert.ok(p.every((x) => x.hz >= 20 && x.hz <= 200));
});
test("unrecorded nights and differing measurement groups are excluded from baseline", () => {
  const selected = {
    date: "2026-10-04",
    recorded: true,
    live: true,
    comparisonKey: "a",
    noiseSeconds: 3600,
  };
  const list = [
    selected,
    {
      date: "2026-10-03",
      recorded: true,
      comparisonKey: "a",
      noiseSeconds: 1800,
    },
    {
      date: "2026-10-02",
      recorded: false,
      comparisonKey: "a",
      noiseSeconds: 0,
    },
    {
      date: "2026-10-01",
      recorded: true,
      comparisonKey: "b",
      noiseSeconds: 9000,
    },
  ];
  const c = M.comparison(list, selected);
  assert.equal(c.count, 1);
  assert.equal(c.noiseSeconds, 1800);
  assert.equal(c.deltaSeconds, 1800);
});
test("phone timezone determines cursor clock, rather than PC timezone", () => {
  assert.equal(
    M.clock(Date.parse("2026-10-03T21:38:00Z") / 1000, "Europe/Rome"),
    "23:38",
  );
});
test("stalled microphone is not silence, stopped server is not offline", () => {
  assert.equal(
    M.liveStatus(
      { running: true, mode: "rec", lastDataAt: 1000, activeBands: {} },
      true,
      100000,
    ).kind,
    "stale",
  );
  assert.equal(
    M.liveStatus({ running: false, lastDataAt: 1000 }, true, 100000).kind,
    "stopped",
  );
  assert.equal(
    M.liveStatus(
      { running: true, lastDataAt: 99999, activeBands: {} },
      false,
      100000,
    ).kind,
    "offline",
  );
});
test("outdated night responses cannot replace newly selected date", () => {
  const gate = new M.RequestGate();
  const a = gate.next(),
    b = gate.next();
  assert.equal(gate.current(a), false);
  assert.equal(gate.current(b), true);
});
test("first observation is quiet, changes alert once and reconnect does not replay event starts", () => {
  const alerts = new M.Alerts();
  const state = {
    running: true,
    mode: "rec",
    lastDataAt: 100000,
    activeBands: { A: 90 },
    batteryPct: 90,
  };
  assert.deepEqual(alerts.observe(state, 100000), []);
  assert.deepEqual(alerts.observe(state, 101000), []);
  assert.ok(
    alerts
      .observe({ ...state, activeBands: {} }, 102000)
      .some((x) => x.type === "end"),
  );
  assert.deepEqual(alerts.observe({ ...state, activeBands: {} }, 103000), []);
  alerts.disconnect(104000);
  assert.ok(alerts.disconnect(165000).some((x) => x.type === "offline"));
  assert.ok(alerts.observe(state, 166000).some((x) => x.type === "online"));
  assert.ok(!alerts.observe(state, 167000).some((x) => x.type === "start"));
});
test("minute formatting carries 60 minutes into the hour", () =>
  assert.equal(M.duration(3 * 3600 + 59 * 60 + 40), "4h00"));

test("a missing selected night cannot be reported as quieter than baseline", () => {
  const c = M.comparison(
    [
      {
        date: "2026-10-01",
        recorded: true,
        comparisonKey: "a",
        noiseSeconds: 3600,
      },
    ],
    {
      date: "2026-10-02",
      recorded: false,
      comparisonKey: "a",
      noiseSeconds: 0,
    },
  );
  assert.equal(c.deltaSeconds, null);
});

test("level axes retain short extrema and keep vibration on its own scale", () => {
  const s = M.levelScales(
    [{ min: { a: -95, v: -70 }, max: { a: -20, v: -10 } }],
    [
      { key: "a", unit: "dBFS", thr: -60 },
      { key: "v", unit: "dB rel 1 g", thr: -45 },
    ],
  );
  assert.ok(s.audio.min <= -95 && s.audio.max >= -20);
  assert.ok(s.vibration.min <= -70 && s.vibration.max >= -10);
  assert.notDeepEqual(s.audio, s.vibration);
});

test("saving edited fields preserves unrelated concurrent phone changes", () => {
  const original = [
    { id: "A", center: 50, thr: -60, enabled: true },
    { id: "B", center: 62, thr: -55, enabled: true },
  ];
  const draft = structuredClone(original);
  draft[0].center = 51;
  const latest = structuredClone(original);
  latest[0].thr = -65;
  latest[1].center = 63;
  const merged = M.mergeBands(original, draft, latest);
  assert.equal(merged[0].center, 51);
  assert.equal(merged[0].thr, -65);
  assert.equal(merged[1].center, 63);
});
test("band additions/removals are explicit and concurrent ID collision is rejected", () => {
  assert.deepEqual(
    M.mergeBands(
      [{ id: "A" }],
      [{ id: "B", center: 62 }],
      [{ id: "A" }, { id: "C" }],
    ),
    [{ id: "C" }, { id: "B", center: 62 }],
  );
  assert.throws(
    () => M.mergeBands([], [{ id: "A" }], [{ id: "A", center: 100 }]),
    /conflitto/i,
  );
});
test("event hit testing uses the clicked lane and ignores empty lane space", () => {
  const n = {
    channels: [{ key: "a" }, { key: "b" }],
    events: [
      { band: "a", startT: 100, endT: 200 },
      { band: "b", startT: 150, endT: 160 },
    ],
  };
  const hit = M.eventAt(n, 155, 24, 40);
  assert.equal(hit.band, "b");
  assert.equal(M.eventAt(n, 170, 24, 40), null);
});
test("outage duration uses the PC clock even when the phone clock differs", () => {
  const a = new M.Alerts();
  a.observe({ running: true, activeBands: {} }, 100000 + 7200000, 100000);
  assert.deepEqual(a.disconnect(110000), []);
  assert.ok(a.disconnect(160001).some((e) => e.type === "offline"));
});
