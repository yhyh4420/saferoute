/*@film
title: SafeRoute — demo
subtitle: Know where to go, before you need to.
artifact:
*/
// 3-minute demo video (docs/demo/narration.md). Rendered frame by frame to 1920x1080 and composited with
// the phone recordings (part 3: the phone area on the left is left empty for the real screen recording).
// Colours keep one meaning: red = earthquake, blue = flood, green = safe place, yellow = warning, purple = agent/guidance.

const RNG = (seed) => () => (seed = (seed * 16807) % 2147483647) / 2147483647;
// Real Seoul: pack boundary, OSM Han river, major roads coarsened to ~350 m (seoul_geo.js, prepended at build).
const NET = { nodes: SEOUL.nodes, links: SEOUL.links };
function geoFit(bx) { const sc = Math.min(bx[2] / SEOUL.span[0], bx[3] / SEOUL.span[1]); return [bx[0] + (bx[2] - SEOUL.span[0] * sc) / 2, bx[1] + (bx[3] - SEOUL.span[1] * sc) / 2, sc]; }
const P = (n, bx) => { const f = geoFit(bx); return [f[0] + n[0] * f[2], f[1] + n[1] * f[2]]; };
// A box that zooms the map so that kmW kilometres span 90% of the width, centred on node c (scene-local zoom).
function zoomBox(c, kmW, cy) { const sc = w * .9 / kmW, p = SEOUL.nodes[c]; return [w / 2 - p[0] * sc, h * (cy || .45) - p[1] * sc, SEOUL.span[0] * sc, SEOUL.span[1] * sc]; }
// "Me": a spot in the middle of Seoul (Dongdaemun) where the app's earthquake and flood routes point opposite ways.
const ME_NODE = (() => { let b = 0, bd = 1e9; SEOUL.nodes.forEach((p, i) => { const d = (p[0] - DETAIL.me[0]) ** 2 + (p[1] - DETAIL.me[1]) ** 2; if (d < bd) { bd = d; b = i; } }); return b; })();
// Camera: p=0 whole city (FULL box) → p=1 zoomed on DETAIL.me, kmW wide. Log-interpolated scale, linear centre.
function camBox(p, kmW) {
  const f = geoFit(FULL()), sc1 = f[2], sc2 = w * .9 / kmW;
  const c1 = [(w / 2 - f[0]) / sc1, (h * .45 - f[1]) / sc1], c2 = DETAIL.me, e = es(clamp(p, 0, 1));
  const sc = Math.exp(lerp(Math.log(sc1), Math.log(sc2), e)), c = [lerp(c1[0], c2[0], e), lerp(c1[1], c2[1], e)];
  return [w / 2 - c[0] * sc, h * .45 - c[1] * sc, SEOUL.span[0] * sc, SEOUL.span[1] * sc];
}
// Every street (not only major roads) near me, fading in as the camera gets close.
function detailStreets(bx, alpha) {
  const sc = geoFit(bx)[2], a = alpha * clamp((sc - 150) / 300, 0, 1); if (a <= 0) return;
  const f = geoFit(bx); A(a); ctx.strokeStyle = '#7a88a6'; ctx.lineWidth = 1.2; ctx.beginPath();
  for (const q of DETAIL.segs) { ctx.moveTo(f[0] + q[0] * f[2], f[1] + q[1] * f[2]); ctx.lineTo(f[0] + q[2] * f[2], f[1] + q[3] * f[2]); }
  ctx.stroke(); A(1);
}
function routeLine(pts, bx, prog, col) {
  const n = Math.max(1, Math.floor(prog * (pts.length - 1)));
  A(1); ctx.strokeStyle = col; ctx.lineWidth = 7; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.beginPath();
  for (let k = 0; k <= n; k++) { const p = P(pts[k], bx); k ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]); } ctx.stroke(); ctx.lineCap = 'butt';
  return P(pts[n], bx);
}
function poly(pts, bx) { ctx.beginPath(); pts.forEach((q, k) => { const p = P(q, bx); k ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]); }); }
function streets(bx, alpha, col) {
  const sc = geoFit(bx)[2];
  A(alpha); poly(SEOUL.ring, bx); ctx.closePath(); ctx.fillStyle = 'rgba(91,156,255,.035)'; ctx.fill(); ctx.strokeStyle = '#6b7c99'; ctx.lineWidth = 2; ctx.stroke();
  // Han river: ~1 km wide band along the centreline, plus its water outline
  ctx.lineCap = 'round'; ctx.lineJoin = 'round';
  for (const r of SEOUL.river) { poly(r, bx); ctx.strokeStyle = 'rgba(64,110,190,.55)'; ctx.lineWidth = Math.max(6, sc * .9); ctx.stroke(); }
  for (const r of SEOUL.water) { poly(r, bx); ctx.strokeStyle = 'rgba(91,156,255,.5)'; ctx.lineWidth = 1.5; ctx.stroke(); }
  ctx.lineCap = 'butt';
  for (const [a, b] of NET.links) { const p = P(NET.nodes[a], bx), q = P(NET.nodes[b], bx); seg(p[0], p[1], q[0], q[1], col || '#5a6a86', 1.3); }
  A(1);
}
// BFS over the road network from a set of sources (the "tree" every node follows); memoised per source set.
const TREES = {};
function tree(sources) {
  const key = sources.join(','); if (TREES[key]) return TREES[key];
  const adj = NET.nodes.map(() => []);
  for (const [a, b] of NET.links) { adj[a].push(b); adj[b].push(a); }
  const dist = NET.nodes.map(() => Infinity), next = NET.nodes.map(() => -1), q = [];
  for (const s of sources) { dist[s] = 0; next[s] = s; q.push(s); }
  for (let qi = 0; qi < q.length; qi++) { const u = q[qi]; for (const v of adj[u]) if (dist[v] === Infinity) { dist[v] = dist[u] + 1; next[v] = u; q.push(v); } }
  let maxD = 0; for (const d of dist) if (d !== Infinity && d > maxD) maxD = d;
  return (TREES[key] = { dist, next, maxD });
}
function pathTo(start, sources) { const tr = tree(sources), p = [start]; let c = start; while (tr.next[c] !== c && tr.next[c] >= 0 && p.length < 400) { c = tr.next[c]; p.push(c); } return p; }
const FULL = () => [w * .04, h * .08, w * .92, h * .74];
const big = (k) => Math.round(h * k);

