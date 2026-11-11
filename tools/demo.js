#!/usr/bin/env node
/**
 * DriveMate 데모 시나리오 자동 진행 (영상 촬영/리허설용)
 *
 * Virtual Console(차량 값)과 Gleo 호출 흉내를 순서대로 실행하고, 각 장면의 해설 문구를 출력한다.
 * 장면 순서와 시간은 docs/DEMO_SCENARIO.md 와 같다.
 *
 * 사용법 (에뮬레이터와 `adb forward tcp:8088 tcp:8088`이 준비된 상태에서, 프로젝트 루트에서):
 *   node tools/demo.js a        파트 A  코칭 시연: 출발 인사 -> 과속 안내 -> 급커브 안내 -> 질문 -> 주차 후 요약   (약 1분 20초)
 *   node tools/demo.js b        파트 B  졸업 시연: 깨끗한 주행 -> 졸업 축하 -> 졸업 후 질문           (약 1분 20초)
 *   node tools/demo.js c        파트 C  "말수가 줄어드는 코치": 같은 70km/h 주행을 1단계/2단계/졸업에서 비교   (약 2분)
 *   node tools/demo.js all      A + B 순서대로 (C는 따로)
 *   node tools/demo.js a --no-reset    기록을 지우지 않고 (이어서 찍을 때)
 *
 * 각 파트는 시작할 때 기록을 정리하고 앱을 새로 켠다:
 *   A: 기록 삭제(졸업 상태 포함) -> 앱 실행
 *   B: 졸업 직전 시드(누적 199.6km, 깨끗한 주행 4번 연속) -> 앱 실행
 *
 * 끝나면 시연 중 앱이 실제로 말한 문장을 모아서 보여준다. (소리는 에뮬레이터에서 직접 들으세요)
 * 필요한 것: Node.js 18 이상, adb, tools/vc.js, tools/gleo.js, tools/seed_demo.js
 */
const { spawnSync } = require('child_process');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const PKG = 'com.example.drivemate';
const ADB = process.env.ADB || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const sleep = (sec) => { const t0 = Date.now(); while (Date.now() - t0 < sec * 1000) { /* 대기 */ } };

const spoken = [];   // 시연 중 앱이 말한 문장 모음
const seen = new Set();

