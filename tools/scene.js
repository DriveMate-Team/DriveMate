#!/usr/bin/env node
/**
 * 시연 영상 장면별 실행기. 장면마다 30초 안에 끝난다.
 *
 *   node tools/scene.js save     지금 앱 기록을 "기준 기록"으로 저장 (처음 한 번. 영상용 기록을 만든 뒤에)
 *   node tools/scene.js all      전체를 한 번에 이어서 (약 2분). 장면 시작·질문 시각을 출력한다
 *   node tools/scene.js 1        홈 → 주행 준비 → 출발
 *   node tools/scene.js 2        좌회전 약 390m 전에서 출발 → 코칭 음성 → 좌회전 지나감
 *   node tools/scene.js 3        직진 구간 주행 (질문할 시간 20초)
 *   node tools/scene.js 4        교차로 210m 전에서 출발 → 코칭 → 천천히 멈춤 → P → 리포트
 *   node tools/scene.js 5        리포트 화면 그대로 (질문할 시간 20초). 장면 4 바로 뒤에 찍는다
 *   node tools/scene.js 6        홈 화면으로. 장면 5 뒤에 찍는다
 *
 * 녹화: 장면마다 "준비 끝"이 나오면 캡처 도구(Win+Shift+R)로 녹화를 시작한 뒤 엔터, 끝나면 캡처 도구의 정지 버튼으로 끈다.
 *       (스크립트가 보낸 키는 Game Bar가 받지 않아서 자동으로 하지 못한다)
 *
 * 기록이 꼬이지 않게: 장면 1~4는 시작할 때 앱 기록을 "기준 기록"으로 되돌린다.
 * 그래서 몇 번을 다시 찍어도 홈은 항상 같은 상태(2회차, 교차로 연습 중)에서 시작한다.
 * 장면 5·6은 장면 4가 만든 주행을 이어서 보여 주므로 기록을 되돌리지 않는다.
 *
 * 필요한 것: 에뮬레이터, adb forward tcp:8088 tcp:8088, 디버그 빌드 앱. 코칭 음성이 실제로 재생된다.
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const BASE = 'http://127.0.0.1:8088';
const PKG = 'com.example.drivemate';
const DB_DIR = `/data/user/10/${PKG}/databases`;
const DB_FILES = ['drivemate.db', 'drivemate.db-wal', 'drivemate.db-shm'];
const SNAP = path.join(__dirname, '.scene_base'); // 기준 기록 보관 폴더 (git에 올리지 않음)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (args, opts = {}) => execFileSync('adb', args, { maxBuffer: 64 * 1024 * 1024, ...opts });
const say = (m) => console.log(m);

// ---------- Virtual Console ----------
const put = async (p) => { const r = await fetch(BASE + p, { method: 'PUT' }); if (!r.ok) throw new Error(`PUT ${p} -> ${r.status}`); };
const state = async () => (await fetch(BASE + '/vehicle/state', { cache: 'no-store' })).json();
let cur = 0; // 지금 속도 (km/h)
const speed = async (s) => { cur = s; await put(`/vehicle/controls?speed=${s}&accelerator=0&brake=0&steering=0`); };
const signal = (m) => put('/vehicle/turn-signal?mode=' + m);
// 기어는 첫 변경이 무시될 때가 있어서 실제로 바뀔 때까지 다시 보낸다
async function gear(g) {
  for (let i = 0; i < 10; i++) {
    await put('/vehicle/gear?gear=' + g); await sleep(400);
    if ((await state()).gear?.actual?.value === g) return;
  }
  throw new Error('기어가 ' + g + '로 바뀌지 않습니다');
}
// 속도를 to까지 step km/h씩 everyMs마다 바꾼다
async function ramp(to, step, everyMs) {
  while (cur !== to) {
    await speed(cur < to ? Math.min(to, cur + step) : Math.max(to, cur - step));
    await sleep(everyMs);
  }
}
// 하트비트: 신호가 3초 넘게 없으면 콘솔이 조작을 거부한다(503)
async function heartbeat() {
  const beat = () => fetch(`${BASE}/browser/session?client=scene`, { method: 'POST' }).catch(() => {});
  await beat();
  const t = setInterval(beat, 1000);
  for (let i = 0; i < 40; i++) {
    const s = await state();
    if (s.controls?.emulator_active && s.gear?.bridge_ready) break;
    await sleep(500);
  }
  return () => clearInterval(t);
}

// ---------- 화면 ----------
// 화면 글자를 찾아 누른다 (분할 화면에서는 DriveMate 창을 먼저 눌러 초점을 옮긴다)
async function tapText(text, tries = 4) {
  for (let i = 0; i < tries; i++) {
    if (i > 0) await sleep(800);
    adb(['shell', 'input', 'tap', '1270', '1200']);
    adb(['shell', 'uiautomator', 'dump', '/sdcard/dm_ui.xml']);
    const xml = adb(['shell', 'cat', '/sdcard/dm_ui.xml'], { encoding: 'utf8' });
    const m = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
    if (m) { adb(['shell', 'input', 'tap', String((+m[1] + +m[3]) / 2), String((+m[2] + +m[4]) / 2)]); return; }
  }
  throw new Error(`화면에서 "${text}"를 찾지 못했습니다`);
}
function launch(extra = []) {
  adb(['shell', 'am', 'start', '--user', '10', '-n', `${PKG}/.MainActivity`, ...extra], { stdio: 'ignore' });
}
// 반반 분할(내비 + DriveMate)인지: 분할이면 내비 창(ai.umos.maps...)이 화면에 보인다
function isSplit() {
  const out = adb(['shell', 'dumpsys activity activities'], { encoding: 'utf8' });
  return out.split('\n').some((l) => l.includes('* Task{') && l.includes('ai.umos.maps.android.navigation.app') && l.includes('visible=true'));
}
// 풀려 있으면 앱 오른쪽 끝 "⋮" 손잡이를 끌어 다시 나눈다
async function ensureSplit() {
  for (let i = 0; i < 3 && !isSplit(); i++) {
    say('  반반 분할이 풀려 있어 다시 나눠요.');
    adb(['shell', 'input', 'swipe', '2547', '717', '1700', '717', '800']);
    await sleep(2500);
  }
  if (!isSplit()) throw new Error('반반 분할을 만들지 못했어요. 화면 오른쪽 끝 ⋮ 를 직접 끌어 주세요');
}
async function countdown(sec, label) {
  for (let s = sec; s > 0; s--) { process.stdout.write(`\r  ${label} ${s}초 `); await sleep(1000); }
  process.stdout.write('\n');
}

// ---------- 기록 저장·되돌리기 ----------
const runAs = (cmd) => ['shell', `run-as ${PKG} --user 10 sh -c '${cmd}'`];
function saveBase() {
  adb(['shell', 'am', 'force-stop', '--user', '10', PKG]); // 앱을 멈춰야 기록이 파일에 다 쓰인다
  fs.mkdirSync(SNAP, { recursive: true });
  for (const f of DB_FILES) {
    let buf = Buffer.alloc(0);
    try { buf = adb(['exec-out', `run-as ${PKG} --user 10 cat ${DB_DIR}/${f}`]); } catch (_) { /* 없는 파일 */ }
    fs.writeFileSync(path.join(SNAP, f), buf);
  }
  say('기준 기록을 저장했어요: tools/.scene_base/');
}
async function resetToBase(route, startM) {
  if (!fs.existsSync(path.join(SNAP, 'drivemate.db'))) throw new Error('기준 기록이 없어요. 먼저 node tools/scene.js save');
  // 앱을 먼저 멈춘다 (먼저 P로 바꾸면 앞 장면 주행이 끝나며 "수고하셨어요"가 나온다)
  adb(['shell', 'am', 'force-stop', '--user', '10', PKG]);
  await signal('Off'); await speed(0); await gear('Park');
  for (const f of DB_FILES) {
    const buf = fs.readFileSync(path.join(SNAP, f));
    adb(['exec-in', `run-as ${PKG} --user 10 sh -c "cat > ${DB_DIR}/${f}"`], { input: buf });
  }
  launch(['--ez', 'demo', 'true', '--es', 'demoRoute', route, '--ei', 'demoStartM', String(startM)]);
  await sleep(4000);
  await ensureSplit();
}
// 홈에서 주행 화면까지 바로 간다 (녹화 전 준비)
async function goDriving() {
  await tapText('주행 시작'); await sleep(1500);
  await tapText('출발하기'); await sleep(1500);
  await gear('Drive');
}
let recStart = 0; // 녹화 시작 시각 (장면 길이 재기용)
// 엔터를 기다린다 (녹화 키는 직접 누른다: 스크립트가 보낸 Win+Alt+R은 Game Bar가 받지 않았다, 2026-11-14 확인)
// SCENE_AUTO=1 이면 엔터를 기다리지 않는다 (녹화를 미리 켜 두고 Claude가 대신 실행할 때)
function waitEnter(msg) {
  process.stdout.write(msg);
  if (process.env.SCENE_AUTO) { process.stdout.write('(자동 진행)\n'); return Promise.resolve(); }
  return new Promise((r) => process.stdin.once('data', () => { process.stdin.pause(); r(); }));
}
async function ready() {
  await ensureSplit();
  await waitEnter('  준비 끝. 에뮬레이터 창을 누르고 캡처 도구(Win+Shift+R)로 녹화를 시작한 뒤 엔터를 누르세요. ');
  recStart = Date.now();   // 엔터를 누른 순간 = 녹화 시작 직후로 본다 (질문 시각은 여기부터 잰다)
  await countdown(3, '시작까지');
}
// 질문 장면: 마이크는 쓰지 않는다 (질문 목소리는 나중에 AI 음성으로 영상에 넣는다).
// "듣는 중" 화면을 2.5초 보여 준 뒤, 질문 문장을 인식된 문장처럼 앱에 직접 넣는다. (디버그 빌드에서만 동작)
// 문장을 넣은 시각(녹화 시작 후 몇 초)을 출력한다 → AI 음성을 그 시각에 맞춰 넣으면 된다.
const sec = () => ((Date.now() - recStart) / 1000).toFixed(1);
async function ask(question, answerSec) {
  launch(['--ez', 'listenShow', 'true']);
  say(`  ● ${sec()}초: "듣는 중" 화면 표시`);
  await sleep(2500);
  // adb shell은 인자를 이어 붙여 기기 셸로 넘기므로, 띄어쓰기·물음표가 있는 문장은 작은따옴표로 감싼다
  launch(['--es', 'ask', `'${question.replace(/'/g, '')}'`]);
  say(`  ★ ${sec()}초: 질문 넣음 → "${question}"  (AI 음성은 이 시각보다 조금 앞에서 끝나게 넣으세요)`);
  await countdown(answerSec, '답변 시간');
}