// ---------- Part 1: the problem ----------
S('commute', '1 · Every day', 7, (lt) => {
  const bx = FULL();
  streets(bx, .55 * E(lt, 0, 1.5));
  // commute route: from the left edge to the centre (the middle of Seoul)
  const route = pathTo(SEOUL.west, [ME_NODE]);
  A(E(lt, .8, 1));
  for (let k = 0; k < route.length - 1; k++) { const p = P(NET.nodes[route[k]], bx), q = P(NET.nodes[route[k + 1]], bx); seg(p[0], p[1], q[0], q[1], C.blue, 4); }
  A(1);
  const prog = es(ap(lt, 1.5, 4.5)) * (route.length - 1), k = Math.min(route.length - 2, Math.floor(prog));
  const pt = mv(P(NET.nodes[route[k]], bx), P(NET.nodes[route[k + 1]], bx), prog - k);
  glow(pt[0], pt[1], h * .05, 'rgba(91,156,255,.6)', 1); dot(pt[0], pt[1], h * .012, C.ink);
  A(E(lt, 1, 1)); T('08:12', w * .88, h * .12, big(.05), C.muted, 'right', 600); A(1);
  A(E(lt, 2.2, 1.2)); T('Every day.', w * .5, h * .78, big(.06), C.ink, 'center', 700); A(1);
}, [{ t0: 0, t1: 7, text: 'Every morning I commute into the middle of Seoul.' }], C.blue);

