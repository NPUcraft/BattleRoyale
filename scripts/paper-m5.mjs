// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m5.mjs <paper-directory> [directory-containing-mineflayer-package.json]
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
const root = path.resolve('.run', 'paper-m5-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-0.1.0-SNAPSHOT.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replaceAll('countdown-seconds: 30', 'countdown-seconds: 30').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 0').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'rooms.yml') text=text.replace('team-size: 4','team-size: 1');
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
console.log('M5 isolated server: ' + root);
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
  await sleep(2300); // Await M8 profile/canonical Lobby before seeding M4 originals.
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
async function restore(name) { const current=bots.findLast(b=>b.username===name); const status=await probe("lsprobe m6state "+name,"M6 mode="); if(status.includes("mode=SPECTATOR")){await chat(current,"/ls leave","Spectating ended");} await until(async()=>{const answer=await probe('lsprobe player '+name,'PROBE player=');return answer.includes('world=world ');},'Lobby respawn '+name); await probe('lsprobe m4original '+name,'M4 original=true world=world'); }
async function startSolo(a,b) {
  await chat(a,'/ls join solo','Joined room solo');await chat(b,'/ls join solo','Joined room solo');
  await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');await kit(a);await kit(b);
}
async function endSolo() {await consoleCommand('ls debug end solo','End requested');await state('solo','WAITING');}
try {
  await until(()=>(output.includes('Done (') && output.includes('Recovery bootstrap complete')),'Paper startup',120000);
  assert.match(output,/Milestone 8 initialized/);assert.doesNotMatch(output,/ERROR|Exception/);
  const a=await connect('LSAlice',port),b=await connect('LSBob',port),c=await connect('LSCarol',port),d=await connect('LSDan',port),e=await connect('LSEve',port),f=await connect('LSFrank',port);
  for(const bot of bots)await probe('lsprobe m4seed '+bot.username,'M4 seeded');
  for(const bot of [a,b,c])await chat(bot,'/ls join solo','Joined room solo');
  for(const bot of [d,e,f])await chat(bot,'/ls join squad','Joined room squad');
  await consoleCommand('ls debug start solo','Start requested');await consoleCommand('ls debug start squad','Start requested');
  await state('solo','RUNNING');await state('squad','RUNNING');
  for(const bot of bots)await kit(bot,bot===b);
  await probe('lsprobe m5damage LSAlice LSBob melee 5','M5 damage');
  await probe('lsprobe m5damage LSCarol LSBob melee 8','M5 damage');
  await probe('lsprobe m5damage LSAlice LSBob melee 50','M5 damage');
  await until(()=>output.includes('M5 death=LSBob drops=0 xp=0 keep=false'),'Suppressed vanilla death');
  await restore('LSBob');await state('solo','RUNNING');
  await openBox(a);await openBox(c);
  const full=await probe('lsprobe m5contents LSAlice','M5 contents=42 xp=50');assert.match(full,/PLAYER_MELEE/);assert.match(full,/assists=\[[^\]]+\]/);
  await probe('lsprobe m5shared LSAlice LSCarol','M5 shared=true');await probe('lsprobe m5guards LSAlice','M5 guards=true');
  await probe('lsprobe m5denied LSAlice LSBob','M5 denied=true');await probe('lsprobe m5denied LSAlice LSEve','M5 denied=true');
  // Actual protocol normal pickup, bottom placement and blocked shift deposit.
  await a.clickWindow(0,0,0);await sleep(200);assert.equal(c.currentWindow.slots[0],null);
  await a.clickWindow(54,0,0);await a.clickWindow(54,0,1);await sleep(200);assert.equal(c.currentWindow.slots[0],null);
  await a.clickWindow(42,0,1);await close(a);
  const stored=a.inventory.items().find(i=>i.name==='experience_bottle');assert.ok(stored);await a.equip(stored,'hand');await a.look(0,Math.PI/2,true);a.activateItem();
  await until(()=>output.includes('M5 XP=50 marked=true'),'Stored XP real throw');await sleep(400);a.deactivateItem();
  await probe('lsprobe m5bottles LSAlice','M5 bottles=true');
  const ordinary=a.inventory.items().find(i=>i.name==='experience_bottle');await a.equip(ordinary,'hand');a.activateItem();
  await until(()=>/M5 XP=(?:[3-9]|10|11) marked=false/.test(output),'Ordinary XP real throw');a.deactivateItem();
  await openBox(a);
  // Take all remaining stacks across two actual clients; the empty display remains.
  for(let i=0;i<42;i++)if(a.currentWindow.slots[i])await a.clickWindow(i,0,1);
  for(let i=0;i<42;i++)if(c.currentWindow.slots[i])await c.clickWindow(i,0,1);
  await sleep(300);await probe('lsprobe m5empty LSCarol','M5 empty persists=true');
  await probe('lsprobe m5blast LSAlice','M5 blast survives=true');
  results.push('Real shared GUI: full 42-stack carried payload including cursed armor/offhand/cursor; XP101 -> one XP50 bottle, real marked and vanilla bottle throws; normal/shift withdrawal, blocked deposits and public-event hotbar/drag/creative guards; empty box persists, actual explosion leaves visuals intact; eliminated and cross-room access rejected.');
  await close(a);await close(c);
  await probe('lsprobe m5damage LSEve LSDan melee 1000','M5 damage');await restore('LSDan');await state('squad','RUNNING');
  assert.match(await consoleCommand('ls debug deathboxes squad','deathboxes='),/deathboxes=1/);
  assert.match(await consoleCommand('ls debug deathboxes solo','deathboxes='),/deathboxes=1/);
  const endingStart=Date.now();await probe('lsprobe m5damage LSAlice LSCarol melee 1000','M5 damage');const ending=await state('solo','ENDING');assert.match(ending,/tie=false/);
  await until(()=>a.packets.some(p=>p.name==='set_title_text' && stringify(p.data).includes('WINNER')),'Winner title');
  await restore('LSCarol');assert.match(await probe('lsprobe m5damage LSAlice LSAlice fall 1000','M5 damage'),/health=20/);
  assert.match(await consoleCommand('ls debug deathboxes solo','deathboxes='),/deathboxes=2/);
  await until(async()=> {await sleep(1000);return (await consoleCommand('ls debug session solo','countdown=')).includes('state=WAITING');},'60-second showcase complete',75000);
  assert.ok(Date.now()-endingStart>=59000);assert.match(output,/M5 own firework outsider damage cancelled=true/);assert.doesNotMatch(output,/M5 own firework outsider damage cancelled=false/);await restore('LSAlice');await state('squad','RUNNING');
  results.push('Solo WINNER title and full default 60-real-second ENDING retain world/boxes, block damage, then restore and clean up. Independent second room with two active combatants remains RUNNING (M6 team winner rules tested in paper-m6).');
  await startSolo(a,b);await probe('lsprobe m5arrow LSAlice LSBob','M5 arrow launched');await state('solo','ENDING');
  assert.ok(a.lines.some(l=>l.includes('LSBob') && l.toLowerCase().includes('projectile')));await endSolo();await restore('LSBob');await restore('LSAlice');
  await startSolo(a,b);await probe('lsprobe m5damage LSAlice LSBob melee 4','M5 damage');await probe('lsprobe m5damage LSAlice LSBob fall 1000','M5 damage');const attributed=await state('solo','ENDING');assert.match(attributed,/kills=1/);await endSolo();await restore('LSBob');await restore('LSAlice');
  await startSolo(a,b);await probe('lsprobe m5damage LSAlice LSBob melee 4','M5 damage');await sleep(16000);await probe('lsprobe m5damage LSAlice LSBob fall 1000','M5 damage');const expired=await state('solo','ENDING');assert.doesNotMatch(expired,/kills=1/);await endSolo();await restore('LSBob');await restore('LSAlice');
  results.push('Actual arrow projectile death; hit -> fall inside window credits killer; >15-second hit -> fall expires; ENDING debug end skips showcase.');
  await startSolo(a,b);await probe('lsprobe m5tie LSAlice LSBob','M5 tie tick=');const tie=await state('solo','ENDING');assert.match(tie,/tie=true/);
  await until(()=>a.packets.some(p=>p.name==='set_title_text' && stringify(p.data).includes('TIE')),'Tie title');await endSolo();await restore('LSAlice');await restore('LSBob');
  results.push('Both final players killed synchronously in one actual server tick -> immutable TIE with both winners.');
  b.quit();await sleep(400);const deadClient=await connect('LSBob',port,false);
  await startSolo(a,deadClient);await probe('lsprobe m5damage LSAlice LSBob melee 1000','M5 damage');await state('solo','ENDING');
  await until(()=>deadClient.health===0,'Death screen without auto-respawn');deadClient.quit();await sleep(400);await endSolo();
  const returned=await connect('LSBob',port);await restore('LSBob');await restore('LSAlice');
  results.push('Death-screen disconnect before respawn retains pending original; after session cleanup, same-JVM login/vanilla respawn restores once.');
  await consoleCommand('lsprobe disable','PROBE disabled');await restore('LSEve');await until(async()=> (await dirs()).length===0,'Disable cleanup');
  assert.equal(await hash(path.join(data,'maps/city/level.dat')),original);assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception|\[LastSector\].*(?:SEVERE|failed)/i);
  results.push('Disable cleans remaining team world/boxes and restores survivor; source template hash unchanged.');
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

