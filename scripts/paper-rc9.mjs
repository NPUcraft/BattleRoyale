// Isolated rc9 upgrade from a passed, normally stopped rc8 fixture; never changes the source or production.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-rc9.mjs [<passed-stopped-rc8-fixture>]
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const source=path.resolve(process.argv[2]??'.run/paper-rc8-1790791539404');
const root=path.resolve('.run','paper-rc9-'+Date.now()),plugins=path.join(root,'plugins'),data=path.join(plugins,'BattleRoyale');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const hash=async file=>createHash('sha256').update(await fs.readFile(file)).digest('hex');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const previousResultsFile=path.join(source,'results.json');
const previousResults=JSON.parse(await fs.readFile(previousResultsFile,'utf8'));
assert.equal(previousResults.status,'passed');assert.equal(previousResults.plugin,'1.0.0-rc.8');
assert.equal(previousResults.runs.length,2);assert.ok(previousResults.runs.every(run=>run.exitCode===0));
assert.equal(await hash(path.join(source,'plugins/battleroyale.jar')),previousResults.pluginSha256);
assert.match(await fs.readFile(path.join(source,'eula.txt'),'utf8'),/^eula=true\s*$/m);
const sourceEvidence={};
async function sourceManifest(directory=source){
 const found={};
 for(const entry of await fs.readdir(directory,{withFileTypes:true})){
  const full=path.join(directory,entry.name),relative=path.relative(source,full).replaceAll('\\','/');
  assert.equal(entry.isSymbolicLink(),false,'No symlink in immutable base: '+relative);
  if(entry.isDirectory())Object.assign(found,await sourceManifest(full));
  else {assert.ok(entry.isFile(),'Only regular base files: '+relative);found[relative]=await hash(full);}
 }
 return found;
}
const originalManifest=await sourceManifest();
for(const file of ['plugins/battleroyale.jar','plugins/BattleRoyale/lobby-structure.properties','plugins/BattleRoyale/lobby-structure-original.blocks.gz',
 'plugins/BattleRoyale/config.yml','plugins/BattleRoyale/rooms.yml','plugins/BattleRoyale/lobby.yml','plugins/BattleRoyale/zones.yml','plugins/BattleRoyale/loot-tables.yml','plugins/BattleRoyale/map-data/survival/loot.yml',
 'plugins/CoinsEngine/data.db','plugins/CoinsEngine/engine.yml','plugins/BattleRoyaleTestProbe/rc6-lobby/baseline.yml'])sourceEvidence[file]=await hash(path.join(source,file));
const dependencies=[];
for(const name of (await fs.readdir(path.join(source,'plugins'))).filter(name=>name.endsWith('.jar')&&!['battleroyale.jar','probe.jar'].includes(name))){
 const sha256=await hash(path.join(source,'plugins',name));dependencies.push({name,sha256});sourceEvidence['plugins/'+name]=sha256;
}
assert.ok(dependencies.some(entry=>entry.name==='CoinsEngine-2.7.0.jar'));assert.ok(dependencies.some(entry=>entry.name==='nightcore-2.15.0.jar'));assert.ok(dependencies.some(entry=>entry.name==='PlaceholderAPI-2.12.3.jar'));
await fs.cp(source,root,{recursive:true,filter:entry=>{
 const relative=path.relative(source,entry).replaceAll('\\','/');
 return !['logs','results.json','plugins/BattleRoyaleTestProbe/rc8','plugins/BattleRoyaleTestProbe/rc8-lobby','plugins/BattleRoyaleTestProbe/buff','plugins/BattleRoyaleTestProbe/rc9','plugins/BattleRoyaleTestProbe/flight'].includes(relative);
}});
const result={status:'running',root,source,plugin:'1.0.0-rc.9',paper:'26.2-129',java:25,
 fixture:{notRemoteSurvivalMain:true,sourceFixture:source,previousResults:previousResultsFile,previousResultsSha256:await hash(previousResultsFile),additionalWorlds:'Dedicated generated local probe worlds only'},
 sourceEvidence,sourceFileCount:Object.keys(originalManifest).length,dependencies,appliedSettings:[],passed:[],runs:[],
 limits:['Generated local sample and dedicated flat worlds only; complete remote Survival-Main terrain is not tested.',
 'Native item metadata, platform blocks, sanitation cursors, ground-loot scheduling, saved beacon records and Bukkit UI APIs are tested.',
 'Aircraft controller uses Player interface recorders; actual multiplayer client gliding and platform rendering require player acceptance.',
 'Navigation, scoreboard and board callbacks use Player interface recorders; no native client rendering, packet input, real mount movement or full match is claimed.',
 'Airdrop session time is explicitly advanced; no real 60-second client announcement wait is claimed.',
 'The existing isolated synthetic economy account is read-only verified at 136; no new account or economy seed is created.']};