S('alert', '1 · Alert', 10, (lt) => {
  const bx = camBox(ap(lt, 4.5, 3.5), 2.2);
  streets(bx, .45); detailStreets(bx, .55);
  const c = P(DETAIL.me, bx);
  for (let k = 0; k < 4; k++) {   // alert pulses from where I am
    const p = ((lt - .5 - k * .7) % 2.8) / 2.8;
    if (lt > .5 + k * .7 && p >= 0) { A((1 - p) * .7); ctx.beginPath(); ctx.arc(c[0], c[1], h * (.05 + p * .45), 0, Math.PI * 2); ctx.strokeStyle = C.red; ctx.lineWidth = 3; ctx.stroke(); A(1); }
  }
  glow(c[0], c[1], h * .04, 'rgba(255,255,255,.35)', 1); dot(c[0], c[1], h * .014, C.ink);
  A(E(lt, 6.5, .8)); T('you · Dongdaemun', c[0], c[1] + h * .045, big(.024), C.muted, 'center', 600); A(1);
  ctx.save(); A(E(lt, .4, .5)); boxR(w * .5 - w * .17, h * .06, w * .34, h * .09, C.red); A(1);
  T('⚠  EARTHQUAKE ALERT', w * .5, h * .105, big(.04), '#fff', 'center', 700); ctx.restore(); A(1);
  const sx = w * .84, sy = h * .22, lost = E(lt, 3.6, 1.2);
  for (let b = 0; b < 4; b++) { const bh = h * (.03 + b * .025); A(1 - lost * (b > 0 ? 1 : .2)); box(sx + b * w * .022, sy - bh, w * .014, bh, 3, C.ink, null); A(1); }
  A(lost); T('No network', sx + w * .044, sy + h * .045, big(.032), C.red, 'center', 700); A(1);
  A(E(lt, 8, 1)); box(w * .5 - w * .15, h * .69, w * .3, h * .1, 16, 'rgba(12,15,22,.8)', null); T('Where do I go?', w * .5, h * .74, big(.06), C.ink, 'center', 700); A(1);
}, [{ t0: 0, t1: 2.8, text: 'But one morning, an alert goes off.' }, { t0: 2.8, t1: 10, text: 'The network is jammed. And I realise I have no idea where to go.' }], C.red);
function boxR(x, y, bw, bh, col) { box(x, y, bw, bh, 14, col, null); }

S('depends', '1 · It depends', 13, (lt) => {
  const bx = camBox(1, 2.2);
  streets(bx, .45); detailStreets(bx, .55);
  const me = P(DETAIL.me, bx), R = DETAIL.routes;
  // earthquake: the app's real route to open space
  if (lt > .8) {
    const end = routeLine(R.earthquake.pts, bx, eo(ap(lt, .8, 2.2)), C.red);
    const d = P(R.earthquake.dest, bx);
    A(E(lt, 2.6, .6)); glow(d[0], d[1], h * .05, 'rgba(67,214,160,.45)', 1); dot(d[0], d[1], h * .014, C.green);
    box(d[0] - w * .29, d[1] - h * .045, w * .26, h * .09, 12, 'rgba(12,15,22,.85)', C.red, 2);
    T('EARTHQUAKE → open space', d[0] - w * .16, d[1] - h * .02, big(.024), C.red, 'center', 700);
    T('Dongdaemun Fortress Park · ' + R.earthquake.m + ' m', d[0] - w * .16, d[1] + h * .02, big(.022), C.ink, 'center', 600); A(1);
  }
  // flood: the opposite way, away from anything underground
  if (lt > 4.3) {
    routeLine(R.flood.pts, bx, eo(ap(lt, 4.3, 2.4)), C.blue);
    const d = P(R.flood.dest, bx);
    A(E(lt, 6.4, .6)); glow(d[0], d[1], h * .05, 'rgba(67,214,160,.45)', 1); dot(d[0], d[1], h * .014, C.green);
    box(d[0] + w * .03, d[1] - h * .045, w * .3, h * .09, 12, 'rgba(12,15,22,.85)', C.blue, 2);
    T('FLOOD → away from underground', d[0] + w * .18, d[1] - h * .02, big(.024), C.blue, 'center', 700);
    T('Seongdong Global Business H.S. · ' + R.flood.m + ' m', d[0] + w * .18, d[1] + h * .02, big(.022), C.ink, 'center', 600); A(1);
  }
  glow(me[0], me[1], h * .04, 'rgba(255,255,255,.35)', 1); dot(me[0], me[1], h * .014, C.ink);
  A(E(lt, 8.2, 1)); box(w * .5 - w * .26, h * .03, w * .52, h * .12, 16, 'rgba(12,15,22,.88)', null);
  T('Opposite directions from the same spot', w * .5, h * .07, big(.04), C.ink, 'center', 700);
  T('1 in 3 people in Seoul: different first direction (35.4%)', w * .5, h * .118, big(.025), C.muted, 'center', 500); A(1);
}, [{ t0: 0, t1: 6.6, text: 'The right answer depends on the disaster: open space for an earthquake, high ground for a flood.' },
    { t0: 6.6, t1: 13, text: 'For one in three people in Seoul, the first direction to walk is different.' }], C.yellow);

