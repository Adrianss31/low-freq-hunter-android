/* Measurement-only helpers, shared by the browser and regression tests. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.LFHModel = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const finite = (v) => typeof v === "number" && Number.isFinite(v);
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  function union(intervals, from, to) {
    const list = intervals
      .map(([a, b]) => [Math.max(a, from), Math.min(b, to)])
      .filter(([a, b]) => b > a)
      .sort((a, b) => a[0] - b[0]);
    const out = [];
    for (const i of list) {
      const p = out[out.length - 1];
      if (p && i[0] <= p[1]) p[1] = Math.max(p[1], i[1]);
      else out.push(i.slice());
    }
    return out;
  }
  function validIntervals(a, b, gaps = []) {
    let t = a;
    const result = [];
    for (const [x, y] of union(
      gaps.map((g) => [g.startT, g.endT]),
      a,
      b,
    )) {
      if (x > t) result.push([t, x]);
      t = y;
    }
    if (t < b) result.push([t, b]);
    return result;
  }
  function eventUnion(events, view, gaps = []) {
    return union(
      events.flatMap((e) => validIntervals(e.startT, e.endT, gaps)),
      view.t0,
      view.t1,
    ).reduce((n, [a, b]) => n + b - a, 0);
  }
  function decodeSlices(payload) {
    const encoding = payload.encoding || {
      fmin: 20,
      fmax: 200,
      bins: 64,
      seconds: 30,
      minDb: -110,
      maxDb: -20,
    };
    const gaps = payload.gaps || [];
    const slices = (payload.slices || [])
      .map((s) => {
        let bytes;
        try {
          bytes =
            typeof atob === "function"
              ? Uint8Array.from(atob(s.b64), (c) => c.charCodeAt(0))
              : Uint8Array.from(Buffer.from(s.b64, "base64"));
        } catch {
          bytes = new Uint8Array();
        }
        return {
          ...s,
          start: s.startT ?? s.t - encoding.seconds,
          end: s.endT ?? s.t,
          values: Array.from(
            bytes,
            (v) =>
              encoding.minDb + (v / 255) * (encoding.maxDb - encoding.minDb),
          ),
        };
      })
      .filter((s) => s.values.length === encoding.bins && s.end > s.start)
      .sort((a, b) => a.start - b.start);
    return { encoding, slices, gaps };
  }
  function sliceAt(data, t) {
    let a = 0,
      b = data.slices.length;
    while (a < b) {
      const m = (a + b) >> 1;
      if (data.slices[m].start <= t) a = m + 1;
      else b = m;
    }
    const s = data.slices[a - 1];
    if (!s || t >= s.end || data.gaps.some((g) => t >= g.startT && t < g.endT))
      return null;
    return s;
  }
  function valueAt(data, t, hz) {
    const e = data.encoding;
    if (hz < e.fmin || hz >= e.fmax) return null;
    const s = sliceAt(data, t);
    return s
      ? s.values[
          clamp(
            Math.floor(((hz - e.fmin) / (e.fmax - e.fmin)) * e.bins),
            0,
            e.bins - 1,
          )
        ]
      : null;
  }
  function visibleContrast(data, v) {
    const e = data.encoding;
    const vals = [];
    for (const s of data.slices) {
      const a = Math.max(s.start, v.t0),
        b = Math.min(s.end, v.t1);
      if (b <= a || !validIntervals(a, b, data.gaps).length) continue;
      for (let i = 0; i < s.values.length; i++) {
        const hz = e.fmin + ((i + 0.5) / e.bins) * (e.fmax - e.fmin);
        if (hz >= v.f0 && hz <= v.f1 && finite(s.values[i]))
          vals.push(s.values[i]);
      }
    }
    if (!vals.length) return { min: -110, max: -60 };
    vals.sort((a, b) => a - b);
    const min = vals[Math.floor((vals.length - 1) * 0.03)],
      max = Math.max(min + 18, vals[Math.floor((vals.length - 1) * 0.997)]);
    return { min, max };
  }
  function profile(data, v) {
    const e = data.encoding;
    const p = new Float64Array(e.bins);
    let weight = 0;
    for (const s of data.slices) {
      const a = Math.max(s.start, v.t0),
        b = Math.min(s.end, v.t1);
      if (b <= a) continue;
      const w = validIntervals(a, b, data.gaps).reduce(
        (n, [a, b]) => n + b - a,
        0,
      );
      if (!w) continue;
      weight += w;
      for (let i = 0; i < e.bins; i++)
        p[i] += Math.pow(10, s.values[i] / 10) * w;
    }
    return weight
      ? Array.from(p, (v, i) => ({
          hz: e.fmin + ((i + 0.5) / e.bins) * (e.fmax - e.fmin),
          db: 10 * Math.log10(v / weight),
        }))
      : [];
  }
  function clampView(v, bounds) {
    const full = bounds.t1 - bounds.t0;
    const span = clamp(v.t1 - v.t0, Math.min(120, full), full);
    const t0 = clamp(v.t0, bounds.t0, bounds.t1 - span);
    const fspan = clamp(v.f1 - v.f0, 1, 250),
      f0 = clamp(v.f0, 0, 250 - fspan);
    return { t0, t1: t0 + span, f0, f1: f0 + fspan };
  }
  const median = (values) => {
    if (!values.length) return null;
    const a = values.slice().sort((a, b) => a - b),
      i = a.length >> 1;
    return a.length % 2 ? a[i] : (a[i - 1] + a[i]) / 2;
  };
  function comparison(nights, selected) {
    const list = nights.filter(
      (n) =>
        n.recorded &&
        !n.live &&
        n.date < selected.date &&
        n.comparisonKey === selected.comparisonKey,
    );
    const noiseSeconds = median(list.map((n) => n.noiseSeconds));
    return {
      count: list.length,
      noiseSeconds,
      deltaSeconds:
        !selected.recorded || noiseSeconds === null
          ? null
          : selected.noiseSeconds - noiseSeconds,
      start: median(
        list
          .map((n) => (finite(n.noiseStart) ? n.noiseStart - n.from : null))
          .filter(finite),
      ),
      end: median(
        list
          .map((n) => (finite(n.noiseEnd) ? n.noiseEnd - n.from : null))
          .filter(finite),
      ),
    };
  }
  function clock(t, zone = "UTC", seconds = false) {
    if (!finite(t)) return "—";
    return new Intl.DateTimeFormat("it-IT", {
      timeZone: zone,
      hourCycle: "h23",
      hour: "2-digit",
      minute: "2-digit",
      ...(seconds ? { second: "2-digit" } : {}),
    }).format(new Date(t * 1000));
  }
  function duration(seconds) {
    if (!finite(seconds)) return "—";
    const m = Math.max(0, Math.round(seconds / 60));
    return m >= 60
      ? `${Math.floor(m / 60)}h${String(m % 60).padStart(2, "0")}`
      : `${m}m`;
  }
  function elapsed(seconds) {
    const s = Math.max(0, Math.floor(seconds));
    if (s < 60) return `${s}s`;
    return s < 3600
      ? `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s`
      : duration(s);
  }
  function liveStatus(state, online, now) {
    if (!online) return { kind: "offline", label: "NON RAGGIUNGIBILE" };
    if (!state?.running)
      return { kind: "stopped", label: "REGISTRAZIONE FERMA" };
    if (!state.lastDataAt || now - state.lastDataAt > 15000)
      return { kind: "stale", label: "NESSUN DATO RECENTE" };
    if (Object.keys(state.activeBands || {}).length)
      return { kind: "noise", label: "RUMORE IN CORSO" };
    return {
      kind: "quiet",
      label: state.mode === "listen" ? "SOLO ASCOLTO" : "SILENZIO",
    };
  }
  class RequestGate {
    constructor() {
      this.id = 0;
    }
    next() {
      return ++this.id;
    }
    current(id) {
      return this.id === id;
    }
  }
  class Alerts {
    constructor() {
      this.prev = null;
      this.lastSeen = 0;
      this.offline = false;
      this.disconnected = false;
      this.startedDown = 0;
      this.long = new Set();
    }
    observe(s, now) {
      const result = [];
      const resumed = this.disconnected;
      this.disconnected = false;
      this.startedDown = 0;
      if (this.offline) {
        result.push({
          type: "online",
          text: "Telefono di nuovo raggiungibile",
        });
        this.offline = false;
      }
      if (this.prev && !resumed) {
        const before = this.prev.activeBands || {},
          after = s.activeBands || {};
        for (const [k, t] of Object.entries(after))
          if (before[k] !== t)
            result.push({
              type: "start",
              band: k,
              text: "È iniziato un rumore",
            });
        for (const k of Object.keys(before))
          if (!(k in after))
            result.push({ type: "end", band: k, text: "Il rumore è finito" });
        if (
          (this.prev.running && this.prev.mode === "rec") !==
          (s.running && s.mode === "rec")
        )
          result.push({
            type: "recording",
            text:
              s.running && s.mode === "rec"
                ? "Registrazione ripartita"
                : "Registrazione ferma",
          });
        if (
          s.batteryPct != null &&
          s.batteryPct < 20 &&
          (this.prev.batteryPct == null || this.prev.batteryPct >= 20)
        )
          result.push({
            type: "battery",
            text: `Batteria telefono al ${s.batteryPct}%`,
          });
        for (const [k, t] of Object.entries(after)) {
          const key = `${k}:${t}`;
          if (now / 1000 - t >= 1800 && !this.long.has(key)) {
            this.long.add(key);
            result.push({
              type: "long",
              band: k,
              text: "Rumore continuo da oltre 30 minuti",
            });
          }
        }
      } else {
        for (const [k, t] of Object.entries(s.activeBands || {}))
          if (now / 1000 - t >= 1800) this.long.add(`${k}:${t}`);
      }
      this.prev = s;
      this.lastSeen = now;
      return result;
    }
    disconnect(now) {
      this.disconnected = true;
      if (!this.startedDown) this.startedDown = this.lastSeen || now;
      if (!this.offline && now - this.startedDown >= 60000) {
        this.offline = true;
        return [
          {
            type: "offline",
            text: "Telefono non raggiungibile da oltre un minuto",
          },
        ];
      }
      return [];
    }
  }
  function levelScales(points, channels) {
    const audio = { min: -85, max: -45 },
      vibration = { min: -100, max: 0 };
    const keys = [{ key: "ref", unit: "dBFS" }, ...channels];
    for (const c of keys) {
      const range = c.unit === "dB rel 1 g" ? vibration : audio;
      for (const p of points)
        for (const map of [p.min, p.max, p.lv]) {
          const db = map?.[c.key];
          if (finite(db)) {
            range.min = Math.min(range.min, Math.floor(db / 5) * 5);
            range.max = Math.max(range.max, Math.ceil(db / 5) * 5);
          }
        }
      if (finite(c.thr)) {
        range.min = Math.min(range.min, Math.floor(c.thr / 5) * 5);
        range.max = Math.max(range.max, Math.ceil(c.thr / 5) * 5);
      }
    }
    return { audio, vibration };
  }
  return {
    levelScales,
    finite,
    clamp,
    union,
    validIntervals,
    eventUnion,
    decodeSlices,
    sliceAt,
    valueAt,
    visibleContrast,
    profile,
    clampView,
    comparison,
    median,
    clock,
    duration,
    elapsed,
    liveStatus,
    RequestGate,
    Alerts,
  };
});
