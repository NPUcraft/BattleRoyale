// Paper 26.2 rc3 final-config smoke test using a generated LOCAL SAMPLE map, never the remote full map.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-rc3.mjs <stopped-paper-dir> <generated-city-template> [deploy-config]
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';

const version='1.0.0-rc.3';
if (!process.argv[2] || !process.argv[3]) throw new Error('Supply a stopped Paper installation and a generated local city template.');
const source=path.resolve(process.argv[2]), fixture=path.resolve(process.argv[3]);
const setup=path.resolve('.run/battle-setup-rc3');
const configuration=path.resolve(process.argv[4]??path.join(setup,'deploy-config'));
assert.match(await fs.readFile(path.join(source,'eula.txt'),'utf8'),/^eula=true\s*$/m);
assert.ok((await fs.stat(path.join(fixture,'data/minecraft/world_gen_settings.dat'))).isFile(),'Paper 26.2 sample dimension data');
const root=path.resolve('.run','paper-rc3-'+Date.now());
const data=path.join(root,'plugins/BattleRoyale'), reports=path.join(root,'plugins/BattleRoyaleTestProbe/rc3');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const digest=async file=>createHash('sha256').update(await fs.readFile(file)).digest('hex');
async function manifest(directory) {
 const result=[];
 async function walk(current,relative='') {
  for (const entry of (await fs.readdir(current,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name))) {
   assert.ok(!entry.isSymbolicLink(),'Fixture must contain no symbolic links');
   const file=path.join(current,entry.name), name=path.posix.join(relative,entry.name);
   if (entry.isDirectory()) await walk(file,name);
   else if (entry.isFile()) result.push({name,bytes:(await fs.stat(file)).size,sha256:await digest(file)});
   else throw new Error('Unsupported fixture entry: '+file);
  }
 }
 await walk(directory);return result;
}
const fixtureBefore=await manifest(fixture);
assert.ok(fixtureBefore.some(file=>/^region\/r\.-?\d+\.-?\d+\.mca$/.test(file.name)),'Generated sample contains terrain regions');
const result={status:'running',root,plugin:version,paper:'26.2-129',java:25,
 fixture:{kind:'locally-generated-normal-terrain-sample',source:fixture,notRemoteSurvivalMain:true,
  regions:fixtureBefore.filter(file=>file.name.startsWith('region/')).length,bytes:fixtureBefore.reduce((n,file)=>n+file.bytes,0)},
 configuration,passed:[],limits:['Local generated sample only; complete remote Survival-Main terrain and conversion are not covered.',
  'No native Paper 26.2 protocol clients; player joins, GUI interaction and live purchases are not covered.',
  'No MySQL service test.']};
async function checkpoint(description) {
 result.passed.push(description);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));
 console.log(description);
}
await fs.mkdir(data,{recursive:true});
console.log('Isolated rc3 sample server: '+root);
for (const name of ['paper.jar','libraries','versions','cache','eula.txt'])
 await fs.cp(path.join(source,name),path.join(root,name),{recursive:true});
