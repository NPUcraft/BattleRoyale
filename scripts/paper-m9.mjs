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
const root = path.resolve('.run', 'paper-m9-' + Date.now());
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
  if (file === 'rooms.yml') text=text.replace('max-players: 8','max-players: 4').replaceAll('maps: [city, desert]','maps: [city]');
  if (file === 'zones.yml') text = text.replace(/wait-seconds: \d+/g, 'wait-seconds: 300').replace(/shrink-seconds: \d+/g, 'shrink-seconds: 10');
  if (file === 'maps.yml') text = text.replace(/3000|2500/g, '600')+'\n  tiny:\n    display-name: Tiny maintenance fixture\n    directory: maps/tiny\n    playable-area:\n      min-x: 4000\n      max-x: 4032\n      min-z: 4000\n      max-z: 4032\n';
  if(file==='config.yml')text=text.replace('particle-wall:\n    enabled: true','particle-wall:\n    enabled: false');
  if(file==='config.yml'&&process.env.M8_ECONOMY)text=text.replace('provider: auto','provider: '+process.env.M8_ECONOMY);
  await fs.writeFile(path.join(data, file), text.replace(/^config-version: 2\r?\n/,''));
}
for (const name of ['city', 'desert','tiny'])
  await fs.cp(path.join(source, 'world'), path.join(data, 'maps', name), {
    recursive: true, filter: entry => !['session.lock', 'playerdata', 'stats', 'advancements'].includes(path.basename(entry))
  });
await fs.mkdir(path.join(data,'map-data/tiny'),{recursive:true});await fs.writeFile(path.join(data,'map-data/tiny/loot.yml'),'containers: []\nareas: []\n');
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
console.log('M9 isolated server: ' + root);
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