function adb(args) {
  // maxBuffer: 로그가 커도 출력이 잘리지 않게 넉넉히 (기본 1MB를 넘으면 뒤쪽이 잘려 말한 문장을 놓쳤음)
  return spawnSync(ADB, args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
}

function node(script, ...args) {
  const r = spawnSync(process.execPath, [path.join(ROOT, 'tools', script), ...args], { encoding: 'utf8' });
  if (r.status !== 0) {
    console.error(r.stdout + r.stderr);
    throw new Error(`${script} ${args.join(' ')} 실패`);
  }
  return r.stdout;
}

// 앱이 말한 문장을 로그에서 모은다 (gleo.js가 호출 때마다 로그를 지우므로 장면이 끝날 때마다 모아 둔다)
function collectSpoken() {
  // 기기 안에서 grep으로 걸러서 가져온다 (로그 전체를 옮기지 않음)
  const lines = adb(['shell', "logcat -d -v time | grep -F 'doSpeak(com.example.drivemate)'"]).stdout.split('\n').filter((l) => l.trim());
  for (const l of lines) {
    if (seen.has(l)) continue;
    seen.add(l);
    spoken.push(l.replace(/\r/g, '').replace(/^.*text=/, '').replace(/, isFinal=.*$/, '').trim());
  }
}

function scene(title, note) {
  console.log(`\n■ ${title}`);
  if (note) console.log(`   ${note}`);
}

function vc(label, seq, waitAfter = 0) {
  console.log(`   [차량] ${label}`);
  node('vc.js', 'seq', seq);
  if (waitAfter) sleep(waitAfter);
  collectSpoken();
}

function ask(label, fn) {
  console.log(`   [질문] "${label}"  -> ${fn}`);
  const out = node('gleo.js', fn);
  const say = out.split('\n').filter((l) => l.includes('[말한 문장]')).map((l) => l.replace(/\r/g, '').replace(/^.*\[말한 문장\]\s*/, ''));
  for (const s of say) console.log(`   [코치 답변] ${s}`);
  collectSpoken();
  sleep(1);
}

function resetLog() {
  const user = adb(['shell', 'am', 'get-current-user']).stdout.trim() || '10';
  adb(['shell', 'am', 'force-stop', PKG]);
  adb(['shell', 'run-as', PKG, '--user', user, 'rm', '-f', 'files/drive_log.json', 'files/drive_log.json.tmp']);
  console.log('   기록 삭제 완료 (졸업 상태 포함)');
}

function launchApp() {
  // 차량 화면 호스트(템플릿 호스트)를 같이 재시작한다: 예전 세션이 남아 화면이 옛 내용으로 멈춰 보이는 일을 막는다
  adb(['shell', 'am', 'force-stop', PKG]);
  adb(['shell', 'am', 'force-stop', 'com.android.car.templates.host']);
  sleep(3);
  adb(['logcat', '-c']);   // 이전 테스트의 말한 문장이 시연 기록에 섞이지 않게 로그를 비운다
  adb(['shell', 'am', 'start', '-S', '-W', '-n', `${PKG}/com.example.drivemate.shared.DriveMateActivity`]);
  sleep(7);   // 앱이 뜨고 TTS가 준비될 때까지
  console.log('   앱 실행 완료');
}

// ---------------- 파트 A: 코칭 시연 ----------------
function partA(opts) {
  console.log('\n==================== 파트 A: 코칭 시연 (지민의 첫 혼자 운전) ====================');
  scene('준비', '기록을 비우고 앱을 켠다. 차량은 P, 조향각 0');
  if (!opts.noReset) resetLog();
  launchApp();
  vc('P 기어, 핸들 정면', 'steer 0; park', 1);

  scene('장면 1. 출발', '기어를 P에서 D로 바꾸는 순간 인사 + 안전벨트 확인  ->  "오늘도 같이 갈게요. 안전벨트부터 확인해요." (에뮬레이터는 벨트 미착용 상태)');
  vc('P -> D, 40km/h', 'gear D; ramp 40 5; until 40 30', 3);

  scene('장면 2. 과속', '60km/h를 넘기면 코치가 짧게 알려 준다  ->  "조금 빨라요. 속도 줄여볼까요?"');
  vc('70km/h까지 가속', 'ramp 70 4; until 70 30', 7);

  scene('장면 3. 질문 (주행 중, 손은 핸들에)', 'Gleo에게 말하면 앱이 기록으로 답한다 (여기서는 Gleo 호출을 흉내냄)');
  ask('지금 나 괜찮게 하고 있어?', 'status');
  sleep(3);

  scene('장면 4. 급커브', '30km/h 이상에서 핸들을 90도 넘게 꺾으면  ->  "커브에서는 속도부터 줄이고, 핸들은 천천히 돌려요."');
  vc('45km/h로 줄이고 핸들 200도', 'ramp 45 4; until 45 30; steer 200', 7);
  vc('핸들 정면', 'steer 0', 1);

  scene('장면 5. 다시 질문', '이번에는 실수가 더 늘었다는 걸 알려 준다');
  ask('지금 나 괜찮게 하고 있어?', 'status');
  sleep(3);
  ask('얼마나 달렸어?', 'distance');
  sleep(3);

  scene('장면 6. 도착 후 요약', '속도 0, 기어 P  ->  음성 요약 + 요약 화면 (실수가 있었으니 졸업 진행은 "깨끗한 주행 0/5")');
  vc('속도 0, P로 주차', 'speed 0; park', 12);
}

// ---------------- 파트 B: 졸업 시연 ----------------
function partB(opts) {
  console.log('\n==================== 파트 B: 졸업 시연 (몇 주 뒤의 지민) ====================');
  scene('준비', '졸업 직전 기록을 만들고(누적 199.6km, 깨끗한 주행 4번 연속) 앱을 켠다');
  node('seed_demo.js', 'near-graduation');
  console.log('   시드 완료');
  launchApp();
  vc('P 기어, 핸들 정면', 'steer 0; park', 1);

  scene('장면 7. 오늘도 깨끗하게', '50km/h로 얌전히 달린다 (과속·급커브 없음)');
  vc('P -> D, 50km/h 유지', 'gear D; ramp 50 4; until 50 30; wait 45', 0);

  scene('장면 8. 졸업!', '주차하면 칭찬과 함께 졸업을 알려 준다 (누적 200km + 깨끗한 주행 5번 연속)');
  vc('속도 0, P로 주차', 'speed 0; park', 14);

  scene('장면 9. 졸업 후 질문', '졸업했다는 걸 다시 물어봐도 답해 준다');
  ask('졸업까지 얼마나 남았어?', 'graduation');
  sleep(3);
}

// ---------------- 파트 C: 말수가 줄어드는 코치 ----------------
function drive70(label, seedName, note) {
  scene(label, note);
  if (seedName === 'reset') resetLog(); else node('seed_demo.js', seedName);
  launchApp();
  vc('P 기어, 핸들 정면', 'steer 0; park', 1);
  vc('P -> D, 70km/h로 8초', 'gear D; ramp 70 4; until 70 30; wait 8', 2);
  vc('속도 0, P로 주차', 'speed 0; park', 9);
}

function partC() {
  console.log('\n==================== 파트 C: 말수가 줄어드는 코치 (같은 70km/h, 실력에 따라 다른 반응) ====================');
  drive70('장면 10. 1단계 새싹', 'reset', '실수마다 바로 안내  ->  "조금 빨라요. 속도 줄여볼까요?" 그리고 주차 후 요약');
  drive70('장면 11. 2단계 적응 중', 'level2', '70km/h는 위험 기준(75km/h) 아래라서 조용하다. 말하지 않은 과속도 기록되어 주차 후 요약에만 나온다');
  drive70('장면 12. 졸업', 'graduated', '졸업 후에는 아주 위험할 때(90km/h 초과)만 말한다. 주차 후 짧은 한 문장 요약만');
}

function main() {
  const args = process.argv.slice(2);
  const part = (args[0] || '').toLowerCase();
  const opts = { noReset: args.includes('--no-reset') };
  if (!['a', 'b', 'c', 'all'].includes(part)) {
    console.log(require('fs').readFileSync(__filename, 'utf8').split('*/')[0].replace(/^#!.*\n/, ''));
    return;
  }
  if (part === 'a' || part === 'all') partA(opts);
  if (part === 'b' || part === 'all') partB(opts);
  if (part === 'c') partC();

  console.log('\n==================== 끝 ====================');
  console.log('시연 중 앱이 말한 문장 (순서대로):');
  spoken.forEach((s, i) => console.log(`  ${String(i + 1).padStart(2)}. ${s}`));
}

try { main(); } catch (e) { console.error('오류: ' + e.message); process.exit(1); }
