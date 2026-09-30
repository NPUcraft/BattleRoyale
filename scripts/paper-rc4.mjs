// Isolated rc3 -> rc4 lobby-label upgrade and Component checks. No protocol player is emulated.
// Usage: JAVA_HOME=<jdk25> node scripts/paper-rc4.mjs <stopped-passing-paper-rc3-fixture>
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
if(!process.argv[2])throw new Error('Supply a stopped, passing rc3 local-sample fixture.');
const source=path.resolve(process.argv[2]),root=path.resolve('.run','paper-rc4-'+Date.now());
const data=path.join(root,'plugins/BattleRoyale'),reports=path.join(root,'plugins/BattleRoyaleTestProbe/rc4');
const original=JSON.parse(await fs.readFile(path.join(source,'results.json'),'utf8'));
assert.equal(original.status,'passed');assert.equal(original.fixture.notRemoteSurvivalMain,true);
assert.match(await fs.readFile(path.join(source,'eula.txt'),'utf8'),/^eula=true\s*$/m);
assert.match(await fs.readFile(path.join(source,'plugins/BattleRoyale/lobby-structure.properties'),'utf8'),/state=READY/);
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const hash=async file=>createHash('sha256').update(await fs.readFile(file)).digest('hex');
const passed=[],result={status:'running',root,source,plugin:'1.0.0-rc.4',paper:'26.2-129',java:25,
 fixture:{...original.fixture,sourceFixture:source},passed,
 limits:['Generated local sample map, not the full remote Survival-Main map.',
 'Scoreboard objects are real Paper API objects, but Player is an interface proxy: no client packets, actual sidebar display or actual player transitions are verified.',
 'Component and native-item API assertions only; no native player client chat rendering, bed click, inventory interaction or live purchase test.']};