try {
  await until(()=>output.includes('Done (')&&output.includes('Recovery bootstrap complete'),'startup',120000);
  const alice=await connect('LSAlice',port),bob=await connect('LSBob',port),carol=await connect('LSCarol',port);await sleep(3000);
  await startSolo(bob,carol);assert.match(await consoleCommand('br debug session solo','countdown='),/mapRevision=0/);
  await consoleCommand('op LSAlice','Made LSAlice');
  await chat(bob,'/br admin map edit city','permission');
  await chat(alice,'/br admin map edit city','Editor ready');
  await until(()=>alice.inventory.slots[36]?.name==='wooden_axe','editor PDC tools');
  assert.equal(await hash(path.join(data,'maps/city/level.dat')),original);
  assert.match(await consoleCommand('br debug worlds','owned=true'),/type=EDITOR/);
  await consoleCommand('brprobe m9interact LSAlice 0 left -600 -60 -600','M9 interaction');
  await consoleCommand('brprobe m9interact LSAlice 0 right 600 -60 600','M9 interaction');
  await consoleCommand('brprobe m9interact LSAlice 1 right 10 -60 10 CHEST','M9 interaction');
  await until(()=>alice.currentWindow,'container GUI');await consoleCommand('brprobe m9abuse LSAlice','M9 abuse cancelled=8');await alice.clickWindow(0,0,0);await sleep(300);await close(alice);
  await consoleCommand('brprobe m9interact LSAlice 2 left 20 -60 20','M9 interaction');
  await consoleCommand('brprobe m9interact LSAlice 2 right 25 -55 25','M9 interaction');
  const loot=(await fs.readFile(path.join(data,'loot-tables.yml'),'utf8')).match(/^  ([\w-]+):/m)[1];
  await chat(alice,`/br admin map ground new ${loot} 1 1 2 8`,'INFO');
  await consoleCommand('brprobe position LSAlice 0 -55 0','PROBE positioned=true');
  await chat(alice,'/br admin map spectator','Spectator fallback set');
  await chat(alice,'/br admin map save','Saved metadata revision 1');
  assert.match(await consoleCommand('br debug session solo','countdown='),/mapRevision=0/);
  const metadataPath=path.join(data,'map-data/city/metadata.json');const saved=await fs.readFile(metadataPath,'utf8');const metadata=JSON.parse(saved);assert.equal(metadata.revision,1);assert.equal(metadata.loot.containers.length,1);assert.equal(metadata.loot.areas.length,1);assert.ok(metadata.spectator);
  await chat(alice,'/br admin map area 100 1300 100 1300','ERROR');
  await chat(alice,'/br admin map save','Save refused');assert.equal(await fs.readFile(metadataPath,'utf8'),saved);
  await chat(alice,'/br admin map discard','');await until(async()=>!(await fs.readdir(path.join(data,'maintenance-worlds'))).length,'editor cleanup');
  assert.equal(await hash(path.join(data,'maps/city/level.dat')),original);
  results.push('Editor clone, PDC area/container/ground tools, LootTable GUI, eight GUI abuse event variants, spectator, atomic save revision 1, invalid save refusal, discard and template unchanged verified.');
  await consoleCommand('br admin map validate city --deep','Preparing');await until(()=>output.includes('Not a supported container:'),'deep validation stone/air error');await until(async()=>!(await fs.readdir(path.join(data,'maintenance-worlds'))).length,'validation cleanup');
  await endSolo();await chat(alice,'/br admin map edit city','Editor ready');await chat(alice,`/br admin map remove ${metadata.loot.containers[0].id} confirm`,'Metadata removed');await chat(alice,'/br admin map save','Saved metadata revision 2');await chat(alice,'/br admin map discard','Editor discard requested');await until(async()=>!(await fs.readdir(path.join(data,'maintenance-worlds'))).length,'saved editor exit');await sleep(1000);await startSolo(bob,carol);assert.match(await consoleCommand('br debug session solo','countdown='),/mapRevision=2/);await endSolo();results.push('An already running match retains metadata revision 0 while editor saves; next match selects revision 2.');await chat(alice,'/br admin map edit city','Editor ready');alice.quit();await until(async()=>!(await fs.readdir(path.join(data,'maintenance-worlds'))).length,'disconnect discard cleanup');
  results.push('Deep validator reports missing template container without NPE; editor disconnect discards and releases owned clone.');
  await consoleCommand('br admin diagnose','Overall:');await consoleCommand('br debug perf','Timings');await consoleCommand('br debug worlds','orphanWorlds');await consoleCommand('br admin config validate','configuration files valid');
  await consoleCommand('br admin supportbundle','Support bundle:');
  const bundles=await fs.readdir(path.join(data,'support'));assert.equal(bundles.length,1);
  execFileSync('python',['-c',"import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); t='\\n'.join(z.read(n).decode() for n in z.namelist()); assert 'battleroyale-isolated-test' not in t; assert 'inventory contents' not in t; assert 'password=battleroyale' not in t; assert 'diagnostics.txt' in z.namelist()",path.join(data,'support',bundles[0])],{windowsHide:true});
  assert.match(await fs.readFile(path.join(data,'config.yml'),'utf8'),/config-version: 2/);assert.ok((await fs.readdir(path.join(data,'config-backups'))).length);
  results.push('Legacy configuration backup/migration, diagnostics/perf/worlds, read-only config validation, permission denial and support ZIP checks passed.');
  // Unreferenced tiny metadata fixture permits a bounded real pregeneration test without reducing gameplay zone rules.
  const desertBefore=await hash(path.join(data,'maps/desert/level.dat'));await consoleCommand('br admin map pregenerate desert','Preparing');await sleep(700);await consoleCommand('br admin map pregenerate cancel desert','');await until(async()=>!(await fs.readdir(path.join(data,'maintenance-worlds'))).length,'pregeneration cancel cleanup',120000);
  assert.equal(await hash(path.join(data,'maps/desert/level.dat')),desertBefore);results.push('Pregeneration cancellation cleans maintenance clone without committing template.');
  await consoleCommand('br admin map pregenerate tiny','Preparing');await until(()=>output.includes('pregenerate commit tiny confirm'),'tiny generation ready',120000);await consoleCommand('br admin map pregenerate commit tiny confirm','Template committed');assert.ok((await fs.readdir(path.join(data,'maps'))).some(n=>n.startsWith('tiny.backup-')));assert.ok((await fs.readdir(path.join(data,'maps/tiny/region'))).some(n=>n==='r.7.7.mca'));const generatedNbt=nbt.simplify((await nbt.parse(await fs.readFile(path.join(data,'maps/tiny/level.dat')))).parsed);assert.equal(stringify(generatedNbt.Data.WorldGenSettings),generatorSettings);results.push('Real tiny-map pregeneration generated distant chunks, confirmed directory replacement, complete backup and unchanged seed/generator.');
  assert.doesNotMatch(output,/Could not pass event|Task #\d+.*exception/);
} catch(error) {results.push('FAILED: '+error.stack);process.exitCode=1;}
finally {
  for(const bot of bots)try{bot.quit();}catch{}
  await sleep(300);if(exit===null)child.stdin.write('stop\n');
  const deadline=Date.now()+60000;while(exit===null&&Date.now()<deadline)await sleep(200);
  if(exit===null){child.kill();results.push('FAILED: shutdown timeout');process.exitCode=1;}
  await fs.writeFile(path.join(root,'console.log'),output);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(results,null,2));await fs.writeFile(path.join(root,'messages.json'),stringify(bots.map(b=>({name:b.username,lines:b.lines}))));console.log(results.join('\n'));console.log('ARTIFACTS '+root);
}
