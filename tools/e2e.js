#!/usr/bin/env node
/**
 * DriveMate v2 통합 테스트 / 시연 진행기: 참가신청서 4번 "A씨 마트 가는 길" 시나리오를 처음부터 끝까지 자동으로 재현한다.
 *
 *   node tools/e2e.js            (에뮬레이터가 켜져 있고, 앱이 설치되어 있어야 함. 디버그 빌드 전용)
 *
 * 하는 일:
 *   1) 앱을 새로 켜고 데모 경로(2km: 400m 우회전, 800m 교차로, 1300m 좌회전, 1700m 우회전)로 주행을 시작
 *   2) Virtual Console로 기어 D, 시속 50km로 달리면서 "달린 거리"에 맞춰 조작
 *        - 첫 우회전: 깜빡이를 켜지 않음            -> 리포트에 "깜빡이 기록 없음"
 *        - 교차로 앞(100m 안): 급하게 감속           -> 리포트에 "교차로 앞 급감속"
 *        - 900m: 질문 "비보호 좌회전은 언제 해"       -> 음성 답변 + 질문 기록
 *        - 좌회전: 100m 안에서 왼쪽 깜빡이를 켬       -> "방향지시등 작동 기록 있음"
 *        - 마지막 우회전: 깜빡이를 켜지 않음
 *   3) 주차(P) -> 리포트 화면으로 자동 전환
 *   4) 앱이 남긴 기록(logcat의 DriveMate 태그: SAY / EVENT / QUESTION / TRIP)을 출력하고 기대와 비교
 *
 * 소리: 코칭·답변 음성이 실제로 재생된다 (테스트 전에 스피커를 확인).
 * 필요한 것: Node.js 18 이상, adb. 외부 패키지 없음.
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const BASE = (process.env.VC_URL || 'http://127.0.0.1:8088').replace(/\/$/, '');
const PKG = 'com.example.drivemate';
const SHOTS = path.join(__dirname, '..', 'docs', 'screenshots', 'v2');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const adb = (...args) => execFileSync('adb', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const tap = (x, y) => adb('shell', 'input', 'tap', String(x), String(y));
const shot = (name) => {
  fs.mkdirSync(SHOTS, { recursive: true });
  fs.writeFileSync(path.join(SHOTS, name), execFileSync('adb', ['exec-out', 'screencap', '-p'], { maxBuffer: 64 * 1024 * 1024 }));
  console.log('  화면 저장: docs/screenshots/v2/' + name);
};

// ---------- Virtual Console ----------
const put = async (p) => {
  const res = await fetch(BASE + p, { method: 'PUT' });
  if (!res.ok) throw new Error(`PUT ${p} -> HTTP ${res.status}`);
};
const state = async () => (await (await fetch(BASE + '/vehicle/state', { cache: 'no-store' })).json());
let target = { speed: 0, brake: 0 };
const controls = (c) => {
  target = { ...target, ...c };
  return put(`/vehicle/controls?speed=${target.speed}&accelerator=0&brake=${target.brake}&steering=0`);
};
const signal = (mode) => put('/vehicle/turn-signal?mode=' + mode);
const gear = (name) => put('/vehicle/gear?gear=' + name);

// 화면에 보이는 글자의 가운데 좌표를 찾는다 (창 크기가 달라도 버튼을 찾을 수 있게)
function findText(text) {
  adb('shell', 'uiautomator', 'dump', '/sdcard/dm_ui.xml');
  const xml = adb('shell', 'cat', '/sdcard/dm_ui.xml');
  const m = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  if (!m) throw new Error(`화면에서 "${text}" 글자를 찾지 못했습니다`);
  return [(+m[1] + +m[3]) / 2, (+m[2] + +m[4]) / 2];
}
const tapText = (text) => { const [x, y] = findText(text); tap(x, y); console.log(`  누름: ${text}`); };

async function main() {
  // 하트비트: Virtual Console은 1초마다 신호가 없으면 조작을 거부한다
  const beat = () => fetch(`${BASE}/browser/session?client=e2e`, { method: 'POST' }).catch(() => {});
  await beat();
  const hb = setInterval(beat, 1000);
  for (let i = 0; i < 40; i++) {
    const s = await state();
    if (s.controls?.emulator_active && s.gear?.bridge_ready) break;
    await sleep(500);
  }

  console.log('1) 준비: 차를 P에 두고 앱을 새로 켠다');
  await controls({ speed: 0, brake: 0 });
  await signal('Off');
  await gear('Park');
  adb('shell', 'am', 'force-stop', PKG);
  adb('logcat', '-c');
  adb('shell', 'am', 'start', '--user', '10', '-n', `${PKG}/.MainActivity`, '--ez', 'demo', 'true');
  await sleep(5000);
  shot('e2e_1_home.png');

  console.log('2) 홈 -> 주행 준비 -> 출발하기');
  tapText('주행 시작');
  await sleep(1500);
  shot('e2e_2_prep.png');
  tapText('출발하기');
  await sleep(1500);

  console.log('3) 주행: 기어 D, 시속 50km');
  await gear('Drive');
  await controls({ speed: 50 });

  // 달린 거리를 속도로 적분한다 (앱의 데모 경로와 같은 방식)
  let dist = 0;
  let last = Date.now();
  const done = new Set();
  const once = async (name, at, fn) => {
    if (dist >= at && !done.has(name)) {
      done.add(name);
      console.log(`  [${Math.round(dist)}m] ${name}`);
      await fn();
    }
  };
  while (dist < 1790) {
    await sleep(200);
    const s = await state();
    const now = Date.now();
    dist += (Number(s.controls.speed_kph) / 3.6) * (now - last) / 1000;
    last = now;

    await once('우회전 코칭이 나온 뒤 화면 저장', 200, async () => shot('e2e_3_drive_coaching.png'));
    await once('교차로 앞: 급하게 감속 (브레이크)', 720, async () => controls({ speed: 15, brake: 80 }));
    await once('다시 시속 50km', 790, async () => controls({ speed: 50, brake: 0 }));
    await once('질문: 비보호 좌회전은 언제 해', 900, async () => {
      adb('shell', 'am', 'start', '--user', '10', '-n', `${PKG}/.MainActivity`, '--es', 'ask', "'비보호 좌회전은 언제 해'");
      await sleep(1200);
      shot('e2e_4_drive_answer.png');
    });
    await once('좌회전 전: 왼쪽 깜빡이 켬', 1220, async () => signal('BlinkLeft'));
    await once('좌회전 지남: 깜빡이 끔', 1320, async () => signal('Off'));
  }

  console.log('4) 주차: 속도 0 -> 기어 P');
  await controls({ speed: 0, brake: 30 });
  for (let i = 0; i < 150; i++) {
    if (Number((await state()).controls.speed_kph) < 1) break;
    await sleep(200);
  }
  await controls({ speed: 0, brake: 0 });
  await gear('Park');
  await sleep(6000);
  shot('e2e_5_report.png');

  clearInterval(hb);
  await fetch(`${BASE}/browser/session?client=e2e&closing=1`, { method: 'POST' }).catch(() => {});

  // ---------- 결과 확인 ----------
  const log = adb('shell', "logcat -d -s DriveMate:I | grep -E 'SAY|EVENT|QUESTION|TRIP|HEARD'");
  console.log('\n===== 앱 기록 =====\n' + log);
  const expect = [
    ['우회전 코칭', /SAY: 곧 우회전이에요/],
    ['교차로 코칭', /SAY: 교차로가 나와요/],
    ['좌회전 코칭', /SAY: 곧 좌회전 구간이에요/],
    ['질문 답변', /SAY: 차량 신호가 녹색일 때/],
    ['교차로 앞 급감속 기록', /EVENT: HARD_BRAKE .*nearTurn=true/],
    ['우회전 깜빡이 기록 없음', /EVENT: TURN .*direction=RIGHT signalUsed=false/],
    ['좌회전 깜빡이 기록 있음', /EVENT: TURN .*direction=LEFT signalUsed=true/],
    ['주행 종료와 다음 목표', /TRIP END .*goal=/],
  ];
  let failed = 0;
  console.log('===== 기대와 비교 =====');
  for (const [name, re] of expect) {
    const ok = re.test(log);
    if (!ok) failed++;
    console.log(`${ok ? '통과' : '실패'}  ${name}`);
  }
  process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error('오류: ' + e.message); process.exit(1); });