// ---------- Part 2: what it is ----------
S('what', '2 · SafeRoute', 12, (lt) => {
  const s = h * .13, cx = w * .5, cy = h * .26, a = E(lt, .2, 1);
  A(a); box(cx - s, cy - s, s * 2, s * 2, s * .45, '#b3261e', null);
  ctx.save(); ctx.translate(cx, cy); ctx.rotate(Math.PI / 4); ctx.fillStyle = '#fff'; ctx.beginPath();
  const u = s / 54; const pts = [[0, -28], [18, -4], [7, -4], [7, 18], [-7, 18], [-7, -4], [-18, -4]];
  pts.forEach(([x, y], i) => i ? ctx.lineTo(x * u, y * u) : ctx.moveTo(x * u, y * u)); ctx.closePath(); ctx.fill(); ctx.restore(); A(1);
  A(E(lt, 1.2, 1)); T('SafeRoute', cx, h * .49, big(.07), C.ink, 'center', 700); A(1);
  A(E(lt, 3, 1)); T('Silent every day.', cx, h * .6, big(.045), C.muted, 'center', 600); A(1);
  A(E(lt, 6, 1)); T('Milliseconds when it matters.', cx, h * .68, big(.045), C.green, 'center', 700); A(1);
  A(E(lt, 7.5, 1)); chip(cx - w * .13, h * .76, w * .26, h * .055, 'rgba(67,214,160,.15)', 'offline · ~1 ms · no model on the phone', C.green, big(.02)); A(1);
}, [{ t0: 0, t1: 4.4, text: 'SafeRoute does nothing you have to think about on an ordinary day.' },
    { t0: 4.4, t1: 12, text: 'When disaster strikes, it tells you where to go — in milliseconds, even with no network.' }], C.green);

