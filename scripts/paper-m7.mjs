// Requires an existing, stopped Paper 1.21.8 installation with world/ and accepted eula.txt.
// node scripts/paper-m6.mjs <paper-directory> [directory-containing-mineflayer-package.json]
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
const root = path.resolve('.run', 'paper-m7-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-1.0.0-rc.1.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replaceAll('countdown-seconds: 30', 'countdown-seconds: 30').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 0').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'rooms.yml') text=text.replace('team-size: 4','team-size: 1').replace('max-players: 8','max-players: 4');
  if (file === 'zones.yml') text = text.replace(/wait-seconds: \d+/g, 'wait-seconds: 5').replace(/shrink-seconds: \d+/g, 'shrink-seconds: 300');
  if (file === 'maps.yml') text = text.replace(/3000|2500/g, '600');
  if(file==='config.yml'){text=text.replace('checkpoint-seconds: 5','checkpoint-seconds: 1');if(process.env.M7_MYSQL_PORT)text=text.replace('type: sqlite','type: mysql').replace('port: 3306','port: '+process.env.M7_MYSQL_PORT).replace('database: lastsector','database: lastsector_test').replace('username: lastsector','username: lastsector_test').replace('password: ""','password: "lastsector-isolated-test"');}
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
console.log('M7 isolated server: ' + root);
let output = '', exit = null;
let child, epoch=0;
function launch(){output='';exit=null;epoch++;child=spawn('java',['-Xms512M','-Xmx1536M','-Dfile.encoding=UTF-8','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});child.stdout.on('data',data=>output+=data.toString());child.stderr.on('data',data=>output+=data.toString());child.on('exit',code=>exit=code??-1);child.on('error',error=>{output+=error.stack;exit=-1;});}
launch();
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
  await sleep(2300); // M8 async profile/canonical Lobby gate.
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

const db=path.join(data,'data/lastsector.db');
function mysql(sql){const text=execFileSync('docker',['exec','--env','MYSQL_PWD=lastsector-isolated-test',(process.env.LASTSECTOR_MYSQL_CONTAINER ?? 'lastsector-m7-mysql'),'mysql','--user=lastsector_test','--database=lastsector_test','--batch','--raw','--execute',sql],{encoding:'utf8',windowsHide:true}).trim();if(!text)return [];const [header,...lines]=text.split('\n');const columns=header.split('\t');return lines.map(line=>Object.fromEntries(line.split('\t').map((value,i)=>[columns[i],value])));}
function query(sql){if(process.env.M7_MYSQL_PORT)return mysql(sql);return JSON.parse(execFileSync('python',['-c','import sqlite3,json,sys; c=sqlite3.connect(sys.argv[1]); c.row_factory=sqlite3.Row; print(json.dumps([dict(r) for r in c.execute(sys.argv[2])]))',db,sql],{encoding:'utf8',windowsHide:true}));}
async function ready(){await until(()=>output.includes('Done (')&&output.includes('Recovery bootstrap complete'),'Paper/DB bootstrap',120000);}
async function json(command){const text=await consoleCommand(command,'M7 JSON=');return JSON.parse(text.match(/M7 JSON=(.*)/)[1]);}
async function kill(){await fs.writeFile(path.join(root,`console-${epoch}.log`),output);child.kill('SIGKILL');await until(()=>exit!==null,'Owned Java process terminated');await sleep(1000);}
async function checkpoint(test){let row;await until(()=>{row=query("SELECT * FROM recovery_sessions WHERE status='ACTIVE' AND room_id='solo'")[0];return row && test(JSON.parse(row.payload));},'Durable checkpoint');return JSON.parse(row.payload);}
const originals=new Map();
async function nativeValues(value){if(value?.format==='paper-native')return nbt.simplify((await nbt.parse(Buffer.from(value.data,'base64'))).parsed);if(Array.isArray(value))return Promise.all(value.map(nativeValues));if(value&&typeof value==='object')return Object.fromEntries(await Promise.all(Object.entries(value).map(async([key,item])=>[key,await nativeValues(item)])));return value;}
function stableLobby(value){const copy=structuredClone(value);delete copy.inventory;delete copy.cursor;delete copy.effects;delete copy.exhaustion;delete copy.fireTicks;delete copy.fallDistance;return copy;}
async function restored(name){await until(async()=>{const text=await consoleCommand('lsprobe player '+name,'PROBE player=');return text.includes('world=world ');},'Lobby restore '+name);let canonical;await until(async()=>{canonical=await json('lsprobe m7lobby '+name);return JSON.stringify(Object.keys(canonical.inventory).sort())===JSON.stringify(['0','1','4','7','8']);},'Canonical Lobby '+name);assert.deepEqual(Object.keys(canonical.inventory).sort(),['0','1','4','7','8']);assert.deepEqual(await nativeValues(stableLobby(canonical)),await nativeValues(stableLobby(originals.get(name))));}
try {
 await ready();const clients=new Map();
 for(const name of ['LSAlice','LSBob','LSCarol','LSDan','LSEve','LSFrank','LSGrace']){const bot=await connect(name,port);clients.set(name,bot);await probe('lsprobe m4seed '+name,'M4 seeded');originals.set(name,await json('lsprobe m7lobby '+name));}
 for(const name of ['LSFrank','LSGrace'])await chat(clients.get(name),'/ls join squad','Joined room squad');
 await consoleCommand('ls debug start squad','Start requested');await state('squad','RUNNING');
 for(const name of ['LSAlice','LSBob','LSCarol','LSDan'])await chat(clients.get(name),'/ls join solo','Joined room solo');
 await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');
 for(const name of ['LSAlice','LSBob','LSCarol','LSDan']){await kit(clients.get(name));await probe('lsprobe m6matchseed '+name,'M6 match seeded');}
 await chat(clients.get('LSEve'),'/ls spectate solo','Spectating room solo');
 await probe('lsprobe m5damage LSAlice LSDan melee 1000','M5 damage');
 await until(async()=>(await consoleCommand('ls debug deathboxes solo','deathboxes=')).includes('deathboxes=1'),'One elimination');
 await consoleCommand('lsprobe position LSAlice 0 -60 0','PROBE positioned=true');await openBox(clients.get('LSAlice'));
 await json('lsprobe m7boxtake LSAlice 0');const box=await json('lsprobe m7box LSAlice');await close(clients.get('LSAlice'));
 await json('lsprobe m7worldseed LSAlice');clients.get('LSBob').quit();await sleep(700);
 await consoleCommand('save-all flush','Saved the game');
 const before=await checkpoint(s=>s.gameState==='RUNNING' && s.zone.phase==='SHRINKING' && s.boxes.length===1 && s.participants.some(p=>p.name==='LSBob'&&p.state==='DISCONNECTED'));
 assert.equal(before.lootState,'COMPLETE');assert.deepEqual(before.boxes[0].inventory,box);assert.equal(query('SELECT * FROM pending_player_restores').length,7);
 await fs.writeFile(path.join(root,'before.json'),JSON.stringify(before,null,2));
 await kill();await sleep(4000);launch();await ready();
 const recovered=await checkpoint(s=>s.revision>before.revision && s.participants.filter(p=>p.state==='DISCONNECTED').length===3);
 assert.equal(recovered.sessionId,before.sessionId);assert.equal(recovered.worldName,before.worldName);assert.deepEqual(recovered.teams,before.teams);assert.deepEqual(recovered.boxes,before.boxes);assert.equal(recovered.zone.phase,'SHRINKING');assert.ok(recovered.zone.remainingNanos>before.zone.remainingNanos-8e9);assert.deepEqual(recovered.sanitizedBlocks,before.sanitizedBlocks);
 const bobBefore=before.participants.find(p=>p.name==='LSBob'),bobAfter=recovered.participants.find(p=>p.name==='LSBob');assert.ok(bobAfter.reconnectRemainingNanos>bobBefore.reconnectRemainingNanos-8e9);assert.ok(recovered.participants.find(p=>p.name==='LSAlice').reconnectRemainingNanos>110e9);
 const squad=query("SELECT * FROM recovery_sessions WHERE room_id='squad' AND status='ACTIVE'")[0];assert.ok(squad);assert.match(output,/recovered=2/);
 await consoleCommand('ls debug end squad','End requested');await state('squad','WAITING');
 for(const name of ['LSFrank','LSGrace']){clients.set(name,await connect(name,port));await restored(name);}

 clients.set('LSAlice',await connect('LSAlice',port));const match=await json('lsprobe m7match LSAlice');const saved=before.participants.find(p=>p.name==='LSAlice').current;for(const field of ['inventory','cursor','totalXp','selected','maxHealth'])assert.deepEqual(match[field],saved[field],field);
 assert.deepEqual(await json('lsprobe m7worldcheck LSAlice'),{autosave:true,block:'DIAMOND_BLOCK',chest:17,ground:true});
 assert.deepEqual(await json('lsprobe m7entities LSAlice'),{bodies:2,boxes:1});
 await openBox(clients.get('LSAlice'));assert.deepEqual(await json('lsprobe m7box LSAlice'),box);await close(clients.get('LSAlice'));
 clients.set('LSBob',await connect('LSBob',port));const bob=await json('lsprobe m7match LSBob');assert.deepEqual(bob.inventory,bobBefore.current.inventory);assert.equal(bob.totalXp,bobBefore.current.totalXp);
 clients.set('LSDan',await connect('LSDan',port));await restored('LSDan');clients.set('LSEve',await connect('LSEve',port));await restored('LSEve');
 results.push('PASS: actual Java SIGKILL + JDBC restart resumes same RUNNING world/session/Teams; shrink and existing body timeout pause; ALIVE becomes full-window body. Reconnect restores carried inventory/cursor/XP/max health; original Lobby state survives. DeathBox partial looting, one visual, ground item/chest/player block and sanitation survive without Loot regeneration. External/dead spectators return Lobby.');
 // A recovered match must still decide a winner, then an ENDING crash must preserve its remaining showcase.
 await probe('lsprobe m6bodyhit LSAlice LSCarol melee 1000','M6 body hit');
 await probe('lsprobe m5damage LSAlice LSBob melee 1000','M5 damage');await state('solo','ENDING');
 const ending=await checkpoint(s=>s.gameState==='ENDING'&&s.outcome.players.length===1);await kill();await sleep(3000);launch();await ready();
 const endingAgain=await checkpoint(s=>s.gameState==='ENDING'&&s.revision>ending.revision);assert.deepEqual(endingAgain.outcome,ending.outcome);assert.ok(endingAgain.showcaseRemainingNanos>ending.showcaseRemainingNanos-8e9);
 for(const name of ['LSAlice','LSBob','LSCarol']){clients.set(name,await connect(name,port));await restored(name);}
 await until(()=>clients.get('LSAlice').lines.some(l=>l.includes('WINNER:')),'Recovered winner message');
 await consoleCommand('ls debug end solo','End requested');await state('solo','WAITING');await until(async()=>(await dirs()).length===0,'Recovered match cleanup');
 await until(()=>query("SELECT * FROM pending_player_restores").length===0,'All originals acknowledged');
 results.push('PASS: recovered RUNNING match decides winner, ENDING survives another force kill with paused showcase/outcome, offline winner receives result, originals restore and runtime world is deleted.');
 // Simulate the narrow restore-acknowledged/playerdata-saved, SQL-not-yet-acknowledged crash window.
 await chat(clients.get('LSAlice'),'/ls join solo','Joined room solo');await chat(clients.get('LSBob'),'/ls join solo','Joined room solo');await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');
 await checkpoint(s=>s.sessionId!==before.sessionId&&s.gameState==='RUNNING');
 const generation=query("SELECT * FROM pending_player_restores WHERE player_uuid='"+before.participants.find(p=>p.name==='LSAlice').id+"'")[0].generation;
 await probe('lsprobe m4seed LSAlice','M4 seeded');await json('lsprobe m7ack LSAlice '+generation);await kill();launch();await ready();
 clients.set('LSAlice',await connect('LSAlice',port));await restored('LSAlice');await state('solo','WAITING');assert.match(output,/conflicts with an acknowledged Lobby restore/);
 clients.set('LSBob',await connect('LSBob',port));await restored('LSBob');await until(()=>query('SELECT * FROM pending_player_restores').length===0,'Conflicting original acknowledgements drain');
 results.push('PASS: saved playerdata generation wins over stale ACTIVE SQL row: reconnect ends conflicting recovered session and keeps original Lobby inventory instead of restoring old carried match items.');
 // Start a separate match to exercise independent corruption fallback.
 await chat(clients.get('LSAlice'),'/ls join solo','Joined room solo');await chat(clients.get('LSBob'),'/ls join solo','Joined room solo');
 await consoleCommand('ls debug start solo','Start requested');await state('solo','RUNNING');
 const corrupt=await checkpoint(s=>s.sessionId!==before.sessionId&&s.gameState==='RUNNING');
 await checkpoint(s=>s.participants.some(p=>p.name==='LSAlice'&&p.state==='ALIVE'));await kill();
 if(process.env.M7_MYSQL_PORT)mysql("UPDATE recovery_sessions SET payload='corrupt' WHERE status='ACTIVE'");else execFileSync('python',['-c','import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute("UPDATE recovery_sessions SET payload=? WHERE status=?", ("corrupt","ACTIVE")); c.commit()',db],{windowsHide:true});
 launch();await ready();assert.match(output,/Abandoning unsafe recovery/);assert.equal(query("SELECT * FROM recovery_sessions WHERE status='ABANDONED' AND session_id='"+corrupt.sessionId+"'").length,1);
 for(const name of ['LSAlice','LSBob']){clients.set(name,await connect(name,port));await restored(name);}
 await chat(clients.get('LSAlice'),'/ls join solo','Joined room solo');await chat(clients.get('LSAlice'),'/ls leave','Left room');
 const marker=await fs.readFile(path.join(root,corrupt.worldName,'.lastsector-runtime'),'utf8');assert.match(marker,/status=ORPHANED/);assert.match(marker,/orphanedAt=/);assert.ok((await dirs()).includes(corrupt.relativePath));
 results.push('PASS: checksum-corrupted snapshot abandons independently, never loads unsafe session, returns all pending originals, persists ORPHANED timestamp and retains world for configured delay.');
 assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception/);
 // Graceful stop must leave no recoverable sessions; failed database bootstrap must not mark/delete worlds.
 for(const bot of bots)try{bot.quit();}catch{}await sleep(300);child.stdin.write('stop\n');await until(()=>exit!==null,'Graceful shutdown');
 await fs.writeFile(path.join(root,`console-${epoch}.log`),output);assert.equal(query("SELECT * FROM recovery_sessions WHERE status IN ('ACTIVE','RECOVERING')").length,0);
 let broken=await fs.readFile(path.join(data,'config.yml'),'utf8');if(process.env.M7_MYSQL_PORT)broken=broken.replace('port: '+process.env.M7_MYSQL_PORT,'port: 1');else{await fs.mkdir(path.join(data,'data','unavailable'));broken=broken.replace('file: data/lastsector.db','file: data/unavailable');}await fs.writeFile(path.join(data,'config.yml'),broken);
 launch();await until(()=>output.includes('Storage/schema unavailable; runtime directories preserved')&&output.includes('Done ('),'Unavailable database fails closed',120000);
 assert.equal(await fs.readFile(path.join(root,corrupt.worldName,'.lastsector-runtime'),'utf8'),marker);assert.ok((await dirs()).includes(corrupt.relativePath));
 results.push('PASS: two independent rooms recover; graceful stop retires sessions. Unavailable database disables startup without modifying the retained orphan marker or deleting its world.');
} catch(error){results.push('FAILED: '+error.stack);process.exitCode=1;}
finally{for(const bot of bots)try{bot.quit();}catch{}await sleep(300);if(exit===null)child.stdin.write('stop\n');const deadline=Date.now()+60000;while(exit===null&&Date.now()<deadline)await sleep(200);if(exit===null){child.kill('SIGKILL');process.exitCode=1;}await fs.writeFile(path.join(root,`console-${epoch}.log`),output);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(results,null,2));console.log(results.join('\n'));console.log('ARTIFACTS '+root);}
