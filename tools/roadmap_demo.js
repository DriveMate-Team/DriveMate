#!/usr/bin/env node
/**
 * 연수 로드맵 시연용 주행: 김포공항 데모 경로(2.9km, 고속도로 진입 포함)를 "회전마다 맞는 쪽 깜빡이를 켜고" 달린다.
 *
 *   node tools/roadmap_demo.js [횟수=2] [끝 거리 m=2900]
 *   영상용(합류 전에 멈춤 → 좌·우회전만 완료, 교차로 "지금 연습 중", 합류 남음): node tools/roadmap_demo.js 2 2650
 *
 * 2번 달리면 기록 기준으로 로드맵의 "좌회전"(2번 연속 깜빡이 기록)과 "고속도로 합류"(1번 지남)가 완료된다.
 * 각 주행 뒤에 리포트 화면이 docs/screenshots/v2/roadmap_report_N.png 로 저장된다.
 * 주의: 실제 주행 기록이 쌓인다. 깨끗한 상태에서 보려면 먼저 앱 데이터를 지운다.
 * 소리: 코칭 음성이 실제로 재생된다. 필요한 것: Node.js 18 이상, adb, adb forward tcp:8088 tcp:8088
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const BASE = 'http://127.0.0.1:8088';
const PKG = 'com.example.drivemate';
const SHOTS = path.join(__dirname, '..', 'docs', 'screenshots', 'v2');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...a) => execFileSync('adb', a, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const shot = (name) => {
  fs.mkdirSync(SHOTS, { recursive: true });
  fs.writeFileSync(path.join(SHOTS, name), execFileSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 }));
  console.log('  화면 저장: docs/screenshots/v2/' + name);
};
const put = async (p) => { const r = await fetch(BASE + p, { method: 'PUT' }); if (!r.ok) throw new Error(`PUT ${p} -> ${r.status}`); };
const state = async () => (await fetch(BASE + '/vehicle/state', { cache: 'no-store' })).json();
const speed = (s) => put(`/vehicle/controls?speed=${s}&accelerator=0&brake=0&steering=0`);
const signal = (m) => put('/vehicle/turn-signal?mode=' + m);
// 기어: 에뮬레이터를 막 켠 뒤에는 첫 변경이 무시될 때가 있어서, 실제로 바뀔 때까지 다시 보낸다
const gear = async (g) => {
  for (let i = 0; i < 10; i++) {
    await put('/vehicle/gear?gear=' + g); await sleep(500);
    if ((await state()).gear?.actual?.value === g) return;
  }
  throw new Error('기어가 ' + g + '로 바뀌지 않습니다');
};

// 화면 글자를 찾아 누른다 (e2e.js와 같은 방식: 분할 화면에서는 DriveMate 창을 먼저 눌러 초점을 옮긴다)
// 화면이 막 바뀐 직후에는 이전 창을 읽을 수 있어서 최대 3번 다시 읽는다
function tapText(text, tries = 3) {
  let m = null;
  for (let i = 0; i < tries && !m; i++) {
    if (i > 0) execFileSync('powershell', ['-c', 'Start-Sleep -Milliseconds 1000']);
    adb('shell', 'input', 'tap', '1270', '1200');
    adb('shell', 'uiautomator', 'dump', '/sdcard/dm_ui.xml');
    m = adb('shell', 'cat', '/sdcard/dm_ui.xml').match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  }
  if (!m) throw new Error(`화면에서 "${text}"를 찾지 못했습니다`);
  adb('shell', 'input', 'tap', String((+m[1] + +m[3]) / 2), String((+m[2] + +m[4]) / 2));
}

// 김포공항 데모 경로(NaviSource.AIRPORT_LEGS)의 누적 거리: 좌 80, 우 460, 고가 밑 우측 1190, 우 2590, 고속도로 진입 2790, 톨게이트 2870
// 각 회전 지점 80m 앞부터 40m 뒤까지 그쪽 깜빡이를 켠다 (앱은 100m 안에서 켜졌는지 본다)
const SIGNALS = [[0, 120, 'BlinkLeft'], [380, 500, 'BlinkRight'], [1110, 1230, 'BlinkRight'], [2510, 2630, 'BlinkRight']];
// 고속도로 진입(2790m) 100m 안에 들어가지 않고 멈추려면 2690m 이하로 준다
let END_M = 2900;

async function drive(n) {
  console.log(`\n=== ${n}번째 주행 ===`);
  await speed(0); await signal('Off'); await gear('Park');
  adb('shell', 'am', 'start', '--user', '10', '-n', `${PKG}/.MainActivity`, '--ez', 'demo', 'true', '--es', 'demoRoute', 'AIRPORT');
  await sleep(4000);
  try { tapText('홈으로', 1); await sleep(1500); } catch (_) { /* 이미 홈 */ }
  tapText('주행 시작'); await sleep(1500);
  tapText('출발하기'); await sleep(1500);
  await gear('Drive');

  let dist = 0, last = Date.now(), cur = 0, lastRamp = 0, mode = 'Off';
  while (dist < END_M) {
    await sleep(200);
    // 1초에 5km씩 시속 60km까지 (급가속 기록을 피한다)
    if (Date.now() - lastRamp >= 1000 && cur < 60) { lastRamp = Date.now(); cur += 5; await speed(cur); }
    const s = await state();
    const now = Date.now();
    dist += Number(s.controls.speed_kph) / 3.6 * (now - last) / 1000;
    last = now;
    const want = (SIGNALS.find(([a, b]) => dist >= a && dist < b) || [0, 0, 'Off'])[2];
    if (want !== mode) { mode = want; await signal(mode); console.log(`  [${Math.round(dist)}m] 깜빡이 ${mode}`); }
  }
  // 천천히 멈추고 주차 (1초에 5km씩)
  await signal('Off');
  while (cur > 0) { cur = Math.max(0, cur - 5); await speed(cur); await sleep(1000); }
  for (let i = 0; i < 50 && Number((await state()).controls.speed_kph) >= 1; i++) await sleep(200);
  await gear('Park');
  await sleep(6000);
  shot(`roadmap_report_${n}.png`);
}

async function main() {
  const runs = Number(process.argv[2] || 2);
  if (process.argv[3]) END_M = Number(process.argv[3]);
  // 하트비트: Virtual Console은 신호가 3초 넘게 없으면 조작을 거부한다(503). 먼저 보내고 준비될 때까지 기다린다
  const beat = () => fetch(`${BASE}/browser/session?client=roadmap`, { method: 'POST' }).catch(() => {});
  await beat();
  const hb = setInterval(beat, 1000);
  for (let i = 0; i < 40; i++) {
    const s = await state();
    if (s.controls?.emulator_active && s.gear?.bridge_ready) break;
    await sleep(500);
  }
  try {
    for (let i = 1; i <= runs; i++) await drive(i);
    // 기기 안 grep은 긴 로그에서 죽을 수 있어서 PC에서 거른다
    const log = adb('logcat', '-d', '-s', 'DriveMate:I').split('\n').filter((l) => /EVENT: (TURN|POINT_PASSED)|TRIP END/.test(l)).join('\n');
    console.log('\n===== 기록 =====\n' + log);
  } finally {
    clearInterval(hb);
  }
}
main().catch((e) => { console.error('오류: ' + e.message); process.exit(1); });