// ---------- 전체 (한 번에 이어서 찍기) ----------
// 장면이 시작되는 시각을 출력한다 (편집할 때 자르는 기준)
const mark = (label) => say(`  ▶ ${sec()}초: ${label}`);
// 앱 경로 FULL(NaviSource.FULL_LEGS): 좌회전 400m, 교차로 950m, 도착 1200m
let dist = 0, lastT = 0;
async function driveUntil(m, onTick) {
  while (dist < m) {
    await sleep(200);
    const now = Date.now(); dist += cur / 3.6 * (now - lastT) / 1000; lastT = now;
    if (onTick) await onTick();
  }
}
async function all() {
  await resetToBase('FULL', 0);        // 분할 확인·기록 초기화는 여기서 한 번만
  await ready();
  mark('1. 홈 (로드맵)');
  await sleep(4000);
  await tapText('주행 시작');
  mark('2. 주행 준비'); await sleep(4000);
  await tapText('출발하기'); await gear('Drive');
  mark('3. 출발'); await sleep(3000);

  // 좌회전: 60km/h까지 천천히 올린다. 코칭은 남은 300m(=100m 지점)에서, 깜빡이는 300m 지점부터
  mark('4. 좌회전 코칭 구간');
  dist = 0; lastT = Date.now();
  let on = false, lastRamp = 0;
  const rampTick = async () => {
    if (cur < 60 && Date.now() - lastRamp >= 1000) { lastRamp = Date.now(); await speed(cur + 5); } // 1초에 5km/h (급가속 기록이 남지 않게)
    if (!on && dist > 300) { on = true; await signal('BlinkLeft'); }
  };
  await driveUntil(440, rampTick);
  await signal('Off');

  // 직진 구간: 40km/h로 줄이고 질문 (다음 안내 = 교차로, 남은 200m에서 코칭)
  await ramp(40, 1, 150);
  mark('5. 직진 구간 질문');           // 감속·질문 동안 달린 거리는 다음 driveUntil이 한꺼번에 더한다
  await ask('비보호 좌회전은 언제 해?', 13);
  await driveUntil(950 - 210);         // 교차로 코칭(남은 200m) 바로 앞까지
  mark('6. 교차로 코칭');
  await driveUntil(950 - 150);         // 코칭을 들으며 60m 더
  mark('7. 천천히 정차 → P');
  await ramp(0, 1, 250);               // 초당 약 4km/h (급감속 기록 없음)
  for (let i = 0; i < 25 && Number((await state()).controls.speed_kph) >= 1; i++) await sleep(200);
  await sleep(500);
  await gear('Park');
  await sleep(6000);                   // "수고하셨어요" → 리포트 화면
  mark('8. 리포트');
  await sleep(3000);
  mark('9. 리포트에서 질문');
  await ask('오늘 부족했던 점은 뭐야?', 13);
  await tapText('홈으로');
  mark('10. 홈 (다음 목표)');
  await sleep(6000);
}