await fs.cp(source,root,{recursive:true,filter:entry=>{
 const relative=path.relative(source,entry).replaceAll('\\','/');
 return !['logs','results.json','initial-attempt-results.json','restart-continuation.mjs','plugins/BattleRoyaleTestProbe/rc4'].includes(relative);
}});
await fs.copyFile(path.resolve('build/integration/battleroyale-test-probe.jar'),path.join(root,'plugins/probe.jar'));
// Old rc3 remains installed for prepare: only the new probe is copied until the white-label baseline is persisted.
const newJar=path.resolve('build/libs/battleroyale-1.0.0-rc.4.jar');
result.previousJarSha256=await hash(path.join(root,'plugins/battleroyale.jar'));result.pluginSha256=await hash(newJar);
const sourceMarkerHash=await hash(path.join(source,'plugins/BattleRoyale/lobby-structure.properties'));
const sourceBackupHash=await hash(path.join(source,'plugins/BattleRoyale/lobby-structure-original.blocks.gz'));
console.log('Isolated rc4 upgrade fixture: '+root);
async function checkpoint(description){passed.push(description);await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));console.log(description);}
function start(logName){
 const child=spawn(java,['-Xms512M','-Xmx2G','-Dfile.encoding=UTF-8','-Dbattleroyale.probe.rc4=true','-jar','paper.jar','--nogui'],{cwd:root,windowsHide:true});
 let raw='',exit=null;
 child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
 child.stdout.on('data',text=>{raw+=text;});child.stderr.on('data',text=>{raw+=text;});
 child.on('exit',code=>{exit=code??-1;});child.on('error',error=>{raw+=error.stack;exit=-1;});
 const plain=()=>raw.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
 async function waitFor(expected,from=0,timeout=240000){
  const end=Date.now()+timeout;
  while(!plain().slice(from).includes(expected)){
   if(/RC[34] .*FAILED|PROBE failed=|Error occurred while enabling BattleRoyale|Startup failed; disabling BattleRoyale|NoClassDefFoundError/.test(plain().slice(from))||exit!==null||Date.now()>end)
    throw new Error('Waiting for '+expected+'\n'+plain().slice(from).slice(-12000));
   await sleep(100);
  }
  return plain().slice(from);
 }
 async function command(command,expected,timeout=60000){const from=plain().length;console.log('Command: '+command);child.stdin.write(command+'\n');return waitFor(expected,from,timeout);}
 async function stop(){
  if(exit===null)child.stdin.write('stop\n');const end=Date.now()+60000;
  while(exit===null&&Date.now()<end)await sleep(100);
  await fs.writeFile(path.join(root,logName),raw);
  if(exit===null){child.kill();throw new Error('Owned isolated rc4 server did not stop normally');}
  assert.equal(exit,0,'Clean shutdown exit');
 }
 return{waitFor,command,stop,text:plain};
}
function cleanLog(text){
 return text.split(/(?=^\[\d{2}:\d{2}:\d{2}\])/m)
  .filter(block=>!/^\[.*\] \[CrashReport preload thread\/(ERROR|WARN)\]: (\[oshi\.|Failed to get system info for Process )/.test(block)).join('');
}
let server;
try{
 server=start('rc3-white-label-fixture.log');
 try{
  await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
  await server.command('br version','版本 1.0.0-rc.3');
  await server.command('brprobe p26rc4 prepare','RC4 PREPARE SUCCESS');
  assert.match(await fs.readFile(path.join(reports,'baseline.yml'),'utf8'),/blueprint-positions: 49574/);
  await checkpoint('Original rc3 READY lobby: four existing UUIDs recorded, labels whitened, foreign label added, and every blueprint block-data hash recorded');
 }finally{await server.stop();server=null;}
 await fs.copyFile(newJar,path.join(root,'plugins/battleroyale.jar'));
 server=start('rc4-upgrade.log');
 try{
  await server.waitFor('LOBBY_READY world=battleroyale_lobby center=0,200,0 built=false');
  await server.command('br version','版本 1.0.0-rc.4');
  await server.command('brprobe p26rc4 styles','RC4 STYLES SUCCESS');
  await server.command('brprobe p26rc4 items','RC4 ITEMS SUCCESS');
  await server.command('brprobe p26rc4 sidebar','RC4 SIDEBAR SUCCESS');
  await checkpoint('Real Paper sidebar title/rows/hidden numbers/state/countdown/pagination and Player-interface-proxy assignment/restoration/foreign-board ownership checks');
  await server.command('brprobe p26rc4 verify','RC4 VERIFY SUCCESS');
  await checkpoint('rc4 READY upgrade: original label UUIDs/text/positions retained, multiple colors and bold applied, no duplicates, foreign label retained, all block data/marker/backup unchanged');
  await server.command('brprobe p26rc3 lobby','RC3 LOBBY SUCCESS');
  await server.command('brprobe p26rc3 rooms survival','RC3 ROOMS SUCCESS');
  const diagnosis=await server.command('br admin diagnose','runtime-root');
  assert.match(diagnosis,/正常 recovery：/);assert.match(diagnosis,/正常 maps：available=1 total=1/);
  assert.match(diagnosis,/正常 economy：configured=excellenteconomy active=excellenteconomy currency=coins available=true shopEnabled=true/);
  assert.doesNotMatch(cleanLog(await fs.readFile(path.join(root,'logs/latest.log'),'utf8')),/ERROR|Exception/);
  await checkpoint('UiText colors/literal-input/no-event checks and actual exit-bed native PDC/original-item/state-predicate checks plus unchanged rc3 lobby/three-room/native-loadout and economy checks');
 }finally{await server.stop();server=null;}
 assert.equal(await hash(path.join(source,'plugins/BattleRoyale/lobby-structure.properties')),sourceMarkerHash);
 assert.equal(await hash(path.join(source,'plugins/BattleRoyale/lobby-structure-original.blocks.gz')),sourceBackupHash);
 result.status='passed';
}catch(error){result.status='failed';result.failure=error.stack??String(error);throw error;}
finally{await fs.writeFile(path.join(root,'results.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({root,status:result.status,passed},null,2));}
