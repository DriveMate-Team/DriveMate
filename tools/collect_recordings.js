#!/usr/bin/env node
/**
 * Game Bar 녹화 파일을 장면 이름으로 옮긴다.
 *
 *   node tools/collect_recordings.js 1 2 3 4 5 6         찍은 순서대로 scene1.mp4 ... scene6.mp4
 *   node tools/collect_recordings.js 4 5 6               장면 4~6만 다시 찍었을 때
 *   node tools/collect_recordings.js --dry 1 2 3 4 5 6   옮기지 않고 어떻게 옮길지만 보여 준다
 *
 * "동영상\캡처" 폴더에서 에뮬레이터 창 녹화(파일 이름이 "Android Emulator"로 시작)만 고른다.
 * 그중 가장 최근 파일을 장면 수만큼 골라 오래된 것부터 장면 번호를 붙인다.
 * 다른 창의 녹화 파일은 건드리지 않는다. 같은 이름이 recordings/에 있으면 덮어쓰지 않고 멈춘다.
 * recordings/ 는 git에 올리지 않는다 (.gitignore).
 */
const fs = require('fs');
const os = require('os');
const path = require('path');

const args = process.argv.slice(2);
const dry = args.includes('--dry');
const scenes = args.filter((a) => a !== '--dry');
if (scenes.length === 0 || scenes.some((s) => !/^\d+$/.test(s))) {
  console.log('사용법: node tools/collect_recordings.js [--dry] 1 2 3 4 5 6');
  process.exit(1);
}

const CAPTURES = path.join(os.homedir(), 'Videos', 'Captures');
const OUT = path.join(__dirname, '..', 'recordings');

const files = fs.readdirSync(CAPTURES)
  .filter((f) => f.startsWith('Android Emulator') && f.toLowerCase().endsWith('.mp4'))
  .map((f) => ({ f, t: fs.statSync(path.join(CAPTURES, f)).birthtimeMs }))
  .sort((a, b) => a.t - b.t);

if (files.length < scenes.length) {
  console.error(`에뮬레이터 녹화 파일이 ${files.length}개뿐이에요 (장면 ${scenes.length}개 필요): ${CAPTURES}`);
  process.exit(1);
}
const picked = files.slice(-scenes.length); // 가장 최근 N개, 오래된 것부터

const plan = picked.map(({ f }, i) => ({ from: path.join(CAPTURES, f), to: path.join(OUT, `scene${scenes[i]}.mp4`) }));
const clash = plan.filter((p) => fs.existsSync(p.to));
if (clash.length) {
  console.error('이미 있는 파일이라 멈췄어요 (지우거나 이름을 바꾼 뒤 다시 실행하세요):');
  clash.forEach((p) => console.error('  ' + path.relative(process.cwd(), p.to)));
  process.exit(1);
}

fs.mkdirSync(OUT, { recursive: true });
for (const p of plan) {
  console.log(`${dry ? '(미리보기) ' : ''}${path.basename(p.from)}  ->  recordings/${path.basename(p.to)}`);
  if (!dry) {
    try { fs.renameSync(p.from, p.to); }
    catch (_) { fs.copyFileSync(p.from, p.to); fs.unlinkSync(p.from); } // 드라이브가 다르면 복사 후 지움
  }
}