await fs.copyFile(path.resolve('build/libs/battleroyale-1.0.0-rc.9.jar'),path.join(plugins,'battleroyale.jar'));
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(plugins,'probe.jar'));
result.pluginSha256=await hash(path.join(plugins,'battleroyale.jar'));result.probeSha256=await hash(path.join(plugins,'probe.jar'));
for(const dependency of dependencies)assert.equal(await hash(path.join(plugins,dependency.name)),dependency.sha256,'No dependency replacement');
for(const file of ['config.yml','rooms.yml','lobby.yml','loot-tables.yml','map-data/survival/loot.yml'])assert.equal(await hash(path.join(data,file)),sourceEvidence['plugins/BattleRoyale/'+file],'Source server configuration retained: '+file);
const desiredManifestFile=path.resolve('.run/battleroyale-rc9/desired/manifest.json');
const desiredManifest=JSON.parse(await fs.readFile(desiredManifestFile,'utf8'));
for(const entry of desiredManifest){
 const name=entry.target.slice('/plugins/BattleRoyale/'.length);
 const captured=path.resolve('.run/battleroyale-rc9/before',entry.target.slice(1));
 assert.equal(createHash('sha256').update((await fs.readFile(captured,'utf8')).replaceAll('\r\n','\n')).digest('hex'),entry.before);
 assert.equal((await fs.readFile(path.join(data,name),'utf8')).replaceAll('\r\n','\n'),(await fs.readFile(captured,'utf8')).replaceAll('\r\n','\n'),'Fixture matches captured production setting (line endings normalized) '+name);
 const desired=path.resolve('.run/battleroyale-rc9/desired',entry.target.slice(1));
 assert.equal(await hash(desired),entry.after);await fs.copyFile(desired,path.join(data,name));
 result.appliedSettings.push({target:name,sha256:await hash(path.join(data,name))});
}
result.desiredManifestSha256=await hash(desiredManifestFile);
assert.equal(await hash(path.join(plugins,'CoinsEngine/data.db')),sourceEvidence['plugins/CoinsEngine/data.db'],'Existing 136 balance database copied byte-for-byte');
assert.equal(await hash(path.join(plugins,'CoinsEngine/engine.yml')),sourceEvidence['plugins/CoinsEngine/engine.yml'],'Economy engine configuration unchanged');
const properties=await fs.readFile(path.join(root,'server.properties'),'utf8');assert.match(properties,/^server-ip=127\.0\.0\.1\s*$/m);assert.match(properties,/^server-port=0\s*$/m);
async function persist(){await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));}
async function checkpoint(text){result.passed.push(text);console.log(text);await persist();}
await persist();console.log('Isolated rc9 fixture: '+root);

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
 const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-Dbattleroyale.probe.rc4=true','-Dbattleroyale.probe.rc6=true','-Dbattleroyale.probe.rc7=true','-Dbattleroyale.probe.rc8=true','-Dbattleroyale.probe.buff=true','-Dbattleroyale.probe.rc9=true','-Dbattleroyale.probe.flight=true','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
 let raw='',exit=null,stopped=false;
 child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');child.stdout.on('data',value=>raw+=value);child.stderr.on('data',value=>raw+=value);
 child.on('exit',code=>exit=code??-1);child.on('error',error=>{raw+=error.stack;exit=-1;});
 const plain=()=>raw.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
 async function waitFor(expected,from=0,timeout=240000){
  const deadline=Date.now()+timeout;
  while(!plain().slice(from).includes(expected)){
   const tail=plain().slice(from);
   if(/RC[3456789] .*FAILED|FLIGHT DEPLOYMENT FAILED|BEACON BUFF FAILED|PROBE failed=|Error occurred while enabling|Startup failed; disabling|NoClassDefFoundError|UnknownDependencyException/.test(tail)||exit!==null||Date.now()>deadline)throw new Error('Waiting for '+expected+'\n'+tail.slice(-16000));
   await sleep(100);
  }
  return plain().slice(from);
 }
 async function command(text,expected,timeout=60000){const from=plain().length;console.log('Command: '+text);child.stdin.write(text+'\n');return waitFor(expected,from,timeout);}
 async function stop(){
  if(stopped)return;stopped=true;if(exit===null)child.stdin.write('stop\n');const deadline=Date.now()+60000;
  while(exit===null&&Date.now()<deadline)await sleep(100);
  await fs.writeFile(path.join(root,name+'-console.log'),raw);
  if(exit===null){child.kill();throw new Error('Owned isolated rc9 server failed normal stop');}
  const latest=await fs.readFile(path.join(root,'logs/latest.log'),'utf8');await fs.writeFile(path.join(root,name+'-server.log'),latest);
  const run={name,exitCode:exit,log:name+'-server.log',coinsShutdownComplete:/CoinsEnginePool[^\r\n]*Shutdown completed/.test(latest),placeholderUnregistered:latest.includes('CoinsEngine 占位符已在 PlaceholderAPI 停止前安全注销。'),warnings:latest.split(/\r?\n/).filter(line=>line.includes('/WARN]')&&!line.includes('CrashReport preload thread'))};result.runs.push(run);await persist();
  assert.equal(exit,0,'Clean normal server exit');assert.doesNotMatch(cleanLog(latest),/ERROR|Exception/,'No non-OSHI exception including during shutdown');
  assert.equal(run.coinsShutdownComplete,true,'CoinsEngine database pool closes normally');assert.equal(run.placeholderUnregistered,true,'CoinsEngine expansion safely removed before PlaceholderAPI stops');
 }
 return{waitFor,command,stop,text:plain};
}
async function ready(server){
 await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
 await server.command('br version','版本 1.0.0-rc.9');
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
 await withServer('rc9-first',async server=>{
  await ready(server);await server.command('br admin config validate','全部配置文件校验通过，未修改任何文件。');
  await checkpoint('rc9 deployment loot tables, ground loot and final-zero zone settings parse on actual Paper; lobby, rooms and dependencies retained');
  await server.command('brprobe p26rc6economy verify','RC6 ECONOMY VERIFY SUCCESS balance=136',120000);
  await server.command('brprobe p26rc6lobby verify','RC6 LOBBY VERIFY SUCCESS',120000);
  await checkpoint('Upgrade from rc6 retains existing 136 balance, existing ranking-board UUIDs/positions (new English rating display allowed), every READY blueprint block, marker and backup');
  for(const [command,token] of [['p26rc4 styles','RC4 STYLES SUCCESS'],['p26rc4 items','RC4 ITEMS SUCCESS'],['p26rc4 sidebar','RC4 SIDEBAR SUCCESS'],['p26rc3 rooms survival','RC3 ROOMS SUCCESS']])await server.command('brprobe '+command,token);
  await checkpoint('Existing Component/native exit item/real scoreboard and three-room/native loadout checks passed');
  await server.command('brprobe p26rc7ui all','RC7 UI SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc7-ui/report.yml'),'utf8'),/status: passed/);
  await checkpoint('Next-square shortest distance, center arrows, simultaneous airdrop direction/distance, squared-side area, zero closure, legacy closure recovery and stage totals passed');
  await server.command('brprobe p26rc7loot all','RC7 LOOT SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc7/loot-report.yml'),'utf8'),/status: passed/);
  await server.command('brprobe p26rc6loot all','RC6 LOOT SUCCESS',120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc6-loot/report.yml'),'utf8'),/status: passed/);
  await checkpoint('Native high-tier airdrop gear, material catalog and unchanged low-tier ordinary loot/potions/fireworks/mob origin regressions passed');
  await server.command('brprobe p26rc6lobby all','RC6 LOBBY ALL SUCCESS',120000);
  await checkpoint('SQL-backed ranking-board native lifecycle and unchanged lobby blueprint passed');
  const independentErrors=[];
  for(const [command,token,report,description] of [
   ['p26beaconbuff all','BEACON BUFF SUCCESS','buff/report.yml','Native tier-one speed I, 24-block spherical range, 100-tick lifetime, alive-only filter, stronger-effect preservation and recovery guard passed'],
   ['p26rc8locale all','RC8 LOCALE SUCCESS','rc8/locale-report.yml','Explicit Chinese/English templates and unchanged user-provided values passed'],
   ['p26rc8lobby all','RC8 LOBBY ALL SUCCESS','rc8-lobby/report.yml','Original Chinese label UUIDs, compact sidebar and mutually exclusive bilingual native displays passed']]){
   try{
    await server.command('brprobe '+command,token,120000);
    const contents=await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe',report),'utf8');assert.match(contents,/status: passed/);
    if(command==='p26beaconbuff all'){assert.match(contents,/recovery-window-closed: true/);assert.match(contents,/ordinary-beacon-unaffected-after-ready: true/);}
    await checkpoint(description);
   }catch(error){independentErrors.push(error);result.probeFailures??=[];result.probeFailures.push({command,error:error.stack??String(error)});await persist();}
  }
  for(const [command,token,report,description] of [
   ['p26rc7airdrop all','RC7 AIRDROP SUCCESS','airdrop-report.yml','Native beacon placement, safe marker persistence/cleanup and closest valid airdrop navigation target passed'],
   ['p26rc7horses all','RC7 HORSES CELEBRATION SUCCESS','horse-celebration-report.yml','Native horse persistence and harmless celebration firework APIs passed']]){
   try{await server.command('brprobe '+command,token,120000);assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe/rc7',report),'utf8'),/status: passed/);await checkpoint(description);}
   catch(error){independentErrors.push(error);result.probeFailures??=[];result.probeFailures.push({command,error:error.stack??String(error)});await persist();}
  }
  if(independentErrors.length)throw new AggregateError(independentErrors,'Independent rc8 probe checks failed; see results.json probeFailures for original causes');
  for(const [command,token,report] of [
   ['p26rc9loot all','RC9 LOOT SUCCESS','rc9/loot-report.yml'],
   ['p26flight all','FLIGHT DEPLOYMENT SUCCESS','flight/report.yml']]){
   await server.command('brprobe '+command,token,180000);
   assert.match(await fs.readFile(path.join(plugins,'BattleRoyaleTestProbe',report),'utf8'),/status: passed/);
   await checkpoint(command+' native public-API checks passed');
  }
  await server.command('brprobe p26rc8lobby baseline','RC8 LOBBY BASELINE SUCCESS',120000);await diagnose(server);
 });
 await checkpoint('First normal shutdown: exit 0, safe placeholder unregister, CoinsEnginePool closed and no plugin exception');
 await withServer('rc9-restart',async server=>{
  await ready(server);await server.command('brprobe p26rc6economy verify','RC6 ECONOMY VERIFY SUCCESS balance=136',120000);
  await server.command('brprobe p26rc8lobby verify','RC8 LOBBY VERIFY SUCCESS',120000);await diagnose(server);
  await checkpoint('Real rc9 restart retains balance 136, all 12 Chinese/English lobby and ranking entity UUIDs/positions, all lobby blueprint block data, marker and backup');
 });
 await checkpoint('Second normal shutdown: exit 0, safe placeholder unregister, CoinsEnginePool closed and no plugin exception');
 for(const [file,digest] of Object.entries(sourceEvidence))assert.equal(await hash(path.join(source,file)),digest,'Original stopped fixture unchanged: '+file);
 assert.deepEqual(await sourceManifest(),originalManifest,'Every source file and its complete listing remain unchanged');
 result.status='passed';
}catch(error){failure=error;result.status='failed';result.failure=error.stack??String(error);}
finally{
 try{assert.deepEqual(await sourceManifest(),originalManifest,'Every source file remains unchanged even after a failed run');result.sourceUnchanged=true;}
 catch(error){failure??=error;result.status='failed';result.sourceUnchanged=false;result.sourceFailure=error.stack??String(error);}
 await persist();console.log(JSON.stringify({root,status:result.status,passed:result.passed,failure:result.failure},null,2));
}
if(failure)throw failure;
