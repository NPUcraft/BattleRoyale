// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m6.mjs <paper-directory> [directory-containing-mineflayer-package.json]
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
const root = path.resolve('.run', 'paper-m6-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-1.0.0-rc.1.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replaceAll('countdown-seconds: 30', 'countdown-seconds: 30').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 0').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'rooms.yml') text=text.replace('team-size: 1','team-size: 2').replace('team-size: 4','team-size: 1').replace('max-players: 8','max-players: 4');
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
console.log('M6 isolated server: ' + root);
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
async function restore(name) { await until(async()=>{const answer=await probe('lsprobe player '+name,'PROBE player=');return answer.includes('world=world ');},'Lobby respawn '+name); await probe('lsprobe m4original '+name,'M4 original=true world=world'); }
async function startSolo(a,b) {
  await chat(a,'/ls join solo','Joined room solo');await chat(b,'/ls join solo','Joined room solo');
  await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');await kit(a);await kit(b);
}
async function endSolo() {await consoleCommand('ls debug end solo','End requested');await state('solo','WAITING');}
try {
  await until(()=>(output.includes('Done (') && output.includes('Recovery bootstrap complete')),'Paper startup',120000);
  const clients=new Map();for(const name of ['LSAlice','LSBob','LSCarol','LSDan','LSEve','LSFrank','LSGrace']){const bot=await connect(name,port);clients.set(name,bot);await probe('lsprobe m4seed '+name,'M4 seeded');}
  for(const name of ['LSAlice','LSBob','LSCarol','LSDan'])await chat(clients.get(name),'/ls join solo','Joined room solo');
  for(const name of ['LSFrank','LSGrace'])await chat(clients.get(name),'/ls join squad','Joined room squad');
  await consoleCommand('ls debug start solo','Start requested');await consoleCommand('ls debug start squad','Start requested');await state('solo','RUNNING');await state('squad','RUNNING');
  for(const bot of clients.values())if(bot.username!=='LSEve')await kit(bot);
  const teamsText=await consoleCommand('ls debug teams solo','Team 1');
  const teams=[...teamsText.matchAll(/members=\[([^\]]+)\]/g)].map(m=>m[1].split(', ').map(member=>member.split('=')[0]));assert.equal(teams.length,2);assert.deepEqual(teams.map(t=>t.length),[2,2]);
  const [winner,deadMate]=teams[0], [enemy,enemyMate]=teams[1];
  assert.match(await probe(`lsprobe m5damage ${winner} ${deadMate} melee 4`,'M5 damage'),/health=20/);
  assert.match(await consoleCommand(`lsprobe hit arrow ${winner} ${deadMate}`,'PROBE hit='),/health=20/);
  assert.match(await consoleCommand(`lsprobe hit tnt ${winner} ${deadMate}`,'PROBE hit='),/health=20/);
  assert.match(await consoleCommand(`lsprobe hit splash ${winner} ${deadMate}`,'PROBE hit='),/detail=0/);
  assert.match(await consoleCommand(`lsprobe hit lava ${winner} ${deadMate}`,'PROBE hit='),/health=20/);
  assert.match(await probe(`lsprobe m5damage ${enemy} ${deadMate} melee 4`,'M5 damage'),/health=16/);
  await chat(clients.get('LSEve'),'/ls spectate solo','Spectating room solo');
  assert.match(await probe('lsprobe m6state LSEve','M6 mode='),/mode=SPECTATOR/);
  assert.match(await probe('lsprobe m6target LSEve LSFrank','M6 target'),/cancelled=true/);
  await probe('lsprobe m6free LSEve','M6 free=true');
  await probe(`lsprobe m5damage ${enemy} ${deadMate} melee 1000`,'M5 damage');
  await until(async()=> (await probe(`lsprobe m6state ${deadMate}`,'M6 mode=')).includes('mode=SPECTATOR'),'Dead participant spectating');
  assert.match(await probe(`lsprobe m6state ${deadMate}`,'M6 mode='),new RegExp('targetName='+winner));
  assert.match(await probe(`lsprobe m6target ${deadMate} LSFrank`,'M6 target'),/cancelled=true/);
  assert.match(await probe(`lsprobe m6target ${deadMate} ${enemy}`,'M6 target'),/cancelled=false/);
  await chat(clients.get(deadMate),'/ls leave','Spectating ended');await restore(deadMate);
  results.push('Balanced 2/2 Duo; teammate melee/arrow/TNT/lava and harmful splash blocked, enemy damage allowed. External spectator bypasses full four-player room capacity; dead participant respawns spectator, same-room target allowed, cross-room target rejected, free flight and leave restore verified.');
  await probe(`lsprobe m6matchseed ${enemyMate}`,'M6 match seeded');clients.get(enemyMate).quit();await sleep(500);
  await probe(`lsprobe m6body ${enemy} ${enemyMate}`,'M6 body entity=');
  const bodyBefore=await probe(`lsprobe m6body ${enemy} ${enemyMate}`,'M6 body');assert.match(bodyBefore,/max=24/);const health=Number(bodyBefore.match(/health=([\d.]+)/)[1]);
  assert.match(await probe(`lsprobe m6bodyhit ${enemy} ${enemyMate} melee 4`,'M6 body hit'),new RegExp('health='+health));
  const hurt=await probe(`lsprobe m6bodyhit ${winner} ${enemyMate} melee 4`,'M6 body hit');assert.ok(Number(hurt.match(/health=([\d.]+)/)[1])<health);
  await consoleCommand('lsprobe reject '+enemyMate,'PROBE reject armed');
  const rejected=mineflayer.createBot({host:'127.0.0.1',port,username:enemyMate,auth:'offline',version:'1.21.8'});let rejectedKick=false;
  rejected.on('error',()=>{});rejected.on('kicked',()=>{rejectedKick=true;});rejected.on('spawn',()=>{rejected.physicsEnabled=false;});
  await until(()=>rejectedKick,'Rejected reconnect safely kicks stale player');
  assert.match(await consoleCommand('ls debug offline solo','offline='),new RegExp('player='+enemyMate));
  assert.match(await consoleCommand('ls debug deathboxes solo','deathboxes='),/deathboxes=1/);
  const returned=await connect(enemyMate,port);clients.set(enemyMate,returned);
  await probe(`lsprobe m6matchcheck ${enemyMate}`,'M6 match restored=true');
  assert.match(await probe(`lsprobe m6state ${enemyMate}`,'M6 mode='),/mode=SURVIVAL/);
  assert.match(await consoleCommand('ls debug offline solo','offline='),/offline=none/);
  results.push('RUNNING quit produces attackable named/equipped body. Same-team body melee blocked; enemy damage persists across reconnect. Original carried inventory/cursor/armor/offhand/selected slot/101 XP restored exactly, speed effect retained, body removed, no loadout reapplied. Rejected reconnect retains authoritative body, kicks quarantined client, and retry succeeds without another DeathBox.');
  // Kill the disconnected enemy with a real arrow. The other enemy remains active until timeout.
  returned.quit();await sleep(500);await probe(`lsprobe m6bodyhit ${winner} ${enemyMate} arrow 50`,'M6 body hit');
  await until(async()=> (await consoleCommand('ls debug offline solo','offline=')).includes('offline=none'),'Offline arrow death');
  const afterDeath=await connect(enemyMate,port);clients.set(enemyMate,afterDeath);await restore(enemyMate);
  clients.get(enemy).quit();await sleep(500);
  await state('solo','RUNNING');const timeoutStart=Date.now();let winnerOffline=false;
  await until(async()=>{await sleep(1000);if(!winnerOffline && Date.now()-timeoutStart>110000){winnerOffline=true;clients.get(winner).quit();}return (await consoleCommand('ls debug session solo','countdown=')).match(/ session=\S+ state=(\w+)/)?.[1]==='ENDING';},'120-second timeout team outcome',135000);
  assert.ok(Date.now()-timeoutStart>=118000);
  const outcome=await state('solo','ENDING');assert.match(outcome,/LAST_ACTIVE_TEAM/);
  assert.ok(clients.get(deadMate).packets.some(p=>p.name==='set_title_text' && stringify(p.data).includes('WINNER')));
  const winningReturn=await connect(winner,port);clients.set(winner,winningReturn);await restore(winner);await until(()=>winningReturn.lines.some(l=>l.includes('WINNER:')),'Offline winning Team result delivered');
  assert.match(await consoleCommand('ls debug offline solo','offline='),/offline=none/);
  const timeoutReturn=await connect(enemy,port);clients.set(enemy,timeoutReturn);await restore(enemy);
  assert.match(await consoleCommand('ls debug deathboxes solo','deathboxes='),/deathboxes=3/);
  results.push('Enemy arrow kills body once and reconnect returns original Lobby state, not duplicated match items. Full default 120-second deadline eliminates final enemy body into DeathBox; last active Team wins including previously dead Lobby-returned teammate who receives WINNER. The live winning body is retired without a DeathBox; its owner logs in during ENDING, receives WINNER and original Lobby state.');
  await consoleCommand('ls debug end solo','End requested');await state('solo','WAITING');await restore(winner);await restore('LSEve');
  // External spectator quit never creates a body and restores next login.
  await chat(clients.get('LSEve'),'/ls spectate squad','Spectating room squad');clients.get('LSEve').quit();await sleep(400);await connect('LSEve',port);await restore('LSEve');
  await consoleCommand('lsprobe disable','PROBE disabled');await restore('LSFrank');await restore('LSGrace');
  await until(async()=> (await dirs()).length===0,'All worlds cleaned');assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception/);
  results.push('ENDING skip restores external/dead/alive states; external spectator disconnect restores Lobby on login, creates no body; disable removes remaining runtime worlds.');
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


