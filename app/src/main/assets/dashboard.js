/* PC dashboard controller. The phone remains the only measurement source. */
(function () {
  "use strict";
  const M = LFHModel,
    $ = (id) => document.getElementById(id),
    K = new URLSearchParams(location.search).get("k") || "";
  const url = (p) =>
    p + (p.includes("?") ? "&" : "?") + "k=" + encodeURIComponent(K);
  const COLORS = [
    "#FFAA00",
    "#00D4AA",
    "#9988FF",
    "#FF6688",
    "#55AAFF",
    "#AAEE44",
    "#FF8844",
    "#44DDEE",
  ];
  const color = (ch) =>
    ch.id === "V"
      ? "#aaa6ba"
      : COLORS[Math.max(0, ch.id.charCodeAt(0) - 65) % COLORS.length];
  const gate = new M.RequestGate(),
    levelGate = new M.RequestGate(),
    alerts = new M.Alerts();
  const A = {
    state: null,
    online: false,
    lastOnline: 0,
    offset: 0,
    zone: "UTC",
    nights: [],
    night: null,
    data: null,
    levels: [],
    spectrum: null,
    selected: null,
    pendingNight: null,
    anchor: null,
    hidden: new Set(),
    palette: "caldo",
    auto: true,
    min: -100,
    max: -45,
    hover: null,
    lock: null,
    drag: null,
    drawer: null,
    settings: null,
    draft: null,
    prefs: {
      start: true,
      end: true,
      long: true,
      offline: true,
      recording: true,
      battery: true,
    },
    muteUntil: 0,
    log: [],
  };
  function readStorage() {
    try {
      const v = JSON.parse(localStorage.getItem("lfh.pc.v4") || "{}");
      A.prefs = { ...A.prefs, ...v.prefs };
      A.muteUntil = Number(v.muteUntil) || 0;
      A.palette = ["caldo", "arancio", "grigio"].includes(v.palette)
        ? v.palette
        : "caldo";
    } catch {}
  }
  function store() {
    try {
      localStorage.setItem(
        "lfh.pc.v4",
        JSON.stringify({
          prefs: A.prefs,
          muteUntil: A.muteUntil,
          palette: A.palette,
        }),
      );
    } catch {}
  }
  readStorage();
  let toastTimer,
    nightAbort,
    levelsAbort,
    refreshBusy = false,
    levelTimer,
    selectedRevision = 0;
  const R = new LFHRenderer(A, {
    color,
    onView: () => {
      renderToolbar();
      clearTimeout(levelTimer);
      levelTimer = setTimeout(loadLevels, 140);
    },
    onContrast: renderToolbar,
    onFrame: renderAnimatedStats,
  });
  async function api(p, options = {}, decode = (r) => r.json()) {
    const controller = new AbortController(),
      external = options.signal;
    let expired = false;
    const cancel = () => controller.abort();
    if (external?.aborted) cancel();
    else external?.addEventListener("abort", cancel, { once: true });
    const timeout = setTimeout(() => {
      expired = true;
      controller.abort();
    }, 10000);
    try {
      const r = await fetch(url(p), {
        ...options,
        signal: controller.signal,
        cache: "no-store",
      });
      if (!r.ok) {
        const text = await r.text();
        throw new Error(
          r.status === 401
            ? "Token mancante o non valido"
            : text || `Errore ${r.status}`,
        );
      }
      return await decode(r);
    } catch (e) {
      if (expired)
        throw new Error("Il telefono non risponde: tempo di attesa scaduto");
      throw e;
    } finally {
      clearTimeout(timeout);
      external?.removeEventListener("abort", cancel);
    }
  }
  function toast(text, error = false) {
    $("toast").textContent = text;
    $("toast").className = "toast visible" + (error ? " error" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(
      () => $("toast").classList.remove("visible"),
      error ? 5000 : 2300,
    );
  }
  function fmtDate(t, opts) {
    return new Intl.DateTimeFormat("it-IT", {
      timeZone: A.zone,
      ...opts,
    }).format(new Date(t * 1000));
  }
  function emit(items) {
    for (const item of items) {
      A.log.unshift({ ...item, t: now() / 1000 });
      A.log = A.log.slice(0, 30);
      const pref = item.type === "online" ? "offline" : item.type;
      if (A.prefs[pref] && Date.now() >= A.muteUntil) {
        const band = item.band
          ? A.state?.cfg?.bands?.find((b) => b.id === item.band)?.label
          : "";
        toast(item.text + (band ? " · " + band : ""));
      }
    }
    renderAlertLog();
  }
  function now() {
    return Date.now() + A.offset;
  }
  function renderHeader() {
    const s = A.state,
      rec = s?.running && s.mode === "rec";
    $("connectionLed").className = "led" + (!A.online ? " offline" : "");
    $("connectionLabel").textContent = A.online
      ? "TEL IN LINEA"
      : "TEL OFFLINE";
    $("recLed").className = "led" + (rec ? " recording" : " dim");
    $("recLabel").textContent = rec ? "REC" : "REC FERMO";
    $("batteryLabel").textContent =
      s?.batteryPct != null ? `BATT ${s.batteryPct}%` : "BATT —";
    const summary =
      A.nights.find((n) => n.date === A.anchor) || A.night?.summary;
    $("gapLed").className = "led" + (summary?.gapCount ? " warning" : " dim");
    $("gapLabel").textContent = summary?.gapCount
      ? `${summary.gapCount} ${summary.gapCount === 1 ? "BUCO" : "BUCHI"}`
      : "0 BUCHI";
    const status = M.liveStatus(s, A.online, now());
    $("liveStatus").textContent = status.label;
    $("liveDot").className = "led " + status.kind;
    $("liveStatus").dataset.kind = status.kind;
    let detail = "";
    if (status.kind === "noise") {
      const [k, t] = Object.entries(s.activeBands).sort(
        (a, b) => a[1] - b[1],
      )[0];
      const b = s.cfg?.bands?.find((b) => b.id === k);
      detail = `${b?.label || "VIBRAZIONI"} · DA ${M.elapsed(now() / 1000 - t)} · DALLE ${M.clock(t, A.zone)}`;
    } else if (status.kind === "offline")
      detail = A.lastOnline
        ? `DA ${M.elapsed((Date.now() - A.lastOnline) / 1000)}`
        : "IN ATTESA DEL TELEFONO";
    else if (status.kind === "stale")
      detail = s?.lastDataAt
        ? `ULTIMO DATO ${M.elapsed((now() - s.lastDataAt) / 1000)} FA`
        : "MICROFONO SENZA DATI";
    else if (status.kind === "stopped")
      detail = s?.nextStartAt
        ? `PROSSIMO AVVIO ${M.clock(s.nextStartAt / 1000, A.zone)}`
        : "DASHBOARD DISPONIBILE";
    else {
      const last = A.log.find((i) => i.type === "end");
      detail = last
        ? `ULTIMO EVENTO ${M.elapsed(now() / 1000 - last.t)} FA`
        : s?.mode === "listen"
          ? "NESSUNA SESSIONE SALVATA"
          : "NESSUN EVENTO IN CORSO";
    }
    $("liveDetail").textContent = detail;
    $("alertsButton").innerHTML =
      "AVVISI" + (Date.now() < A.muteUntil ? " <span>· MUTO</span>" : "");
    renderReliability();
  }
  function renderReliability() {
    const s = A.state,
      night = A.nights.find((n) => n.date === A.anchor);
    const status = M.liveStatus(s, A.online, now());
    $("reliabilityConnection").textContent = !A.online
      ? A.lastOnline
        ? `perso da ${M.elapsed((Date.now() - A.lastOnline) / 1000)}`
        : "non raggiungibile"
      : s?.lastDataAt
        ? `ultimo dato ${M.elapsed((now() - s.lastDataAt) / 1000)} fa`
        : "collegato · nessuna misura";
    $("reliabilityConnection").closest(".quality-row").dataset.warn =
      !A.online || status.kind === "stale" ? "true" : "false";
    $("reliabilityRecording").textContent =
      s?.running && s.mode === "rec"
        ? `attiva dalle ${M.clock(s.startedAt / 1000, A.zone)}`
        : s?.running
          ? "solo ascolto"
          : "ferma";
    $("reliabilityGaps").textContent = night
      ? `${night.gapCount} · ${M.elapsed(night.gapSeconds)}`
      : "—";
    $("reliabilityGaps").closest(".quality-row").dataset.warn = night?.gapCount
      ? "true"
      : "false";
    $("reliabilityBattery").textContent =
      s?.batteryPct != null
        ? `${s.batteryPct}%${s.charging ? " · in carica" : ""}`
        : "non disponibile";
    $("reliabilitySpace").textContent =
      s?.freeBytes != null
        ? `${(s.freeBytes / 1e9).toLocaleString("it-IT", { maximumFractionDigits: 1 })} GB`
        : "non disponibile";
    $("qualityError").textContent = s?.error || "";
  }
  let statsMotion = null;
  function renderSession(animate = false, old = null) {
    const n = A.night;
    if (!n) return;
    const s = n.summary;
    const i = A.nights.findIndex((x) => x.date === n.date);
    $("previousNight").disabled = i < 0 || i >= A.nights.length - 1;
    $("nextNight").disabled = i <= 0;
    $("sessionKind").textContent = !s.recorded
      ? "NON REGISTRATA"
      : s.live
        ? "STANOTTE · IN CORSO"
        : n.date === A.anchor
          ? "ULTIMA NOTTE · CHIUSA"
          : `${i} ${i === 1 ? "NOTTE" : "NOTTI"} FA`;
    $("sessionKind").classList.toggle("accent", s.live);
    $("sessionDate").textContent =
      `${fmtDate(n.from, { weekday: "short", day: "numeric" })} → ${fmtDate(n.to - 1, { weekday: "short", day: "numeric", month: "short" })}`;
    const comp = M.comparison(A.nights, s);
    const deltaText = (v) =>
      v == null
        ? "confronto non disponibile"
        : Math.abs(v) < 300
          ? "come al solito"
          : `${v > 0 ? "+" : "−"}${M.duration(Math.abs(v))} vs solito`;
    $("noiseCompare").textContent = deltaText(comp.deltaSeconds);
    $("noiseCompare").classList.toggle("worse", comp.deltaSeconds >= 900);
    const startDelta =
      comp.start != null && s.noiseStart != null
        ? s.noiseStart - n.from - comp.start
        : null;
    $("startCompare").textContent = deltaText(startDelta);
    $("startCompare").classList.toggle(
      "worse",
      startDelta !== null && startDelta <= -900,
    );
    $("endCompare").textContent = s.live ? "evento aperto" : "";
    $("peakCompare").textContent = s.recorded
      ? `${s.eventsCount} eventi`
      : "nessuna misura";
    if (animate && old && !R.reduced)
      statsMotion = { start: performance.now(), old, new: s };
    else statsMotion = null;
    renderAnimatedStats(performance.now());
    for (const id of ["exportReport", "exportPng", "exportCsv"])
      $(id).disabled = !s.recorded;
    renderToolbar();
    renderLegend();
    renderDiary();
  }
  function renderAnimatedStats(t) {
    const s = A.night?.summary;
    if (!s) return;
    let p = 1,
      old = null;
    if (statsMotion) {
      p = Math.min(1, (t - statsMotion.start) / 700);
      p = 1 - Math.pow(1 - p, 3);
      old = statsMotion.old;
      if (p >= 1) statsMotion = null;
    }
    const interp = (k) =>
      old && M.finite(old[k]) && M.finite(s[k])
        ? old[k] + (s[k] - old[k]) * p
        : s[k];
    $("noiseValue").textContent = s.recorded
      ? M.duration(interp("noiseSeconds"))
      : "—";
    $("startValue").textContent = M.clock(interp("noiseStart"), A.zone);
    $("endValue").textContent = s.live
      ? "in corso"
      : M.clock(interp("noiseEnd"), A.zone);
    $("peakValue").textContent = M.finite(s.peak)
      ? interp("peak").toFixed(1).replace("-", "−")
      : "—";
  }
  function renderToolbar() {
    const v = R.target;
    if (!v || !A.night) return;
    const zoom = v.t0 > A.night.from + 0.5 || v.t1 < A.night.to - 0.5;
    $("viewRange").textContent =
      `${M.clock(v.t0, A.zone)} → ${M.clock(v.t1, A.zone)}${zoom ? " · " + M.duration(v.t1 - v.t0) : ""} · ${Math.round(v.f0)}–${Math.round(v.f1)} HZ`;
    $("resetZoom").hidden = !zoom;
    document
      .querySelectorAll(".overview-cell")
      .forEach((e) => (e.hidden = !zoom));
    $("overviewShare").textContent =
      `VISTA ${Math.round(((v.t1 - v.t0) / (A.night.to - A.night.from)) * 100)}%`;
    const presets = [
      { label: "20–80", lo: 20, hi: 80 },
      { label: "20–200", lo: 20, hi: 200 },
      { label: "0–250", lo: 0, hi: 250 },
      ...(A.night.channels || [])
        .filter((c) => c.center != null && c.center <= 250)
        .map((c) => ({
          label: String(c.center),
          lo: Math.max(0, c.center - 15),
          hi: Math.min(250, c.center + 15),
          color: color(c),
        })),
    ];
    $("frequencyPresets").replaceChildren(
      ...presets.map((p) => {
        const b = document.createElement("button");
        b.textContent = p.label;
        b.className =
          Math.abs(v.f0 - p.lo) < 1 && Math.abs(v.f1 - p.hi) < 1
            ? "selected"
            : "";
        b.type = "button";
        if (p.color) {
          const dot = document.createElement("i");
          dot.style.background = p.color;
          b.prepend(dot);
        }
        b.onclick = () => R.setView({ ...R.target, f0: p.lo, f1: p.hi }, 440);
        return b;
      }),
    );
    $("autoContrast").classList.toggle("selected", A.auto);
    $("manualContrast").hidden = A.auto;
    $("contrastBar").dataset.palette = A.palette;
    document
      .querySelectorAll("[data-palette]")
      .forEach((b) =>
        b.classList.toggle("selected", b.dataset.palette === A.palette),
      );
    const c = R.contrast || { min: A.min, max: A.max };
    $("contrastMin").textContent = Math.round(c.min);
    $("contrastMax").textContent = Math.round(c.max);
    $("lockedCursor").hidden = !A.lock;
    $("lockedCursor").textContent = A.lock
      ? "CURSORE FISSO " + M.clock(A.lock.t, A.zone, true) + " ✕"
      : "";
  }
  function renderLegend() {
    if (!A.night) return;
    const channels = [
      ...A.night.channels,
      { key: "ref", id: "ref", label: "FONDO", unit: "dBFS" },
    ];
    $("levelLegend").replaceChildren(
      ...channels.map((c) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className =
          "legend-item" + (A.hidden.has(c.key) ? " hidden-channel" : "");
        const line = document.createElement("i");
        line.style.background = c.key === "ref" ? "#6b6962" : color(c);
        const label = document.createElement("span");
        label.textContent =
          c.label + (c.unit === "dB rel 1 g" ? " · dB rel 1 g" : "");
        label.title = c.thr != null ? `Soglia ${c.thr} ${c.unit}` : c.unit;
        const value = document.createElement("span");
        value.className = "legend-value";
        value.dataset.channel = c.key;
        value.textContent = "—";
        b.append(line, label, value);
        b.onclick = () => {
          A.hidden.has(c.key) ? A.hidden.delete(c.key) : A.hidden.add(c.key);
          renderLegend();
          R.invalidate();
        };
        return b;
      }),
    );
  }
  function renderDiary() {
    if (!A.nights.length) return;
    const s = A.night?.summary;
    const comparable = A.nights.filter(
      (n) => n.recorded && !n.live && n.comparisonKey === s?.comparisonKey,
    );
    const noisy = comparable.filter((n) => n.noiseSeconds >= 1800);
    $("insightNights").textContent = `${noisy.length}/${comparable.length}`;
    $("insightNightsDetail").textContent = comparable.length
      ? "notti registrate confrontabili"
      : "nessuna notte confrontabile";
    const starts = comparable.filter((n) => n.noiseStart != null);
    const medianStart = M.median(starts.map((n) => n.noiseStart - n.from));
    const medianEnd = M.median(starts.map((n) => n.noiseEnd - n.from));
    $("insightStart").textContent =
      medianStart != null ? M.clock(A.night.from + medianStart, A.zone) : "—";
    $("insightEnd").textContent =
      medianEnd != null
        ? "fino alle " + M.clock(A.night.from + medianEnd, A.zone)
        : "dati insufficienti";
    $("insightAverage").textContent = comparable.length
      ? M.duration(
          comparable.reduce((v, n) => v + n.noiseSeconds, 0) /
            comparable.length,
        )
      : "—";
    $("nightRows").replaceChildren(
      ...A.nights.map((n, i) => {
        const row = document.createElement("button");
        row.type = "button";
        row.className = "night-row";
        row.dataset.date = n.date;
        row.style.setProperty("--row-delay", `${260 + i * 24}ms`);
        const label = document.createElement("span");
        label.className = "night-label";
        label.textContent =
          i === 0
            ? n.live
              ? "stanotte"
              : "ultima notte"
            : fmtDate(n.from, {
                weekday: "short",
                day: "numeric",
                month: "short",
              });
        const cells = document.createElement("span");
        cells.className = "hour-cells";
        for (let j = 0; j < 12; j++) {
          const hh = (21 + j) % 24;
          const values = (n.hours || [])
            .filter((h) => Number(M.clock(h.t, A.zone).slice(0, 2)) === hh)
            .map((h) => h.value)
            .filter(M.finite);
          const value = values.length ? Math.max(...values) : null;
          const cell = document.createElement("i");
          cell.style.background =
            value == null
              ? n.recorded
                ? "#161614"
                : "transparent"
              : heatColor(value);
          cell.classList.toggle("missing", !n.recorded);
          cell.title = `${String(hh).padStart(2, "0")}:00 · ${value == null ? "nessuna misura" : `${value >= 0 ? "+" : ""}${value.toFixed(1)} dB vs soglia`}`;
          cells.append(cell);
        }
        const total = document.createElement("span");
        total.className = "night-total";
        total.textContent = n.recorded ? M.duration(n.noiseSeconds) : "—";
        row.setAttribute(
          "aria-label",
          label.textContent +
            " · " +
            (n.recorded ? total.textContent : "non registrata"),
        );
        row.append(label, cells, total);
        row.onclick = () => selectNight(n.date);
        return row;
      }),
    );
    const i = A.nights.findIndex((n) => n.date === A.selected);
    $("nightIndicator").hidden = i < 0;
    $("nightIndicator").style.top = `${i * 18}px`;
  }
  function heatColor(v) {
    const stops =
      v < 0
        ? [
            [38, 37, 33],
            [58, 56, 50],
          ]
        : [
            [110, 45, 18],
            [255, 90, 31],
            [255, 205, 180],
          ];
    const x =
        M.clamp(v < 0 ? (v + 12) / 12 : v / 10, 0, 1) * (stops.length - 1),
      i = Math.min(stops.length - 2, Math.floor(x)),
      a = stops[i],
      b = stops[i + 1],
      p = x - i;
    return `rgb(${a.map((v, i) => Math.round(v + (b[i] - v) * p)).join(",")})`;
  }
  async function refreshNights() {
    if (!A.anchor) return;
    try {
      const result = await api("/api/nights?anchor=" + A.anchor + "&count=16");
      A.nights = result;
      renderDiary();
      renderHeader();
      if (A.night) renderSession();
    } catch (e) {
      /* Selected data stays available during a connection loss. */
    }
  }
  async function selectNight(date, refresh = false) {
    if (
      refresh &&
      (A.pendingNight !== null || A.selected !== date || A.night?.date !== date)
    )
      return;
    const id = gate.next();
    selectedRevision++;
    A.pendingNight = id;
    nightAbort?.abort();
    levelsAbort?.abort();
    nightAbort = new AbortController();
    const previous = A.night,
      previousIndex = A.nights.findIndex((n) => n.date === A.selected),
      nextIndex = A.nights.findIndex((n) => n.date === date);
    A.selected = date;
    if (!refresh) {
      $("loadState").textContent = "Caricamento della notte…";
      $("loadState").hidden = false;
    }
    try {
      const data = await api("/api/night?date=" + date, {
        signal: nightAbort.signal,
      });
      if (!gate.current(id)) return;
      A.night = data;
      A.data = M.decodeSlices(data);
      A.zone = data.timezone || A.zone;
      if (!refresh) {
        A.lock = null;
        A.hover = null;
        A.levels = [];
        R.setNight(data, nextIndex > previousIndex ? -1 : 1);
      } else R.updateNight(data);
      $("loadState").hidden = true;
      const row = A.nights.findIndex((n) => n.date === date);
      if (row >= 0) A.nights[row] = { ...A.nights[row], ...data.summary };
      renderSession(!refresh, previous?.summary);
      renderHeader();
      await loadLevels();
    } catch (e) {
      if (e.name === "AbortError") return;
      if (!gate.current(id)) return;
      A.selected = previous?.date || date;
      $("loadState").textContent = e.message;
      toast("Notte non caricata: " + e.message, true);
      renderDiary();
    } finally {
      if (A.pendingNight === id) A.pendingNight = null;
    }
  }
  async function loadLevels() {
    if (!A.night || !R.target) return;
    const id = levelGate.next(),
      date = A.night.date;
    levelsAbort?.abort();
    levelsAbort = new AbortController();
    const v = R.target;
    const from = Math.max(A.night.from, Math.floor(v.t0)),
      to = Math.min(A.night.to, Math.ceil(v.t1));
    try {
      const result = await api(
        `/api/night/levels?date=${date}&from=${from}&to=${to}&cols=${Math.min(2000, Math.max(200, Math.round($("waterfall").clientWidth)))}`,
        { signal: levelsAbort.signal },
      );
      if (levelGate.current(id) && A.night?.date === date) {
        A.levels = result.points || [];
        R.invalidate();
      }
    } catch (e) {
      if (e.name !== "AbortError")
        toast("Livelli non caricati: " + e.message, true);
    }
  }
  let lastNightRefresh = 0,
    lastListRefresh = 0;
  async function poll() {
    if (refreshBusy) return;
    refreshBusy = true;
    try {
      const s = await api("/api/state");
      const changed =
        A.state &&
        (A.state.running !== s.running ||
          A.state.mode !== s.mode ||
          A.state.sessionId !== s.sessionId);
      A.state = s;
      A.zone = s.timezone || A.zone;
      A.offset = s.now - Date.now();
      A.online = true;
      A.lastOnline = Date.now();
      emit(alerts.observe(s, now(), Date.now()));
      if (!A.anchor || s.nightDate !== A.anchor) {
        A.anchor = s.nightDate;
        await refreshNights();
        if (!A.selected || A.selected > A.anchor) await selectNight(A.anchor);
      }
      if (!A.night && A.selected) await selectNight(A.selected);
      if (s.running && M.liveStatus(s, true, now()).kind !== "stale") {
        try {
          A.spectrum = await api("/api/spectrum");
        } catch {
          A.spectrum = null;
        }
      } else A.spectrum = null;
      if (Date.now() - lastListRefresh > 30000) {
        lastListRefresh = Date.now();
        await refreshNights();
      }
      if (
        A.night &&
        A.night.date === A.anchor &&
        A.selected === A.night.date &&
        A.pendingNight === null &&
        (changed ||
          (Date.now() - lastNightRefresh > 5000 &&
            s.running &&
            s.mode === "rec"))
      ) {
        lastNightRefresh = Date.now();
        await selectNight(A.selected, true);
      }
      renderHeader();
      R.invalidate();
    } catch (e) {
      A.online = false;
      emit(alerts.disconnect(Date.now()));
      renderHeader();
      R.invalidate();
      if (!A.night) {
        $("loadState").hidden = false;
        $("loadState").textContent =
          e.message === "Token mancante o non valido"
            ? e.message
            : "Telefono non raggiungibile. La dashboard riprova automaticamente.";
      }
    } finally {
      refreshBusy = false;
      setTimeout(poll, 1000);
    }
  }
  function resetZoom() {
    if (A.night)
      R.setView({ ...R.target, t0: A.night.from, t1: A.night.to }, 440);
    A.lock = null;
    renderToolbar();
    R.invalidate();
  }
  function moveNight(delta) {
    const i = A.nights.findIndex((n) => n.date === A.selected);
    const n = A.nights[i + delta];
    if (n) selectNight(n.date);
  }
  $("previousNight").onclick = () => moveNight(1);
  $("nextNight").onclick = () => moveNight(-1);
  $("resetZoom").onclick = resetZoom;
  $("lockedCursor").onclick = () => {
    A.lock = null;
    renderToolbar();
    R.invalidate();
  };
  for (const b of document.querySelectorAll("[data-palette]"))
    b.onclick = () => {
      A.palette = b.dataset.palette;
      store();
      R.recolor();
      renderToolbar();
    };
  $("autoContrast").onclick = () => {
    A.auto = !A.auto;
    R.recolor();
    renderToolbar();
  };
  $("minSlider").oninput = (e) => {
    A.min = Number(e.target.value);
    A.max = Math.max(A.max, A.min + 10);
    $("maxSlider").value = A.max;
    R.recolor();
    renderToolbar();
  };
  $("maxSlider").oninput = (e) => {
    A.max = Number(e.target.value);
    A.min = Math.min(A.min, A.max - 10);
    $("minSlider").value = A.min;
    R.recolor();
    renderToolbar();
  };
  function localPoint(e, canvas) {
    const rect = canvas.getBoundingClientRect();
    return {
      x: M.clamp(e.clientX - rect.left, 0, rect.width),
      y: M.clamp(e.clientY - rect.top, 0, rect.height),
      w: rect.width,
      h: rect.height,
    };
  }
  function cursor(e, canvas) {
    const p = localPoint(e, canvas),
      v = R.view;
    return {
      ...p,
      t: v.t0 + (p.x / p.w) * (v.t1 - v.t0),
      hz: canvas.id === "waterfall" ? v.f1 - (p.y / p.h) * (v.f1 - v.f0) : null,
    };
  }
  for (const id of ["waterfall", "eventLanes", "levelsCanvas"]) {
    const c = $(id);
    c.addEventListener("pointerdown", (e) => {
      if (e.button !== 0 || !A.night) return;
      c.setPointerCapture(e.pointerId);
      const p = cursor(e, c);
      A.drag = { canvas: id, start: p.t, end: p.t, x: p.x, moved: false };
      e.preventDefault();
      R.invalidate();
    });
    c.addEventListener("pointermove", (e) => {
      if (!A.night) return;
      const p = cursor(e, c);
      A.hover = p;
      if (A.drag) {
        A.drag.end = p.t;
        if (Math.abs(A.drag.x - p.x) > 5) A.drag.moved = true;
      }
      updateTooltip(p, id);
      R.invalidate();
    });
    c.addEventListener("pointerup", (e) => {
      if (!A.drag) return;
      const d = A.drag;
      A.drag = null;
      if (d.moved) {
        const t0 = Math.min(d.start, d.end),
          t1 = Math.max(d.start, d.end);
        R.releaseSelection(t0, t1);
        R.setView({ ...R.target, t0, t1 }, 440);
      } else {
        const p = cursor(e, c);
        if (id === "eventLanes") {
          const event = M.eventAt(A.night, p.t, p.y, p.h);
          if (event) {
            R.setView(
              { ...R.target, t0: event.startT - 60, t1: event.endT + 60 },
              440,
            );
            A.lock = { t: (event.startT + event.endT) / 2, hz: null };
            R.flashCursor(380);
          }
        } else if (id === "waterfall") {
          A.lock =
            A.lock &&
            Math.abs(A.lock.t - p.t) < ((R.view.t1 - R.view.t0) / p.w) * 5
              ? null
              : { t: p.t, hz: p.hz };
          R.flashCursor();
        }
        renderToolbar();
        R.invalidate();
      }
    });
    c.addEventListener("pointercancel", () => {
      A.drag = null;
      R.invalidate();
    });
    c.addEventListener("pointerleave", () => {
      if (!A.drag) {
        A.hover = null;
        $("hoverChip").hidden = true;
        R.invalidate();
      }
    });
    c.addEventListener("dblclick", resetZoom);
  }
  function updateTooltip(p, id) {
    const chip = $("hoverChip");
    if (id !== "waterfall" || A.drag) {
      chip.hidden = true;
      return;
    }
    const db = M.valueAt(A.data, p.t, p.hz);
    $("hoverTime").textContent = M.clock(p.t, A.zone, true);
    const enc = A.data.encoding;
    const index = Math.floor(
        ((p.hz - enc.fmin) / (enc.fmax - enc.fmin)) * enc.bins,
      ),
      binHz = enc.fmin + ((index + 0.5) / enc.bins) * (enc.fmax - enc.fmin);
    $("hoverHz").textContent = (db == null ? p.hz : binHz).toFixed(1) + " HZ";
    $("hoverDb").textContent =
      db == null ? "NESSUN DATO" : db.toFixed(1) + " DBFS";
    $("hoverDb").classList.toggle("accent", db >= -60);
    chip.hidden = false;
    const cw = chip.offsetWidth;
    chip.style.left = Math.max(0, Math.min(p.w - cw - 8, p.x + 14)) + "px";
    chip.style.top = Math.max(8, Math.min(p.h - 36, p.y + 14)) + "px";
  }
  $("waterfall").addEventListener(
    "wheel",
    (e) => {
      if (!A.night) return;
      e.preventDefault();
      const p = cursor(e, $("waterfall")),
        v = R.target;
      if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
        const dx = (e.deltaX / $("waterfall").clientWidth) * (v.t1 - v.t0);
        R.setView({ ...v, t0: v.t0 + dx, t1: v.t1 + dx }, 170);
      } else {
        const factor = e.deltaY > 0 ? 1.25 : 0.8,
          ratio = (p.t - v.t0) / (v.t1 - v.t0),
          span = (v.t1 - v.t0) * factor;
        R.setView(
          { ...v, t0: p.t - span * ratio, t1: p.t + span * (1 - ratio) },
          170,
        );
      }
    },
    { passive: false },
  );
  let navDrag = null;
  $("overview").addEventListener("pointerdown", (e) => {
    if (!A.night) return;
    const p = localPoint(e, $("overview")),
      t = A.night.from + (p.x / p.w) * (A.night.to - A.night.from),
      v = R.target;
    navDrag = { x: p.x, t, start: { ...v }, inside: t >= v.t0 && t <= v.t1 };
    $("overview").setPointerCapture(e.pointerId);
    if (!navDrag.inside) {
      const span = v.t1 - v.t0;
      R.setView({ ...v, t0: t - span / 2, t1: t + span / 2 }, 90);
      navDrag.start = { ...R.target };
    }
  });
  $("overview").addEventListener("pointermove", (e) => {
    if (!navDrag) return;
    const p = localPoint(e, $("overview")),
      dx = ((p.x - navDrag.x) / p.w) * (A.night.to - A.night.from);
    R.setView(
      {
        ...navDrag.start,
        t0: navDrag.start.t0 + dx,
        t1: navDrag.start.t1 + dx,
      },
      90,
    );
  });
  $("overview").addEventListener("pointerup", () => (navDrag = null));
  $("overview").addEventListener("pointercancel", () => (navDrag = null));
  document.addEventListener("keydown", (e) => {
    if (e.target.closest("input,textarea,select,[contenteditable]")) return;
    if (e.key === "Escape") {
      closeDrawer();
      resetZoom();
      return;
    }
    if (A.drawer) return;
    if (["ArrowLeft", "ArrowRight", "+", "=", "-", "[", "]"].includes(e.key))
      e.preventDefault();
    if (e.key === "[") moveNight(1);
    else if (e.key === "]") moveNight(-1);
    else if (A.night) {
      const v = R.target,
        span = v.t1 - v.t0;
      if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
        const dx = span * 0.2 * (e.key === "ArrowLeft" ? -1 : 1);
        R.setView({ ...v, t0: v.t0 + dx, t1: v.t1 + dx }, 260);
      } else if (["+", "=", "-"].includes(e.key)) {
        const newSpan = span * (e.key === "-" ? 1.25 : 0.8),
          mid = (v.t0 + v.t1) / 2;
        R.setView({ ...v, t0: mid - newSpan / 2, t1: mid + newSpan / 2 }, 260);
      } else if (e.key.toLowerCase() === "m") sendMarker();
    }
  });
  let focusBeforeDrawer;
  function openDrawer(name) {
    focusBeforeDrawer = document.activeElement;
    A.drawer = name;
    $("backdrop").hidden = false;
    $("drawer").classList.add("open");
    $("drawer").setAttribute("aria-hidden", "false");
    $("drawerTitle").textContent =
      name === "bands"
        ? "BANDE"
        : name === "advanced"
          ? "IMPOSTAZIONI"
          : "AVVISI";
    $("alertsContent").hidden = name !== "alerts";
    $("bandsContent").hidden = name !== "bands";
    $("advancedContent").hidden = name !== "advanced";
    $("bandFooter").hidden = name !== "bands";
    $("drawerClose").focus();
    if (name === "bands" || name === "advanced") loadSettings(name);
    else renderAlerts();
  }
  function closeDrawer() {
    A.drawer = null;
    $("drawer").classList.remove("open");
    $("drawer").setAttribute("aria-hidden", "true");
    $("backdrop").hidden = true;
    focusBeforeDrawer?.focus();
  }
  $("alertsButton").onclick = () => openDrawer("alerts");
  $("bandsButton").onclick = () => openDrawer("bands");
  $("drawerClose").onclick = closeDrawer;
  $("backdrop").onclick = closeDrawer;
  $("cancelBands").onclick = closeDrawer;
  $("advancedButton").onclick = () => openDrawer("advanced");
  $("drawer").addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      closeDrawer();
      return;
    }
    if (e.key !== "Tab") return;
    const list = [
      ...$("drawer").querySelectorAll("button,input,select,a"),
    ].filter((x) => !x.disabled && x.getClientRects().length);
    const first = list[0],
      last = list[list.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  });
  function makeToggle(label, on, change) {
    const row = document.createElement("label");
    row.className = "toggle-row";
    const text = document.createElement("span");
    text.textContent = label;
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = on;
    input.onchange = () => change(input.checked);
    const track = document.createElement("i");
    track.className = "switch-track";
    row.append(text, input, track);
    return row;
  }
  function renderAlerts() {
    const labels = {
      start: "Quando parte un rumore",
      end: "Quando finisce",
      long: "Rumore continuo oltre 30 min",
      offline: "Telefono non raggiungibile · dopo 60 s e al ritorno",
      recording: "Registrazione ferma o ripartita",
      battery: "Batteria del telefono sotto 20%",
    };
    $("alertToggles").replaceChildren(
      ...Object.entries(labels).map(([key, label]) =>
        makeToggle(label, A.prefs[key], (on) => {
          A.prefs[key] = on;
          store();
        }),
      ),
    );
    document.querySelectorAll("[data-mute]").forEach((b) => {
      const min = Number(b.dataset.mute);
      b.classList.toggle(
        "selected",
        min === 0
          ? Date.now() >= A.muteUntil
          : A.muteUntil > Date.now() &&
              A.muteUntil - Date.now() <= min * 60000 &&
              A.muteUntil - Date.now() >
                (min === 30 ? 0 : min === 60 ? 30 : 60) * 60000,
      );
    });
    $("muteStatus").textContent =
      Date.now() < A.muteUntil
        ? "Avvisi silenziati fino alle " + M.clock(A.muteUntil / 1000, A.zone)
        : "Avvisi attivi mentre questa pagina è aperta";
    renderAlertLog();
  }
  for (const b of document.querySelectorAll("[data-mute]"))
    b.onclick = () => {
      A.muteUntil = Number(b.dataset.mute)
        ? Date.now() + Number(b.dataset.mute) * 60000
        : 0;
      store();
      renderAlerts();
      renderHeader();
    };
  function renderAlertLog() {
    $("alertHistory").replaceChildren(
      ...A.log.slice(0, 8).map((i) => {
        const p = document.createElement("p");
        p.textContent = M.clock(i.t, A.zone) + " · " + i.text;
        return p;
      }),
    );
  }
  async function loadSettings(name) {
    $("settingsStatus").textContent = "Caricamento…";
    $("saveBands").disabled = true;
    try {
      A.settings = await api("/api/settings");
      A.draft = structuredClone(A.settings);
      if (A.drawer !== name) return;
      $("settingsStatus").textContent = "";
      $("saveBands").disabled = false;
      if (name === "bands") renderBands();
      else renderAdvanced();
    } catch (e) {
      $("settingsStatus").textContent = e.message;
    }
  }
  function stepper(label, value, step, min, max, onchange) {
    const box = document.createElement("div");
    box.className = "stepper";
    const title = document.createElement("label");
    title.textContent = label;
    const controls = document.createElement("div");
    const minus = document.createElement("button"),
      plus = document.createElement("button"),
      input = document.createElement("input");
    minus.type = plus.type = "button";
    minus.textContent = "−";
    plus.textContent = "+";
    input.type = "number";
    input.step = step;
    input.min = min;
    input.max = max;
    input.value = value;
    input.setAttribute("aria-label", label);
    const update = (v) => {
      const x = Math.round(M.clamp(v, min, max) / step) * step;
      input.value = x;
      onchange(x);
    };
    minus.onclick = () => update(Number(input.value) - step);
    plus.onclick = () => update(Number(input.value) + step);
    input.onchange = () => {
      if (Number.isFinite(input.valueAsNumber)) update(input.valueAsNumber);
    };
    controls.append(minus, input, plus);
    box.append(title, controls);
    return box;
  }
  function renderBands() {
    $("bandCards").replaceChildren(
      ...A.draft.engine.bands.map((b) => {
        const card = document.createElement("section");
        card.className = "band-card";
        card.style.setProperty("--band", color(b));
        card.append(
          makeToggle(
            `${b.center} HZ · BANDA ${b.id}`,
            b.enabled,
            (on) => (b.enabled = on),
          ),
        );
        const values = document.createElement("div");
        values.className = "band-steppers";
        values.append(
          stepper("CENTRO HZ", b.center, 1, 1, 2000, (x) => (b.center = x)),
          stepper("LARGHEZZA ±HZ", b.width, 1, 1, 1000, (x) => (b.width = x)),
          stepper("SOGLIA DBFS", b.thr, 0.5, -150, 20, (x) => (b.thr = x)),
        );
        card.append(values);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.textContent = "RIMUOVI BANDA";
        remove.onclick = () => {
          A.draft.engine.bands = A.draft.engine.bands.filter((x) => x !== b);
          renderBands();
        };
        card.append(remove);
        return card;
      }),
    );
    const add = document.createElement("button");
    add.type = "button";
    add.textContent = "AGGIUNGI BANDA";
    add.disabled = A.draft.engine.bands.length >= 8;
    add.onclick = () => {
      const id = "ABCDEFGH"
        .split("")
        .find((id) => !A.draft.engine.bands.some((b) => b.id === id));
      if (id) {
        A.draft.engine.bands.push({
          id,
          center: 62,
          width: 5,
          thr: -60,
          enabled: true,
        });
        renderBands();
      }
    };
    $("bandCards").append(add);
  }
  $("saveBands").onclick = async () => {
    if (!A.draft) return;
    $("saveBands").disabled = true;
    $("settingsStatus").textContent = "Salvataggio…";
    try {
      const latest = await api("/api/settings");
      latest.engine.bands = M.mergeBands(
        A.settings.engine.bands,
        A.draft.engine.bands,
        latest.engine.bands,
      );
      const result = await api("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(latest),
      });
      if (!result.ok) throw new Error("Salvataggio non confermato");
      closeDrawer();
      toast(
        result.restarted
          ? "Bande salvate · nuova sessione sul telefono"
          : "Bande salvate sul telefono",
      );
      lastNightRefresh = 0;
      lastListRefresh = 0;
    } catch (e) {
      $("settingsStatus").textContent = e.message;
      toast("Salvataggio non confermato: " + e.message, true);
    } finally {
      $("saveBands").disabled = false;
    }
  };
  function renderAdvanced() {
    const container = $("advancedForm");
    container.replaceChildren();
    const d = A.draft;
    const entries = [
      ["Apertura evento · s", d.engine, "minOnS", 1, 1, 3600],
      ["Chiusura evento · s", d.engine, "minOffS", 1, 1, 3600],
      ["Isteresi · dB", d.engine, "hystDb", 0.5, 0, 60],
      ["Soglia vibrazioni · dB rel 1 g", d.engine.vib, "thr", 0.5, -150, 20],
      ["Durata clip · s", d.engine, "clipSeconds", 1, 1, 120],
      ["Clip massime", d.engine, "clipsMax", 1, 0, 1000],
      ["Offset SPL · dB", d.calib, "offsetDb", 0.5, -200, 200],
    ];
    for (const [label, obj, key, step, min, max] of entries)
      container.append(
        stepper(label, obj[key], step, min, max, (v) => (obj[key] = v)),
      );
    for (const [label, obj, key] of [
      ["Vibrazioni", d.engine.vib, "enabled"],
      ["Clip WAV", d.engine, "clipsEnabled"],
      ["Eventi pulsanti", d.engine, "pulseEnabled"],
      ["Stima SPL", d.calib, "enabled"],
      ["Programmazione oraria", d.schedule, "enabled"],
      ["Registrazione continua", d.continuous, "enabled"],
      ["Secondo spezzamento", d.continuous, "split2Enabled"],
    ])
      container.append(makeToggle(label, obj[key], (v) => (obj[key] = v)));
    for (const [label, obj, key] of [
      ["Inizio programma", d.schedule, "startMin"],
      ["Fine programma", d.schedule, "endMin"],
      ["Primo spezzamento", d.continuous, "splitMin"],
      ["Secondo spezzamento", d.continuous, "split2Min"],
    ]) {
      const row = document.createElement("label");
      row.className = "advanced-time";
      row.textContent = label;
      const input = document.createElement("input");
      input.type = "time";
      input.value =
        String(Math.floor(obj[key] / 60)).padStart(2, "0") +
        ":" +
        String(obj[key] % 60).padStart(2, "0");
      input.onchange = () => {
        const [h, m] = input.value.split(":").map(Number);
        obj[key] = h * 60 + m;
      };
      row.append(input);
      container.append(row);
    }
    const fft = document.createElement("label");
    fft.className = "advanced-time";
    fft.textContent = "Risoluzione FFT";
    const select = document.createElement("select");
    for (const size of [16384, 32768]) {
      const o = document.createElement("option");
      o.value = size;
      o.textContent = size;
      o.selected = d.engine.fftSize === size;
      select.append(o);
    }
    select.onchange = () => (d.engine.fftSize = Number(select.value));
    fft.append(select);
    container.append(fft);
  }
  $("saveAdvanced").onclick = async () => {
    if (!A.draft) return;
    const b = $("saveAdvanced");
    b.disabled = true;
    try {
      const latest = await api("/api/settings");
      for (const key of ["schedule", "continuous", "calib"])
        latest[key] = A.draft[key];
      for (const key of [
        "minOnS",
        "minOffS",
        "hystDb",
        "fftSize",
        "vib",
        "pulseEnabled",
        "clipsEnabled",
        "clipSeconds",
        "clipsMax",
      ])
        latest.engine[key] = A.draft.engine[key];
      const r = await api("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(latest),
      });
      if (!r.ok) throw new Error("Salvataggio non confermato");
      closeDrawer();
      toast("Impostazioni salvate sul telefono");
    } catch (e) {
      toast(e.message, true);
    } finally {
      b.disabled = false;
    }
  };
  async function sendMarker() {
    if (!A.state?.running || A.state.mode !== "rec") {
      toast("Avvia REC sul telefono per aggiungere un marker");
      return;
    }
    try {
      const r = await api("/api/marker", { method: "POST" });
      if (!r.ok) throw new Error("Marker non confermato");
      toast("Marker salvato sul telefono");
      lastNightRefresh = 0;
    } catch (e) {
      toast(e.message, true);
    }
  }
  $("markerButton").onclick = sendMarker;
  async function saveBlob(blob, name) {
    if (!blob) throw new Error("Immagine non creata");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = name;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 30000);
  }
  function canvasBlob(canvas) {
    return new Promise((resolve, reject) =>
      canvas.toBlob(
        (b) => (b ? resolve(b) : reject(new Error("Immagine non disponibile"))),
        "image/png",
      ),
    );
  }
  $("exportPng").onclick = async () => {
    const date = A.night?.date;
    if (!date) return;
    try {
      await saveBlob(await canvasBlob(R.exportView()), `LFH_${date}_vista.png`);
      toast("PNG della vista creato");
    } catch (e) {
      toast(e.message, true);
    }
  };
  $("exportReport").onclick = async () => {
    const n = A.night;
    if (!n) return;
    try {
      const result = await api(
        `/api/night/levels?date=${n.date}&from=${n.from}&to=${n.to}&cols=2000`,
      );
      if (A.selected !== n.date || A.night?.date !== n.date)
        throw new Error(
          "Notte cambiata: ripeti il report sulla notte selezionata",
        );
      await saveBlob(
        await canvasBlob(R.exportReport(result.points)),
        `LFH_${n.date}_report.png`,
      );
      toast("Report della notte creato");
    } catch (e) {
      toast(e.message, true);
    }
  };
  $("exportCsv").onclick = async () => {
    const date = A.night?.date;
    if (!date) return;
    try {
      const blob = await api("/api/night/eventi.csv?date=" + date, {}, (r) =>
        r.blob(),
      );
      await saveBlob(blob, `LFH_${date}_eventi.csv`);
      toast("CSV della notte scaricato");
    } catch (e) {
      toast(e.message, true);
    }
  };
  $("sessionExports").onclick = () => {
    if (!A.night) return;
    const box = $("sessionDownloads");
    box.replaceChildren();
    for (const s of A.night.sessions) {
      const row = document.createElement("p");
      const label = document.createElement("span");
      label.textContent = M.clock(s.startedAt / 1000, A.zone) + " · ";
      row.append(label);
      for (const [k, path] of [
        ["JSON", `${s.id}.json`],
        ["CAMPIONI CSV", `${s.id}/campioni.csv`],
        ["REPORT PNG", `${s.id}/report.png`],
      ]) {
        const a = document.createElement("a");
        a.textContent = k;
        a.href = url("/api/session/" + path);
        a.download = "";
        row.append(a, document.createTextNode(" "));
      }
      box.append(row);
    }
    box.hidden = !box.hidden;
  };
  window.addEventListener("resize", () => {
    R.resize();
    renderToolbar();
    clearTimeout(levelTimer);
    levelTimer = setTimeout(loadLevels, 200);
  });
  setInterval(() => {
    if (A.lastOnline && Date.now() - A.lastOnline >= 10000) {
      A.online = false;
      emit(alerts.disconnect(Date.now()));
    }
    renderHeader();
    R.invalidate();
  }, 1000);
  renderHeader();
  renderAlerts();
  poll();
})();
