// Isolated rc5 public-Paper-API regression. Never starts or modifies a production server.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-rc5.mjs <stopped-passing-rc4-fixture> [--production-settings <directory>]
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
if(!process.argv[2])throw new Error('Supply a stopped, passing rc4 local-sample fixture.');
const source=path.resolve(process.argv[2]),root=path.resolve('.run','paper-rc5-'+Date.now());
if(process.argv.length>3&&(process.argv.length!==5||process.argv[3]!=='--production-settings'))throw new Error('Optional argument: --production-settings <directory>');
const productionSettings=process.argv[4]?path.resolve(process.argv[4]):null;
const original=JSON.parse(await fs.readFile(path.join(source,'results.json'),'utf8'));
assert.equal(original.status,'passed');assert.equal(original.plugin,'1.0.0-rc.4');assert.equal(original.fixture.notRemoteSurvivalMain,true);
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const hash=async file=>createHash('sha256').update(await fs.readFile(file)).digest('hex');
const oldJarHash=await hash(path.join(source,'plugins/battleroyale.jar'));
await fs.cp(source,root,{recursive:true,filter:entry=>!['logs','results.json','plugins/BattleRoyaleTestProbe/rc5'].includes(path.relative(source,entry).replaceAll('\\','/'))});
await fs.copyFile(path.resolve('build/libs/battleroyale-1.0.0-rc.5.jar'),path.join(root,'plugins/battleroyale.jar'));
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(root,'plugins/probe.jar'));
const appliedSettings=[];
if(productionSettings){
 // Only these three files are authorized overrides. The source fixture and map identity remain unchanged.
 const data=path.join(root,'plugins/BattleRoyale');
 const authoritative=await fs.stat(path.join(data,'map-data/survival/metadata.json')).then(()=>true,error=>{if(error.code==='ENOENT')return false;throw error;});
 assert.equal(authoritative,false,'The healthy sample must use survival/loot.yml directly; an overriding metadata.json would invalidate this parser check');
 for(const [name,target] of [['config.yml','config.yml'],['loot-tables.yml','loot-tables.yml'],['loot.yml','map-data/survival/loot.yml']]){
  const from=path.join(productionSettings,name),to=path.join(data,target);
  await fs.copyFile(from,to);const digest=await hash(from);assert.equal(await hash(to),digest);
  appliedSettings.push({source:name,target,sha256:digest});
 }
}
const properties=await fs.readFile(path.join(root,'server.properties'),'utf8');
assert.match(properties,/^server-ip=127\.0\.0\.1\s*$/m);assert.match(properties,/^server-port=0\s*$/m);
assert.equal((await fs.readdir(path.join(root,'plugins'))).some(name=>/PlaceholderAPI|PAPIProxyBridge/i.test(name)),false,'Use the healthy fixture without the third-party PAPI shutdown conflict');
const result={status:'running',root,source,plugin:'1.0.0-rc.5',paper:'26.2-129',java:25,
 pluginSha256:await hash(path.join(root,'plugins/battleroyale.jar')),probeSha256:await hash(path.join(root,'plugins/probe.jar')),
 fixture:{...original.fixture,sourceFixture:source,additionalWorld:'Dedicated generated flat world owned only by Rc5Probe'},productionSettings,appliedSettings,passed:[],
 limits:['Both worlds are generated local samples, not the full remote Survival-Main map.',
 'Real Paper containers, saved/reloaded world, BlockDisplay/barrel entities, PDC, chunk tickets and particle payload APIs are tested.',
 'Navigation and scoreboard use Player interface recorders; no client rendering, real player clicks, real-match inventory restoration or live proxy connection is asserted.']};