// ---------- 장면 ----------
const scenes = {
  all,
  // 1. 홈 → 주행 준비 → 출발
  async 1() {
    await resetToBase('EMART', 81);
    await ready();
    await sleep(4000);                 // 홈 화면 (로드맵)
    await tapText('주행 시작'); await sleep(5000);  // 주행 준비 화면
    await tapText('출발하기'); await sleep(5000);   // 주행 중 화면: "좌회전 300m 전에 다시 알려드릴게요."
  },
  // 2. 좌회전 약 390m 전 → 코칭 음성(300m) → 깜빡이 → 좌회전 지나감
  async 2() {
    await resetToBase('EMART', 81);    // 이마트 경로 81m 지점: 다음 좌회전(470m)까지 389m
    await goDriving();
    await ready();
    await ramp(60, 10, 1000);          // 6초 동안 60km/h까지 (이 장면 기록은 다음 장면에서 지워진다)
    // 좌회전 100m 전부터 지난 뒤까지 왼쪽 깜빡이
    let dist = 0, last = Date.now(), on = false;
    while (dist < 389 + 40) {
      await sleep(200);
      const now = Date.now(); dist += cur / 3.6 * (now - last) / 1000; last = now;
      if (!on && dist > 389 - 100) { on = true; await signal('BlinkLeft'); }
    }
    await signal('Off');
    await sleep(2000);
    say('  좌회전을 지났어요. (차는 계속 달리는 중. 다음 장면이 멈춰 줘요)');
  },
  // 3. 직진 구간: 다음 우회전까지 1km 넘게 남은 곳. 질문할 시간 20초
  async 3() {
    await resetToBase('EMART', 2860);  // 다음 우회전(3950m)까지 1090m
    await goDriving();
    await ready();
    await ramp(40, 10, 1000);
    await sleep(2000);
    await ask('비보호 좌회전은 언제 해?', 15);
  },
  // 4. 교차로 210m 전 → 코칭(200m) → 천천히 멈춤 → P → 리포트
  async 4() {
    await resetToBase('CROSS', 90);    // 장면용 경로: 교차로(300m)까지 210m
    await goDriving();
    await ready();
    await ramp(40, 1, 150);            // 약 6초 동안 40km/h까지 (급가속 기록이 남지 않게 천천히)
    await sleep(5000);
    await ramp(0, 1, 250);             // 초당 약 4km/h로 천천히 멈춤 (급감속 기록 없음)
    for (let i = 0; i < 25 && Number((await state()).controls.speed_kph) >= 1; i++) await sleep(200);
    await sleep(1000);
    await gear('Park');
    await sleep(7000);                 // 주차하면 리포트 화면으로 바뀐다
  },
  // 5. 리포트 화면 (장면 4 바로 뒤). 질문할 시간 20초
  async 5() {
    launch();
    await sleep(1000);
    await ready();
    await sleep(3000);                 // 리포트 화면을 먼저 보여 준다
    await ask('오늘 부족했던 점은 뭐야?', 15);
  },
  // 6. 홈 화면 (장면 5 뒤)
  async 6() {
    await ready();
    await tapText('홈으로');
    await sleep(6000);
  },
};

async function main() {
  const arg = process.argv[2];
  if (arg === 'save') return saveBase();
  const scene = scenes[arg];
  if (!scene) { say('사용법: node tools/scene.js save | all | 1 | 2 | 3 | 4 | 5 | 6'); process.exit(1); }
  const stop = await heartbeat();
  try {
    say(`=== 장면 ${arg} ===`);
    await scene();
    say('  ■ 캡처 도구의 정지 버튼으로 녹화를 멈추세요.');
    say(`=== 장면 ${arg} 끝 (녹화 구간 약 ${Math.round((Date.now() - recStart) / 1000)}초) ===`);
  } finally {
    stop();
  }
}
main().catch((e) => { console.error('오류: ' + e.message); process.exit(1); });
