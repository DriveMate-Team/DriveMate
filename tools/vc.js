#!/usr/bin/env node
/**
 * Pleos Connect Virtual Console 리모컨 (테스트/데모 영상용)
 *
 * Virtual Console 웹페이지(기본 http://127.0.0.1:8088)가 내부적으로 쓰는 HTTP API를 그대로 호출해서
 * 기어/속도/조향각을 바꾼다. (페이지 코드에서 확인한 API)
 *   GET /vehicle/state
 *   POST /browser/session?client=<id>      <- 하트비트 (아래 "중요" 참고)
 *   PUT /vehicle/body?door_fl=true|false    (door_fl / door_fr / door_rl / door_rr = 문 열림)
 *   PUT /vehicle/gear?gear=Park|Reverse|Neutral|Drive
 *   PUT /vehicle/controls?speed=<km/h>&accelerator=0&brake=<0~100>&steering=<deg>
 *   PUT /vehicle/turn-signal?mode=Off|BlinkLeft|BlinkRight     (방향지시등)
 *
 * 중요 - 하트비트:
 *   Virtual Console은 "브라우저 페이지가 열려 있다"는 신호(1초마다 POST /browser/session)가 3초 이상 끊기면
 *   emulator_inactive(HTTP 503)가 되어 기어/속도 변경을 거부한다. (브라우저 탭을 닫거나 백그라운드로 보내면 끊김)
 *   이 스크립트는 실행하는 동안 직접 하트비트를 보내므로 브라우저 탭이 없어도 동작한다.
 *   명령 사이에도 계속 활성 상태로 두고 싶으면:  node tools/vc.js keepalive   (끄려면 Ctrl+C)
 *
 * 필요한 것: Node.js 18 이상 (fetch 내장). 외부 패키지 없음.
 *
 * 사용법 (프로젝트 루트에서):
 *   node tools/vc.js state                       현재 기어/속도/조향각 보기
 *   node tools/vc.js gear D                      기어 바꾸기 (P / R / N / D)
 *   node tools/vc.js speed 50                    속도 바꾸기 (km/h, 0~240)
 *   node tools/vc.js steer 180                   조향각 바꾸기 (도, -540~540)
 *   node tools/vc.js park                        속도 0 -> 기어 P (콘솔의 P 버튼과 같은 순서)
 *   node tools/vc.js keepalive                   하트비트만 계속 보내서 콘솔을 활성 상태로 유지 (Ctrl+C로 종료)
 *   node tools/vc.js seq "gear D; speed 50; wait 20; steer 0; park"
 *                                                여러 동작을 순서대로 (세미콜론으로 구분)
 *
 * seq 안에서 쓸 수 있는 동작:
 *   gear <P|R|N|D>        기어 변경
 *   speed <km/h>          속도를 바로 바꿈
 *   ramp <km/h> <초>      지금 속도에서 목표 속도까지 1초 단위로 서서히 바꿈 (데모에서 자연스러움)
 *                         데모에서는 "ramp 50 10"처럼 1초에 11km보다 적게 올려야 급가속으로 기록되지 않는다
 *   steer <도>            조향각 변경
 *   signal <left|right|off>   방향지시등(깜빡이) 켜기/끄기
 *   brake <0~100>         브레이크 페달 밟은 정도(%) (0 = 뗌)
 *   door <fl|fr|rl|rr> <open|close>   문 열기/닫기 (앞왼쪽=운전석, 앞오른쪽, 뒤왼쪽, 뒤오른쪽)
 *   wait <초>             기다리기
 *   park                  속도 0 -> (감속이 끝날 때까지 기다림) -> 기어 P
 *   until <km/h> [초]     실제 속도가 목표(±2km/h)에 도달할 때까지 기다림 (기본 최대 30초). 에뮬레이터는 속도가 서서히 변해서 필요
 *   state                 현재 값 출력
 *   say <글자>            화면에 메시지 출력 (데모 진행 표시용, 차량에는 영향 없음)
 *
 * 환경변수: VC_URL (기본 http://127.0.0.1:8088)
 */

const BASE = (process.env.VC_URL || 'http://127.0.0.1:8088').replace(/\/$/, '');

const GEARS = { P: 'Park', R: 'Reverse', N: 'Neutral', D: 'Drive' };
const SPEED_RANGE = [0, 240];   // 콘솔이 알려준 제한값 (state.controls.limits)
const STEER_RANGE = [-540, 540];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- 하트비트 (콘솔 활성 유지) ----------
const CLIENT_ID = 'vc-js';
let heartbeatTimer = null;