await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));
console.log('Isolated rc5 fixture: '+root);
const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-Dbattleroyale.probe.rc4=true','-Dbattleroyale.probe.rc5=true','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
let raw='',exit=null;
child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
child.stdout.on('data',text=>{raw+=text;});child.stderr.on('data',text=>{raw+=text;});
child.on('exit',code=>{exit=code??-1;});child.on('error',error=>{raw+=error.stack;exit=-1;});
const plain=()=>raw.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function waitFor(expected,from=0,timeout=240000){
 const deadline=Date.now()+timeout;
 while(!plain().slice(from).includes(expected)){
  if(/RC[345] .*FAILED|PROBE failed=|Error occurred while enabling BattleRoyale|Startup failed; disabling BattleRoyale|NoClassDefFoundError/.test(plain().slice(from))||exit!==null||Date.now()>deadline)
   throw new Error('Waiting for '+expected+'\n'+plain().slice(from).slice(-14000));
  await sleep(100);
 }
 return plain().slice(from);
}
async function command(command,expected,timeout=60000){const from=plain().length;console.log('Command: '+command);child.stdin.write(command+'\n');return waitFor(expected,from,timeout);}
async function checkpoint(description){result.passed.push(description);console.log(description);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));}
function cleanLog(text){return text.split(/(?=^\[\d{2}:\d{2}:\d{2}\])/m).filter(block=>!/^\[.*\] \[CrashReport preload thread\/(ERROR|WARN)\]: (\[oshi\.|Failed to get system info for Process )/.test(block)).join('');}
let failure;
try{
 await waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
 await command('br version','版本 1.0.0-rc.5');
 await command('br admin config validate','全部配置文件校验通过，未修改任何文件。');
 await checkpoint(productionSettings?'Exact three production configuration files parsed successfully on actual Paper, with survival loot metadata at its effective path':'Existing local-sample configuration parsed successfully on actual Paper');
 await command('brprobe p26rc4 styles','RC4 STYLES SUCCESS');
 await command('brprobe p26rc4 items','RC4 ITEMS SUCCESS');
 await command('brprobe p26rc4 sidebar','RC4 SIDEBAR SUCCESS');
 await checkpoint('Existing rc4 Component, queue-exit native-item and real Scoreboard/proxy ownership checks preserved');
 await command('brprobe p26rc3 rooms survival','RC3 ROOMS SUCCESS');
 await command('brprobe p26rc5 all','RC5 ALL SUCCESS',120000);
 const report=await fs.readFile(path.join(root,'plugins/BattleRoyaleTestProbe/rc5/report.yml'),'utf8');
 assert.match(report,/status: passed/);assert.match(report,/actual-world-reload-and-recovery-no-refill: true/);
 assert.match(report,/cancelled-in-flight-cleanup: true/);assert.match(report,/result-durability-gate: true/);
 await checkpoint('Real Paper navigation/particle payloads, container population/reload/exclusions, one landed supply drop/in-flight cancellation/ledger recovery, and ending countdown APIs passed');
 const diagnosis=await command('br admin diagnose','runtime-root');
 assert.match(diagnosis,/正常 recovery：/);assert.match(diagnosis,/正常 maps：available=1 total=1/);
 assert.match(diagnosis,/正常 economy：configured=excellenteconomy active=excellenteconomy currency=coins available=true shopEnabled=true/);
 assert.doesNotMatch(cleanLog(await fs.readFile(path.join(root,'logs/latest.log'),'utf8')),/ERROR|Exception/);
 await checkpoint('Three room configuration and BattleRoyale recovery/map/economy diagnostics healthy after probe cleanup');
}catch(error){failure=error;}
finally{
 if(exit===null)child.stdin.write('stop\n');
 const deadline=Date.now()+60000;
 while(exit===null&&Date.now()<deadline)await sleep(100);
 await fs.writeFile(path.join(root,'rc5-console.log'),raw);
 if(exit===null){child.kill();failure??=new Error('Owned isolated rc5 server failed to stop normally');}
 else if(exit!==0)failure??=new Error('Server exit code '+exit);
 if(!failure){try{
  assert.doesNotMatch(cleanLog(await fs.readFile(path.join(root,'logs/latest.log'),'utf8')),/ERROR|Exception/);
  assert.equal(await hash(path.join(source,'plugins/battleroyale.jar')),oldJarHash,'Source fixture remains unchanged');
  result.passed.push('Clean normal shutdown with exit 0 and source fixture JAR unchanged');
 }catch(error){failure=error;}}
 result.status=failure?'failed':'passed';result.exitCode=exit;if(failure)result.failure=failure.stack??String(failure);
 await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({root,status:result.status,passed:result.passed,failure:result.failure},null,2));
}
if(failure)throw failure;