// ---------- Part 3: demo (phone recording composited into the frame on the left) ----------
const PH = { x: 450, y: 56, w: 392, h: 840 };   // in 1920x1080 pixels; build_demo.sh overlays the video here
function phoneFrame(lt) {
  const k = w / 1920, x = PH.x * k, y = PH.y * k, pw = PH.w * k, ph = PH.h * k;
  A(E(lt, 0, .6)); box(x - 14 * k, y - 14 * k, pw + 28 * k, ph + 28 * k, 38 * k, '#05070b', '#3a4458', 2); A(1);
  return [x, y, pw, ph];
}
function demoCards(lt, kicker, color, lines) {
  const x = w * .54;
  A(E(lt, .2, .6)); T(kicker, x, h * .16, big(.024), color, 'left', 700); A(1);
  lines.forEach(([t0, text, sub, col], i) => {
    const a = E(lt, t0, .7), y = h * (.27 + i * .17);
    A(a); box(x, y - h * .055, w * .4, h * .13, 16, 'rgba(22,27,38,.9)', col || C.line, 1.6);
    T(text, x + w * .018, y, big(.033), C.ink, 'left', 700);
    if (sub) T(sub, x + w * .018, y + h * .045, big(.022), C.muted, 'left', 500); A(1);
  });
}
function pointer(lt, t0, fromFrac, toY) { // a line from a card to a spot on the phone
  const [x, y, pw] = phoneFrame(-1), a = E(lt, t0, .6);
  A(a * .8); bez(w * .54, h * toY.card, x + pw * fromFrac, y + toY.phone * (h * .85), C.yellow, 2, [6, 6]); A(1);
}
S('d_quake', '3 · Demo · Earthquake', 15, (lt) => {
  phoneFrame(lt);
  demoCards(lt, 'AIRPLANE MODE ✈  ·  REAL PHONE', C.red, [
    [1.5, 'One tap → where to go', 'arrow · walking distance · what not to do', C.red],
    [5, '≈ 1 ms, offline', 'computed before the alert, read from the pack', C.green],
    [9, 'Official shelter, labelled', 'earthquake → open space (school field, park)', C.line],
  ]);
}, [{ t0: 0, t1: 15, text: 'Airplane mode is on. One tap: the arrow, the walking distance, and what not to do — computed offline in about a millisecond.' }], C.red);

S('d_flood', '3 · Demo · Flood', 12, (lt) => {
  phoneFrame(lt);
  demoCards(lt, 'SAME PLACE · DIFFERENT DISASTER', C.blue, [
    [2.5, 'Earthquake → Mullae Park, 72 m', 'open space', C.red],
    [3.8, 'Flood → senior centre, 232 m', 'away from underground · around past floods', C.blue],
  ]);
}, [{ t0: 0, t1: 12, text: 'Switch to flood, and it sends me somewhere else — away from anything underground and around streets that flooded before.' }], C.blue);

S('d_night', '3 · Demo · Night', 13, (lt) => {
  phoneFrame(lt);
  demoCards(lt, '23:00 · SCHOOL YARDS MAY BE LOCKED', C.yellow, [
    [1.5, '44.6% of Seoul → a school yard', 'gates may be locked at night and weekends', C.yellow],
    [5, 'Nearest non-school place, ready', 'already routed · step-free aware', C.green],
  ]);
}, [{ t0: 0, t1: 13, text: 'At night, school yards may be locked. Almost half of Seoul would be sent to one, so SafeRoute already knows the nearest place that is not a school.' }], C.yellow);

S('d_qa', '3 · Demo · Questions', 12, (lt) => {
  phoneFrame(lt);
  demoCards(lt, 'OFFLINE ANSWERS · MUSE GLIMMER (OPEN WEIGHTS)', C.purple, [
    [1.5, 'Written before, checked for safety', 'generated on a laptop with open weights', C.purple],
    [4.5, 'Safety test: 23 / 23', 'a 1B on-device model: 8 / 23', C.green],
  ]);
}, [{ t0: 0, t1: 12, text: "Questions get answers that were written in advance by Meta's open-weight model, Muse Glimmer, and checked for safety — no network needed." }], C.purple);

S('d_plan', '3 · Demo · Prepared plan', 15, (lt) => {
  phoneFrame(lt);
  demoCards(lt, 'PREPARED WHILE ONLINE · MUSE SPARK AGENT', C.purple, [
    [1.5, 'Backup shelter · family meeting point', 'chosen with verified tools only', C.purple],
    [5.5, 'Guidance-matching places nearby', 'open space · high ground · not designated', C.purple],
    [9.5, 'Shown on the map', 'all offline at alert time', C.green],
  ]);
}, [{ t0: 0, t1: 15, text: 'And for the places I actually go, a Muse Spark agent prepared a plan ahead of time — a backup shelter and a family meeting point — plus places that match the official guidance nearby.' }], C.purple);

