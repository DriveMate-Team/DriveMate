#!/usr/bin/env node
/**
 * DriveMate 데모/테스트용 주행 기록 시드
 *
 * 졸업은 누적 200km가 필요해서 에뮬레이터로 실제로 달려 채울 수 없다.
 * 이 스크립트가 "이미 어느 정도 달린 기록"을 앱의 저장 파일(files/drive_log.json)에 넣어 준다.
 * (앱을 종료한 뒤 넣고, 다시 켜면 그 상태에서 시작한다. 기록 삭제는 reset_log.bat)
 *
 * 사용법 (에뮬레이터를 켠 상태에서, 프로젝트 루트에서):
 *   node tools/seed_demo.js streak2           깨끗한 주행 2번 연속 -> 깨끗한 주행을 한 번 더 하면 "3번 연속" 칭찬
 *   node tools/seed_demo.js level2            2단계(코치 수준 2): 위험할 때만 실시간 안내
 *   node tools/seed_demo.js near-level        누적 29.7km -> 0.5km 이상 깨끗하게 달리면 "적응 중" 단계로 상승
 *   node tools/seed_demo.js near-graduation   누적 199.6km, 깨끗한 주행 4번 연속 -> 깨끗하게 0.5km 이상 달리면 졸업!
 *   node tools/seed_demo.js demo-near         [데모 모드용] 누적 0.6km, 깨끗한 주행 1번 -> 0.3km 이상 깨끗하게 달리면 졸업 (DEMO_MODE=true 필요)
 *   node tools/seed_demo.js graduated         이미 졸업한 상태
 *   node tools/seed_demo.js list              프리셋 목록
 *
 * 졸업 규칙(GraduationEvaluator.kt 맨 위 상수와 같아야 함): 30/100/200km, 깨끗한 주행(0.5km 이상, 실수 0번) 연속 5번.
 * 필요한 것: Node.js 18 이상, adb(Android SDK platform-tools).
 */
const { spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PKG = 'com.example.drivemate';
const ADB = process.env.ADB || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe');

// 프리셋: 오래된 주행부터. km = 주행 거리, mistakes = 그 주행의 과속/급커브 기록 수
const PRESETS = {
  streak2: {
    desc: '깨끗한 주행 2번 연속 (누적 10.7km, 1단계)',
    announcedStage: 1, graduated: false,
    trips: [{ km: 8.0, mistakes: 2 }, { km: 1.5, mistakes: 0 }, { km: 1.2, mistakes: 0 }],
  },
  level2: {
    desc: '2단계 적응 중 (누적 35km, 마지막 주행에 실수 있음) -> 코치 수준 2: 75km/h 초과·50km/h 이상 급커브만 실시간 안내',
    announcedStage: 2, graduated: false,
    trips: [{ km: 30.0, mistakes: 4 }, { km: 5.0, mistakes: 1 }],
  },
  'near-stage3': {
    desc: '지켜보기 2단계 끝자락 (누적 99.7km) -> 깨끗하게 0.5km 이상 달리면 3단계로 오르며 "졸업이 얼마 남지 않았어요"',
    announcedStage: 2, graduated: false,
    trips: [{ km: 95.0, mistakes: 3 }, { km: 4.7, mistakes: 1 }],
  },
  'near-level': {
    desc: '누적 29.7km (1단계), 마지막 주행에 실수 있음 -> 깨끗한 주행 0.5km 이상이면 2단계로 상승',
    announcedStage: 1, graduated: false,
    trips: [{ km: 27.0, mistakes: 4 }, { km: 2.7, mistakes: 2 }],
  },
  'near-graduation': {
    desc: '누적 199.6km (3단계), 깨끗한 주행 4번 연속 -> 깨끗한 주행 0.5km 이상이면 졸업',
    announcedStage: 3, graduated: false,
    trips: [{ km: 191.2, mistakes: 6 }, { km: 4.0, mistakes: 1 },
            { km: 1.2, mistakes: 0 }, { km: 1.0, mistakes: 0 }, { km: 0.9, mistakes: 0 }, { km: 1.3, mistakes: 0 }],
  },
  'demo-near': {
    desc: '[DEMO_MODE=true 용] 누적 0.6km, 깨끗한 주행 1번 연속 -> 깨끗한 주행 0.3km 이상이면 졸업(누적 1km + 2번 연속)',
    announcedStage: 2, graduated: false,
    trips: [{ km: 0.6, mistakes: 0 }],
  },
  graduated: {
    desc: '이미 졸업한 상태 (누적 205km)',
    announcedStage: 3, graduated: true,
    trips: [{ km: 195.0, mistakes: 5 }, { km: 2.0, mistakes: 0 }, { km: 2.0, mistakes: 0 }, { km: 2.0, mistakes: 0 },
            { km: 2.0, mistakes: 0 }, { km: 2.0, mistakes: 0 }],
  },
};

function run(args, opts = {}) {
  const r = spawnSync(ADB, args, { encoding: 'utf8', ...opts });
  if (r.error) throw new Error(`adb 실행 실패: ${r.error.message}\n(ADB 경로: ${ADB})`);
  return r;
}

// 프리셋 -> 앱 저장 파일(JSON) 내용. 형식은 DriveEventStore.kt의 save()와 같다.
function buildJson(p) {
  const DAY = 24 * 3600 * 1000;
  const now = Date.now();
  const events = [];
  const trips = [];
  p.trips.forEach((t, i) => {
    const daysAgo = p.trips.length - i;                       // 오래된 주행일수록 더 과거
    const startMs = now - daysAgo * DAY;
    const endMs = startMs + 10 * 60 * 1000;                   // 10분짜리 주행으로 가정
    trips.push({ startMs, endMs, distanceKm: t.km, closed: true });
    for (let k = 0; k < t.mistakes; k++) {                    // 실수 기록: 과속/급커브 번갈아, 주행 시간대 안에
      const isSpeed = k % 2 === 0;
      events.push({
        type: isSpeed ? 'SPEED' : 'CURVE',
        timeMs: startMs + (k + 1) * 60 * 1000,
        speedKmh: isSpeed ? 68 : 55,
        angleDeg: isSpeed ? 0 : 200,
      });
    }
  });
  return JSON.stringify({
    version: 1,
    events,
    graduation: { announcedStage: p.announcedStage, graduatedAtMs: p.graduated ? now - 60 * 1000 : 0 },
    trips,
  }, null, 2);
}

function main() {
  const name = process.argv[2];
  if (!name || name === 'list' || name === 'help') {
    console.log('프리셋 목록:');
    for (const [k, v] of Object.entries(PRESETS)) {
      const total = v.trips.reduce((a, t) => a + t.km, 0);
      console.log(`  ${k.padEnd(16)} ${v.desc}  [총 ${total.toFixed(1)}km]`);
    }
    return;
  }
  const preset = PRESETS[name];
  if (!preset) { console.error(`알 수 없는 프리셋: ${name}  (node tools/seed_demo.js list)`); process.exit(1); }

  // 현재 에뮬레이터 사용자(보통 10)
  const user = run(['shell', 'am', 'get-current-user']).stdout.trim() || '10';

  run(['shell', 'am', 'force-stop', PKG]);                    // 앱이 켜져 있으면 파일을 덮어쓰므로 먼저 종료
  const tmp = path.join(os.tmpdir(), 'drivemate_seed.json');
  fs.writeFileSync(tmp, buildJson(preset), 'utf8');
  const remote = '/data/local/tmp/drivemate_seed.json';
  let r = run(['push', tmp, remote]);
  if (r.status !== 0) throw new Error('adb push 실패: ' + r.stderr);
  run(['shell', 'run-as', PKG, '--user', user, 'mkdir', '-p', 'files']);
  r = run(['shell', 'run-as', PKG, '--user', user, 'cp', remote, 'files/drive_log.json']);
  run(['shell', 'rm', '-f', remote]);
  fs.rmSync(tmp, { force: true });
  if (r.status !== 0) throw new Error('앱 폴더로 복사 실패: ' + r.stderr + r.stdout);

  const total = preset.trips.reduce((a, t) => a + t.km, 0);
  console.log(`시드 완료: ${name} - ${preset.desc}`);
  console.log(`  누적 ${total.toFixed(1)}km, 주행 ${preset.trips.length}건 (사용자 ${user}). 앱을 켜면 이 상태에서 시작합니다.`);
}

try { main(); } catch (e) { console.error('오류: ' + e.message); process.exit(1); }
