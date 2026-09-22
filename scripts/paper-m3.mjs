// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m3.mjs <paper-directory> [directory-containing-mineflayer-package.json]
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import crypto from 'node:crypto';
import net from 'node:net';
import assert from 'node:assert/strict';
const require = createRequire(path.resolve(process.argv[3] ?? 'scripts/integration', 'package.json'));
const mineflayer = require('mineflayer');
const nbt = require('prismarine-nbt');
const source = path.resolve(process.argv[2]);
assert.match(await fs.readFile(path.join(source, 'eula.txt'), 'utf8'), /^eula=true\s*$/m);
const root = path.resolve('.run', 'paper-m3-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-1.0.0-rc.1.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replace('team-size: 4','team-size: 1').replaceAll('countdown-seconds: 30', 'countdown-seconds: 4').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 20').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'zones.yml') text = text.replace(/wait-seconds: \d+/g, 'wait-seconds: 5').replace(/shrink-seconds: \d+/g, 'shrink-seconds: 10');
  if (file === 'maps.yml') text = text.replace(/3000|2500/g, '600');
  await fs.writeFile(path.join(data, file), text);
}
for (const name of ['city', 'desert'])
  await fs.cp(path.join(source, 'world'), path.join(data, 'maps', name), {
    recursive: true, filter: entry => !['session.lock', 'playerdata', 'stats', 'advancements'].includes(path.basename(entry))
  });
const hash = async file => crypto.createHash('sha256').update(await fs.readFile(file)).digest('hex');
const original = await hash(path.join(data, 'maps/city/level.dat'));
const sourceNbt = nbt.simplify((await nbt.parse(await fs.readFile(path.join(data, 'maps/city/level.dat')))).parsed);
const stringify = value => JSON.stringify(value, (_, item) => typeof item === 'bigint' ? item.toString() : item);
const generatorSettings = stringify(sourceNbt.Data.WorldGenSettings);
const reservation = net.createServer();
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
const port = reservation.address().port;
await new Promise(resolve => reservation.close(resolve));
await fs.writeFile(path.join(root, 'server.properties'), [
  'server-ip=127.0.0.1', 'server-port=' + port, 'online-mode=false', 'enforce-secure-profile=false',
  'level-type=minecraft:flat', 'level-seed=991122', 'generate-structures=false',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}',
  'view-distance=2', 'simulation-distance=2', 'max-players=8', 'spawn-protection=0',
  'enable-query=false', 'enable-rcon=false', 'difficulty=peaceful', 'gamemode=creative'
].join('\n'));
await fs.writeFile(path.join(root, 'bukkit.yml'), 'settings:\n  allow-end: false\n');
console.log('M3 isolated server: ' + root);
let output = '', exit = null;
const child = spawn('java', ['-Xms512M', '-Xmx1536M', '-Dfile.encoding=UTF-8', '-jar', 'paper.jar', '--nogui'],
  { cwd: root, windowsHide: true });
child.stdout.on('data', data => { output += data.toString(); });
child.stderr.on('data', data => { output += data.toString(); });
child.on('exit', code => { exit = code; });
child.on('error', error => { output += error.stack; exit = -1; });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const bots = [], results = [];
async function until(test, label, timeout = 60000) {
  const deadline = Date.now() + timeout;
  while (!(await test())) {
    if (exit !== null || Date.now() > deadline) throw new Error(label + '\n' + output.slice(-10000));
    await sleep(100);
  }
}
async function consoleCommand(command, expected) {
  const offset = output.length; child.stdin.write(command + '\n');
  await until(() => output.slice(offset).includes(expected), command);
  return output.slice(offset);
}
async function connect(name, port) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, auth: 'offline', version: '1.21.8', hideErrors: false });
  bot.lines = []; bot.spawned = false; bot.packets = [];
  bot._client.on('packet', (data, meta) => { if (['boss_bar', 'world_particles'].includes(meta.name)) bot.packets.push({ name: meta.name, data }); });
  bot.on('messagestr', text => bot.lines.push(text));
  bot.on('error', error => bot.lines.push('BOT ERROR: ' + error.message));
  bot.on('kicked', reason => bot.lines.push('BOT KICK: ' + reason));
  bot.once('spawn', () => { bot.spawned = true; bot.physicsEnabled = false; });
  bots.push(bot);
  await until(() => bot.spawned, 'Bot spawn ' + name);
  return bot;
}
async function chat(bot, command, expected) {
  const offset = bot.lines.length; bot.chat(command);
  await until(() => bot.lines.slice(offset).some(line => line.includes(expected)), command + ' for ' + bot.username);
}
async function state(room, expected) {
  let answer;
  await until(async () => {
    answer = await consoleCommand('ls debug session ' + room, 'countdown=');
    return answer.match(/ session=\S+ state=(\w+)/)?.[1] === expected;
  }, 'Session ' + room + ' ' + expected);
  return answer;
}
async function dirs() {
  try { return await fs.readdir(path.join(data, 'runtime')); } catch (error) { if (error.code === 'ENOENT') return []; throw error; }
}

