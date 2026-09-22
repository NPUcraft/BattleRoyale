// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m6-edges.mjs <paper-directory> [directory-containing-mineflayer-package.json]
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import crypto from 'node:crypto';
import net from 'node:net';
import assert from 'node:assert/strict';
const require = createRequire(path.resolve(process.argv[3] ?? 'scripts/integration', 'package.json'));
const mineflayer = require('mineflayer');
const nbt = require('prismarine-nbt');
const source = path.resolve(process.argv[2]);
assert.match(await fs.readFile(path.join(source, 'eula.txt'), 'utf8'), /^eula=true\s*$/m);
const root = path.resolve('.run', 'paper-m8-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
if(process.env.M8_LIBRARY_CACHE)await fs.cp(path.join(process.env.M8_LIBRARY_CACHE,'libraries'),path.join(root,'libraries'),{recursive:true});
await fs.copyFile('build/libs/lastsector-0.1.0-SNAPSHOT.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
if(process.env.M8_ECONOMY) {
  for(const name of (process.env.M8_ECONOMY==='excellenteconomy'?['ExcellentEconomy-2.8.0.jar','nightcore-2.16.2.jar','Vault-1.7.3.jar']:['CoinsEngine-2.7.0.jar','nightcore-2.15.0.jar','Vault-1.7.3.jar']))await fs.copyFile(path.join('.run/m8-api',name),path.join(root,'plugins',name));
}
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replaceAll('countdown-seconds: 30', 'countdown-seconds: 30').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 0').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'rooms.yml') text=text.replace('max-players: 8','max-players: 4');
  if (file === 'zones.yml') text = text.replace(/wait-seconds: \d+/g, 'wait-seconds: 300').replace(/shrink-seconds: \d+/g, 'shrink-seconds: 10');
  if (file === 'maps.yml') text = text.replace(/3000|2500/g, '600');
  if(file==='config.yml')text=text.replace('particle-wall:\n    enabled: true','particle-wall:\n    enabled: false');
  if(file==='config.yml'&&process.env.M8_ECONOMY)text=text.replace('provider: auto','provider: '+process.env.M8_ECONOMY);
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
console.log('M8 isolated server: ' + root);
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
async function connect(name, port, respawn=true) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: name, auth: 'offline', respawn, version: '1.21.8', hideErrors: false });
  bot.lines = []; bot.spawned = false; bot.packets = [];
  bot._client.on('packet', (data, meta) => { if (['boss_bar', 'world_particles', 'set_title_text', 'spawn_entity'].includes(meta.name)) bot.packets.push({ name: meta.name, data }); });
  bot.on('messagestr', text => bot.lines.push(text));
  bot.on('error', error => bot.lines.push('BOT ERROR: ' + error.message));
  bot.on('kicked', reason => bot.lines.push('BOT KICK: ' + reason));
  bot.on('spawn', () => { bot.spawned = true; bot.physicsEnabled = false; });
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
  await until(()=>/M6 |M4 |M5 (?!death=|XP=)|PROBE (?:failed|player)=/.test(output.slice(offset)),command);
  const text=output.slice(offset);
  assert.ok(text.includes(expected), text);
  return text;
}
async function kit(bot, full=false) {
  await probe('lsprobe m5kit '+bot.username+(full?' full':''),'M5 kit=');
  await consoleCommand('lsprobe position '+bot.username+' 0 -60 0','PROBE positioned=true');
}
async function openBox(bot) {
  const line=await probe('lsprobe m5box '+bot.username,'M5 box entity=');
  const id=Number(line.match(/entity=(\d+)/)[1]);
  await until(()=>bot.entities[id],'Interaction entity delivered');
  bot.activateEntity(bot.entities[id]);
  await until(()=>bot.currentWindow?.inventoryStart===54,'DeathBox GUI opens');
}
async function close(bot) {if(bot.currentWindow)bot.closeWindow(bot.currentWindow);await sleep(200);}
async function restore(name) { await until(async()=>{const answer=await probe('lsprobe player '+name,'PROBE player=');return answer.includes('world=world ');},'Lobby respawn '+name); await probe('lsprobe m4original '+name,'M4 original=true world=world'); }
async function startSolo(a,b) {
  await chat(a,'/ls join solo','Joined room solo');await chat(b,'/ls join solo','Joined room solo');
  await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');await kit(a);await kit(b);
}
async function endSolo() {await consoleCommand('ls debug end solo','End requested');await state('solo','WAITING');}
try {
  await until(()=>output.includes('Done (')&&output.includes('Recovery bootstrap complete'),'startup',120000);
  const clients=[];for(const name of ['LSAlice','LSBob','LSCarol','LSDan'])clients.push(await connect(name,port));
  await sleep(3000);
  for(const bot of clients) {
    await until(()=>bot.inventory.slots[36]?.name==='compass'&&bot.inventory.slots[44]?.name==='emerald','canonical menu');
    await bot.clickWindow(36,0,1);await sleep(150);assert.equal(bot.inventory.slots[36]?.name,'compass');
    await bot.clickWindow(36,1,2);await sleep(150);assert.equal(bot.inventory.slots[36]?.name,'compass');assert.equal(bot.inventory.slots[37]?.name,'ender_eye');
    const before=bot.inventory.slots[36].count;await bot.tossStack(bot.inventory.slots[36]);await sleep(200);assert.equal(bot.inventory.slots[36]?.count,before);
  }
  const alice=clients[0];alice.chat('/ls profile');await until(()=>alice.currentWindow?.inventoryStart===54,'profile GUI');await close(alice);
  alice.chat('/ls leaderboard');await until(()=>alice.currentWindow?.inventoryStart===54,'leaderboard GUI');await sleep(400);await close(alice);
  alice.chat('/ls cosmetics');await until(()=>alice.currentWindow?.inventoryStart===54,'cosmetics GUI');await alice.clickWindow(1,0,0);await sleep(1500);await close(alice);
  assert.match(await consoleCommand('ls debug stats LSAlice','PlayerProfile'),/LOBBY_EFFECT=lobby_sparkle/);
  const lobbyParticles=alice.packets.filter(p=>p.name==='world_particles').length;await sleep(1100);assert.ok(alice.packets.filter(p=>p.name==='world_particles').length>lobbyParticles);
  alice.setQuickBarSlot(0);alice.activateItem();await until(()=>alice.currentWindow?.inventoryStart===54,'Room selector item');await alice.clickWindow(0,0,0);await until(()=>alice.lines.some(l=>l.includes('Joined room solo')),'Room selector joins via runtime');
  clients[1].setQuickBarSlot(1);clients[1].activateItem();await until(()=>clients[1].lines.some(l=>l.includes('Joined room solo')),'Auto Join item uses populated room');
  await chat(alice,'/ls leave','Left room');await chat(clients[1],'/ls leave','Left room');await sleep(1500);
  if(process.env.M8_ECONOMY) {
    const provider=process.env.M8_ECONOMY;
    assert.match(await consoleCommand('ls debug economy','configured='),new RegExp('active='+provider));
    const balanceText=await consoleCommand('lsprobe m8money LSAlice '+provider+' 1000','M8 balance=');const before=Number(balanceText.match(/M8 balance=([0-9.]+)/)[1]);
    alice.chat('/ls shop');await until(()=>alice.currentWindow?.inventoryStart===54,'paid shop');
    await alice.clickWindow(3,0,0);await until(()=>alice.currentWindow?.slots[20]?.name==='lime_concrete','purchase confirmation');
    await alice.clickWindow(20,0,0);await until(()=>alice.lines.some(l=>l.includes('Purchased')),'purchase transaction');await sleep(1500);await close(alice);
    const afterText=await consoleCommand('lsprobe m8money LSAlice '+provider,'M8 balance=');assert.equal(Number(afterText.match(/M8 balance=([0-9.]+)/)[1]),before-100);
    assert.match(await consoleCommand('ls debug stats LSAlice','PlayerProfile'),/kill_visual_lightning/);
    alice.chat('/ls cosmetics');await until(()=>alice.currentWindow?.inventoryStart===54,'owned cosmetics');await alice.clickWindow(1,0,0);await sleep(1500);await close(alice);
    assert.match(await consoleCommand('ls debug stats LSAlice','PlayerProfile'),/KILL_EFFECT=kill_visual_lightning/);
    results.push('Actual '+provider+' provider: deposited test balance through public API; confirmed shop withdraws exactly 100 and unlocks permanently.');
  }
  results.push('Canonical menu slots and drop protection; profile, leaderboard and cosmetics GUIs open.');
  for(const bot of clients)await chat(bot,'/ls join solo','Joined room solo');
  await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');
  for(const bot of clients)await kit(bot);
  const activeParticles=alice.packets.filter(p=>p.name==='world_particles').length;await sleep(1100);assert.equal(alice.packets.filter(p=>p.name==='world_particles').length,activeParticles);
  await probe('lsprobe m5damage LSAlice LSBob melee 1000','M5 damage health=');
  assert.match(await consoleCommand('lsprobe player LSCarol','PROBE player='),/health=20.0/);
  assert.match(await consoleCommand('lsprobe player LSDan','PROBE player='),/health=20.0/);
  for(const victim of clients.slice(2))await probe('lsprobe m5damage LSAlice '+victim.username+' melee 1000','M5 damage health=');
  await state('solo','ENDING');
  await until(async()=> (await consoleCommand('ls debug stats LSAlice','PlayerProfile')).includes('matches=1'),'result transaction');
  const stats=await consoleCommand('ls debug stats LSAlice','PlayerProfile');assert.match(stats,/rating=1040/);assert.match(stats,/wins=1/);assert.match(stats,/kills=3/);assert.match(stats,/killScore=30/);
  results.push('Four-player Solo completes: one winner, 3 credited kills, +40 rating, independent +30 kill score.');
  await endSolo();await sleep(2000);
  execFileSync('python',['-c',"import sqlite3,sys,uuid; c=sqlite3.connect(sys.argv[1]); c.executemany('INSERT INTO player_profiles(player_uuid,last_known_name,first_seen,last_seen,rating,highest_rating) VALUES(?,?,?,?,?,?)',[(str(uuid.uuid4()),'Fixture'+str(i).zfill(2),0,0,500,500) for i in range(40)]); c.commit()",path.join(data,'data/lastsector.db')],{windowsHide:true});
  alice.chat('/ls leaderboard');await until(()=>alice.currentWindow?.slots[53]?.name==='arrow','leaderboard next page');
  await alice.clickWindow(53,0,0);await until(()=>alice.currentWindow?.slots[51]?.name==='arrow','leaderboard second page');
  await alice.clickWindow(37,0,0);await until(()=>alice.currentWindow?.slots[47]?.name==='clock','daily navigation');
  await alice.clickWindow(47,0,0);await until(()=>alice.currentWindow?.slots[13]?.name==='paper','empty historical day');
  await alice.clickWindow(48,0,0);await until(()=>alice.currentWindow?.slots[0]?.name==='player_head','current daily data');
  await alice.clickWindow(38,0,0);await sleep(350);assert.equal(alice.currentWindow?.slots[0]?.name,'player_head');
  await alice.clickWindow(39,0,0);await sleep(350);assert.equal(alice.currentWindow?.slots[0]?.name,'player_head');await close(alice);
  results.push('Actual GUI SQL pagination over 44 profiles; historical empty day, current day, ISO week and month navigation. Shift/number/drop menu exploits remain cancelled.');

  for(const bot of clients.slice(0,2))await chat(bot,'/ls join solo','Joined room solo');
  await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');for(const bot of clients.slice(0,2))await kit(bot);
  await probe('lsprobe m5tie LSAlice LSBob','M5 tie tick=');await state('solo','ENDING');
  await until(async()=> (await consoleCommand('ls debug stats LSAlice','PlayerProfile')).includes('matches=2'),'tie transaction');
  assert.match(await consoleCommand('ls debug stats LSAlice','PlayerProfile'),/wins=2/);
  assert.match(await consoleCommand('ls debug stats LSBob','PlayerProfile'),/wins=1/);
  results.push('Final same-tick tie grants both players full winner stats.');
  await endSolo();await sleep(2000);
  for(const bot of clients.slice(0,2))await chat(bot,'/ls join solo','Joined room solo');
  await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');await endSolo();await sleep(2000);
  assert.match(await consoleCommand('ls debug stats LSAlice','PlayerProfile'),/matches=2/);
  assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception|Recovery capture rejected/);
  results.push('Admin abort does not increment official matches. No plugin event/task errors.');
} catch(error) {results.push('FAILED: '+error.stack);process.exitCode=1;}
finally {
  for(const bot of bots)try{bot.quit();}catch{}
  await sleep(300);if(exit===null)child.stdin.write('stop\n');
  const deadline=Date.now()+60000;while(exit===null && Date.now()<deadline)await sleep(200);
  if(exit===null){child.kill();results.push('FAILED: shutdown timeout');process.exitCode=1;}
  await fs.writeFile(path.join(root,'console.log'),output);
  await fs.writeFile(path.join(root,'results.json'),JSON.stringify(results,null,2));
  await fs.writeFile(path.join(root,'messages.json'),stringify(bots.map(b=>({name:b.username,lines:b.lines}))));
  console.log(results.join('\n'));console.log('ARTIFACTS '+root);
}


