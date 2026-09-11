// Compile the production asynchronous icon loader against a small Android model.
// Uses the Kotlin compiler directly; does not invoke Gradle or build an APK.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const lib = process.argv[2] || process.env.KOTLIN_LIB_DIR;
if (!lib) throw new Error('Pass the directory containing Kotlin compiler, stdlib and dependency JARs.');
const jars = fs.readdirSync(lib).filter(name => /^(kotlin-compiler-embeddable-|kotlin-stdlib-|kotlin-reflect-|kotlin-script-runtime-|kotlinx-coroutines-core-jvm-|annotations-|trove4j-).*\.jar$/.test(name));
const stdlib = jars.find(name => /^kotlin-stdlib-[0-9]/.test(name));
if (!stdlib || !jars.some(name => name.startsWith('kotlin-compiler-embeddable-'))) throw new Error('Missing Kotlin compiler or stdlib');
const java = process.env.JAVA || 'java';
const root = path.resolve(__dirname, '..');
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-floating-icon-tests-'));
const jar = path.join(output, 'icons.jar');
function run(args) {
    const result = spawnSync(java, args, { cwd: root, encoding: 'utf8', timeout: 60_000 });
    if (result.stdout) process.stdout.write(result.stdout);
    if (result.stderr) process.stderr.write(result.stderr);
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
run(['-Xmx256m', '-cp', jars.map(name => path.join(lib, name)).join(path.delimiter),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-classpath', path.join(lib, stdlib), '-d', jar,
    'app/src/main/java/h/Hchat/hooks/items/floatingshortcut/FloatingShortcutIconLoader.kt',
    ...fs.readdirSync(path.join(root, 'scripts/tests/floating_shortcut_icons')).filter(name => name.endsWith('.kt')).map(name => 'scripts/tests/floating_shortcut_icons/' + name)]);
run(['-cp', jar + path.delimiter + path.join(lib, stdlib), 'h.Hchat.hooks.items.floatingshortcut.IconLoaderRegressionKt']);
console.log('Test artifacts: ' + output);
