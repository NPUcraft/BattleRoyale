// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m2.mjs <paper-directory> [directory-containing-mineflayer-package.json]
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
const root = path.resolve('.run', 'paper-m2-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-1.0.0-rc.1.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replaceAll('countdown-seconds: 30', 'countdown-seconds: 4').replace('min-players: 4', 'min-players: 2');
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
console.log('M2 isolated server: ' + root);
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
  bot.lines = []; bot.spawned = false;
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
    return answer.includes('state=' + expected);
  }, 'Session ' + room + ' ' + expected);
  return answer;
}
async function dirs() {
  try { return await fs.readdir(path.join(data, 'runtime')); } catch (error) { if (error.code === 'ENOENT') return []; throw error; }
}
try {
  await until(() => output.includes('Done ('), 'Paper startup', 120000);
  assert.match(output, /Paper version 1\.21\.8/);
  assert.doesNotMatch(output, /ERROR|Exception/);
  assert.match(await consoleCommand('ls rooms', 'squad state='), /solo state=WAITING players=0/);
  results.push('Paper startup and two idle rooms');
  const a = await connect('LSAlice', port), b = await connect('LSBob', port), c = await connect('LSCarol', port);
  await chat(a, '/ls debug start solo', 'do not have permission');
  await chat(a, '/ls join solo', 'Joined room solo');
  await chat(a, '/ls join squad', 'Already in room');
  await chat(b, '/ls join solo', 'Countdown started');
  await state('solo', 'COUNTDOWN');
  await chat(b, '/ls leave', 'Left room solo'); await state('solo', 'WAITING');
  await until(() => a.lines.some(line => line.includes('Countdown cancelled')), 'Countdown cancellation message');
  await consoleCommand('ls reload', 'Cannot reload LastSector while rooms or game sessions are active.');
  results.push('Real non-OP joins, duplicate membership rejection, countdown cancellation, reload and admin permission gates');
  await chat(b, '/ls join squad', 'Joined room squad');
  await chat(c, '/ls join squad', 'Countdown started');
  await consoleCommand('ls debug start solo', 'Start requested');
  const solo = await state('solo', 'RUNNING');
  const squad = await state('squad', 'RUNNING');
  const soloName = solo.match(/ world=(\S+)/)[1], squadName = squad.match(/ world=(\S+)/)[1];
  assert.notEqual(soloName, squadName);
  assert.equal((await dirs()).length, 2);
  for (const name of [soloName, squadName]) {
    const clone = path.join(root, name);
    assert.ok((await fs.stat(path.join(clone, '.lastsector-runtime'))).isFile());
    const cloneNbt = nbt.simplify((await nbt.parse(await fs.readFile(path.join(clone, 'level.dat')))).parsed);
    assert.equal(stringify(cloneNbt.Data.WorldGenSettings), generatorSettings);
  }
  const aProbe = await consoleCommand('lsprobe player LSAlice', 'PROBE player=');
  const bProbe = await consoleCommand('lsprobe player LSBob', 'PROBE player=');
  assert.ok(aProbe.includes('world=' + soloName)); assert.ok(bProbe.includes('world=' + squadName));
  assert.match(aProbe, /floor=GRASS_BLOCK feet=AIR head=AIR/); assert.match(bProbe, /floor=GRASS_BLOCK feet=AIR head=AIR/);
  assert.notEqual(aProbe.match(/ uuid=(\S+)/)[1], bProbe.match(/ uuid=(\S+)/)[1]);
  results.push('Debug and countdown starts select maps, independent UUIDs/worlds, clones preserve WorldGenSettings, actual players land at safe M3 positions');
  await consoleCommand('ls debug end solo', 'End requested');
  await until(async () => (await dirs()).length === 1, 'Solo world deleted');
  await state('solo', 'WAITING'); await state('squad', 'RUNNING');
  assert.match(await consoleCommand('lsprobe player LSAlice', 'PROBE player='), /world=world /);
  const loaded = await consoleCommand('lsprobe worlds', 'PROBE worlds=');
  assert.ok(!loaded.includes(soloName)); assert.ok(loaded.includes(squadName));
  results.push('End returns players, unloads and deletes only the ended room world');
  await chat(a, '/ls autojoin', 'Joined room solo');
  await consoleCommand('ls debug start solo', 'Start requested');
  const nextSolo = await state('solo', 'RUNNING');
  assert.notEqual(nextSolo.match(/ session=(\S+)/)[1], solo.match(/ session=(\S+)/)[1]);
  await consoleCommand('ls debug end squad', 'End requested');
  await until(async () => (await dirs()).length === 1, 'Squad cleaned');
  await state('squad', 'WAITING'); // Filesystem completion precedes server-thread retirement by up to one tick.
  const templateFile = path.join(data, 'maps/city/level.dat');
  const backup = await fs.readFile(templateFile);
  await fs.writeFile(templateFile, 'deliberately invalid test template');
  try {
    await chat(b, '/ls join squad', 'Joined room squad');
    await consoleCommand('ls debug start squad', 'Start requested');
    await state('squad', 'WAITING');
    await state('solo', 'RUNNING');
    await until(() => b.lines.some(line => line.includes('World preparation failed')), 'Invalid template notification');
    assert.equal((await dirs()).length, 1);
  } finally { await fs.writeFile(templateFile, backup); }
  results.push('Broken template aborts only its session; other room stays RUNNING');
  await chat(b, '/ls join squad', 'Joined room squad');
  const cancelOffset = output.length;
  await consoleCommand('ls debug start squad\nls debug end squad', 'End requested');
  await state('squad', 'WAITING');
  await until(async () => (await dirs()).length === 1, 'Cancelled preparation discarded');
  assert.doesNotMatch(output.slice(cancelOffset), /PROBE teleport=LSBob world=plugins/);
  results.push('Immediate debug end invalidates async preparation without late player staging');
  await chat(b, '/ls join squad', 'Joined room squad');
  await consoleCommand('ls debug start squad', 'Start requested');
  await state('squad', 'RUNNING');
  assert.equal((await dirs()).length, 2);
  const disableOffset = output.length;
  await consoleCommand('lsprobe disable', 'PROBE disabled');
  await until(async () => (await dirs()).length === 0, 'Disable cleanup');
  assert.match(await consoleCommand('lsprobe player LSAlice', 'PROBE player='), /world=world /);
  assert.doesNotMatch(output.slice(disableOffset), /ERROR|Exception/);
  assert.equal(await hash(path.join(data, 'maps/city/level.dat')), original);
  results.push('New match identity, active plugin disable returns players and cleans worlds without ERROR/Exception; template level.dat unchanged');
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
}

