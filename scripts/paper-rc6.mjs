// Isolated rc6 Paper/API, persisted economy and lobby restart regression. Never touches production.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-rc6.mjs [<stopped-passing-rc5-fixture>]
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';

const source=path.resolve(process.argv[2]??'.run/paper-rc5-1790781913911');
const root=path.resolve('.run','paper-rc6-'+Date.now()),plugins=path.join(root,'plugins'),data=path.join(plugins,'BattleRoyale');
const setup=path.resolve('.run/battle-expansion-rc6'),desired=path.join(setup,'desired');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const hash=async file=>createHash('sha256').update(await fs.readFile(file)).digest('hex');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const exists=async file=>fs.stat(file).then(()=>true,error=>{if(error.code==='ENOENT')return false;throw error;});
const original=JSON.parse(await fs.readFile(path.join(source,'results.json'),'utf8'));
assert.equal(original.status,'passed');assert.equal(original.plugin,'1.0.0-rc.5');assert.equal(original.fixture.notRemoteSurvivalMain,true);
assert.match(await fs.readFile(path.join(source,'eula.txt'),'utf8'),/^eula=true\s*$/m);
const sourceEvidence={};
for(const file of ['plugins/battleroyale.jar','plugins/BattleRoyale/lobby-structure.properties','plugins/BattleRoyale/lobby-structure-original.blocks.gz','plugins/BattleRoyale/map-data/survival/loot.yml','plugins/ExcellentEconomy/data.db'])sourceEvidence[file]=await hash(path.join(source,file));
await fs.cp(source,root,{recursive:true,filter:entry=>{
 const relative=path.relative(source,entry).replaceAll('\\','/');
 return !['logs','results.json','plugins/BattleRoyaleTestProbe/rc6','plugins/BattleRoyaleTestProbe/rc6-lobby','plugins/BattleRoyaleTestProbe/rc6-loot'].includes(relative);
}});
const result={status:'running',root,source,plugin:'1.0.0-rc.6',paper:'26.2-129',java:25,
 fixture:{...original.fixture,sourceFixture:source,additionalWorlds:'Dedicated generated local flat worlds owned by Rc6 probes'},
 sourceEvidence,dependencies:[],appliedSettings:[],passed:[],runs:[],
 limits:['Generated local sample and dedicated flat worlds only, not the complete remote Survival-Main map.',
 'Native item metadata, saved entities, persistent loot, display entities, chunk tickets and economy database/API are exercised.',
 'Sidebar and board interaction use Player interface proxies; no actual client rendering, player GUI click, drinking/throwing potions, crossbow use, or complete real-player match is asserted.',
 'Air-drop session time is advanced by the probe; the 60-second contract is checked without claiming a real-time client announcement.',
 'Only an explicitly isolated synthetic economy account is created, and it is never added to BattleRoyale player rankings.']};
await fs.copyFile(path.resolve('build/libs/battleroyale-1.0.0-rc.6.jar'),path.join(plugins,'battleroyale.jar'));
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(plugins,'probe.jar'));
result.pluginSha256=await hash(path.join(plugins,'battleroyale.jar'));result.probeSha256=await hash(path.join(plugins,'probe.jar'));
for(const name of await fs.readdir(plugins))if(/^(?:ExcellentEconomy|nightcore)[^/\\]*\.jar$/i.test(name)){
 const target=path.resolve(plugins,name);assert.equal(path.dirname(target),plugins,'Only a JAR directly within the new isolated plugins directory can be removed');await fs.rm(target);
}
const coinsDirectory=path.join(plugins,'CoinsEngine');assert.equal(await exists(coinsDirectory),false,'Fresh migrated CoinsEngine directory required');
await fs.cp(path.join(plugins,'ExcellentEconomy'),coinsDirectory,{recursive:true});
assert.equal(await hash(path.join(coinsDirectory,'data.db')),sourceEvidence['plugins/ExcellentEconomy/data.db'],'Economy database copied byte-for-byte before startup');
await fs.copyFile(path.join(desired,'coinsengine-engine.yml'),path.join(coinsDirectory,'engine.yml'));
assert.match(await fs.readFile(path.join(coinsDirectory,'engine.yml'),'utf8'),/^\s*Table_Prefix: excellenteconomy\s*$/m);
result.economyMigration={source:'ExcellentEconomy',target:'CoinsEngine',databaseSha256BeforeStart:await hash(path.join(coinsDirectory,'data.db')),engineSha256:await hash(path.join(coinsDirectory,'engine.yml')),tablePrefix:'excellenteconomy',syntheticAccount:'132f779f-1dc3-4d77-aafa-7fe5965a19e6'};
for(const file of [path.join(setup,'dependencies/CoinsEngine-2.7.0.jar'),path.join(setup,'dependencies/nightcore-2.15.0.jar'),path.resolve('.run/battle-ui-rc4/dependencies/PlaceholderAPI-2.12.3.jar')]){
 const target=path.join(plugins,path.basename(file));await fs.copyFile(file,target);const sha256=await hash(file);assert.equal(await hash(target),sha256);result.dependencies.push({name:path.basename(file),sha256});
}
for(const name of ['config.yml','rooms.yml','loot-tables.yml']){
 const from=path.join(desired,name),target=path.join(data,name);await fs.copyFile(from,target);const sha256=await hash(from);assert.equal(await hash(target),sha256);result.appliedSettings.push({source:from,target:name,sha256});
}
assert.equal(await hash(path.join(data,'map-data/survival/loot.yml')),sourceEvidence['plugins/BattleRoyale/map-data/survival/loot.yml'],'Existing rc5 map loot retained');
const properties=await fs.readFile(path.join(root,'server.properties'),'utf8');
assert.match(properties,/^server-ip=127\.0\.0\.1\s*$/m);assert.match(properties,/^server-port=0\s*$/m);
async function persist(){await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));}
async function checkpoint(text){result.passed.push(text);console.log(text);await persist();}
await persist();console.log('Isolated rc6 fixture: '+root);