// ---------- Part 4: how it works ----------
const SHELTERS = SEOUL.shelters;
S('h_tree', '4 · Before any alert', 13, (lt) => {
  const bx = [w * .06, h * .16, w * .88, h * .6], tr = tree(SHELTERS);
  stepHud(lt, [[0, 'OFFICIAL SHELTERS · whole city · once', 'multi-source shortest paths, one tree per disaster · 10 MB']]);
  streets(bx, .5);
  const wave = ap(lt, 1.2, 8) * (tr.maxD + 1);
  NET.nodes.forEach((n, k) => {
    const d = tr.dist[k]; if (d === Infinity) return;
    const p = P(n, bx);
    if (d <= wave) {
      const nx = P(NET.nodes[tr.next[k]], bx), a = clamp(wave - d, 0, 1);
      A(a * .9); if (tr.next[k] !== k) seg(p[0], p[1], nx[0], nx[1], C.blue, 1.8);
      dot(p[0], p[1], h * .0028, C.blue); A(1);
    }
  });
  SHELTERS.forEach(s => { const p = P(NET.nodes[s], bx); glow(p[0], p[1], h * .04, 'rgba(67,214,160,.5)', .8); dot(p[0], p[1], h * .014, C.green); });
  A(E(lt, 9.5, 1)); T('every street corner knows its next step', w * .5, h * .82, big(.032), C.ink, 'center', 700); A(1);
}, [{ t0: 0, t1: 13, text: 'How is it that fast? Routes to the official shelters are computed once for the whole city — a ten-megabyte pack — so every street corner already knows its next step.' }], C.blue);

S('h_follow', '4 · During the alert', 10, (lt) => {
  const bx = [w * .06, h * .16, w * .88, h * .56], tr = tree(SHELTERS);
  stepHud(lt, [[0, 'ALERT TIME · on the phone', 'route = follow next[] until a shelter']]);
  streets(bx, .5);
  NET.nodes.forEach((n, k) => { const p = P(n, bx); A(.3); dot(p[0], p[1], h * .0025, C.blue); A(1); });
  SHELTERS.forEach(s => { const p = P(NET.nodes[s], bx); dot(p[0], p[1], h * .014, C.green); });
  const path = pathTo(SEOUL.mapo, SHELTERS);
  const prog = es(ap(lt, 1.5, 3)) * (path.length - 1), k = Math.min(path.length - 2, Math.floor(prog));
  for (let i = 0; i < Math.floor(prog); i++) { const p = P(NET.nodes[path[i]], bx), q = P(NET.nodes[path[i + 1]], bx); seg(p[0], p[1], q[0], q[1], C.red, 5); }
  const pt = mv(P(NET.nodes[path[k]], bx), P(NET.nodes[path[k + 1]], bx), prog - k);
  glow(pt[0], pt[1], h * .05, 'rgba(242,122,110,.6)', 1); dot(pt[0], pt[1], h * .013, C.ink);
  A(E(lt, 5, 1)); T('≈ 1 ms', w * .5, h * .71, big(.065), C.green, 'center', 700);
  T('no search · no server · no model', w * .5, h * .77, big(.026), C.muted, 'center', 500); A(1);
}, [{ t0: 0, t1: 10, text: 'During an alert the phone only follows those arrows. No search, no server, no model: about one millisecond.' }], C.red);

