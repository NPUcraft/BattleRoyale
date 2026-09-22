// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m4.mjs <paper-directory> [directory-containing-mineflayer-package.json]
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
const root = path.resolve('.run', 'paper-m4-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-0.1.0-SNAPSHOT.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replace('team-size: 4','team-size: 1').replaceAll('countdown-seconds: 30', 'countdown-seconds: 4').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 20').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'zones.yml') text = text.replace(/wait-seconds: \d+/g, 'wait-seconds: 300').replace(/shrink-seconds: \d+/g, 'shrink-seconds: 10');
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
  'enable-query=false', 'enable-rcon=false', 'difficulty=normal', 'gamemode=creative'
].join('\n'));
await fs.writeFile(path.join(root, 'bukkit.yml'), 'settings:\n  allow-end: false\n');
// Public-API fixtures are installed on WorldLoad before LastSector registers sanitation.
for (const map of ['city', 'desert']) {
  await fs.mkdir(path.join(data, 'map-data', map), { recursive: true });
  await fs.writeFile(path.join(data, 'map-data', map, 'loot.yml'), `containers:
  - id: inside
    x: 0
    y: -60
    z: 0
    loot-table: basic
  - id: invalid
    x: 20
    y: -60
    z: 20
    loot-table: basic
  - id: outside
    x: 600
    y: -60
    z: 0
    loot-table: basic
  - id: double-left
    x: 15
    y: -60
    z: 4
    loot-table: basic
  - id: double-right
    x: 16
    y: -60
    z: 4
    loot-table: basic
areas:
  - id: ground
    min-x: -10
    max-x: 10
    min-y: -60
    max-y: -60
    min-z: -10
    max-z: 10
    loot-table: basic
    activation-chance: 1.0
    min-spawns: 2
    max-spawns: 2
    max-attempts: 10
  - id: disabled
    min-x: -10
    max-x: 10
    min-y: -60
    max-y: -60
    min-z: -10
    max-z: 10
    loot-table: basic
    activation-chance: 0.0
    min-spawns: 2
    max-spawns: 2
    max-attempts: 10
`);
}
await fs.writeFile(path.join(data, 'loot-tables.yml'), `loot-tables:
  basic:
    min-rolls: 1
    max-rolls: 1
    entries:
      - item: minecraft:bread
        weight: 1
        min-amount: 4
        max-amount: 4
`);
console.log('M4 isolated server: ' + root);
let output = '', exit = null;
const child = spawn('java', ['-Xms512M', '-Xmx1536M', '-Dfile.encoding=UTF-8', '-Dlastsector.probe.m4=true', '-jar', 'paper.jar', '--nogui'],
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

async function probe(command, expected) {
  const offset=output.length; child.stdin.write(command+'\n');
  await until(()=>/M4 |PROBE failed=/.test(output.slice(offset)),command);
  const text=output.slice(offset);
  assert.ok(text.includes(expected), text);
  return text;
}
try {
  await until(() => (output.includes('Done (') && output.includes('Recovery bootstrap complete')), 'Paper startup', 120000);
  assert.match(output, /Milestone 7 initialized/); assert.doesNotMatch(output, /ERROR|Exception/);
  await probe('lsprobe m4roundtrip','M4 roundtrip=true');
  const a=await connect('LSAlice',port), b=await connect('LSBob',port), c=await connect('LSCarol',port), d=await connect('LSDan',port);
  await consoleCommand('op LSAlice','Made LSAlice');
  await consoleCommand('op LSCarol','Made LSCarol');
  for (const name of ['LSAlice','LSBob','LSCarol','LSDan']) await probe('lsprobe m4seed '+name,'M4 seeded=true');
  await probe('lsprobe m4portal LSAlice','active=false');
  await chat(b,'/ls admin loadout edit solo','permission');
  await chat(a,'/ls admin loadout edit solo','Shared loadout');
  await until(()=>a.currentWindow,'GUI opened');
  await probe('lsprobe m4gui LSAlice','M4 gui-actions=true');
  await chat(c,'/ls admin loadout edit squad','being edited');
  await a.clickWindow(81,0,0); // Bottom inventory hotbar 0; copies rich sword as brush.
  await a.clickWindow(0,0,0);
  await a.clickWindow(1,0,1); // Shift-click is cancelled.
  await a.clickWindow(49,0,0);
  await until(()=>a.lines.some(line=>line.includes('Loadout saved')),'Atomic GUI save');
  await probe('lsprobe m4original LSAlice','M4 original=true');
  assert.match(await fs.readFile(path.join(data,'loadouts.yml'),'utf8'), /paper-native/);
  results.push('Native sword and potion roundtrip with name, lore, enchantment, damage, model data, PDC; corrupt bytes rejected. Real GUI brush/save, shift cancellation, shared lock, permissions, unchanged original inventory/XP.');
  await chat(a,'/ls join solo','Joined room solo'); await chat(b,'/ls join solo','Countdown started');
  await chat(c,'/ls join squad','Joined room squad');await chat(d,'/ls join squad','Joined room squad'); await consoleCommand('ls debug start squad','Start requested');
  const solo=await state('solo','RUNNING'); await state('squad','RUNNING');
  for (const name of ['LSAlice','LSBob','LSCarol','LSDan']) await probe('lsprobe m4match '+name,'M4 match=true');
  for (const name of ['LSAlice','LSCarol']) {
    await probe('lsprobe m4sanitize '+name,'M4 sanitized=true');
    await probe('lsprobe m4loot '+name,'M4 loot=true');
    await probe('lsprobe m4portal '+name,'active=true');
  }
  const diag=await consoleCommand('ls debug loot solo','loot='); assert.match(diag,/loot=COMPLETE/); assert.match(diag,/active\/skipped-areas=1\/1/);
  await probe('lsprobe m4once LSAlice','M4 once=true');
  await probe('lsprobe m4break LSAlice','M4 break=true');
  await probe('lsprobe m4unload LSAlice','M4 unload-reload=true');
  results.push('Two room isolation, shared saved loadout, normalized inventory/XP/ender chest. First-load entities/containers cleaned, villagers/decorations/minecart retained, natural rules unchanged. InitialZone-filtered container/ground loot, unlimited ground lifetime, invalid point skipped. Replayed chunk/entity-load events preserve new entities and do not refill. Portal events blocked only in registered game worlds; pearls/chorus allowed.');
  // Offline restore remains available after the session and an idle reload have finished.
  b.quit(); await sleep(500);
  await consoleCommand('ls debug end solo','End requested'); await state('solo','WAITING');
  await probe('lsprobe m4original LSAlice','M4 original=true world=world');
  await chat(a,'/ls admin loadout edit solo','Shared loadout');
  await until(()=>a.currentWindow,'Second GUI opened');
  await a.clickWindow(0,1,0); await a.clickWindow(49,0,0);
  await until(()=>a.currentWindow===null,'Second save closed GUI');
  await probe('lsprobe m4match LSCarol','M4 match=true');
  await consoleCommand('ls debug end squad','End requested'); await state('squad','WAITING');
  await until(async()=> (await dirs()).length===0,'Runtime worlds removed');
  const reloadOffset=output.length;await consoleCommand('ls reload','Configuration reloaded successfully');
  await until(()=>output.slice(reloadOffset).includes('Recovery bootstrap complete'),'Reload recovery gate');
  const rejoined=await connect('LSBob',port); await probe('lsprobe m4original LSBob','M4 original=true world=world');
  await probe('lsprobe m4portal LSAlice','active=false');
  results.push('Online restoration discards match loot/XP; offline snapshot survives retired session plus full idle reload and restores on join. Second room continues independently. All runtime directories cleaned.');
  // Apply rollback: cancel one safe landing after equipment has been applied.
  await chat(a,'/ls join solo','Joined room solo'); await consoleCommand('lsprobe reject LSAlice','PROBE reject armed');
  await consoleCommand('ls debug start solo','Start requested');
  await until(()=>output.includes('PROBE rejected landing'),'Rejected landing rollback'); await state('solo','WAITING');
  await probe('lsprobe m4original LSAlice','M4 original=true world=world');
  results.push('Rejected landing rolls back applied equipment and restores original player state.');
  await chat(a,'/ls join solo','Joined room solo');await chat(rejoined,'/ls join solo','Joined room solo'); await consoleCommand('ls debug start solo','Start requested'); await state('solo','RUNNING');
  await probe('lsprobe m4empty LSAlice','M4 empty=true');
  await consoleCommand('lsprobe disable','PROBE disabled'); await probe('lsprobe m4original LSAlice','M4 original=true world=world');
  await until(async()=> (await dirs()).length===0,'Disable world cleanup');
  results.push('Plugin disable restores online state before unloading/deleting runtime worlds.');
  assert.equal(await hash(path.join(data,'maps/city/level.dat')),original);
} catch (error) { results.push('FAILED: '+error.stack); process.exitCode=1; }
finally {
  for(const bot of bots) { try {bot.quit();} catch {} }
  await sleep(300); if(exit===null) child.stdin.write('stop\n');
  const deadline=Date.now()+60000; while(exit===null && Date.now()<deadline) await sleep(200);
  if(exit===null) {child.kill();results.push('FAILED: shutdown timeout');process.exitCode=1;}
  await fs.writeFile(path.join(root,'console.log'),output);
  await fs.writeFile(path.join(root,'results.json'),JSON.stringify(results,null,2));
  await fs.writeFile(path.join(root,'messages.json'),JSON.stringify(bots.map(b=>({name:b.username,lines:b.lines})),null,2));
  console.log(results.join('\n')); console.log('ARTIFACTS '+root);
}