// Only the exact Windows OSHI crash-report preload failures seen in the baseline are excluded.
// Plugin errors, including shutdown exceptions, are never ignored.
function cleanLog(text){
 return text.split(/(?=^\[\d{2}:\d{2}:\d{2}\])/m).filter(block=>{
  const header=block.split(/\r?\n/,1)[0];
  if(/^\[\d{2}:\d{2}:\d{2}\] \[CrashReport preload thread\/ERROR\]: \[oshi\.driver\.windows\.registry\.HkeyPerformanceDataUtil\] Unable to locate English counter names in registry Perflib 009\. Counters may need to be rebuilt:\s*$/.test(header)
    &&block.includes('com.sun.jna.platform.win32.Win32Exception:')&&block.includes('at oshi.driver.windows.registry.HkeyPerformanceDataUtil.'))return false;
  if(/^\[\d{2}:\d{2}:\d{2}\] \[CrashReport preload thread\/WARN\]: Failed to get system info for Process (?:Loads|Virtual Size \(MiB\)|Resident Size \(MiB\))\s*$/.test(header)
    &&block.includes('java.lang.NullPointerException: Cannot invoke "oshi.software.os.OSProcess.')&&block.includes('at net.minecraft.SystemReport.'))return false;
  return true;
 }).join('');
}
function start(name){
 const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-Dbattleroyale.probe.rc4=true','-Dbattleroyale.probe.rc6=true','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
 let raw='',exit=null,stopped=false;
 child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');child.stdout.on('data',value=>raw+=value);child.stderr.on('data',value=>raw+=value);
 child.on('exit',code=>exit=code??-1);child.on('error',error=>{raw+=error.stack;exit=-1;});
 const plain=()=>raw.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
 async function waitFor(expected,from=0,timeout=240000){
  const deadline=Date.now()+timeout;
  while(!plain().slice(from).includes(expected)){
   const tail=plain().slice(from);
   if(/RC[3456] .*FAILED|PROBE failed=|Error occurred while enabling|Startup failed; disabling|NoClassDefFoundError|UnknownDependencyException/.test(tail)||exit!==null||Date.now()>deadline)throw new Error('Waiting for '+expected+'\n'+tail.slice(-16000));
   await sleep(100);
  }
  return plain().slice(from);
 }
 async function command(text,expected,timeout=60000){const from=plain().length;console.log('Command: '+text);child.stdin.write(text+'\n');return waitFor(expected,from,timeout);}
 async function stop(){
  if(stopped)return;stopped=true;if(exit===null)child.stdin.write('stop\n');const deadline=Date.now()+60000;
  while(exit===null&&Date.now()<deadline)await sleep(100);
  await fs.writeFile(path.join(root,name+'-console.log'),raw);
  if(exit===null){child.kill();throw new Error('Owned isolated rc6 server failed normal stop');}
  const latest=await fs.readFile(path.join(root,'logs/latest.log'),'utf8');await fs.writeFile(path.join(root,name+'-server.log'),latest);
  const run={name,exitCode:exit,log:name+'-server.log',coinsShutdownComplete:/CoinsEnginePool[^\r\n]*Shutdown completed/.test(latest),placeholderUnregistered:latest.includes('CoinsEngine 占位符已在 PlaceholderAPI 停止前安全注销。'),warnings:latest.split(/\r?\n/).filter(line=>line.includes('/WARN]')&&!line.includes('CrashReport preload thread'))};result.runs.push(run);await persist();
  assert.equal(exit,0,'Clean normal server exit');assert.doesNotMatch(cleanLog(latest),/ERROR|Exception/,'No non-OSHI exception including during shutdown');
  assert.equal(run.coinsShutdownComplete,true,'CoinsEngine database pool closes normally');assert.equal(run.placeholderUnregistered,true,'CoinsEngine expansion safely removed before PlaceholderAPI stops');
 }
 return{waitFor,command,stop,text:plain};
}
async function ready(server){
 await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
 await server.command('br version','版本 1.0.0-rc.6');
 assert.match(server.text(),/\[CoinsEngine\] Enabling CoinsEngine v2\.7\.0/);assert.match(server.text(),/\[PlaceholderAPI\] Enabling PlaceholderAPI v2\.12\.3/);
}
async function diagnose(server){
 const diagnosis=await server.command('br admin diagnose','runtime-root');assert.match(diagnosis,/正常 recovery：/);assert.match(diagnosis,/正常 maps：available=1 total=1/);
 assert.match(diagnosis,/正常 economy：configured=coinsengine active=coinsengine currency=coins available=true shopEnabled=true/);
 return diagnosis;
}
async function withServer(name,work){
 const server=start(name);let failure;
 try{await work(server);}catch(error){failure=error;}
 try{await server.stop();}catch(error){
  if(failure){result.secondaryFailures??=[];result.secondaryFailures.push(error.stack??String(error));}else failure=error;
 }
 if(failure)throw failure;
}
let failure;
try{
 await withServer('rc6-first',async server=>{
  await ready(server);await server.command('br admin config validate','全部配置文件校验通过，未修改任何文件。');
  await checkpoint('Exact rc6 config/rooms/loot-tables parse on real Paper; original survival map loot retained');
  for(const [command,token] of [['p26rc4 styles','RC4 STYLES SUCCESS'],['p26rc4 items','RC4 ITEMS SUCCESS'],['p26rc4 sidebar','RC4 SIDEBAR SUCCESS'],['p26rc3 rooms survival','RC3 ROOMS SUCCESS']])await server.command('brprobe '+command,token);
  await checkpoint('Existing Component, queue-exit native-item, real scoreboard/proxy and three-room/native-loadout regressions passed');
  await server.command('brprobe p26rc6loot all','RC6 LOOT SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc6-loot/report.yml'),'utf8'),/status: passed/);
  await checkpoint('Native rc6 potion/firework/enchantment/mob-origin probes passed');
  await server.command('brprobe p26rc6lobby all','RC6 LOBBY ALL SUCCESS',120000);
  await checkpoint('Actual SQL-backed colored board, native display/lectern/hitbox, throttling, deduplication, shutdown/reopen, retirement and unchanged lobby blocks passed');
  await server.command('brprobe p26rc6economy seed','RC6 ECONOMY SEED SUCCESS balance=136',120000);
  await server.command('brprobe p26rc6lobby baseline','RC6 LOBBY BASELINE SUCCESS',120000);await diagnose(server);
 });
 await checkpoint('First normal shutdown: safe placeholder unregister, CoinsEnginePool shutdown completed, exit 0 and no plugin exception');
 await withServer('rc6-restart',async restarted=>{
  await ready(restarted);await restarted.command('brprobe p26rc6economy verify','RC6 ECONOMY VERIFY SUCCESS balance=136',120000);
  await restarted.command('brprobe p26rc6lobby verify','RC6 LOBBY VERIFY SUCCESS',120000);await diagnose(restarted);
  await checkpoint('Real restart retained synthetic economy balance 136 and all three lobby entity UUIDs/positions, every blueprint block, marker and original backup');
  await restarted.command('brprobe p26rc6airdrop all','RC6 AIRDROP SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc6/airdrop-report.yml'),'utf8'),/status: passed/);
  await checkpoint('Real 60-second fixed-point guaranteed-airdrop, occupied-point cancellation and recovery probes passed');
 });
 await checkpoint('Second normal shutdown: safe placeholder unregister, CoinsEnginePool shutdown completed, exit 0 and no plugin exception');
 for(const [file,digest] of Object.entries(sourceEvidence))assert.equal(await hash(path.join(source,file)),digest,'Original stopped fixture unchanged: '+file);
 result.status='passed';
}catch(error){failure=error;result.status='failed';result.failure=error.stack??String(error);}
finally{await persist();console.log(JSON.stringify({root,status:result.status,passed:result.passed,failure:result.failure},null,2));}
if(failure)throw failure;
