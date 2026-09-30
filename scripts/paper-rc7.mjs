// Isolated rc7 upgrade from a healthy stopped rc6 fixture; never changes production.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-rc7.mjs [<stopped-passing-rc6-fixture>]
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const source=path.resolve(process.argv[2]??'.run/paper-rc6-1790784404697');
const root=path.resolve('.run','paper-rc7-'+Date.now()),plugins=path.join(root,'plugins'),data=path.join(plugins,'BattleRoyale');
const desired=path.resolve('.run/battle-navigation-rc7/desired');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const hash=async file=>createHash('sha256').update(await fs.readFile(file)).digest('hex');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const original=JSON.parse(await fs.readFile(path.join(source,'results.json'),'utf8'));
assert.equal(original.status,'passed');assert.equal(original.plugin,'1.0.0-rc.6');assert.equal(original.fixture.notRemoteSurvivalMain,true);
assert.match(await fs.readFile(path.join(source,'eula.txt'),'utf8'),/^eula=true\s*$/m);
const sourceEvidence={};
for(const file of ['plugins/battleroyale.jar','plugins/BattleRoyale/lobby-structure.properties','plugins/BattleRoyale/lobby-structure-original.blocks.gz',
 'plugins/BattleRoyale/config.yml','plugins/BattleRoyale/rooms.yml','plugins/BattleRoyale/lobby.yml','plugins/BattleRoyale/loot-tables.yml','plugins/BattleRoyale/map-data/survival/loot.yml',
 'plugins/CoinsEngine/data.db','plugins/CoinsEngine/engine.yml','plugins/BattleRoyaleTestProbe/rc6-lobby/baseline.yml'])sourceEvidence[file]=await hash(path.join(source,file));
const dependencies=[];
for(const name of (await fs.readdir(path.join(source,'plugins'))).filter(name=>name.endsWith('.jar')&&!['battleroyale.jar','probe.jar'].includes(name))){
 const sha256=await hash(path.join(source,'plugins',name));dependencies.push({name,sha256});sourceEvidence['plugins/'+name]=sha256;
}
assert.ok(dependencies.some(entry=>entry.name==='CoinsEngine-2.7.0.jar'));assert.ok(dependencies.some(entry=>entry.name==='nightcore-2.15.0.jar'));assert.ok(dependencies.some(entry=>entry.name==='PlaceholderAPI-2.12.3.jar'));
await fs.cp(source,root,{recursive:true,filter:entry=>{
 const relative=path.relative(source,entry).replaceAll('\\','/');
 return !['logs','results.json','plugins/BattleRoyaleTestProbe/rc7','plugins/BattleRoyaleTestProbe/rc7-ui'].includes(relative);
}});
const result={status:'running',root,source,plugin:'1.0.0-rc.7',paper:'26.2-129',java:25,
 fixture:{...original.fixture,sourceFixture:source,additionalWorlds:'Dedicated generated local probe worlds only'},
 sourceEvidence,dependencies,appliedSettings:[],passed:[],runs:[],
 limits:['Generated local sample and dedicated flat worlds only; complete remote Survival-Main terrain is not tested.',
 'Native item metadata, entities, saved beacon records and valid Bukkit UI APIs are tested.',
 'Navigation, scoreboard and board callbacks use Player interface recorders; no native client rendering, packet input, real mount movement or full match is claimed.',
 'Airdrop session time is explicitly advanced; no real 60-second client announcement wait is claimed.',
 'The existing isolated synthetic economy account is read-only verified at 136; no new account or economy seed is created.']};