await fs.copyFile(path.resolve('build/libs/battleroyale-'+version+'.jar'),path.join(root,'plugins/battleroyale.jar'));
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(root,'plugins/probe.jar'));
result.pluginSha256=await digest(path.join(root,'plugins/battleroyale.jar'));
const initial=(await fs.readFile('src/main/resources/config.yml','utf8')).replace('world: world','world: battleroyale_lobby');
await fs.writeFile(path.join(data,'config.yml'),initial);
await fs.writeFile(path.join(root,'server.properties'),[
 'server-ip=127.0.0.1','server-port=0','online-mode=true','level-name=battleroyale_lobby',
 'level-type=minecraft:normal','generate-structures=true','view-distance=2','simulation-distance=2',
 'max-players=32','enable-query=false','enable-rcon=false','spawn-protection=0'
].join('\n'));
await fs.writeFile(path.join(root,'bukkit.yml'),'settings:\n  allow-end: false\n');
await fs.writeFile(path.join(root,'spigot.yml'),'world-settings:\n  default:\n    verbose: false\n');
function start(logName) {
 const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
 let output='',exit=null;
 child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
 child.stdout.on('data',text=>{output+=text;});child.stderr.on('data',text=>{output+=text;});
 child.on('exit',code=>{exit=code??-1;});child.on('error',error=>{output+=error.stack;exit=-1;});
 async function waitFor(expected,from=0,timeout=240000) {
  const end=Date.now()+timeout;
  while (!output.slice(from).includes(expected)) {
   if (/(?:P26|RC3) .*FAILED|PROBE failed=|Error occurred while enabling BattleRoyale|Startup failed; disabling BattleRoyale/.test(output.slice(from)) || exit!==null || Date.now()>end)
    throw new Error('Waiting for '+expected+'\n'+output.slice(from).slice(-18000));
   await sleep(100);
  }
  return output.slice(from);
 }
 async function command(command,expected,timeout=60000) {const from=output.length;console.log('Command: '+command);child.stdin.write(command+'\n');return waitFor(expected,from,timeout);}
 async function stop() {
  if (exit===null) child.stdin.write('stop\n');
  const end=Date.now()+60000;
  while (exit===null && Date.now()<end) await sleep(100);
  await fs.writeFile(path.join(root,logName),output);
  if (exit===null) {child.kill();throw new Error('Owned disposable test server failed to stop normally');}
  assert.equal(exit,0,'Server shutdown exit code');
 }
 return {waitFor,command,stop,text:()=>output};
}
function cleanLog(text) {
 // This Windows host lacks English OSHI Perflib counters; retain raw logs and filter only identified system-info blocks.
 return text.split(/(?=^\[\d{2}:\d{2}:\d{2}\])/m)
  .filter(block=>!/^\[.*\] \[CrashReport preload thread\/(ERROR|WARN)\]: (\[oshi\.|Failed to get system info for Process )/.test(block)).join('');
}
async function finalChecks(server) {
 await server.waitFor('Done (');
 await server.waitFor('BattleRoyale '+version+' rooms=3');
 assert.match(server.text(),/\[BattleRoyale\] Loaded 3 rooms/);
 await server.command('br version','版本 '+version);
 const definitions=await server.command('br debug rooms','squad (四人小队)');
 for (const name of ['solo (单人竞技)','duo (双人组队)','squad (四人小队)']) assert.ok(definitions.includes(name),name);
 const diagnosis=await server.command('br admin diagnose','runtime-root');
 assert.match(diagnosis,/正常 recovery：/);assert.match(diagnosis,/正常 maps：available=1 total=1/);
 assert.match(diagnosis,/正常 economy：configured=excellenteconomy active=excellenteconomy currency=coins available=true shopEnabled=true/);
 const map=await server.command('br admin map validate survival','地图：survival　配置版本：');
 assert.doesNotMatch(map,/错误：|校验未通过/);
 await server.command('brprobe p26rc3 rooms survival','RC3 ROOMS SUCCESS rooms=3 map=survival area=+-10000 nativeLoadouts=OK');
 await server.command('brprobe p26rc3 lobby','RC3 LOBBY SUCCESS');
 const worlds=await server.command('brprobe worlds','PROBE worlds=');
 assert.doesNotMatch(worlds,/battleroyale_game_|battleroyale_maintenance_|battleroyale_fixture_/);
 assert.doesNotMatch(cleanLog(await fs.readFile(path.join(root,'logs/latest.log'),'utf8')),/ERROR|Exception/);
}
let server;
try {
 server=start('loadout-generation.log');
 try {
  await server.waitFor('Done (');
  await server.command('br version','版本 '+version);
  await server.command('brprobe p26rc3 loadout starter','RC3 LOADOUT SUCCESS id=starter');
  const starter=path.join(reports,'starter-loadouts.yml');
  const yaml=await fs.readFile(starter,'utf8');
  assert.match(yaml,/config-version: 2/);assert.match(yaml,/loadouts:\s+starter:/);
  assert.equal((yaml.match(/\bdata: /g)??[]).length,5,'Five serialized native items');
  result.starterLoadout=starter;
  await checkpoint('Native starter loadout generated and round-tripped: '+starter);
 } finally {await server.stop();server=null;}

 // The final deployment config is copied verbatim, then its generated native loadout is installed.
 await fs.cp(configuration,data,{recursive:true});
 await fs.copyFile(result.starterLoadout,path.join(data,'loadouts.yml'));
 await fs.cp(fixture,path.join(data,'maps/Survival-Main'),{recursive:true});
 for (const entry of await fs.readdir(path.join(setup,'dependencies'))) {
  if (entry.endsWith('.jar')) await fs.copyFile(path.join(setup,'dependencies',entry),path.join(root,'plugins',entry));
 }
 await fs.cp(path.join(setup,'dependency-config'),path.join(root,'plugins'),{recursive:true});
 await fs.cp(path.join(setup,'dependency-libraries'),path.join(root,'libraries'),{recursive:true});
 result.dependencyJars=await Promise.all((await fs.readdir(path.join(setup,'dependencies'))).filter(file=>file.endsWith('.jar'))
  .sort().map(async file=>({file,sha256:await digest(path.join(root,'plugins',file))})));
 result.configurationFiles=await manifest(configuration);
 await checkpoint('Final three-room config and economic dependency stack staged with a '+result.fixture.regions+'-region local sample');

 server=start('final-config.log');
 let markerHash,backupHash;
 try {
  await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=true');
  await finalChecks(server);
  await fs.copyFile(path.join(reports,'lobby-report.yml'),path.join(reports,'lobby-first-build.yml'));
  await fs.copyFile(path.join(reports,'rooms-report.yml'),path.join(reports,'rooms-first-build.yml'));
  markerHash=await digest(path.join(data,'lobby-structure.properties'));
  backupHash=await digest(path.join(data,'lobby-structure-original.blocks.gz'));
  await checkpoint('Three rooms, +/-10000 random sectors, native starter items, complete lobby blocks/Chinese labels/spawn, and BattleRoyale economy availability');
  // The block scan releases its tickets; retain this chunk while testing a persistent manual edit.
  await server.command('minecraft:forceload add 12 0','force loaded');
  await sleep(1000);
  await server.command('minecraft:execute in minecraft:overworld if block 12 200 0 minecraft:cyan_terracotta run minecraft:say RC3_ORIGINAL_FLOOR_OK','RC3_ORIGINAL_FLOOR_OK');
  await server.command('minecraft:setblock 12 200 0 minecraft:emerald_block','Changed the block at 12, 200, 0');
  await server.command('minecraft:execute in minecraft:overworld if block 12 200 0 minecraft:emerald_block run minecraft:say RC3_USER_EDIT_WRITTEN','RC3_USER_EDIT_WRITTEN');
 } finally {await server.stop();server=null;}

 server=start('restart.log');
 try {
  await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
  await server.command('minecraft:execute in minecraft:overworld if block 12 200 0 minecraft:emerald_block run minecraft:say RC3_USER_EDIT_PRESERVED','RC3_USER_EDIT_PRESERVED');
  await server.command('minecraft:setblock 12 200 0 minecraft:cyan_terracotta','Changed the block at 12, 200, 0');
  await server.command('minecraft:forceload remove 12 0','force loading');
  await finalChecks(server);
  assert.equal(await digest(path.join(data,'lobby-structure.properties')),markerHash,'READY marker unchanged');
  assert.equal(await digest(path.join(data,'lobby-structure-original.blocks.gz')),backupHash,'Original-block backup unchanged');
  await checkpoint('READY restart preserves a manual floor edit without rebuilding or duplicating labels; restored blueprint fully passes again');
 } finally {await server.stop();server=null;}
 assert.deepEqual(await manifest(fixture),fixtureBefore,'Source sample remains unchanged');
 assert.deepEqual(await manifest(path.join(data,'maps/Survival-Main')),fixtureBefore,'Installed sample template remains unchanged');
 await checkpoint('Local sample source and installed template byte hashes unchanged');
 result.status='passed';
} catch (error) {
 result.status='failed';result.failure=error.stack??String(error);throw error;
} finally {
 await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify({root,status:result.status,starterLoadout:result.starterLoadout,passed:result.passed},null,2));
}