S('h_background', '4 · On ordinary days', 13, (lt) => {
  stepHud(lt, [[0, 'YOUR PLACES ONLY · in the background', 'learn places → look around them → plan while online → validate']]);
  const bx = zoomBox(SEOUL.mapo, 24, .47);
  streets(bx, .35);
  // learned places (home, station, office) with a search radius; everything outside stays dark
  const places = [[SEOUL.west, 'home', 1], [SEOUL.mapo, 'station', 2.2], [ME_NODE, 'office', 3.4]];
  places.forEach(([n, name, t0]) => {
    const p = P(NET.nodes[n], bx), r = geoFit(bx)[2] * 1.6 * E(lt, t0, 1);
    A(.9 * E(lt, t0, .6)); ctx.beginPath(); ctx.arc(p[0], p[1], r, 0, Math.PI * 2); ctx.fillStyle = 'rgba(91,156,255,.08)'; ctx.fill();
    ctx.setLineDash([8, 8]); ctx.strokeStyle = C.blue; ctx.lineWidth = 2; ctx.stroke(); ctx.setLineDash([]);
    dot(p[0], p[1], h * .014, C.blue); T(name, p[0], p[1] - h * .04, big(.024), C.ink, 'center', 600); A(1);
    // guidance-matching places found inside the circle only
    const rr = RNG(n + 11);
    for (let k = 0; k < 4; k++) {
      const ang = rr() * Math.PI * 2, d = (.35 + rr() * .55) * geoFit(bx)[2] * 1.6, q = [p[0] + Math.cos(ang) * d, p[1] + Math.sin(ang) * d];
      A(E(lt, t0 + 1.4 + k * .35, .4)); glow(q[0], q[1], h * .025, 'rgba(183,156,255,.5)', .8); dot(q[0], q[1], h * .008, C.purple); A(1);
    }
  });
  A(E(lt, 5.2, .8)); T('only map tiles around your places are fetched', w * .5, h * .7, big(.028), C.purple, 'center', 600); A(1);
  // agent prepares plans with verified tools
  A(E(lt, 7.5, .6)); chip(w * .2, h * .755, w * .2, h * .05, 'rgba(183,156,255,.15)', 'Muse Spark agent → plan', C.purple, big(.02));
  chip(w * .44, h * .755, w * .16, h * .05, 'rgba(67,214,160,.15)', 'verified tools ✓', C.green, big(.02));
  chip(w * .64, h * .755, w * .18, h * .05, 'rgba(242,122,110,.15)', 'made-up place ✕', C.red, big(.02)); A(1);
}, [{ t0: 0, t1: 13, text: 'On ordinary days it learns only the places you actually go. Around them, and only there, it looks for more safe places that match the official guidance, and prepares plans while online.' }], C.purple);

// ---------- Part 5: hope ----------
S('hope', '5 · Hope', 16, (lt) => {
  const bx = [w * .04, h * .06, w * .92, h * .78], tr = tree(SHELTERS);
  streets(bx, .3 + .35 * E(lt, 0, 6));
  const r = RNG(3);
  for (let k = 0; k < 160; k++) {
    const n = Math.floor(r() * NET.nodes.length); if (tr.dist[n] === Infinity) continue;
    const p = P(NET.nodes[n], bx), nx = P(NET.nodes[tr.next[n]], bx), a = E(lt, .5 + r() * 5, 1);
    A(a * .9); dot(p[0], p[1], h * .006, C.ink); if (tr.next[n] !== n) arrow(p[0], p[1], lerp(p[0], nx[0], .6), lerp(p[1], nx[1], .6), C.green, 2); A(1);
  }
  SHELTERS.forEach(s => { const p = P(NET.nodes[s], bx); glow(p[0], p[1], h * .05, 'rgba(67,214,160,.4)', E(lt, 2, 3)); });
  A(E(lt, 9.5, 1.2) * .85); box(0, h * .28, w, h * .44, 0, 'rgba(12,15,22,.8)', null); A(1);
  A(E(lt, 10, 1.2)); T('SafeRoute', w * .5, h * .42, big(.08), C.ink, 'center', 700); A(1);
  A(E(lt, 11.2, 1.2)); T('Know where to go, before you need to.', w * .5, h * .53, big(.04), C.green, 'center', 600); A(1);
  A(E(lt, 12.6, 1)); TM('github.com/yhyh4420/saferoute', w * .5, h * .63, big(.024), C.muted, 'center', 500); A(1);
}, [{ t0: 0, t1: 3, text: "Disasters don't give us time to think." },
    { t0: 3, t1: 16, text: "I hope SafeRoute can turn a moment of panic into a few calm steps in the right direction — for my commute, and for everyone else's." }], C.green);
