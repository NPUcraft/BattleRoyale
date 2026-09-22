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
const root = path.resolve('.run', 'paper-m8-recovery-' + Date.now());
const data = path.join(root, 'plugins/LastSector');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
await fs.copyFile('build/libs/lastsector-0.1.0-SNAPSHOT.jar', path.join(root, 'plugins/lastsector.jar'));
await fs.copyFile('build/integration/lastsector-test-probe.jar', path.join(root, 'plugins/probe.jar'));
for (const file of ['config.yml', 'rooms.yml', 'maps.yml', 'zones.yml']) {
  let text = await fs.readFile(path.join('src/main/resources', file), 'utf8');
  if (file === 'rooms.yml') text = text.replaceAll('countdown-seconds: 30', 'countdown-seconds: 30').replace('min-players: 4', 'min-players: 2').replaceAll('pvp-protection-seconds: 60', 'pvp-protection-seconds: 0').replaceAll('max-players: 24', 'max-players: 8').replaceAll('max-players: 32', 'max-players: 8');
  if (file === 'rooms.yml') text=text.replace('team-size: 4','team-size: 2').replace('max-players: 8','max-players: 4');
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
console.log('M8 recovery isolated server: ' + root);
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
function mysql(sql){const text=execFileSync('docker',['exec','--env','MYSQL_PWD=lastsector-isolated-test','lastsector-m7-mysql','mysql','--user=lastsector_test','--database=lastsector_test','--batch','--raw','--execute',sql],{encoding:'utf8',windowsHide:true}).trim();if(!text)return [];const [header,...lines]=text.split('\n');const columns=header.split('\t');return lines.map(line=>Object.fromEntries(line.split('\t').map((value,i)=>[columns[i],value])));}
function query(sql){if(process.env.M7_MYSQL_PORT)return mysql(sql);return JSON.parse(execFileSync('python',['-c','import sqlite3,json,sys; c=sqlite3.connect(sys.argv[1]); c.row_factory=sqlite3.Row; print(json.dumps([dict(r) for r in c.execute(sys.argv[2])]))',db,sql],{encoding:'utf8',windowsHide:true}));}
async function ready(){await until(()=>output.includes('Done (')&&output.includes('Recovery bootstrap complete'),'Paper/DB bootstrap',120000);}
async function json(command){const text=await consoleCommand(command,'M7 JSON=');return JSON.parse(text.match(/M7 JSON=(.*)/)[1]);}
async function kill(){await fs.writeFile(path.join(root,`console-${epoch}.log`),output);child.kill('SIGKILL');await until(()=>exit!==null,'Owned Java process terminated');await sleep(1000);}
async function checkpoint(test){let row;await until(()=>{row=query("SELECT * FROM recovery_sessions WHERE status='ACTIVE' AND room_id='solo'")[0];return row && test(JSON.parse(row.payload));},'Durable checkpoint');return JSON.parse(row.payload);}
const originals=new Map();
async function nativeValues(value){if(value?.format==='paper-native')return nbt.simplify((await nbt.parse(Buffer.from(value.data,'base64'))).parsed);if(Array.isArray(value))return Promise.all(value.map(nativeValues));if(value&&typeof value==='object')return Object.fromEntries(await Promise.all(Object.entries(value).map(async([key,item])=>[key,await nativeValues(item)])));return value;}
function stableLobby(value){const copy=structuredClone(value);delete copy.inventory;delete copy.cursor;delete copy.effects;delete copy.exhaustion;delete copy.fireTicks;delete copy.fallDistance;return copy;}
async function restored(name){await until(async()=>{const text=await consoleCommand('lsprobe player '+name,'PROBE player=');return text.includes('world=world ');},'Lobby restore '+name);let canonical;await until(async()=>{canonical=await json('lsprobe m7lobby '+name);return JSON.stringify(Object.keys(canonical.inventory).sort())===JSON.stringify(['0','1','4','7','8']);},'Canonical Lobby '+name);assert.deepEqual(Object.keys(canonical.inventory).sort(),['0','1','4','7','8']);assert.deepEqual(await nativeValues(stableLobby(canonical)),await nativeValues(stableLobby(originals.get(name))));}try {
 await ready();const clients=new Map();
 for(const name of ['LSAlice','LSBob','LSCarol','LSDan'])clients.set(name,await connect(name,port));
 await consoleCommand('ls admin cosmetic grant LSAlice deathbox_gold','Cosmetic updated');await sleep(400);
 const alice=clients.get('LSAlice');alice.chat('/ls cosmetics');await until(()=>alice.currentWindow?.inventoryStart===54,'cosmetics GUI');
 await alice.clickWindow(0,0,0);await sleep(1200);await close(alice);
 assert.match(await consoleCommand('ls debug stats LSAlice','PlayerProfile'),/DEATHBOX_SKIN=deathbox_gold/);
 for(const bot of clients.values())await chat(bot,'/ls join squad','Joined room squad');
 await consoleCommand('ls debug start squad','Start requested');await state('squad','RUNNING');
 const teamsText=await consoleCommand('ls debug teams squad','Team 1');const teams=[...teamsText.matchAll(/members=\[([^\]]+)\]/g)].map(m=>m[1].split(', ').map(v=>v.split('=')[0]));assert.deepEqual(teams.map(t=>t.length),[2,2]);
 const winners=teams.find(t=>t.includes('LSAlice')),hero=winners.find(n=>n!=='LSAlice'),enemies=teams.find(t=>!t.includes('LSAlice'));
 for(const bot of clients.values())await kit(bot);
 await consoleCommand('ls admin cosmetic revoke LSAlice deathbox_gold','Cosmetic updated');
 await probe('lsprobe m5damage '+enemies[0]+' LSAlice melee 1000','M5 damage');
 await state('squad','RUNNING');
 await until(()=>{const rows=query("SELECT payload FROM recovery_sessions WHERE room_id='squad' AND status='ACTIVE'");return rows.length&&JSON.parse(rows[0].payload).progression?.placements?.assigned!==undefined;},'M8 snapshot');
 const saved=JSON.parse(query("SELECT payload FROM recovery_sessions WHERE room_id='squad' AND status='ACTIVE'")[0].payload);
 assert.equal(saved.progression.frozen['01a006f8-6ed1-3be2-8918-7949bb8c4466'].cosmetics.equipped.DEATHBOX_SKIN,'deathbox_gold');
 assert.match(await consoleCommand('lsprobe m8visual '+hero,'M8 displays='),/GOLD_BLOCK/);
 execFileSync('python',['-c',"import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute(\"CREATE TRIGGER fail_result BEFORE INSERT ON match_results BEGIN SELECT RAISE(ABORT,'outage'); END\"); c.commit()",db],{windowsHide:true});
 for(const victim of enemies)await probe('lsprobe m5damage '+hero+' '+victim+' melee 1000','M5 damage');
 await state('squad','ENDING');
 await until(async()=>{try{return(await fs.readdir(path.join(data,'result-outbox'))).some(n=>n.endsWith('.json'));}catch{return false;}},'Durable result outbox');
 const pendingFile=(await fs.readdir(path.join(data,'result-outbox'))).find(n=>n.endsWith('.json'));const immutable=await fs.readFile(path.join(data,'result-outbox',pendingFile),'utf8');
 await consoleCommand('ls debug end squad','End requested');await state('squad','WAITING');await until(async()=>(await dirs()).length===0,'World cleanup while result pending');
 assert.equal(query('SELECT * FROM match_results').length,0);
 await kill();execFileSync('python',['-c',"import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('DROP TRIGGER fail_result'); c.commit()",db],{windowsHide:true});
 launch();await ready();await until(()=>query('SELECT * FROM match_results').length===1,'Outbox finalized after process restart without world');
 const ledger=JSON.parse(query('SELECT payload FROM match_results')[0].payload);assert.equal(ledger.reason,'NORMAL');
 for(const name of winners){const row=query("SELECT * FROM player_profiles WHERE last_known_name='"+name+"'")[0];assert.equal(row.wins,1);assert.equal(row.matches,1);assert.equal(row.rating,1040);}
 const dead=query("SELECT * FROM player_profiles WHERE last_known_name='LSAlice'")[0];assert.equal(dead.deaths,1);assert.equal(dead.wins,1);assert.equal(query('SELECT * FROM player_period_stats').length,12);
 results.push('Duo dead winning teammate receives placement 1/win/+40; frozen gold DeathBox survives permanent revoke; visual is GOLD_BLOCK.');
 results.push('Result INSERT outage retains durable outbox; world cleanup then SIGKILL; restart finalizes full lifetime/day/week/month transaction.');
 await kill();await fs.writeFile(path.join(data,'result-outbox',pendingFile),immutable);launch();await ready();await sleep(2000);
 assert.equal(query('SELECT * FROM match_results').length,1);assert.equal(query("SELECT matches FROM player_profiles WHERE last_known_name='LSAlice'")[0].matches,1);
 results.push('Identical outbox replay after second restart does not double count. Profiles and periods survive restart.');
 assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception/);
} catch(error){results.push('FAILED: '+error.stack);process.exitCode=1;}
finally{for(const bot of bots)try{bot.quit();}catch{}await sleep(300);if(exit===null)child.stdin.write('stop\n');const deadline=Date.now()+60000;while(exit===null&&Date.now()<deadline)await sleep(200);if(exit===null){child.kill('SIGKILL');process.exitCode=1;}await fs.writeFile(path.join(root,`console-${epoch}.log`),output);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(results,null,2));console.log(results.join('\n'));console.log('ARTIFACTS '+root);}