async function zone(room) {
  const text = await consoleCommand('ls debug zone ' + room, 'protectionSeconds=');
  const parse = label => {
    const m = text.match(new RegExp(label + '=Zone\\[centerX=([^,]+), centerZ=([^,]+), halfSize=([^\\]]+)\\]'));
    return m ? { x: +m[1], z: +m[2], h: +m[3] } : null;
  };
  return { initial: parse('initial'), current: parse('current'), next: parse('next'),
    phase: text.match(/phase=(\w+)/)[1], protection: +text.match(/protectionSeconds=([\d.E-]+)/)[1], text };
}
function contains(outer, inner) {
  return Math.abs(inner.x - outer.x) + inner.h <= outer.h + 1e-8
    && Math.abs(inner.z - outer.z) + inner.h <= outer.h + 1e-8;
}
async function player(name) {
  const text = await consoleCommand('lsprobe player ' + name, 'PROBE player=');
  const values = {};
  for (const key of ['x', 'y', 'z', 'health', 'tick']) values[key] = +text.match(new RegExp(' ' + key + '=([^ ]+)'))[1];
  values.text = text; return values;
}
async function position(name, x, y, z) {
  await consoleCommand('lsprobe position ' + name + ' ' + x + ' ' + y + ' ' + z, 'PROBE positioned=true');
}
async function hit(kind, expectedHealth, detail) {
  const text = await consoleCommand('lsprobe hit ' + kind + ' LSAlice LSBob', 'PROBE hit=');
  if (expectedHealth !== null) assert.equal(+text.match(/health=([\d.]+)/)[1], expectedHealth, text);
  if (detail !== undefined) assert.equal(+text.match(/detail=([\d.-]+)/)[1], detail, text);
  return text;
}
try {
  await until(() => (output.includes('Done (') && output.includes('Recovery bootstrap complete')), 'Paper startup', 120000);
  assert.match(output, /Paper version 1\.21\.8/); assert.doesNotMatch(output, /ERROR|Exception/);
  await consoleCommand('gamerule naturalRegeneration false', 'naturalRegeneration');
  const a = await connect('LSAlice', port), b = await connect('LSBob', port), c = await connect('LSCarol', port), d = await connect('LSDan', port);
  await chat(a, '/ls join solo', 'Joined room solo');
  await chat(b, '/ls join solo', 'Countdown started');
  await chat(c, '/ls join squad', 'Joined room squad');await chat(d, '/ls join squad', 'Joined room squad');
  await consoleCommand('ls debug start squad', 'Start requested');
  const solo = await state('solo', 'RUNNING'), squad = await state('squad', 'RUNNING');
  const initial = await zone('solo');
  assert.equal(initial.initial.h, 500);
  assert.ok(contains(initial.current, initial.next));
  const aPos = await player('LSAlice'), bPos = await player('LSBob');
  assert.ok(Math.hypot(aPos.x - bPos.x, aPos.z - bPos.z) >= 64);
  for (const p of [aPos, bPos]) {
    assert.ok(Math.abs(p.x - initial.initial.x) <= 500 && Math.abs(p.z - initial.initial.z) <= 500);
    assert.match(p.text, /floor=GRASS_BLOCK feet=AIR head=AIR/);
  }
  const soloWorld = solo.match(/ world=(\S+)/)[1], squadWorld = squad.match(/ world=(\S+)/)[1];
  assert.notEqual(soloWorld, squadWorld);
  const landing = name => output.split('\n').find(line => line.includes('PROBE teleport=' + name + ' world=' + soloWorld));
  assert.equal(landing('LSAlice').match(/tick=(\d+)/)[1], landing('LSBob').match(/tick=(\d+)/)[1]);
  for (const worldName of [soloWorld, squadWorld]) {
    const clone = nbt.simplify((await nbt.parse(await fs.readFile(path.join(root, worldName, 'level.dat')))).parsed);
    assert.equal(stringify(clone.Data.WorldGenSettings), generatorSettings);
  }
  results.push('Countdown/debug start; actual-count initial halfSize=500; safe distinct spaced positions; same-tick landing; cloned WorldGenSettings intact; two rooms');
  for (const kind of ['melee', 'arrow', 'tnt', 'lava', 'flow']) await hit(kind, 20);
  await hit('splash', 20, 0); await hit('cloud', 20, 0); await hit('fire', 20, 0);
  for (const kind of ['natural', 'mob', 'natural-lava']) {
    const text = await hit(kind, null);
    assert.ok(+text.match(/health=([\d.]+)/)[1] < 20, text);
  }
  await consoleCommand('lsprobe health LSBob 20', 'PROBE health set');
  results.push('Real Paper API damage pipeline: player melee/arrow/TNT blocked; synthetic splash/cloud/ignition/bucket-flow events enforce protection; natural fall/mob/lava remain damaging');
  let shrinking;
  await until(async () => {
    shrinking = await zone('solo');
    return shrinking.phase === 'SHRINKING';
  }, 'First continuous shrink');
  await sleep(550);
  const moving = await zone('solo');
  assert.ok(moving.current.h < shrinking.current.h);
  assert.ok(contains(initial.initial, moving.current));
  assert.deepEqual(moving.initial, initial.initial);
  results.push('WAITING to SHRINKING; halfSize changes continuously with real elapsed time and remains contained; immutable initial zone');
  await position('LSAlice', moving.next.x, -60, moving.next.z);
  await position('LSBob', moving.next.x, -60, moving.next.z);
  const otherZone = await zone('squad');
  await position('LSCarol', otherZone.next.x, -60, otherZone.next.z);await position('LSDan', otherZone.next.x, -60, otherZone.next.z);
  await until(() => a.lines.some(line => line.includes('PvP protection expired')), 'Protection expires', 35000);
  await hit('melee', 16);
  await hit('splash', 20, 1); await hit('cloud', 20, 1);
  assert.equal(a.lines.filter(line => line.includes('PvP protection expired')).length, 1);
  results.push('Protection expires exactly once; player damage and potion target selection resume');
  let final;
  await until(async () => {
    final = await zone('solo');
    await position('LSAlice', final.current.x, -60, final.current.z);
    await position('LSBob', final.current.x, -60, final.current.z);
    const other = await zone('squad');
    await position('LSCarol', other.current.x, -60, other.current.z);await position('LSDan', other.current.x, -60, other.current.z);
    return final.phase === 'FINAL';
  }, 'FINAL zone', 70000);
  assert.equal(final.current.h, 50); assert.equal(final.next, null);
  assert.deepEqual(final.initial, initial.initial);
  // The flat template floor is Y=-61; player feet remain Y=-60.
  await position('LSBob', final.current.x, -60, final.current.z);
  await consoleCommand('lsprobe health LSBob 20', 'PROBE health set');
  await sleep(1300);
  const inside = await player('LSBob');
  assert.ok(inside.text.includes('world=' + soloWorld + ' '), inside.text);
  assert.equal(inside.health, 20);
  await position('LSBob', final.current.x + 60, -60, final.current.z);
  await consoleCommand('lsprobe armor LSBob', 'PROBE armored');
  await consoleCommand('lsprobe health LSBob 20', 'PROBE health set');
  let damaged;
  await until(async () => { damaged = await player('LSBob'); return damaged.health < 20; }, 'Outside zone damage');
  assert.ok(Math.abs(damaged.health - 15.7) < 0.00001, damaged.text);
  await until(() => b.packets.some(p => p.name === 'world_particles'), 'Local wall particle packets');
  assert.ok(b.packets.some(p => p.name === 'boss_bar'));
  await position('LSBob', final.current.x + 550, -60, final.current.z);
  await consoleCommand('lsprobe health LSBob 20', 'PROBE health set');
  await until(async () => (await player('LSBob')).health === 1, 'Farther outside capped formula', 5000);
  results.push('FINAL persists; inside no damage; 10m outside 4.3 true damage through full diamond Protection IV + Resistance V; 500m outside 19 damage; BossBar and nearby particle packets observed');
  // Keep the test clients alive for cleanup assertions; M5 death/outcome behavior is covered by paper-m5.mjs.
  await position('LSBob', final.current.x, -60, final.current.z);
  await consoleCommand('lsprobe health LSBob 20', 'PROBE health set');
  const beforeEnd = b.packets.length;
  await consoleCommand('ls debug end solo', 'End requested');
  await state('solo', 'WAITING'); await state('squad', 'RUNNING');
  await until(async () => (await dirs()).length === 1, 'Solo cleanup');
  assert.match((await player('LSBob')).text, /world=world /);
  await sleep(700);
  const afterEnd = b.packets.slice(beforeEnd);
  assert.ok(afterEnd.some(p => p.name === 'boss_bar' && p.data.action === 1), JSON.stringify(afterEnd.slice(0,5)));
  results.push('End detaches BossBar, returns participants and deletes one runtime; second room loop remains running');
  await chat(a, '/ls join solo', 'Joined room solo');
  await consoleCommand('lsprobe reject LSAlice', 'PROBE reject armed');
  await consoleCommand('ls debug start solo', 'Start requested');
  await state('solo', 'WAITING');
  assert.match(output, /PROBE rejected landing/);
  await state('squad', 'RUNNING');
  assert.match((await player('LSAlice')).text, /world=world /);
  results.push('Injected teleport rejection rolls STARTING back, cleans runtime and leaves the other room running');
  await chat(a, '/ls join solo', 'Joined room solo');
  await consoleCommand('ls debug start solo\nls debug end solo', 'End requested');
  await state('solo', 'WAITING');
  await until(async () => (await dirs()).length === 1, 'Cancelled preparation');
  results.push('Cancelled preparation has no late start or retained world');
  const offset = output.length;
  await consoleCommand('lsprobe disable', 'PROBE disabled');
  await until(async () => (await dirs()).length === 0, 'Disable cleanup');
  assert.doesNotMatch(output.slice(offset), /ERROR|Exception/);
  assert.equal(await hash(path.join(data, 'maps/city/level.dat')), original);
  results.push('Disable cleans remaining loop/world without ERROR; source template unchanged');
  await fs.writeFile(path.join(root, 'results.json'), JSON.stringify({ results, root }, null, 2));
  console.log(JSON.stringify({ results, root }, null, 2));
} finally {
  bots.forEach(bot => bot.quit());
  if (exit === null) child.stdin.write('stop\n');
  const deadline = Date.now() + 45000;
  while (exit === null && Date.now() < deadline) await sleep(100);
  if (exit === null) child.kill();
  await fs.writeFile(path.join(root, 'console.log'), output);
  await fs.writeFile(path.join(root, 'bot-messages.json'), JSON.stringify(bots.map(bot => ({ name: bot.username, messages: bot.lines })), null, 2));
  await fs.writeFile(path.join(root, 'packets.json'), JSON.stringify(bots.map(bot => ({ name: bot.username, packets: bot.packets })), null, 2));
}

