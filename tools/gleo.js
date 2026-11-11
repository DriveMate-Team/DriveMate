#!/usr/bin/env node
/**
 * Gleo 호출 흉내 (테스트/데모 영상용)
 *
 * 실제 Gleo AI가 운전자 말을 듣고 우리 앱 함수를 호출하는 것과 똑같은 브로드캐스트를 adb로 보낸다.
 * (Gleo 문서: action = 함수 이름, extra "ACTION_PARAM" = JSON 글자, 대상 패키지 지정)
 *
 * 사용법 (에뮬레이터를 켠 상태에서, 프로젝트 루트에서):
 *   node tools/gleo.js status        "지금 나 괜찮게 하고 있어?"   -> GET_DRIVING_STATUS
 *   node tools/gleo.js distance      "얼마나 달렸어?"              -> GET_TRIP_DISTANCE
 *   node tools/gleo.js graduation    "졸업까지 얼마나 남았어?"     -> GET_GRADUATION_PROGRESS
 *   node tools/gleo.js GET_DRIVING_STATUS   (함수 이름을 직접 써도 됨)
 *   node tools/gleo.js status --stopped      앱이 강제 종료(force-stop)된 상태에서도 전달 (화면 없이 받는 경로 시험용)
 *
 * 보낸 뒤 앱의 로그(답변 문장, Gleo에 보낸 응답)와 실제로 말한 문장(TTS)을 보여준다. 소리는 에뮬레이터에서 직접 들으면 된다.
 * 참고: 안드로이드는 강제 종료된 앱에는 사용자가 한 번 실행하기 전까지 브로드캐스트를 전달하지 않는다(실제 Gleo도 같음).
 * 필요한 것: Node.js 18 이상, adb(Android SDK platform-tools).
 */
const { spawnSync } = require('child_process');
const path = require('path');

const PKG = 'com.example.drivemate';
const ADB = process.env.ADB || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');
const FUNCTIONS = {
  status: 'GET_DRIVING_STATUS',
  distance: 'GET_TRIP_DISTANCE',
  graduation: 'GET_GRADUATION_PROGRESS',
};

function adb(args) {
  const r = spawnSync(ADB, args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  if (r.error) throw new Error(`adb 실행 실패: ${r.error.message}\n(ADB 경로: ${ADB})`);
  return r;
}

function sleepMs(ms) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { /* 대기 */ }
}

function main() {
  const arg = process.argv[2];
  if (!arg || arg === 'help') {
    console.log(require('fs').readFileSync(__filename, 'utf8').split('*/')[0].replace(/^#!.*\n/, ''));
    return;
  }
  const stopped = process.argv.includes('--stopped');
  const fn = FUNCTIONS[arg] || arg.toUpperCase();
  const user = adb(['shell', 'am', 'get-current-user']).stdout.trim() || '10';

  adb(['logcat', '-c']);
  const flags = stopped ? ['--include-stopped-packages'] : [];
  const r = adb(['shell', 'am', 'broadcast', '--user', user, ...flags, '-a', fn, '-p', PKG, '--es', 'ACTION_PARAM', '{}']);
  console.log(`> ${fn} 호출 (${r.stdout.trim().split('\n').pop()})`);

  sleepMs(4000);   // 답이 만들어지고 말하기가 시작될 때까지 잠깐 기다린 뒤 로그를 보여준다
  const lines = adb(['logcat', '-d']).stdout.split('\n');
  for (const l of lines.filter((x) => x.includes('DMGleo'))) {
    console.log('  ' + l.replace(/^.*DMGleo\s*:\s*/, ''));
  }
  for (const l of lines.filter((x) => x.includes('doSpeak(com.example.drivemate)'))) {
    console.log('  [말한 문장] ' + l.replace(/\r/g, '').replace(/^.*text=/, '').replace(/, isFinal=.*$/, ''));
  }
}

try { main(); } catch (e) { console.error('오류: ' + e.message); process.exit(1); }