async function heartbeat(closing = false) {
  try {
    await fetch(`${BASE}/browser/session?client=${CLIENT_ID}${closing ? '&closing=1' : ''}`, { method: 'POST', cache: 'no-store' });
  } catch (_) { /* 한 번 실패해도 다음 1초 뒤에 다시 보낸다 */ }
}

// 하트비트를 시작하고, 콘솔이 실제로 활성(emulator_active + DDS 준비)이 될 때까지 기다린다.
async function startSession() {
  await heartbeat();
  heartbeatTimer = setInterval(() => heartbeat(), 1000);
  const deadline = Date.now() + 20000;
  for (;;) {
    const s = await (await fetch(BASE + '/vehicle/state', { cache: 'no-store' })).json();
    if (s.controls?.emulator_active && s.gear?.bridge_ready) return;
    if (Date.now() > deadline) {
      throw new Error('하트비트를 보냈지만 Virtual Console이 활성화되지 않았습니다 (에뮬레이터 부팅이 끝났는지 확인하세요)');
    }
    await sleep(500);
  }
}

async function endSession() {
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  heartbeatTimer = null;
  await heartbeat(true);   // "이 클라이언트는 닫는다"고 알림 (다른 브라우저 탭에는 영향 없음)
}

// ---------- HTTP ----------
async function http(method, path) {
  const res = await fetch(BASE + path, { method, cache: 'no-store' });
  if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status}`);
  return res;
}

async function getState() {
  const s = await (await http('GET', '/vehicle/state')).json();
  return {
    gear: s.gear?.actual?.value,                 // "Park" / "Drive" ...
    speed: s.controls?.speed_kph,
    steer: s.controls?.steering_deg,
    raw: s,
  };
}

// 콘솔은 speed/accelerator/brake/steering을 한 번에 보낸다. 바꾸지 않을 값은 현재 값을 그대로 유지한다.
async function putControls({ speed, steer, brake }) {
  const cur = await getState();
  const sp = speed ?? cur.speed ?? 0;
  const st = steer ?? cur.steer ?? 0;
  const q = new URLSearchParams({
    speed: String(Math.round(sp)),
    accelerator: '0',
    brake: String(Math.round(brake ?? 0)),
    steering: String(Math.round(st)),
  });
  await http('PUT', '/vehicle/controls?' + q);
}

const clamp = (v, [lo, hi], name) => {
  if (!Number.isFinite(v)) throw new Error(`${name}: 숫자가 아닙니다`);
  if (v < lo || v > hi) throw new Error(`${name}: ${lo}~${hi} 범위를 벗어났습니다 (${v})`);
  return v;
};

function fmt(s) {
  return `기어=${s.gear}  속도=${s.speed} km/h  조향각=${s.steer}°`;
}

// ---------- 동작 ----------
async function setGear(letter) {
  const name = GEARS[String(letter).toUpperCase()];
  if (!name) throw new Error(`기어는 P/R/N/D 중 하나여야 합니다 (받은 값: ${letter})`);
  await http('PUT', '/vehicle/gear?gear=' + encodeURIComponent(name));
  console.log(`  기어 -> ${name}`);
}

async function setSpeed(v) {
  clamp(v, SPEED_RANGE, '속도');
  await putControls({ speed: v });
  console.log(`  속도 -> ${v} km/h`);
}

async function setSteer(v) {
  clamp(v, STEER_RANGE, '조향각');
  await putControls({ steer: v });
  console.log(`  조향각 -> ${v}°`);
}

// 방향지시등: left / right / off
async function setSignal(which) {
  const mode = { left: 'BlinkLeft', right: 'BlinkRight', off: 'Off' }[String(which).toLowerCase()];
  if (!mode) throw new Error('방향지시등은 left/right/off 중 하나여야 합니다 (받은 값: ' + which + ')');
  await http('PUT', '/vehicle/turn-signal?mode=' + mode);
  console.log(`  방향지시등 -> ${mode}`);
}

// 브레이크 페달 (0~100%)
async function setBrake(v) {
  clamp(v, [0, 100], '브레이크');
  await putControls({ brake: v });
  console.log(`  브레이크 -> ${v}%`);
}

// 문 열기/닫기: 콘솔의 body API (door_fl / door_fr / door_rl / door_rr)
async function setDoor(which, action) {
  const w = String(which).toLowerCase();
  if (!['fl', 'fr', 'rl', 'rr'].includes(w)) throw new Error('문은 fl/fr/rl/rr 중 하나여야 합니다 (받은 값: ' + which + ')');
  if (!['open', 'close'].includes(String(action).toLowerCase())) throw new Error('open 또는 close 여야 합니다 (받은 값: ' + action + ')');
  await http('PUT', `/vehicle/body?door_${w}=${String(action).toLowerCase() === 'open'}`);
  const names = { fl: 'front_left', fr: 'front_right', rl: 'rear_left', rr: 'rear_right' };
  await sleep(300);
  const st = await (await http('GET', '/vehicle/state')).json();
  console.log(`  문 ${w} -> ${action} (콘솔 상태: ${st.body?.doors?.[names[w]] ? '열림' : '닫힘'})`);
}

async function ramp(target, seconds) {
  clamp(target, SPEED_RANGE, '목표 속도');
  const start = (await getState()).speed ?? 0;
  const steps = Math.max(1, Math.round(seconds));
  console.log(`  속도 ${start} -> ${target} km/h (${steps}초에 걸쳐)`);
  for (let i = 1; i <= steps; i++) {
    await putControls({ speed: start + ((target - start) * i) / steps });
    if (i < steps) await sleep(1000);
  }
}

// 실제 속도가 목표 근처가 될 때까지 기다린다. 에뮬레이터는 목표 속도를 주면 서서히 가속/감속하기 때문.
async function until(target, timeoutSec = 30) {
  const deadline = Date.now() + timeoutSec * 1000;
  for (;;) {
    const s = await getState();
    if (Math.abs(Number(s.speed) - target) <= 2) {
      console.log(`  속도 ${s.speed} km/h 도달`);
      return;
    }
    if (Date.now() > deadline) throw new Error(`${timeoutSec}초 안에 ${target} km/h에 도달하지 못했습니다 (현재 ${s.speed})`);
    await sleep(500);
  }
}

// 콘솔의 P 버튼과 같은 순서: 속도 0으로 만든 뒤(감속이 끝날 때까지 기다림) 기어 Park, 그리고 확인
async function park() {
  await putControls({ speed: 0 });
  await until(0, 30);
  await http('PUT', '/vehicle/gear?gear=Park');
  await sleep(300);
  const s = await getState();
  if (s.gear !== 'Park') throw new Error('P 전환이 확인되지 않았습니다: ' + fmt(s));
  console.log('  P로 전환 완료 (속도 ' + s.speed + ')');
}

async function wait(seconds) {
  console.log(`  ${seconds}초 대기...`);
  await sleep(seconds * 1000);
}

async function runStep(text) {
  const [cmd, ...a] = text.trim().split(/\s+/);
  if (!cmd) return;
  console.log(`> ${text.trim()}`);
  switch (cmd.toLowerCase()) {
    case 'gear': return setGear(a[0]);
    case 'speed': return setSpeed(Number(a[0]));
    case 'ramp': return ramp(Number(a[0]), Number(a[1] ?? 5));
    case 'door': return setDoor(a[0], a[1]);
    case 'steer': return setSteer(Number(a[0]));
    case 'signal': return setSignal(a[0]);
    case 'brake': return setBrake(Number(a[0]));
    case 'wait': return wait(Number(a[0]));
    case 'park': return park();
    case 'until': return until(Number(a[0]), Number(a[1] ?? 30));
    case 'state': return console.log('  ' + fmt(await getState()));
    case 'say': return console.log('  ' + a.join(' '));
    default: throw new Error(`알 수 없는 동작: ${cmd}`);
  }
}

// ---------- 실행 ----------
async function main() {
  const [cmd, ...args] = process.argv.slice(2);
  if (!cmd || cmd === 'help' || cmd === '-h' || cmd === '--help') {
    console.log(require('fs').readFileSync(__filename, 'utf8').split('*/')[0].replace(/^#!.*\n/, ''));
    return;
  }
  await startSession();   // 이후 모든 명령은 콘솔이 활성인 상태에서 실행된다
  if (cmd === 'keepalive') {
    console.log('하트비트 전송 중... 콘솔이 활성 상태로 유지됩니다. 끝내려면 Ctrl+C');
    process.on('SIGINT', async () => { await endSession(); process.exit(0); });
    await new Promise(() => {});   // 영원히 대기
  }
  if (cmd === 'seq') {
    const steps = (args.join(' ') || '').split(';').map((s) => s.trim()).filter(Boolean);
    if (steps.length === 0) throw new Error('seq 뒤에 동작을 적어주세요. 예: seq "gear D; speed 50; wait 20; park"');
    for (const st of steps) await runStep(st);
    console.log('끝: ' + fmt(await getState()));
    return;
  }
  await runStep([cmd, ...args].join(' '));
  if (cmd !== 'state') console.log('현재: ' + fmt(await getState()));
}

main().then(endSession, async (e) => { await endSession(); throw e; }).catch((e) => {
  const hint = /fetch failed|ECONNREFUSED/.test(String(e.cause || e))
    ? `\n(Virtual Console이 ${BASE} 에서 켜져 있는지 확인하세요)` : '';
  console.error('오류: ' + e.message + hint);
  process.exit(1);
});
