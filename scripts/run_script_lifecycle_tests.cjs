// Isolated JVM regressions: compile production lifecycle code against small framework stubs.
// Does not invoke Gradle or build an APK.
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
const output = fs.mkdtempSync(path.join(os.tmpdir(), 'hchat-lifecycle-tests-'));
const stub = 'scripts/tests/script_lifecycle/';
const tests = [
    ['hooks', 'HookRegistryRegressionKt', [
        'app/src/main/java/h/Hchat/hooks/core/HookRegistry.kt',
        'scripts/tests/hook_registry/XposedStub.kt',
        'scripts/tests/hook_registry/HookRegistryRegression.kt'
    ]],
    ['delays', 'h.Hchat.hooks.items.script.DelayRegressionKt', [
        'app/src/main/java/h/Hchat/hooks/items/script/ScriptDelayScope.kt',
        stub + 'AndroidStub.kt', stub + 'HLogStub.kt', stub + 'DelayRegression.kt'
    ]],
    ['messages', 'h.Hchat.hooks.items.script.MessageRegressionKt', [
        'app/src/main/java/h/Hchat/hooks/items/script/ScriptMessageHook.kt',
        ...['AndroidStub', 'HLogStub', 'FeatureStub', 'FinderStub', 'MessageApiStub', 'ScriptRuntimeStub', 'MessageRegression'].map(name => stub + name + '.kt')
    ]]
];
function run(args) {
    const result = spawnSync(java, args, { cwd: root, encoding: 'utf8', timeout: 60_000 });
    if (result.stdout) process.stdout.write(result.stdout);
    if (result.stderr) process.stderr.write(result.stderr);
    if (result.error || result.status !== 0) throw result.error || new Error('JVM check failed: ' + result.status);
}
for (const [name, main, sources] of tests) {
    const jar = path.join(output, name + '.jar');
    run(['-Xmx256m', '-cp', jars.map(name => path.join(lib, name)).join(path.delimiter),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-classpath', path.join(lib, stdlib), '-d', jar, ...sources]);
    run(['-cp', jar + path.delimiter + path.join(lib, stdlib), main]);
}
console.log('Test artifacts: ' + output);
