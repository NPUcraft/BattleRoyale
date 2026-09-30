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
const root = path.resolve('.run', 'paper-m9-soak-' + Date.now());
const data = path.join(root, 'plugins/BattleRoyale');
await fs.mkdir(data, { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt'])
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
if(process.env.M8_LIBRARY_CACHE)await fs.cp(path.join(process.env.M8_LIBRARY_CACHE,'libraries'),path.join(root,'libraries'),{recursive:true});
await fs.copyFile('build/libs/battleroyale-1.0.0-rc.1.jar', path.join(root, 'plugins/battleroyale.jar'));
await fs.copyFile('build/integration/battleroyale-test-probe.jar', path.join(root, 'plugins/probe.jar'));
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
  if(file==='config.yml')text=text.replace('winner-showcase-seconds: 60','winner-showcase-seconds: 1');
  if(file==='rooms.yml')text='config-version: 2\nrooms:\n'+Array.from({length:5},(_,i)=>`  soak${i}:\n    display-name: Soak ${i}\n    min-players: 2\n    max-players: 2\n    team-size: 1\n    countdown-seconds: 300\n    pvp-protection-seconds: 0\n    spawn:\n      min-distance: 8\n      max-attempts-per-player: 100\n    allow-external-spectators: true\n    maps: [city]\n    loadout: default\n    zone-profile: default\n`).join('');
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
  'view-distance=2', 'simulation-distance=2', 'max-players=16', 'spawn-protection=0',
  'enable-query=false', 'enable-rcon=false', 'difficulty=normal', 'gamemode=creative'
].join('\n'));
await fs.writeFile(path.join(root, 'bukkit.yml'), 'settings:\n  allow-end: false\n');
console.log('M9 soak isolated server: ' + root);
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
    answer = await consoleCommand('br debug session ' + room, 'countdown=');
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
  await probe('brprobe m5kit '+bot.username+(full?' full':''),'M5 kit=');
  await consoleCommand('brprobe position '+bot.username+' 0 -60 0','PROBE positioned=true');
}
async function openBox(bot) {
  const line=await probe('brprobe m5box '+bot.username,'M5 box entity=');
  const id=Number(line.match(/entity=(\d+)/)[1]);
  await until(()=>bot.entities[id],'Interaction entity delivered');
  bot.activateEntity(bot.entities[id]);
  await until(()=>bot.currentWindow?.inventoryStart===54,'DeathBox GUI opens');
}
async function close(bot) {if(bot.currentWindow)bot.closeWindow(bot.currentWindow);await sleep(200);}
async function restore(name) { await until(async()=>{const answer=await probe('brprobe player '+name,'PROBE player=');return answer.includes('world=world ');},'Lobby respawn '+name); await probe('brprobe m4original '+name,'M4 original=true world=world'); }
async function startSolo(a,b) {
  await chat(a,'/br join solo','Joined room solo');await chat(b,'/br join solo','Joined room solo');
  await consoleCommand('br debug start solo','Start requested');await state('solo','RUNNING');await kit(a);await kit(b);
}
async function endSolo() {await consoleCommand('br debug end solo','End requested');await state('solo','WAITING');}

const observations=[];
try {
  await until(()=>output.includes('Done (')&&output.includes('Recovery bootstrap complete'),'startup',120000);
  const clients=[];for(let i=0;i<10;i++)clients.push(await connect('Soak'+i,port));await sleep(3000);
  const baseline=await consoleCommand('br debug perf','Timings');const baselineTasks=Number(baseline.match(/tasks=(\d+)/)[1]);
  const cycles=Number(process.env.BATTLEROYALE_SOAK_ROUNDS??20);assert.ok(cycles>=1&&cycles<=100);
  for(let cycle=0;cycle<cycles;cycle++){
    for(let i=0;i<10;i++)await chat(clients[i],'/br join soak'+Math.floor(i/2),'Joined room');
    for(let i=0;i<5;i++)await consoleCommand('br debug start soak'+i,'Start requested');
    for(let i=0;i<5;i++)await state('soak'+i,'RUNNING');
    if(cycle%5===0){clients[1].quit();await sleep(500);clients[1]=await connect('Soak1',port);await sleep(800);}
    for(let i=0;i<10;i++)await probe('brprobe m5kit Soak'+i,'M5 kit=');
    for(let i=0;i<5;i++)await probe(`brprobe m5damage Soak${i*2} Soak${i*2+1} melee 1000`,'M5 damage health=');
    for(let i=0;i<5;i++)await state('soak'+i,'WAITING');
    await until(async()=>!(await dirs()).length,'runtime world cleanup',120000);
    await until(async()=>(await consoleCommand('br debug stats Soak0','PlayerProfile')).includes('matches='+(cycle+1)+','),'result persisted');
    await sleep(300);
    const perf=await consoleCommand('br debug perf','Timings');assert.match(perf,/sessions=0 players=0 spectators=0/);for(const key of ['entries','draining','bossbars','deathboxes','offlineBodies'])assert.match(perf,new RegExp(key+'=0'));assert.match(perf,/pendingRestore=0/);assert.ok(Number(perf.match(/tasks=(\d+)/)[1])<=baselineTasks+2,'no accumulating scheduled tasks');
    observations.push({cycle:cycle+1,matches:(cycle+1)*5,perf});console.log(`SOAK ${cycle+1}/${cycles}: five concurrent matches cleaned; no resource leaks`);
  }
  assert.equal(await hash(path.join(data,'maps/city/level.dat')),original);assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception|Recovery capture rejected/);
  results.push(`${cycles} rounds x 5 concurrent rooms = ${cycles*5} actual Paper matches with 10 Mineflayer protocol clients; periodic disconnect/reconnect, real elimination, stats and every-round resource checks passed. This is not an 80-client capacity claim.`);
} catch(error){results.push('FAILED: '+error.stack);process.exitCode=1;}
finally {
  for(const bot of bots)try{bot.quit();}catch{}await sleep(300);if(exit===null)child.stdin.write('stop\n');const deadline=Date.now()+60000;while(exit===null&&Date.now()<deadline)await sleep(200);if(exit===null){child.kill();results.push('FAILED shutdown timeout');process.exitCode=1;}
  await fs.writeFile(path.join(root,'console.log'),output);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(results,null,2));await fs.writeFile(path.join(root,'observations.json'),JSON.stringify(observations,null,2));console.log(results.join('\n'));console.log('ARTIFACTS '+root);
}
