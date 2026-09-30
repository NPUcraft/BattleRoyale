// Local integration test. Reuses a supplied Paper installation's binaries and existing EULA consent.
// Usage: node scripts/paper-smoke.mjs <existing-paper-server-directory>
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';

const source = path.resolve(process.argv[2] ?? '');
if (!process.argv[2]) throw new Error('Pass an existing Paper 1.21.8 server directory.');
assert.match(await fs.readFile(path.join(source, 'eula.txt'), 'utf8'), /^eula=true\s*$/m,
  'The supplied installation must already have accepted the Minecraft EULA.');
const root = path.resolve('.run', 'paper-smoke-' + Date.now());
await fs.mkdir(path.join(root, 'plugins'), { recursive: true });
for (const name of ['paper.jar', 'libraries', 'versions', 'cache', 'eula.txt']) {
  await fs.cp(path.join(source, name), path.join(root, name), { recursive: true });
}
await fs.copyFile(path.resolve('build/libs/battleroyale-1.0.0-rc.1.jar'),
  path.join(root, 'plugins', 'battleroyale.jar'));
await fs.writeFile(path.join(root, 'server.properties'), [
  'server-ip=127.0.0.1', 'server-port=0', 'online-mode=true',
  'level-type=minecraft:flat', 'generate-structures=false', 'view-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}',
  'simulation-distance=2', 'max-players=1', 'enable-query=false', 'enable-rcon=false',
  'sync-chunk-writes=false', 'spawn-protection=0'
].join('\n'));
await fs.writeFile(path.join(root, 'bukkit.yml'), 'settings:\n  allow-end: false\n');
await fs.writeFile(path.join(root, 'spigot.yml'), 'world-settings:\n  default:\n    verbose: false\n');
console.log('Isolated smoke server: ' + root);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function start(logName) {
  const child = spawn('java', ['-Xms512M', '-Xmx1G', '-Dfile.encoding=UTF-8',
    '-jar', 'paper.jar', '--nogui'], { cwd: root, windowsHide: true });
  let output = '';
  let exit = null;
  child.stdout.on('data', data => { output += data.toString(); });
  child.stderr.on('data', data => { output += data.toString(); });
  child.on('exit', code => { exit = code; });
  child.on('error', error => { output += error.stack; exit = -1; });
  async function waitFor(text, from = 0, timeout = 120000) {
    const deadline = Date.now() + timeout;
    while (!output.slice(from).includes(text)) {
      if (exit !== null || Date.now() > deadline) throw new Error('Waiting for ' + text + '\n' + output.slice(-12000));
      await sleep(100);
    }
    return output.slice(from);
  }
  async function command(command, expected) {
    const from = output.length;
    child.stdin.write(command + '\n');
    return waitFor(expected, from);
  }
  async function stop() {
    if (exit === null) child.stdin.write('stop\n');
    const deadline = Date.now() + 45000;
    while (exit === null && Date.now() < deadline) await sleep(100);
    if (exit === null) { child.kill(); throw new Error('Server failed to stop within 45 seconds'); }
    await fs.writeFile(path.join(root, logName), output);
  }
  return { waitFor, command, stop, text: () => output };
}

const results = [];
let server = start('valid-run.log');
try {
  await server.waitFor('Done (');
  assert.match(server.text(), /Paper version 1\.21\.8/);
  assert.match(server.text(), /\[BattleRoyale\] Loaded 2 rooms/);
  assert.match(server.text(), /\[BattleRoyale\] Loaded 2 map templates/);
  assert.doesNotMatch(server.text(), /ERROR.*BattleRoyale|Error occurred while enabling BattleRoyale/);
  assert.doesNotMatch(server.text(), /ERROR|Exception/);
  results.push('Paper 1.21.8 startup and plugin enable');
  await server.command('battleroyale version', 'Version 1.0.0-rc.1');
  await server.command('br version', 'Version 1.0.0-rc.1');
  await server.command('battleroyale help', '/battleroyale reload');
  await server.command('battleroyale', '/battleroyale help');
  const rooms = await server.command('battleroyale debug rooms', 'squad (Squad)');
  assert.match(rooms, /solo \(Solo\) players=2\.\.24/);
  const maps = await server.command('battleroyale debug maps', 'desert (Desert)');
  assert.match(maps, /city \(City\)/);
  results.push('Base command, help, version, alias, debug rooms and maps');
  const roomFile = path.join(root, 'plugins/BattleRoyale/rooms.yml');
  const original = await fs.readFile(roomFile, 'utf8');
  await fs.writeFile(roomFile, original.replace('max-players: 24', 'max-players: 1'));
  const failed = await server.command('battleroyale reload', 'Reload failed; previous configuration retained.');
  assert.match(failed, /rooms.yml: rooms.solo.max-players.*value=1/);
  const retained = await server.command('battleroyale debug rooms', 'squad (Squad)');
  assert.match(retained, /players=2\.\.24/);
  results.push('Invalid room diagnostics and atomic rollback');
  await fs.writeFile(roomFile, original.replace('display-name: "Solo"', 'display-name: "Solo Reloaded"'));
  await server.command('battleroyale reload', 'Configuration reloaded successfully.');
  await server.command('battleroyale debug rooms', 'Solo Reloaded');
  results.push('Successful configuration reload');
  await fs.writeFile(roomFile, original.replace('max-players: 24', 'max-players: 1'));
} finally { await server.stop(); }

server = start('invalid-startup.log');
try {
  await server.waitFor('Done (');
  assert.match(server.text(), /Startup failed; disabling BattleRoyale/);
  assert.match(server.text(), /rooms.yml: rooms.solo.max-players.*value=1/);
  assert.match(server.text(), /Disabling BattleRoyale/);
  results.push('Invalid startup configuration disables plugin');
} finally { await server.stop(); }
await fs.writeFile(path.join(root, 'results.json'), JSON.stringify({ passed: results, root }, null, 2));
console.log(JSON.stringify({ passed: results, root }, null, 2));