await fs.copyFile(path.resolve('build/libs/battleroyale-1.0.0-rc.7.jar'),path.join(plugins,'battleroyale.jar'));
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(plugins,'probe.jar'));
result.pluginSha256=await hash(path.join(plugins,'battleroyale.jar'));result.probeSha256=await hash(path.join(plugins,'probe.jar'));
for(const dependency of dependencies)assert.equal(await hash(path.join(plugins,dependency.name)),dependency.sha256,'No dependency replacement');
for(const name of ['loot-tables.yml','lobby.yml']){
 const from=path.join(desired,name),target=path.join(data,name);await fs.copyFile(from,target);const sha256=await hash(from);assert.equal(await hash(target),sha256);result.appliedSettings.push({source:from,target:name,sha256});
}
for(const file of ['config.yml','rooms.yml','map-data/survival/loot.yml'])assert.equal(await hash(path.join(data,file)),sourceEvidence['plugins/BattleRoyale/'+file],'Source server configuration retained: '+file);
assert.equal(await hash(path.join(plugins,'CoinsEngine/data.db')),sourceEvidence['plugins/CoinsEngine/data.db'],'Existing 136 balance database copied byte-for-byte');
assert.equal(await hash(path.join(plugins,'CoinsEngine/engine.yml')),sourceEvidence['plugins/CoinsEngine/engine.yml'],'Economy engine configuration unchanged');
const properties=await fs.readFile(path.join(root,'server.properties'),'utf8');assert.match(properties,/^server-ip=127\.0\.0\.1\s*$/m);assert.match(properties,/^server-port=0\s*$/m);
async function persist(){await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));}
async function checkpoint(text){result.passed.push(text);console.log(text);await persist();}
await persist();console.log('Isolated rc7 fixture: '+root);

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
 const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-Dbattleroyale.probe.rc4=true','-Dbattleroyale.probe.rc6=true','-Dbattleroyale.probe.rc7=true','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
 let raw='',exit=null,stopped=false;
 child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');child.stdout.on('data',value=>raw+=value);child.stderr.on('data',value=>raw+=value);
 child.on('exit',code=>exit=code??-1);child.on('error',error=>{raw+=error.stack;exit=-1;});
 const plain=()=>raw.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
 async function waitFor(expected,from=0,timeout=240000){
  const deadline=Date.now()+timeout;
  while(!plain().slice(from).includes(expected)){
   const tail=plain().slice(from);
   if(/RC[34567] .*FAILED|PROBE failed=|Error occurred while enabling|Startup failed; disabling|NoClassDefFoundError|UnknownDependencyException/.test(tail)||exit!==null||Date.now()>deadline)throw new Error('Waiting for '+expected+'\n'+tail.slice(-16000));
   await sleep(100);
  }
  return plain().slice(from);
 }
 async function command(text,expected,timeout=60000){const from=plain().length;console.log('Command: '+text);child.stdin.write(text+'\n');return waitFor(expected,from,timeout);}
 async function stop(){
  if(stopped)return;stopped=true;if(exit===null)child.stdin.write('stop\n');const deadline=Date.now()+60000;
  while(exit===null&&Date.now()<deadline)await sleep(100);
  await fs.writeFile(path.join(root,name+'-console.log'),raw);
  if(exit===null){child.kill();throw new Error('Owned isolated rc7 server failed normal stop');}
  const latest=await fs.readFile(path.join(root,'logs/latest.log'),'utf8');await fs.writeFile(path.join(root,name+'-server.log'),latest);
  const run={name,exitCode:exit,log:name+'-server.log',coinsShutdownComplete:/CoinsEnginePool[^\r\n]*Shutdown completed/.test(latest),placeholderUnregistered:latest.includes('CoinsEngine 占位符已在 PlaceholderAPI 停止前安全注销。'),warnings:latest.split(/\r?\n/).filter(line=>line.includes('/WARN]')&&!line.includes('CrashReport preload thread'))};result.runs.push(run);await persist();
  assert.equal(exit,0,'Clean normal server exit');assert.doesNotMatch(cleanLog(latest),/ERROR|Exception/,'No non-OSHI exception including during shutdown');
  assert.equal(run.coinsShutdownComplete,true,'CoinsEngine database pool closes normally');assert.equal(run.placeholderUnregistered,true,'CoinsEngine expansion safely removed before PlaceholderAPI stops');
 }
 return{waitFor,command,stop,text:plain};
}
async function ready(server){
 await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
 await server.command('br version','版本 1.0.0-rc.7');
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
 await withServer('rc7-first',async server=>{
  await ready(server);await server.command('br admin config validate','全部配置文件校验通过，未修改任何文件。');
  await checkpoint('Exact rc7 loot-tables/lobby parse on actual Paper; source config, three rooms, map loot and all dependency JARs retained');
  await server.command('brprobe p26rc6economy verify','RC6 ECONOMY VERIFY SUCCESS balance=136',120000);
  await server.command('brprobe p26rc6lobby verify','RC6 LOBBY VERIFY SUCCESS',120000);
  await checkpoint('Upgrade from rc6 retains existing 136 balance, three ranking-board UUIDs/positions, every READY blueprint block, marker and backup');
  for(const [command,token] of [['p26rc4 styles','RC4 STYLES SUCCESS'],['p26rc4 items','RC4 ITEMS SUCCESS'],['p26rc4 sidebar','RC4 SIDEBAR SUCCESS'],['p26rc3 rooms survival','RC3 ROOMS SUCCESS']])await server.command('brprobe '+command,token);
  await checkpoint('Existing Component/native exit item/real scoreboard and three-room/native loadout checks passed');
  await server.command('brprobe p26rc7ui all','RC7 UI SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc7-ui/report.yml'),'utf8'),/status: passed/);
  await checkpoint('Next-square shortest distance, center arrows, simultaneous airdrop direction/distance, exact square area and final 8/8 UI passed');
  await server.command('brprobe p26rc7loot all','RC7 LOOT SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc7/loot-report.yml'),'utf8'),/status: passed/);
  await server.command('brprobe p26rc6loot all','RC6 LOOT SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc6-loot/report.yml'),'utf8'),/status: passed/);
  await checkpoint('Native high-tier airdrop gear, material catalog and unchanged low-tier ordinary loot/potions/fireworks/mob origin regressions passed');
  await server.command('brprobe p26rc6lobby all','RC6 LOBBY ALL SUCCESS',120000);
  await checkpoint('SQL-backed ranking-board native lifecycle and unchanged lobby blueprint passed');
  const independentErrors=[];
  for(const [command,token,report,description] of [
   ['p26rc7airdrop all','RC7 AIRDROP SUCCESS','airdrop-report.yml','Native beacon placement, safe marker persistence/cleanup and closest valid airdrop navigation target passed'],
   ['p26rc7horses all','RC7 HORSES CELEBRATION SUCCESS','horse-celebration-report.yml','Native horse persistence and harmless celebration firework APIs passed']]){
   try{await server.command('brprobe '+command,token,120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc7',report),'utf8'),/status: passed/);await checkpoint(description);}
   catch(error){independentErrors.push(error);result.probeFailures??=[];result.probeFailures.push({command,error:error.stack??String(error)});await persist();}
  }
  if(independentErrors.length)throw new AggregateError(independentErrors,'Independent rc7 probe checks failed; see results.json probeFailures for original causes');
  await server.command('brprobe p26rc6lobby baseline','RC6 LOBBY BASELINE SUCCESS',120000);await diagnose(server);
 });
 await checkpoint('First normal shutdown: exit 0, safe placeholder unregister, CoinsEnginePool closed and no plugin exception');
 await withServer('rc7-restart',async server=>{
  await ready(server);await server.command('brprobe p26rc6economy verify','RC6 ECONOMY VERIFY SUCCESS balance=136',120000);
  await server.command('brprobe p26rc6lobby verify','RC6 LOBBY VERIFY SUCCESS',120000);await diagnose(server);
  await checkpoint('Real rc7 restart retains balance 136, ranking entity UUIDs/positions, all lobby blueprint block data, marker and backup');
 });
 await checkpoint('Second normal shutdown: exit 0, safe placeholder unregister, CoinsEnginePool closed and no plugin exception');
 for(const [file,digest] of Object.entries(sourceEvidence))assert.equal(await hash(path.join(source,file)),digest,'Original stopped fixture unchanged: '+file);
 result.status='passed';
}catch(error){failure=error;result.status='failed';result.failure=error.stack??String(error);}
finally{await persist();console.log(JSON.stringify({root,status:result.status,passed:result.passed,failure:result.failure},null,2));}
if(failure)throw failure;
