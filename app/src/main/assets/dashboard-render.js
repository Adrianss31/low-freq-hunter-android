/* Canvas rendering uses stored frequency/time coverage; interpolation is visual only. */
(function () {
  "use strict";
  const M = LFHModel,
    $ = (id) => document.getElementById(id),
    INK = "#10100e",
    LIGHT = "#efece3",
    ORANGE = "#ff5a1f";
  const PALETTES = {
    caldo: [
      [5, 10, 30],
      [30, 20, 90],
      [120, 30, 130],
      [210, 60, 120],
      [255, 140, 50],
      [255, 220, 100],
      [255, 255, 235],
    ],
    arancio: [
      [12, 12, 10],
      [52, 26, 14],
      [140, 50, 16],
      [255, 90, 31],
      [255, 180, 130],
      [255, 246, 236],
    ],
    grigio: [
      [10, 10, 9],
      [60, 59, 55],
      [140, 138, 128],
      [239, 236, 227],
    ],
  };
  class LFHRenderer {
    constructor(state, callbacks) {
      this.s = state;
      this.cb = callbacks;
      this.view = null;
      this.target = null;
      this.contrast = { min: -110, max: -60 };
      this.reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
      this.motionQuery = matchMedia("(prefers-reduced-motion: reduce)");
      this.motionQuery.addEventListener("change", (e) => {
        this.reduced = e.matches;
        if (e.matches) {
          this.tween = null;
          this.wipe = null;
          this.fade = null;
          this.flash = null;
          if (this.target) this.view = { ...this.target };
        }
        this.invalidate();
      });
      this.dirty = true;
      this.live = [];
      this.peaks = [];
      this.bandLevels = new Map();
      this.lastFrame = performance.now();
      this.lastBitmap = 0;
      requestAnimationFrame((t) => this.tick(t));
    }
    fit(canvas) {
      const w =
          canvas.clientWidth ||
          Number(canvas.dataset.width) ||
          canvas.width ||
          1,
        h =
          canvas.clientHeight ||
          Number(canvas.dataset.height) ||
          canvas.height ||
          1,
        dpr = Math.min(devicePixelRatio || 1, 2);
      if (
        canvas.width !== Math.round(w * dpr) ||
        canvas.height !== Math.round(h * dpr)
      ) {
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
      }
      const g = canvas.getContext("2d");
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.font = '9px "Geist Mono", monospace';
      g.textBaseline = "alphabetic";
      g.lineWidth = 1;
      g.setLineDash([]);
      return [g, w, h];
    }
    canvas(w, h) {
      const c = document.createElement("canvas");
      c.dataset.width = w;
      c.dataset.height = h;
      return c;
    }
    invalidate() {
      this.dirty = true;
    }
    resize() {
      this.lastBitmap = 0;
      if (this.target) this.prepare(this.target, true);
      this.invalidate();
    }
    setNight(night, direction) {
      const snaps = {};
      if (this.view && !this.reduced)
        for (const id of [
          "waterfall",
          "eventLanes",
          "levelsCanvas",
          "profileCanvas",
        ]) {
          const src = $(id),
            c = document.createElement("canvas");
          c.width = src.width;
          c.height = src.height;
          c.getContext("2d").drawImage(src, 0, 0);
          snaps[id] = c;
        }
      this.view = {
        t0: night.from,
        t1: night.to,
        f0: this.target?.f0 ?? 20,
        f1: this.target?.f1 ?? 200,
      };
      this.target = { ...this.view };
      this.tween = null;
      this.fade = null;
      this.cache = null;
      this.oldCache = null;
      this.wipe = Object.keys(snaps).length
        ? { snaps, start: performance.now(), direction }
        : null;
      this.prepare(this.target, true);
      this.invalidate();
      this.cb.onView();
    }
    updateNight() {
      if (!this.target) return;
      this.prepare(this.target, true, false);
      this.invalidate();
    }
    setView(target, duration = 440) {
      if (!this.s.night) return;
      const next = M.clampView(target, {
        t0: this.s.night.from,
        t1: this.s.night.to,
      });
      this.target = next;
      if (this.reduced) {
        this.view = { ...next };
        this.tween = null;
      } else
        this.tween = {
          from: { ...this.view },
          to: next,
          start: performance.now(),
          duration,
        };
      this.prepare(next, false);
      this.invalidate();
      this.cb.onView();
    }
    recolor() {
      if (this.target) {
        this.prepare(this.target, true, true, 240);
        this.invalidate();
      }
    }
    prepare(v, force = false, dissolve = true, duration = 440) {
      const now = performance.now();
      if (!force && now - this.lastBitmap < 130) {
        this.pendingBitmap = { ...v };
        return;
      }
      this.pendingBitmap = null;
      this.lastBitmap = now;
      this.contrast = this.s.auto
        ? M.visibleContrast(this.s.data, v)
        : { min: this.s.min, max: this.s.max };
      const width = Math.max(
        100,
        Math.round(($("waterfall").clientWidth || 1000) / 2),
      );
      this.oldCache = dissolve ? this.cache : null;
      this.cache = this.bitmap(v, width, 180);
      this.mean = M.profile(this.s.data, v);
      this.fade =
        this.oldCache && !this.reduced ? { start: now, duration } : null;
      this.cb.onContrast?.();
      this.overviewCache = this.bitmap(
        { t0: this.s.night.from, t1: this.s.night.to, f0: 20, f1: 200 },
        Math.max(100, width),
        24,
      );
    }
    color(db) {
      const p = PALETTES[this.s.palette],
        x =
          M.clamp(
            (db - this.contrast.min) / (this.contrast.max - this.contrast.min),
            0,
            1,
          ) *
          (p.length - 1),
        i = Math.min(p.length - 2, Math.floor(x)),
        f = x - i;
      return p[i].map((v, k) => Math.round(v + (p[i + 1][k] - v) * f));
    }
    bitmap(v, w, h) {
      const c = document.createElement("canvas");
      c.width = w;
      c.height = h;
      const g = c.getContext("2d"),
        img = g.createImageData(w, h),
        e = this.s.data.encoding;
      for (let x = 0; x < w; x++) {
        const t = v.t0 + ((x + 0.5) / w) * (v.t1 - v.t0),
          slice = M.sliceAt(this.s.data, t);
        for (let y = 0; y < h; y++) {
          const hz = v.f1 - ((y + 0.5) / h) * (v.f1 - v.f0),
            i = (y * w + x) * 4;
          let rgb = [24, 23, 21];
          if (t > (this.s.state?.now || Date.now()) / 1000) rgb = [22, 22, 20];
          else if (slice && hz >= e.fmin && hz < e.fmax)
            rgb = this.color(
              slice.values[
                M.clamp(
                  Math.floor(((hz - e.fmin) / (e.fmax - e.fmin)) * e.bins),
                  0,
                  e.bins - 1,
                )
              ],
            );
          img.data.set([...rgb, 255], i);
        }
      }
      g.putImageData(img, 0, 0);
      return { canvas: c, view: { ...v } };
    }
    tick(t) {
      const dt = Math.min(100, t - this.lastFrame);
      this.lastFrame = t;
      let moving = false;
      if (this.tween) {
        const p = M.clamp((t - this.tween.start) / this.tween.duration, 0, 1),
          ease = 1 - Math.pow(1 - p, 4);
        for (const k of ["t0", "t1", "f0", "f1"])
          this.view[k] =
            this.tween.from[k] + (this.tween.to[k] - this.tween.from[k]) * ease;
        moving = true;
        if (p === 1) this.tween = null;
      }
      if (this.pendingBitmap && t - this.lastBitmap >= 130) {
        this.prepare(this.pendingBitmap, true);
        this.dirty = true;
      }
      if (this.fade && t - this.fade.start >= this.fade.duration) {
        this.fade = null;
        this.oldCache = null;
        this.dirty = true;
      }
      if (this.wipe && t - this.wipe.start >= 660) {
        this.wipe = null;
        this.dirty = true;
      }
      if (this.flash && t - this.flash.start >= 560) {
        this.flash = null;
        this.dirty = true;
      }
      if (this.selection && t - this.selection.start >= 440) {
        this.selection = null;
        this.dirty = true;
      }
      this.drawLive(dt);
      if (this.view) {
        if (
          this.dirty ||
          moving ||
          this.wipe ||
          this.fade ||
          this.flash ||
          this.selection
        ) {
          this.drawAll();
          this.dirty = false;
        } else if (this.s.night.summary.live && !this.reduced)
          this.drawWaterfall($("waterfall"));
      }
      this.cb.onFrame(t);
      requestAnimationFrame((x) => this.tick(x));
    }
    drawAll() {
      this.drawWaterfall($("waterfall"));
      this.drawEvents($("eventLanes"));
      this.drawLevels($("levelsCanvas"));
      this.drawAxis($("frequencyAxis"));
      this.drawProfile($("profileCanvas"));
      this.drawOverview();
      this.updateLegend();
    }
    drawLive(dt) {
      const [g, w, h] = this.fit($("liveSpectrum"));
      g.clearRect(0, 0, w, h);
      g.fillStyle = INK;
      g.fillRect(0, 0, w, h);
      const top = 28,
        bottom = h - 14,
        plotH = bottom - top,
        x = (f) => (f / 250) * w,
        y = (db) => top + (1 - M.clamp((db + 100) / 60, 0, 1)) * plotH;
      const state = this.s.state,
        online =
          this.s.online &&
          state?.running &&
          state?.lastDataAt &&
          Date.now() + this.s.offset - state.lastDataAt < 15000;
      const spec = online ? this.s.spectrum : null;
      const n = 251,
        alpha = this.reduced ? 1 : 1 - Math.pow(1 - 0.22, dt / 16.67);
      for (let i = 0; i < n; i++) {
        const index = spec?.binHz ? i / spec.binHz : 0;
        const j = Math.floor(index);
        const value = spec?.spec?.length
          ? (spec.spec[j] ?? -100) * (1 - (index - j)) +
            (spec.spec[j + 1] ?? spec.spec[j] ?? -100) * (index - j)
          : -100;
        this.live[i] =
          (this.live[i] ?? -100) + (value - (this.live[i] ?? -100)) * alpha;
        this.peaks[i] = Math.max(
          (this.peaks[i] ?? -100) - (0.07 * dt) / 16.67,
          this.live[i],
        );
      }
      for (let f = 0; f <= 250; f += 25) {
        g.strokeStyle = f % 50 ? "#181715" : "#24231f";
        g.beginPath();
        g.moveTo(x(f), top);
        g.lineTo(x(f), bottom);
        g.stroke();
        if (!(f % 50)) {
          g.fillStyle = "#5d5b55";
          g.textAlign = f === 250 ? "right" : "left";
          g.fillText(f, x(f) + (f === 250 ? -1 : 2), h - 2);
        }
      }
      for (let db = -100; db <= -40; db += 10) {
        g.strokeStyle = "#1c1b18";
        g.beginPath();
        g.moveTo(0, y(db));
        g.lineTo(w, y(db));
        g.stroke();
      }
      g.textAlign = "right";
      g.fillStyle = "#5d5b55";
      for (const db of [-50, -90])
        g.fillText("−" + Math.abs(db), w - 2, y(db) - 3);
      const labels = [];
      for (const b of state?.cfg?.bands || []) {
        if (b.center > 250) continue;
        const c = this.cb.color(b),
          xx = x(b.center);
        g.fillStyle = c + "2a";
        g.fillRect(x(b.lo), top, x(b.hi) - x(b.lo), plotH);
        g.strokeStyle = c;
        g.setLineDash([3, 3]);
        g.beginPath();
        g.moveTo(x(b.lo), y(b.thr));
        g.lineTo(x(b.hi), y(b.thr));
        g.stroke();
        g.setLineDash([]);
        let v = online ? state.levels?.[b.id] : null;
        if (M.finite(v)) {
          const old = this.bandLevels.get(b.id) ?? v;
          v =
            old +
            (v - old) * (this.reduced ? 1 : 1 - Math.pow(1 - 0.08, dt / 16.67));
          this.bandLevels.set(b.id, v);
        }
        labels.push({ b, c, xx, xc: xx, v });
      }
      labels.sort((a, b) => a.xx - b.xx);
      let right = -22;
      for (const l of labels) {
        l.xc = Math.max(22, Math.min(w - 22, Math.max(l.xx, right + 44)));
        right = l.xc;
      }
      for (let i = labels.length - 2; i >= 0; i--)
        labels[i].xc = Math.min(labels[i].xc, labels[i + 1].xc - 44);
      for (const l of labels) {
        const { b, c, xx, xc, v } = l;
        g.textAlign = "center";
        g.fillStyle = c;
        g.font = '600 9px "Geist Mono", monospace';
        g.fillText(b.center + " HZ", xc, 9);
        g.fillStyle = M.finite(v) && v >= b.thr ? ORANGE : LIGHT;
        g.font = "700 11px Doto, monospace";
        g.fillText(M.finite(v) ? v.toFixed(1).replace("-", "−") : "—", xc, 22);
        if (Math.abs(xx - xc) > 1) {
          g.strokeStyle = c + "99";
          g.beginPath();
          g.moveTo(xc, 25);
          g.lineTo(xx, 30);
          g.stroke();
        }
        if (M.finite(v)) {
          g.fillStyle = c;
          g.beginPath();
          g.arc(xx, y(v), 2.5, 0, Math.PI * 2);
          g.fill();
        }
      }
      g.strokeStyle = "rgba(255,90,31,.55)";
      g.lineWidth = 1;
      g.beginPath();
      this.peaks.forEach((v, i) =>
        i ? g.lineTo(x(i), y(v)) : g.moveTo(x(i), y(v)),
      );
      g.stroke();
      g.beginPath();
      g.moveTo(0, bottom);
      this.live.forEach((v, i) => g.lineTo(x(i), y(v)));
      g.lineTo(w, bottom);
      g.closePath();
      g.fillStyle = "rgba(239,236,227,.12)";
      g.fill();
      g.strokeStyle = LIGHT;
      g.lineWidth = 1.1;
      g.beginPath();
      this.live.forEach((v, i) =>
        i ? g.lineTo(x(i), y(v)) : g.moveTo(x(i), y(v)),
      );
      g.stroke();
      if (!online) {
        g.font = '600 10px "Geist Mono", monospace';
        g.fillStyle = "#55534c";
        g.textAlign = "center";
        g.fillText(
          state?.running ? "NESSUN DATO DAL TELEFONO" : "REGISTRAZIONE FERMA",
          w / 2,
          top + plotH / 2,
        );
      }
      g.textAlign = "left";
    }
    x(t, w, v = this.view) {
      return ((t - v.t0) / (v.t1 - v.t0)) * w;
    }
    y(f, h, v = this.view) {
      return ((v.f1 - f) / (v.f1 - v.f0)) * h;
    }
    blit(g, cache, w, h, v = this.view, alpha = 1) {
      if (!cache) return;
      g.save();
      g.globalAlpha = alpha;
      g.imageSmoothingEnabled = true;
      g.drawImage(
        cache.canvas,
        this.x(cache.view.t0, w, v),
        this.y(cache.view.f1, h, v),
        ((cache.view.t1 - cache.view.t0) / (v.t1 - v.t0)) * w,
        ((cache.view.f1 - cache.view.f0) / (v.f1 - v.f0)) * h,
      );
      g.restore();
    }
    timeTicks(v, w) {
      const spans = [300, 600, 900, 1800, 3600, 7200];
      const step = spans.find((s) => (s / (v.t1 - v.t0)) * w >= 70) || 10800;
      const out = [];
      for (let t = Math.ceil(v.t0 / step) * step; t <= v.t1; t += step)
        out.push(t);
      return out;
    }
    drawWaterfall(canvas, v = this.view, explicitCache = null, clean = false) {
      const [g, w, h] = this.fit(canvas);
      g.fillStyle = "#181715";
      g.fillRect(0, 0, w, h);
      if (explicitCache) this.blit(g, explicitCache, w, h, v);
      else {
        if (this.oldCache && this.fade) this.blit(g, this.oldCache, w, h, v);
        const alpha = this.fade
          ? 1 -
            Math.pow(
              1 -
                M.clamp(
                  (performance.now() - this.fade.start) / this.fade.duration,
                  0,
                  1,
                ),
              2,
            )
          : 1;
        this.blit(g, this.cache, w, h, v, alpha);
      }
      for (const t of this.timeTicks(v, w)) {
        g.strokeStyle = "rgba(255,255,255,.07)";
        g.beginPath();
        g.moveTo(this.x(t, w, v), 0);
        g.lineTo(this.x(t, w, v), h);
        g.stroke();
      }
      for (const c of this.s.night.channels || []) {
        if (c.center == null || c.center < v.f0 || c.center > v.f1) continue;
        g.strokeStyle = this.cb.color(c) + "55";
        g.setLineDash([2, 6]);
        g.beginPath();
        g.moveTo(0, this.y(c.center, h, v));
        g.lineTo(w, this.y(c.center, h, v));
        g.stroke();
        g.setLineDash([]);
      }
      for (const gap of this.s.night.gaps || []) {
        g.fillStyle = "rgba(255,170,0,.35)";
        g.fillRect(
          this.x(gap.startT, w, v),
          0,
          this.x(gap.endT, w, v) - this.x(gap.startT, w, v),
          h,
        );
      }
      const now = (Date.now() + this.s.offset) / 1000;
      if (now > v.t0 && now < v.t1) {
        const xx = this.x(now, w, v);
        g.fillStyle = "#161614";
        g.fillRect(xx, 0, w - xx, h);
        if (w - xx > 180) {
          g.font = '500 9px "Geist Mono", monospace';
          g.fillStyle = "#55534c";
          g.fillText("NON ANCORA REGISTRATO", xx + 10, 18);
        }
        if (this.s.night.summary.live) {
          const alpha = this.reduced
            ? 0.7
            : 0.55 + 0.45 * Math.sin(performance.now() / 380);
          const gradient = g.createLinearGradient(xx - 28, 0, xx, 0);
          gradient.addColorStop(0, "rgba(255,90,31,0)");
          gradient.addColorStop(1, `rgba(255,90,31,${alpha * 0.45})`);
          g.fillStyle = gradient;
          g.fillRect(xx - 28, 0, 28, h);
          g.fillStyle = ORANGE;
          g.fillRect(xx - 1, 0, 2, h);
        }
      }
      if (!this.s.data.slices.length) {
        g.font = '600 11px "Geist Mono", monospace';
        g.fillStyle = "#55534c";
        g.textAlign = "center";
        g.fillText(
          this.s.night.summary.recorded
            ? "SPETTROGRAMMA NON DISPONIBILE"
            : "NOTTE NON REGISTRATA",
          w / 2,
          h / 2,
        );
        g.textAlign = "left";
      }
      if (!clean) {
        this.crosshair(g, w, h, v, true);
        this.applyWipe(canvas, g, w, h);
      }
    }
    crosshair(g, w, h, v = this.view, horizontal = false) {
      const s = this.s;
      if (s.hover && s.hover.t >= v.t0 && s.hover.t <= v.t1) {
        g.strokeStyle = "rgba(239,236,227,.75)";
        g.lineWidth = 1;
        g.beginPath();
        g.moveTo(this.x(s.hover.t, w, v), 0);
        g.lineTo(this.x(s.hover.t, w, v), h);
        g.stroke();
        if (horizontal && s.hover.hz != null) {
          g.strokeStyle = "rgba(239,236,227,.5)";
          g.beginPath();
          g.moveTo(0, this.y(s.hover.hz, h, v));
          g.lineTo(w, this.y(s.hover.hz, h, v));
          g.stroke();
        }
      }
      if (s.lock && s.lock.t >= v.t0 && s.lock.t <= v.t1) {
        const x = this.x(s.lock.t, w, v);
        if (
          this.flash &&
          !this.reduced &&
          performance.now() >= this.flash.start
        ) {
          const p = M.clamp((performance.now() - this.flash.start) / 560, 0, 1);
          if (p >= 0) {
            g.strokeStyle = `rgba(255,90,31,${0.4 * (1 - p)})`;
            g.lineWidth = 2 + 22 * Math.pow(1 - p, 2);
            g.beginPath();
            g.moveTo(x, 0);
            g.lineTo(x, h);
            g.stroke();
          }
        }
        g.strokeStyle = ORANGE;
        g.lineWidth = 1.5;
        g.beginPath();
        g.moveTo(x, 0);
        g.lineTo(x, h);
        g.stroke();
      }
      if (s.drag?.moved) {
        const a = this.x(Math.min(s.drag.start, s.drag.end), w, v),
          b = this.x(Math.max(s.drag.start, s.drag.end), w, v);
        g.fillStyle = "rgba(255,90,31,.14)";
        g.fillRect(a, 0, b - a, h);
        g.strokeStyle = ORANGE;
        g.lineWidth = 1;
        g.strokeRect(a, 0, b - a, h);
      }
      if (this.selection && !this.reduced) {
        const p = M.clamp(
          (performance.now() - this.selection.start) / 440,
          0,
          1,
        );
        g.fillStyle = `rgba(255,90,31,${0.14 * (1 - p)})`;
        g.fillRect(
          this.x(this.selection.a, w, v),
          0,
          this.x(this.selection.b, w, v) - this.x(this.selection.a, w, v),
          h,
        );
      }
      g.lineWidth = 1;
    }
    releaseSelection(a, b) {
      this.selection = this.reduced ? null : { a, b, start: performance.now() };
    }
    flashCursor(delay = 0) {
      this.flash = this.reduced ? null : { start: performance.now() + delay };
      this.invalidate();
    }
    applyWipe(canvas, g, w, h) {
      if (!this.wipe || !this.wipe.snaps[canvas.id]) return;
      const p = M.clamp((performance.now() - this.wipe.start) / 660, 0, 1),
        e = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2,
        x = this.wipe.direction < 0 ? e * w : (1 - e) * w;
      g.save();
      g.beginPath();
      if (this.wipe.direction < 0) g.rect(x, 0, w - x, h);
      else g.rect(0, 0, x, h);
      g.clip();
      g.drawImage(this.wipe.snaps[canvas.id], 0, 0, w, h);
      g.restore();
      const a = this.wipe.direction < 0 ? x - 40 : x + 40,
        gradient = g.createLinearGradient(a, 0, x, 0);
      gradient.addColorStop(0, "rgba(255,90,31,0)");
      gradient.addColorStop(1, "rgba(255,90,31,.34)");
      g.fillStyle = gradient;
      g.fillRect(Math.min(a, x), 0, 40, h);
      g.fillStyle = ORANGE;
      g.fillRect(x - 1, 0, 2, h);
    }
    drawEvents(canvas, v = this.view, clean = false) {
      const [g, w, h] = this.fit(canvas);
      g.fillStyle = INK;
      g.fillRect(0, 0, w, h);
      const channels = this.s.night.channels || [],
        laneH = Math.min(7, (h - 15) / Math.max(1, channels.length) - 1);
      channels.forEach((c, i) => {
        const y = 15 + i * (laneH + 1);
        g.fillStyle = "#1f1e1b";
        g.fillRect(0, y, w, laneH);
        for (const e of this.s.night.events.filter((e) => e.band === c.key)) {
          g.fillStyle = this.cb.color(c);
          g.fillRect(
            this.x(e.startT, w, v),
            y,
            Math.max(2, this.x(e.endT, w, v) - this.x(e.startT, w, v)),
            laneH,
          );
        }
      });
      for (const gap of this.s.night.gaps) {
        const x = this.x(gap.startT, w, v);
        g.fillStyle = "#ffaa00";
        g.fillText("⚠", x - 3, 11);
        g.fillRect(x, 15, Math.max(2, this.x(gap.endT, w, v) - x), h - 15);
      }
      const now = (Date.now() + this.s.offset) / 1000;
      if (now < v.t1) {
        g.fillStyle = "rgba(16,16,14,.72)";
        g.fillRect(Math.max(0, this.x(now, w, v)), 0, w, h);
      }
      if (!clean) {
        this.crosshair(g, w, h, v);
        this.applyWipe(canvas, g, w, h);
      }
    }
    drawAxis(canvas, v = this.view) {
      const [g, w, h] = this.fit(canvas);
      g.clearRect(0, 0, w, h);
      g.fillStyle = INK;
      g.fillRect(0, 0, w, h);
      const span = v.f1 - v.f0,
        step = span <= 40 ? 5 : span <= 80 ? 10 : span <= 180 ? 20 : 50;
      g.textAlign = "right";
      g.font = '9px "Geist Mono", monospace';
      for (let f = Math.ceil(v.f0 / step) * step; f <= v.f1; f += step) {
        const y = this.y(f, h, v);
        g.strokeStyle = "#55534c";
        g.beginPath();
        g.moveTo(w - 5, y);
        g.lineTo(w, y);
        g.stroke();
        g.fillStyle = "#5d5b55";
        g.fillText(f, w - 8, M.clamp(y + 3, 9, h - 2));
      }
      for (const c of this.s.night.channels) {
        if (c.center == null || c.center < v.f0 || c.center > v.f1) continue;
        const y = this.y(c.center, h, v);
        g.fillStyle = INK;
        g.fillRect(0, y - 8, w, 15);
        g.fillStyle = this.cb.color(c);
        g.font = '600 10px "Geist Mono", monospace';
        g.fillText(c.center, w - 6, y + 3);
      }
      if (this.s.hover?.hz != null) {
        const y = M.clamp(this.y(this.s.hover.hz, h, v), 8, h - 8);
        g.fillStyle = ORANGE;
        g.fillRect(0, y - 8, w, 16);
        g.fillStyle = "#1b1b19";
        g.fillText(this.s.hover.hz.toFixed(1), w - 3, y + 4);
      }
      g.textAlign = "left";
    }
    drawProfile(canvas, v = this.view) {
      const [g, w, h] = this.fit(canvas);
      g.fillStyle = "#161614";
      g.fillRect(0, 0, w, h);
      const x = (db) =>
        M.clamp(
          (db - this.contrast.min) / (this.contrast.max - this.contrast.min),
          0,
          1,
        ) *
          (w - 8) +
        4;
      for (const c of this.s.night.channels) {
        if (c.center == null) continue;
        g.fillStyle = this.cb.color(c) + "40";
        g.fillRect(0, this.y(c.center, h, v) - 1, w, 2);
      }
      const draw = (profile, color, width) => {
        g.strokeStyle = color;
        g.lineWidth = width;
        g.beginPath();
        let started = false;
        for (const p of profile) {
          if (p.hz < v.f0 || p.hz > v.f1) continue;
          started
            ? g.lineTo(x(p.db), this.y(p.hz, h, v))
            : g.moveTo(x(p.db), this.y(p.hz, h, v));
          started = true;
        }
        g.stroke();
      };
      draw(this.mean || [], "#6b6962", 1.2);
      const at = (t) => {
        const s = M.sliceAt(this.s.data, t),
          e = this.s.data.encoding;
        return s
          ? s.values.map((db, i) => ({
              db,
              hz: e.fmin + ((i + 0.5) / e.bins) * (e.fmax - e.fmin),
            }))
          : [];
      };
      if (this.s.hover) draw(at(this.s.hover.t), LIGHT, 1.4);
      if (this.s.lock) draw(at(this.s.lock.t), ORANGE, 1.6);
      if (this.s.hover?.hz != null) {
        g.strokeStyle = "rgba(239,236,227,.5)";
        g.lineWidth = 1;
        g.beginPath();
        g.moveTo(0, this.y(this.s.hover.hz, h, v));
        g.lineTo(w, this.y(this.s.hover.hz, h, v));
        g.stroke();
      }
      this.applyWipe(canvas, g, w, h);
    }
    drawLevels(canvas, v = this.view, points = this.s.levels, clean = false) {
      const [g, w, h] = this.fit(canvas),
        bottom = h - 20,
        scales = M.levelScales(points, this.s.night.channels),
        hasV = this.s.night.channels.some((c) => c.unit === "dB rel 1 g"),
        axis = (db, scale) =>
          6 +
          (1 - M.clamp((db - scale.min) / (scale.max - scale.min), 0, 1)) *
            (bottom - 6),
        y = (db) => axis(db, scales.audio);
      g.fillStyle = INK;
      g.fillRect(0, 0, w, h);
      for (
        let db = scales.audio.min;
        db <= scales.audio.max;
        db += Math.max(
          10,
          Math.ceil((scales.audio.max - scales.audio.min) / 40) * 10,
        )
      ) {
        g.strokeStyle = "#24231f";
        g.beginPath();
        g.moveTo(0, y(db));
        g.lineTo(w, y(db));
        g.stroke();
        g.fillStyle = "#5d5b55";
        g.textAlign = "right";
        g.fillText(db, hasV ? 38 : w - 2, y(db) - 3);
      }
      if (hasV) {
        g.fillStyle = "#aaa6ba";
        g.textAlign = "right";
        for (
          let db = scales.vibration.min;
          db <= scales.vibration.max;
          db += 25
        )
          g.fillText("V " + db, w - 2, axis(db, scales.vibration) - 3);
      }
      g.textAlign = "left";
      for (const t of this.timeTicks(v, w)) {
        const x = this.x(t, w, v);
        g.strokeStyle = "#24231f";
        g.beginPath();
        g.moveTo(x, 0);
        g.lineTo(x, bottom);
        g.stroke();
        g.fillStyle = "#5d5b55";
        g.fillText(M.clock(t, this.s.zone), Math.min(x + 2, w - 38), h - 4);
      }
      const channels = [
        { key: "ref", color: "#6b6962" },
        ...this.s.night.channels.map((c) => ({
          ...c,
          color: this.cb.color(c),
        })),
      ];
      for (const c of channels) {
        if (!clean && this.s.hidden.has(c.key)) continue;
        const cy =
          c.unit === "dB rel 1 g" ? (db) => axis(db, scales.vibration) : y;
        g.lineWidth = c.key === "ref" ? 1 : 1.4;
        g.strokeStyle = c.color;
        if (c.thr != null) {
          g.setLineDash([4, 4]);
          g.globalAlpha = 0.53;
          g.beginPath();
          g.moveTo(0, cy(c.thr));
          g.lineTo(w, cy(c.thr));
          g.stroke();
          g.globalAlpha = 1;
          g.setLineDash([]);
        }
        let prev = null,
          ema = null;
        g.beginPath();
        for (const p of points) {
          const value = p.lv[c.key];
          if (!M.finite(value) || p.t < v.t0 || p.t > v.t1) {
            prev = null;
            ema = null;
            continue;
          }
          const gap = this.s.night.gaps.some(
              (gp) => p.t >= gp.startT && p.t < gp.endT,
            ),
            connected =
              prev &&
              prev.sessionId === p.sessionId &&
              p.from <= prev.to + 2 &&
              !gap;
          if (!connected) ema = value;
          else ema = ema + (value - ema) * 0.6;
          const x = this.x(p.t, w, v),
            yy = cy(ema);
          connected ? g.lineTo(x, yy) : g.moveTo(x, yy);
          prev = gap ? null : p;
        }
        g.stroke();
        g.lineWidth = 1;
        g.globalAlpha = 0.28;
        for (const p of points) {
          if (!M.finite(p.max?.[c.key]) || p.t < v.t0 || p.t > v.t1) continue;
          g.beginPath();
          g.moveTo(this.x(p.t, w, v), cy(p.min[c.key]));
          g.lineTo(this.x(p.t, w, v), cy(p.max[c.key]));
          g.stroke();
        }
        g.globalAlpha = 1;
      }
      for (const gap of this.s.night.gaps) {
        g.fillStyle = "rgba(255,170,0,.16)";
        g.fillRect(
          this.x(gap.startT, w, v),
          0,
          this.x(gap.endT, w, v) - this.x(gap.startT, w, v),
          bottom,
        );
      }
      if (!clean) {
        this.crosshair(g, w, h, v);
        this.applyWipe(canvas, g, w, h);
      }
    }
    drawOverview() {
      if ($("overview").hidden || !this.overviewCache) return;
      const [g, w, h] = this.fit($("overview"));
      g.fillStyle = INK;
      g.fillRect(0, 0, w, h);
      g.globalAlpha = 0.75;
      g.drawImage(this.overviewCache.canvas, 0, 0, w, h);
      g.globalAlpha = 1;
      const n = this.s.night,
        v = { t0: n.from, t1: n.to };
      for (const e of n.events) {
        g.fillStyle = ORANGE;
        g.fillRect(
          this.x(e.startT, w, v),
          h - 3,
          Math.max(1, this.x(e.endT, w, v) - this.x(e.startT, w, v)),
          3,
        );
      }
      for (let t = n.from; t <= n.to; t += 3 * 3600) {
        g.fillStyle = "#cfccc2";
        g.fillText(
          M.clock(t, this.s.zone).slice(0, 2),
          this.x(t, w, v) + 2,
          10,
        );
      }
      const a = this.x(this.view.t0, w, v),
        b = this.x(this.view.t1, w, v);
      g.fillStyle = "rgba(16,16,14,.62)";
      g.fillRect(0, 0, a, h);
      g.fillRect(b, 0, w - b, h);
      g.strokeStyle = LIGHT;
      g.lineWidth = 1.5;
      g.strokeRect(a, 0.75, b - a, h - 1.5);
    }
    updateLegend() {
      const t = this.s.hover?.t ?? this.s.lock?.t;
      if (t == null) {
        document
          .querySelectorAll(".legend-value")
          .forEach((e) => (e.textContent = "—"));
        return;
      }
      let nearest = null,
        delta = Infinity;
      for (const p of this.s.levels) {
        if (t < p.from || t >= p.to) continue;
        const d = Math.abs(p.t - t);
        if (d < delta) {
          nearest = p;
          delta = d;
        }
      }
      for (const el of document.querySelectorAll(".legend-value")) {
        const v = nearest?.lv[el.dataset.channel],
          c = this.s.night.channels.find((c) => c.key === el.dataset.channel);
        el.textContent = M.finite(v) ? v.toFixed(1).replace("-", "−") : "—";
        el.style.color = M.finite(v) && c && v >= c.thr ? ORANGE : "#cfccc2";
      }
    }
    exportView(view = this.view) {
      const width = 1400,
        axis = 54,
        title = 54,
        height = 360,
        time = 34,
        c = this.canvas(width, height + title + time),
        [g, w, h] = this.fit(c);
      g.fillStyle = INK;
      g.fillRect(0, 0, w, h);
      g.fillStyle = LIGHT;
      g.font = '600 12px "Geist Mono", monospace';
      g.fillText("LFH · SPETTROGRAMMA · " + this.s.night.date, axis, 22);
      g.font = '10px "Geist Mono", monospace';
      g.fillStyle = "#8d8a80";
      g.fillText(
        `${M.clock(view.t0, this.s.zone)} → ${M.clock(view.t1, this.s.zone)} · ${Math.round(view.f0)}–${Math.round(view.f1)} Hz · dBFS · ${this.s.zone}`,
        axis,
        41,
      );
      const plot = this.canvas(width - axis - 12, height),
        a = this.canvas(axis - 8, height);
      const cache = this.bitmap(view, Math.round((width - axis - 12) / 2), 180);
      this.drawWaterfall(plot, view, cache, true);
      this.drawAxis(a, view);
      g.drawImage(plot, axis, title, width - axis - 12, height);
      g.drawImage(a, 0, title, axis - 8, height);
      g.font = '10px "Geist Mono", monospace';
      for (const t of this.timeTicks(view, width - axis - 12))
        g.fillText(
          M.clock(t, this.s.zone),
          Math.min(axis + this.x(t, width - axis - 12, view), width - 46),
          h - 12,
        );
      return c;
    }
    exportReport(points) {
      const n = this.s.night,
        s = n.summary,
        view = { t0: n.from, t1: n.to, f0: 20, f1: 200 },
        c = this.canvas(1400, 808 + Math.ceil(n.channels.length / 3) * 20),
        [g, w, h] = this.fit(c);
      g.fillStyle = "#d9d7d0";
      g.fillRect(0, 0, w, h);
      g.fillStyle = "#1b1b19";
      g.font = "700 24px Geist, sans-serif";
      g.fillText("Low-Freq Hunter · " + n.date, 24, 38);
      g.font = '12px "Geist Mono", monospace';
      g.fillText(
        `CON RUMORE ${M.duration(s.noiseSeconds)}  ·  ${s.eventsCount} EVENTI  ·  PICCO ${s.peak?.toFixed(1) ?? "—"} dBFS  ·  BUCHI ${s.gapCount} / ${M.elapsed(s.gapSeconds)}`,
        24,
        68,
      );
      const spec = this.exportView(view);
      g.drawImage(spec, 12, 88, 1376, 440);
      const levels = this.canvas(1290, 140);
      this.drawLevels(levels, view, points || this.s.levels, true);
      g.fillStyle = INK;
      g.fillRect(12, 538, 1376, 154);
      g.drawImage(levels, 66, 545, 1290, 140);
      g.font = '10px "Geist Mono", monospace';
      n.channels.forEach((ch, i) => {
        const x = 24 + (i % 3) * 450,
          y = 710 + Math.floor(i / 3) * 20;
        g.fillStyle = this.cb.color(ch);
        g.fillRect(x, y - 7, 12, 3);
        g.fillStyle = "#1b1b19";
        g.fillText(
          `${ch.label} · soglia ${ch.thr} ${ch.unit}${ch.width != null ? " · ±" + ch.width + " Hz" : ""}`,
          x + 18,
          y,
        );
      });
      const footer = 736 + Math.ceil(n.channels.length / 3) * 20;
      g.fillStyle = "#5d5b55";
      g.font = '11px "Geist Mono", monospace';
      g.fillText(
        `Sessioni: ${n.sessions.length} · Dati misurati: ${M.duration(s.coverageSeconds)} · medie spettrali ≈30 s / 64 intervalli · nessun dato fuori 20–200 Hz`,
        24,
        footer,
      );
      g.fillText(
        "Audio: dBFS, non fonometria certificata. Vibrazioni: dB rel 1 g. Vuoti e interruzioni non indicano silenzio.",
        24,
        footer + 24,
      );
      return c;
    }
  }
  window.LFHRenderer = LFHRenderer;
})();
